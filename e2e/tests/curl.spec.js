const { test, expect } = require('@playwright/test');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const src = fs.readFileSync(path.join(__dirname, '../../chrome-extension/viewer.js'), 'utf8');
const body = src.match(/\nfunction toCurl\(n\) \{[\s\S]*?\n\}\n/)[0];
const toCurl = new Function(`${body}; return toCurl;`)();

function shellArgs(cmd) {
  const out = execFileSync('bash', ['-c', `curl() { printf '%s\\0' "$@"; }\n${cmd}`], { encoding: 'utf8' });
  return out.split('\0').slice(0, -1);
}

test('a crafted method, URL, header or body stays a plain argument', () => {
  const marker = path.join(require('os').tmpdir(), `bb-curl-${process.pid}`);
  const evil = `$(touch ${marker})`;
  const cmd = toCurl({
    method: `GET ${evil} #`,
    url: `--output=${marker}`,
    requestHeaders: { [`X-${evil}`]: `'; touch ${marker}; '` },
    postData: `a=\`touch ${marker}\`&b='x'`,
  });
  const args = shellArgs(cmd);
  expect(fs.existsSync(marker)).toBe(false);
  expect(args).toEqual(['--url', `--output=${marker}`, '-H', `X-${evil}: '; touch ${marker}; '`,
    '--data-raw', `a=\`touch ${marker}\`&b='x'`]);
});

test('real methods are kept', () => {
  expect(shellArgs(toCurl({ method: 'post', url: 'https://h/x?a=1&b=2', postData: 'x=1' })))
    .toEqual(['https://h/x?a=1&b=2', '-X', 'POST', '--data-raw', 'x=1']);
  expect(shellArgs(toCurl({ method: 'GET', url: 'https://h/' }))).toEqual(['https://h/']);
});
