// Applies the Theme setting (System / Light / Dark) to this extension page.
// localStorage gives the last choice instantly (no flash); chrome.storage is the real setting.
(function () {
  const root = document.documentElement;
  const apply = (t) => {
    if (t === 'light' || t === 'dark') root.dataset.theme = t;
    else delete root.dataset.theme; // System: CSS follows prefers-color-scheme
    try { localStorage.setItem('bb-theme', t || ''); } catch (e) { /* storage blocked */ }
  };
  try { apply(localStorage.getItem('bb-theme')); } catch (e) { /* storage blocked */ }
  chrome.storage.local.get('theme').then(({ theme }) => apply(theme));
  chrome.storage.onChanged.addListener((c) => { if (c.theme) apply(c.theme.newValue); });
})();
