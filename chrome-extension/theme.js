// Applies the Theme (System / Light / Dark) and Text size settings to this page: every extension page, and the upload
// server's BlackBox pages as a content script (registered by background.js). Those pages have no pickers of their own.
// localStorage gives the last choice instantly (no flash); chrome.storage is the real setting. Content scripts can't
// read chrome.storage (background.js keeps it to the extension's pages): on the server's pages the background
// answers a 'display' message with the two values, asked again whenever the page comes back into view.
(function () {
  const root = document.documentElement;
  const zoom = (v) => {
    v = ['0.9', '1', '1.15', '1.3', '1.5', '1.75', '2'].includes(String(v)) ? String(v) : '1';
    root.style.zoom = v === '1' ? '' : v;
    try { localStorage.setItem('bb-text-size', v); } catch (e) { /* storage blocked */ }
  };
  try { zoom(localStorage.getItem('bb-text-size')); } catch (e) { /* storage blocked */ }
  const apply = (t) => {
    if (t === 'light' || t === 'dark') root.dataset.theme = t;
    else delete root.dataset.theme; // System: CSS follows prefers-color-scheme
    try { localStorage.setItem('bb-theme', t || ''); } catch (e) { /* storage blocked */ }
  };
  try { apply(localStorage.getItem('bb-theme')); } catch (e) { /* storage blocked */ }
  const set = (v) => { if (v) { zoom(v.textSize); apply(v.theme); } };
  if (location.protocol === 'chrome-extension:') {
    chrome.storage.local.get(['theme', 'textSize']).then(set);
    chrome.storage.onChanged.addListener((c) => {
      if (c.textSize) zoom(c.textSize.newValue);
      if (c.theme) apply(c.theme.newValue);
    });
  } else {
    const ask = () => chrome.runtime.sendMessage({ target: 'background', type: 'display' }).then(set, () => {});
    ask();
    document.addEventListener('visibilitychange', () => { if (!document.hidden) ask(); });
  }
})();
