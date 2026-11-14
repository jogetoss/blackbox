(function () {
  const root = document.documentElement;
  const zoom = (v) => {
    v = ['0.9', '1', '1.15', '1.3', '1.5', '1.75', '2'].includes(String(v)) ? String(v) : '1';
    root.style.zoom = v === '1' ? '' : v;
    try { localStorage.setItem('bb-text-size', v); } catch (e) { }
  };
  try { zoom(localStorage.getItem('bb-text-size')); } catch (e) { }
  const apply = (t) => {
    if (t === 'light' || t === 'dark') root.dataset.theme = t;
    else delete root.dataset.theme;
    try { localStorage.setItem('bb-theme', t || ''); } catch (e) { }
  };
  try { apply(localStorage.getItem('bb-theme')); } catch (e) { }
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
