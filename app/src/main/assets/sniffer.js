(() => {
  if (window.__sniffSubPlayerInstalled) return true;
  window.__sniffSubPlayerInstalled = true;

  const seen = new Set();
  const pattern = /(\.(m3u8|mp4|m4v|mov|mpd|m4s|ts|aac)(?=([?#/&]|$))|^blob:)/i;

  const absolute = (url) => {
    if (!url || typeof url !== 'string') return '';
    try {
      return new URL(url, location.href).href;
    } catch (_) {
      return '';
    }
  };

  const report = (url, source) => {
    const value = absolute(url);
    if (!value || !pattern.test(value) || seen.has(value)) return;
    if (seen.size >= 2000) seen.clear();
    seen.add(value);
    try {
      window.SniffBridge.report(JSON.stringify({
        url: value,
        source,
        pageUrl: location.href,
        userAgent: navigator.userAgent,
      }));
    } catch (_) {}
  };

  const inspect = (input, source) => {
    if (!input) return;
    if (typeof input === 'string') {
      report(input, source);
    } else if (input.url) {
      report(input.url, source);
    }
  };

  const originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function(input) {
      inspect(input, 'fetch');
      return originalFetch.apply(this, arguments).then((response) => {
        inspect(response && response.url, 'fetch-response');
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
    document.querySelectorAll('video, source, a').forEach((element) => {
      report(
        element.currentSrc || element.src || element.href,
        element.tagName.toLowerCase(),
      );
    });
    try {
      performance.getEntriesByType('resource').forEach((entry) => {
        report(entry.name, 'performance');
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

  scan();
  window.__sniffSubPlayerTimer = setInterval(scan, 1200);
  return true;
})();
