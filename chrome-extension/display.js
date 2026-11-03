// Theme (System / Light / Dark) and Text size pickers for the pages that run without the extension: the downloaded
// HTML file (RecordingHtml) and dist/blackbox-viewer.html (build-standalone.py). Inlined in <head> so the saved choice
// applies before the page draws. Same values and localStorage keys as theme.js, which sets them from the extension's
// Settings on the other pages. Saved in this browser for every file opened from disk; without storage, the choice
// lasts until the page closes.
(function () {
  const root = document.documentElement;
  const SIZES = [['0.9', '90%'], ['1', '100%'], ['1.15', '115%'], ['1.3', '130%'], ['1.5', '150%'], ['1.75', '175%'], ['2', '200%']];
  const THEMES = [['', 'System'], ['light', 'Light'], ['dark', 'Dark']];
  const load = (key) => { try { return localStorage.getItem(key); } catch (e) { return null; } };
  const save = (key, v) => { try { localStorage.setItem(key, v); } catch (e) { /* storage blocked */ } };

  const size = (v) => (SIZES.some(([s]) => s === String(v)) ? String(v) : '1');
  const theme = (t) => (t === 'light' || t === 'dark' ? t : '');
  const applySize = (v) => { root.style.zoom = v === '1' ? '' : v; }; // viewer.js redraws its canvases on this change
  const applyTheme = (t) => { if (t) root.dataset.theme = t; else delete root.dataset.theme; }; // '': prefers-color-scheme
  applySize(size(load('bb-text-size')));
  applyTheme(theme(load('bb-theme')));

  function picker(id, label, options, value, onChange) {
    const sel = document.createElement('select');
    sel.id = id;
    sel.title = label;
    sel.setAttribute('aria-label', label);
    for (const [v, text] of options) sel.add(new Option(text, v, false, v === value));
    sel.onchange = () => onChange(sel.value);
    const wrap = document.createElement('label');
    wrap.className = 'display-pick';
    wrap.append(label + ' ', sel);
    return wrap;
  }

  document.addEventListener('DOMContentLoaded', () => {
    const actions = document.querySelector('.replay-head .actions');
    if (!actions) return;
    const box = document.createElement('div');
    box.className = 'display-settings';
    box.append(
      picker('textSizePick', 'Text size', SIZES, size(load('bb-text-size')), (v) => { applySize(v); save('bb-text-size', v); }),
      picker('themePick', 'Theme', THEMES, theme(load('bb-theme')), (t) => { applyTheme(t); save('bb-theme', t); }),
    );
    actions.prepend(box);
  });
})();
