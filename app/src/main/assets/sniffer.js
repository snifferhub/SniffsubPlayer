(() => {
  if (window.__sniffSubPlayerInstalled) {
    window.__sniffSubPlayerRescan(window.__sniffSubPlayerRequest);
    return true;
  }
  window.__sniffSubPlayerInstalled = true;

  const seen = new Set();
  let active = false;
  let pageUrl = location.href;
  let pageStartedAt = 0;
  let sessionId = 0;
  let deadline = 0;
  let timer = null;
  const stop = () => {
    active = false;
    clearTimeout(timer);
    timer = null;
    window.__sniffSubPlayerTimer = null;
  };
  const navigationChanged = () => {
    if (pageUrl === location.href) return;
    stop();
    pageUrl = location.href;
    pageStartedAt = performance.now();
    seen.clear();
  };
  // SPA navigation does not replace this document or its Performance entries.
  ['pushState', 'replaceState'].forEach((name) => {
    const original = history[name];
    history[name] = function() {
      const result = original.apply(this, arguments);
      navigationChanged();
      return result;
    };
  });
  ['popstate', 'hashchange'].forEach((name) => window.addEventListener(name, navigationChanged));
  const canReport = () => {
    navigationChanged();
    if (active && performance.now() >= deadline) stop();
    return active;
  };
  const pattern = /(\.(m3u8|mp4|m4v|mov|mpd|m4s|ts|aac)(?=([?#/&]|$))|^blob:)/i;

  const inputUrl = (input) => {
    if (typeof input === 'string') return input;
    if (!input || typeof input !== 'object') return '';
    if (typeof input.url === 'string') return input.url;
    if (typeof input.href === 'string') return input.href;
    return '';
  };

  const absolute = (input) => {
    const url = inputUrl(input);
    if (!url) return '';
    try {
      return new URL(url, location.href).href;
    } catch (_) {
      return '';
    }
  };

  const report = (url, source) => {
    if (!canReport()) return;
    const value = absolute(url);
    if (!value || !pattern.test(value) || seen.has(value)) return;
    if (seen.size >= 2000) seen.clear();
    seen.add(value);
    try {
      window.SniffBridge.report(JSON.stringify({
        url: value,
        source,
        pageUrl,
        sessionId,
        userAgent: navigator.userAgent,
      }));
    } catch (_) {}
  };

  const inspect = (input, source) => {
    if (!input) return;
    report(input, source);
  };

  const originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function(input) {
      if (!canReport()) return originalFetch.apply(this, arguments);
      const requestSession = sessionId;
      const requestPage = pageUrl;
      inspect(input, 'fetch');
      return originalFetch.apply(this, arguments).then((response) => {
        if (requestSession === sessionId && requestPage === location.href) {
          inspect(response && response.url, 'fetch-response');
        }
        return response;
      });
    };
  }

  const originalOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    report(url, 'xhr');
    return originalOpen.apply(this, arguments);
  };

  const scan = () => {
    if (!canReport()) return;
    document.querySelectorAll('video, source, a').forEach((element) => {
      report(
        element.currentSrc || element.src || element.href,
        element.tagName.toLowerCase(),
      );
    });
    try {
      performance.getEntriesByType('resource').forEach((entry) => {
        if (entry.startTime >= pageStartedAt) report(entry.name, 'performance');
      });
    } catch (_) {}
  };

  ['loadstart', 'loadedmetadata', 'play', 'canplay'].forEach((name) => {
    document.addEventListener(name, (event) => {
      const target = event.target;
      if (target && target.tagName === 'VIDEO') {
        report(target.currentSrc || target.src, `video-${name}`);
      }
    }, true);
  });

  const tick = () => {
    scan();
    if (canReport()) {
      timer = setTimeout(tick, 500);
      window.__sniffSubPlayerTimer = timer;
    }
  };

  window.__sniffSubPlayerRescan = (requestId = sessionId) => {
    stop();
    navigationChanged();
    sessionId = requestId;
    seen.clear();
    deadline = performance.now() + 8000;
    active = true;
    tick();
  };

  // Returning from settings/player/background never starts a new sniff session.
  window.__sniffSubPlayerSetActive = (value) => {
    if (!value) stop();
  };

  window.__sniffSubPlayerRescan(window.__sniffSubPlayerRequest);
  return true;
})();
