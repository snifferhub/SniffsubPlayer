const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const script = fs.readFileSync(path.join(__dirname, '../../../../composeApp/src/commonMain/composeResources/files/sniffer.js'), 'utf8');

function harness() {
  const reports = [];
  let now = 0;
  let nextTimer = 0;
  const timers = new Map();
  const events = new Map();
  const documentEvents = new Map();
  class Request { constructor(input) { this.url = new URL(input, 'https://page.test/watch').href; } }
  class XHR { open(method, url) { this.request = { method, url }; return true; } }
  const page = {
    URL, Request, XMLHttpRequest: XHR,
    location: { href: 'https://page.test/watch' },
    navigator: { userAgent: 'Test UA' },
    history: {
      pushState(_state, _title, url) { page.location.href = new URL(url, page.location.href).href; },
      replaceState(_state, _title, url) { if (url) page.location.href = new URL(url, page.location.href).href; },
    },
    addEventListener: (name, fn) => events.set(name, fn),
    document: {
      querySelectorAll: () => [],
      addEventListener: (name, fn) => documentEvents.set(name, fn),
    },
    performance: { now: () => now, getEntriesByType: () => [] },
    setTimeout: (fn, delay) => { const id = ++nextTimer; timers.set(id, { fn, at: now + delay }); return id; },
    clearTimeout: (id) => timers.delete(id),
    SniffBridge: { report: json => reports.push(JSON.parse(json)) },
    fetch: () => Promise.resolve({ url: 'https://example.test/non-media' }),
    __sniffSubPlayerRequest: 1,
  };
  page.window = page;
  vm.createContext(page);
  return {
    page, reports, timers, documentEvents,
    start(id = 1) { page.__sniffSubPlayerRequest = id; vm.runInContext(script, page); },
    advance(ms) {
      const target = now + ms;
      while (true) {
        const timer = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
        if (!timer || timer[1].at > target) break;
        now = timer[1].at;
        timers.delete(timer[0]);
        timer[1].fn();
      }
      now = target;
    },
  };
}

test('manual session collects DOM, performance, fetch, XHR and video events without double hooking', async () => {
  const h = harness();
  const { page, reports } = h;
  page.document.querySelectorAll = () => [{ tagName: 'VIDEO', currentSrc: 'dom.mp4' }];
  page.performance.getEntriesByType = () => [{ name: 'https://cdn.test/perf.mpd', startTime: 0 }];
  page.fetch = () => Promise.resolve({ url: 'https://cdn.test/response.m3u8' });
  h.start();
  const hooked = page.fetch;
  await page.fetch('fetch.m3u8');
  await page.fetch(new URL('https://cdn.test/url.mp4'));
  await page.fetch(new page.Request('request.mov'));
  const xhr = new page.XMLHttpRequest();
  xhr.open('GET', 'xhr.aac');
  xhr.open('GET', new URL('https://cdn.test/segment.m4s'));
  h.documentEvents.get('play')({ target: { tagName: 'VIDEO', src: 'event.mp4' } });
  assert.deepEqual(reports.map(r => r.source), [
    'video', 'performance', 'fetch', 'fetch-response', 'fetch', 'fetch', 'xhr', 'xhr', 'video-play',
  ]);
  assert.ok(reports.every(r => r.sessionId === 1 && r.pageUrl === 'https://page.test/watch'));
  h.start(2);
  assert.equal(page.fetch, hooked);
  assert.equal(h.timers.size, 1);
  assert.equal(reports.at(-1).sessionId, 2);
});

test('session stops after 8 seconds; a foreground signal cannot restart it', async () => {
  const h = harness();
  h.start();
  await h.page.fetch('first.mp4');
  h.advance(8000);
  assert.equal(h.timers.size, 0);
  assert.equal(h.page.__sniffSubPlayerTimer, null);
  h.page.__sniffSubPlayerSetActive(true);
  await h.page.fetch('expired.mp4');
  assert.equal(h.reports.length, 1);
  h.start(2);
  await h.page.fetch('second.mp4');
  assert.equal(h.reports.at(-1).sessionId, 2);
});

test('suspending cancels timers and idle fetch preserves the original promise', () => {
  const h = harness();
  const response = Promise.resolve({ url: 'https://cdn.test/response.mp4' });
  h.page.fetch = () => response;
  h.start();
  h.page.__sniffSubPlayerSetActive(false);
  h.page.__sniffSubPlayerSetActive(false);
  assert.equal(h.timers.size, 0);
  assert.equal(h.page.fetch('idle.mp4'), response);
  assert.equal(h.reports.length, 0);
});

test('SPA navigation drops old performance entries and requires a new click', async () => {
  const h = harness();
  h.start();
  h.advance(1000);
  await h.page.fetch('old.mp4');
  h.page.history.pushState({}, '', '/new');
  assert.equal(h.timers.size, 0);
  await h.page.fetch('before-click.mp4');
  h.page.performance.getEntriesByType = () => [
    { name: 'https://cdn.test/old.mp4', startTime: 100 },
    { name: 'https://cdn.test/new.mp4', startTime: 1001 },
  ];
  h.start(2);
  assert.equal(h.reports.length, 2);
  assert.equal(h.reports.at(-1).url, 'https://cdn.test/new.mp4');
  assert.equal(h.reports.at(-1).pageUrl, 'https://page.test/new');
  h.page.history.replaceState({}, '', '/third');
  assert.equal(h.timers.size, 0);
});

test('delayed fetch response from a previous page or scan cannot pollute the new session', async () => {
  const h = harness();
  let resolve;
  h.page.fetch = () => new Promise(r => { resolve = r; });
  h.start();
  const oldRequest = h.page.fetch('https://example.test/request');
  h.page.history.pushState({}, '', '/new');
  h.start(2);
  resolve({ url: 'https://cdn.test/old-response.mp4' });
  await oldRequest;
  assert.equal(h.reports.length, 0);
  const previousScan = h.page.fetch('https://example.test/request');
  h.start(3);
  resolve({ url: 'https://cdn.test/old-session.mp4' });
  await previousScan;
  assert.equal(h.reports.length, 0);
});
