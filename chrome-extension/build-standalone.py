#!/usr/bin/env python3
"""Build dist/blackbox-viewer.html: viewer.html with viewer.css and viewer.js inlined, no extension needed.

Run after changing the viewer:  python3 build-standalone.py
"""
from pathlib import Path

here = Path(__file__).resolve().parent
html = (here / 'viewer.html').read_text()
css = (here / 'viewer.css').read_text()
js = (here / 'viewer.js').read_text()

for name, code in (('viewer.css', css), ('viewer.js', js)):
    if '</style' in code or '</script' in code:
        raise SystemExit(f'{name} contains a closing tag and cannot be inlined')

parts = [
    ('<link rel="stylesheet" href="viewer.css">', f'<style>\n{css}</style>'),
    ('<script src="viewer.js"></script>', f'<script>\n{js}</script>'),
    ('  <script src="theme.js"></script>\n', ''),  # the standalone file follows the OS theme
    # extension only (chrome.* APIs, the extension's storage): recordings list, linked folder, status
    ('  <script src="idb.js"></script>\n', ''),
    ('  <script src="store.js"></script>\n', ''),
    ('  <script src="replay.js"></script>\n', ''),
]
for old, new in parts:
    if old not in html:
        raise SystemExit(f'viewer.html no longer contains: {old.strip()}')
    html = html.replace(old, new, 1)

out = here / 'dist' / 'blackbox-viewer.html'
out.parent.mkdir(exist_ok=True)
out.write_text(html)
print(f'wrote {out} ({out.stat().st_size // 1024} KB)')
