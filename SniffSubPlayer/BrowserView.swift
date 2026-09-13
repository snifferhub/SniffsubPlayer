import SwiftUI
import WebKit

struct BrowserView: UIViewRepresentable {
    @ObservedObject var store: BrowserStore
    @ObservedObject var adBlockManager: AdBlockManager
    let onWebViewCreated: (WKWebView) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(store: store)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let userContentController = WKUserContentController()
        userContentController.add(context.coordinator, name: "sniffer")
        configuration.userContentController = userContentController
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.customUserAgent = store.userAgent.isEmpty ? nil : store.userAgent
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        context.coordinator.webView = webView
        context.coordinator.observeProgress(of: webView)
        context.coordinator.lastAppliedRuleRevision = adBlockManager.ruleRevision
        onWebViewCreated(webView)
        adBlockManager.applyRules(to: userContentController) {
            Self.load(store.address, in: webView)
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let requestedUserAgent = store.userAgent.isEmpty ? nil : store.userAgent
        if webView.customUserAgent != requestedUserAgent {
            webView.customUserAgent = requestedUserAgent
        }

        if context.coordinator.lastAppliedRuleRevision != adBlockManager.ruleRevision {
            context.coordinator.lastAppliedRuleRevision = adBlockManager.ruleRevision
            adBlockManager.applyRules(to: webView.configuration.userContentController) {
                if webView.url == nil {
                    Self.load(store.address, in: webView)
                } else {
                    webView.reload()
                }
            }
        }
    }

    static func enableSniffer(in webView: WKWebView?, completion: @escaping (Bool) -> Void) {
        guard let webView else {
            completion(false)
            return
        }

        webView.evaluateJavaScript(snifferScript) { _, error in
            completion(error == nil)
        }
    }

    static func load(_ address: String, in webView: WKWebView?) {
        guard let webView else { return }
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        let urlString: String
        if trimmed.contains("://") {
            urlString = trimmed
        } else if trimmed.contains(".") && !trimmed.contains(" ") {
            urlString = "https://\(trimmed)"
        } else {
            urlString = "https://www.google.com/search?q=\(trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? trimmed)"
        }

        guard let url = URL(string: urlString) else { return }
        webView.load(URLRequest(url: url))
    }

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
        let store: BrowserStore
        weak var webView: WKWebView?
        var lastAppliedRuleRevision = -1
        private var progressObservation: NSKeyValueObservation?

        init(store: BrowserStore) {
            self.store = store
        }

        func observeProgress(of webView: WKWebView) {
            progressObservation = webView.observe(\.estimatedProgress, options: [.initial, .new]) { [weak self] webView, _ in
                let progress = webView.estimatedProgress
                Task { @MainActor [weak self] in
                    self?.store.loadProgress = progress
                }
            }
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "sniffer",
                  let body = message.body as? [String: Any],
                  let url = body["url"] as? String else {
                return
            }

            let source = body["source"] as? String ?? "unknown"
            let pageURL = body["pageURL"] as? String
            let userAgent = body["userAgent"] as? String
            Task { @MainActor in
                store.addCandidate(url: url, source: source, pageURL: pageURL, userAgent: userAgent)
            }
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            Task { @MainActor in
                store.isLoading = true
                store.loadProgress = max(webView.estimatedProgress, 0.05)
                store.isSnifferEnabled = false
                store.currentPageURL = webView.url?.absoluteString ?? store.currentPageURL
            }
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            Task { @MainActor in
                store.currentPageURL = webView.url?.absoluteString ?? store.currentPageURL
                store.address = webView.url?.absoluteString ?? store.address
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor in
                store.loadProgress = 1
                store.isLoading = false
                store.title = webView.title ?? ""
                store.currentPageURL = webView.url?.absoluteString ?? store.currentPageURL
                store.address = webView.url?.absoluteString ?? store.address
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            Task { @MainActor in
                store.loadProgress = 0
                store.isLoading = false
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            Task { @MainActor in
                store.loadProgress = 0
                store.isLoading = false
            }
        }
    }

    static let snifferScript = """
    (() => {
      if (window.__sniffSubPlayerInstalled) return;
      window.__sniffSubPlayerInstalled = true;

      const seen = new Set();
      const mediaPattern = /(\\.m3u8(\\?|$)|\\.mp4(\\?|$)|\\.m4v(\\?|$)|\\.mov(\\?|$)|\\.mpd(\\?|$)|\\.m4s(\\?|$)|\\.ts(\\?|$)|\\.aac(\\?|$)|blob:)/i;

      function absolutize(url) {
        if (!url || typeof url !== 'string') return '';
        try { return new URL(url, location.href).href; } catch (_) { return url; }
      }

      function report(url, source) {
        const absolute = absolutize(url);
        if (!absolute || !mediaPattern.test(absolute) || seen.has(absolute)) return;
        seen.add(absolute);
        try {
            window.webkit.messageHandlers.sniffer.postMessage({
            url: absolute,
            source,
            pageURL: location.href,
            userAgent: navigator.userAgent
          });
        } catch (_) {}
      }

      function inspectRequest(input, source) {
        if (!input) return;
        if (typeof input === 'string') {
          report(input, source);
          return;
        }
        if (input.url) report(input.url, source);
      }

      const rawFetch = window.fetch;
      if (rawFetch) {
        window.fetch = function(input, init) {
          inspectRequest(input, 'fetch');
          return rawFetch.apply(this, arguments).then((response) => {
            inspectRequest(response && response.url, 'fetch-response');
            return response;
          });
        };
      }

      const rawOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(method, url) {
        report(url, 'xhr');
        return rawOpen.apply(this, arguments);
      };

      function scanDOM() {
        document.querySelectorAll('video, source, a').forEach((el) => {
          report(el.currentSrc || el.src || el.href, el.tagName.toLowerCase());
        });

        try {
          performance.getEntriesByType('resource').forEach((entry) => report(entry.name, 'performance'));
        } catch (_) {}
      }

      ['loadstart', 'loadedmetadata', 'play', 'canplay'].forEach((eventName) => {
        document.addEventListener(eventName, (event) => {
          const target = event.target;
          if (target && target.tagName === 'VIDEO') {
            report(target.currentSrc || target.src, `video-${eventName}`);
          }
        }, true);
      });

      scanDOM();
      setInterval(scanDOM, 1200);
    })();
    """
}
