// Applies the Theme (System / Light / Dark) and Text size settings to this page: every extension page, and the upload
// server's BlackBox pages as a content script (registered by background.js). Those pages have no pickers of their own.
// localStorage gives the last choice instantly (no flash); chrome.storage is the real setting.
(function () {
  const root = document.documentElement;
  const zoom = (v) => {
    v = ['0.9', '1', '1.15', '1.3', '1.5', '1.75', '2'].includes(String(v)) ? String(v) : '1';
    root.style.zoom = v === '1' ? '' : v;
    try { localStorage.setItem('bb-text-size', v); } catch (e) { /* storage blocked */ }
  };
  try { zoom(localStorage.getItem('bb-text-size')); } catch (e) { /* storage blocked */ }
  chrome.storage.local.get('textSize').then(({ textSize }) => zoom(textSize));
  chrome.storage.onChanged.addListener((c) => { if (c.textSize) zoom(c.textSize.newValue); });
  const apply = (t) => {
    if (t === 'light' || t === 'dark') root.dataset.theme = t;
    else delete root.dataset.theme; // System: CSS follows prefers-color-scheme
    try { localStorage.setItem('bb-theme', t || ''); } catch (e) { /* storage blocked */ }
  };
  try { apply(localStorage.getItem('bb-theme')); } catch (e) { /* storage blocked */ }
  chrome.storage.local.get('theme').then(({ theme }) => apply(theme));
  chrome.storage.onChanged.addListener((c) => { if (c.theme) apply(c.theme.newValue); });
})();
