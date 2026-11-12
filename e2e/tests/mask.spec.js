// mask.js (credential masking in the recorder), run in node: no browser or server needed.
const { test, expect } = require('@playwright/test');
const Mask = require('../../chrome-extension/mask.js');

test('masks credentials in a recorded request and response', () => {
  const e = Mask.entry({
    url: 'https://u:pw123@h/x?token=abc&id=5&j_password=x#k',
    requestHeaders: { Authorization: 'Bearer abc.def.ghi12345', Cookie: 'JSESSIONID=1', OWASP_CSRFTOKEN: 'T',
      'Content-Type': 'x', api_id: 'A', api_key: 'K' },
    postData: 'j_username=cat&j_password=cat&OWASP_CSRFTOKEN=T&id=5',
    responseHeaders: { 'Set-Cookie': 'x=1', 'Content-Type': 'application/json' },
    responseBody: { text: '{"user":"cat","access_token":"eyJabcdefghij.eyJabcdefghij.sigabcdefghij","password":"x","n":5}' },
  });
  expect(e.url).toBe('https://u:****@h/x?token=****&id=5&j_password=****#k');
  expect(e.requestHeaders).toEqual({ Authorization: '****', Cookie: '****', OWASP_CSRFTOKEN: '****',
    'Content-Type': 'x', api_id: 'A', api_key: '****' });
  expect(e.postData).toBe('j_username=cat&j_password=****&OWASP_CSRFTOKEN=****&id=5'); // record ids stay for the app export
  expect(e.responseHeaders['Set-Cookie']).toBe('****');
  expect(JSON.parse(e.responseBody.text)).toEqual({ user: 'cat', access_token: '****', password: '****', n: 5 });
});

test('masks secrets in free text, not look-alike names', () => {
  expect(Mask.text('Bearer abcdefgh12345678 done')).toBe('Bearer **** done');
  expect(Mask.text('{"bypass":"y","compass":"z","pwd":"q"}')).toBe('{"bypass":"y","compass":"z","pwd":"****"}');
});

test('masks unless the server offers it and the box is ticked', () => {
  expect(Mask.on(null, { credentials: true })).toBe(true); // no server
  expect(Mask.on({ credentials: false }, { credentials: true })).toBe(true); // not allowed
  expect(Mask.on({ credentials: true }, { credentials: false })).toBe(true); // allowed, not ticked (the default)
  expect(Mask.on({ credentials: true }, { credentials: true })).toBe(false);
});

test('long inputs stay fast', () => {
  const t = Date.now();
  Mask.text('a'.repeat(1e6) + '=' + '"'.repeat(1e5));
  Mask.text('{"' + 'x'.repeat(5e5));
  expect(Date.now() - t).toBeLessThan(2000);
});
