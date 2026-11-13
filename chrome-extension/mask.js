// Masks credentials in recorded network data before it is saved (session.json, the partial saves, the upload).
// On unless the server allows this user to record them unmasked (plugin setting "Allow recording browser secrets
// unmasked", admins always) and the popup's "Record browser secrets unmasked" is ticked.
// The secret words are the plugin's Secrets.java list (from Joget Upgrade Advisor's SecretMasker).
// Used by the recorder; a plain script (no modules), also loaded by node for the tests.

const Mask = (() => {
  const MASK = '****';
  const KEY_WORDS = 'password|passwd|passphrase|pwd|secret|token|api[_-]?key|apikey|access[_-]?key|private[_-]?key|'
    + 'client[_-]?secret|auth[_-]?key|credential|signing[_-]?key|encryption[_-]?key|csrf|xsrf|session[_-]?id|jsessionid|otp';
  const SECRET_NAME = new RegExp(`(${KEY_WORDS}|^pass$|^pass[_.-]|[_.-]pass$|_pass_|salt)`, 'i');
  // Headers that are credentials whatever the secret words say
  const SECRET_HEADERS = new Set(['authorization', 'proxy-authorization', 'cookie', 'set-cookie', 'api_key', 'x-api-key']);
  const TOKEN_SHAPES = [
    /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g,
    /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g, // JWT
    /\bAKIA[0-9A-Z]{16}\b/g,
    /\bgh[pousr]_[A-Za-z0-9]{20,}\b/g,
    /\bxox[abprs]-[A-Za-z0-9-]{10,}/g,
    /\bAIza[0-9A-Za-z_-]{30,}/g,
  ];
  const AUTH_SCHEME = /\b(Bearer|Basic)\s+[A-Za-z0-9\-._~+/]{8,}=*/gi;
  // "name": "value" in JSON-ish text, name=value in forms and query strings
  const JSON_PAIR = /("([A-Za-z0-9_.\-]{1,100})"\s*:\s*)("(?:[^"\\\n]|\\.)*"|-?\d[\d.eE+-]*|true|false)/g;
  const FORM_PAIR = /(^|[?&;\s])([A-Za-z0-9_.\-[\]%]{1,100})=([^&;\s#]*)/g;

  const isSecretName = (name) => !!name && SECRET_NAME.test(safeDecode(name));
  function safeDecode(s) {
    try { return decodeURIComponent(s); } catch (e) { return s; }
  }

  // Free text: token shapes, Bearer/Basic, secret-named JSON and form pairs
  function text(s) {
    if (typeof s !== 'string' || !s) return s;
    for (const re of TOKEN_SHAPES) s = s.replace(re, MASK);
    s = s.replace(AUTH_SCHEME, (m, scheme) => `${scheme} ${MASK}`);
    s = s.replace(JSON_PAIR, (m, head, name, v) => (isSecretName(name) ? `${head}"${MASK}"` : m));
    s = s.replace(FORM_PAIR, (m, pre, name, v) => (isSecretName(name) && v ? `${pre}${name}=${MASK}` : m));
    return s;
  }

  function url(u) {
    if (typeof u !== 'string' || !u) return u;
    // user:password@host
    u = u.replace(/^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\s:/@#]+:)[^\s@/#]+@/, `$1${MASK}@`);
    const q = u.search(/[?#]/);
    return q < 0 ? u : u.slice(0, q) + text(u.slice(q));
  }

  function headers(h) {
    if (!h || typeof h !== 'object') return h;
    const out = {};
    for (const [k, v] of Object.entries(h)) {
      const lower = k.toLowerCase();
      out[k] = SECRET_HEADERS.has(lower) || isSecretName(lower) ? MASK : text(String(v));
    }
    return out;
  }

  // A recorded network entry, in place: URL, request and response headers, post data, response body
  function entry(e) {
    if (!e) return e;
    if (e.url) e.url = url(e.url);
    if (e.redirectedTo) e.redirectedTo = url(e.redirectedTo);
    if (e.requestHeaders) e.requestHeaders = headers(e.requestHeaders);
    if (e.responseHeaders) e.responseHeaders = headers(e.responseHeaders);
    if (typeof e.postData === 'string') e.postData = text(e.postData);
    if (typeof e.responseBody === 'string') e.responseBody = text(e.responseBody);
    else if (e.responseBody && typeof e.responseBody.text === 'string') e.responseBody.text = text(e.responseBody.text);
    return e;
  }

  // Whether this recording masks: offer = the server's me.options (null: no server), q = the popup's choices
  const on = (offer, q) => !(offer?.credentials === true && q?.credentials === true);

  return { MASK, isSecretName, text, url, headers, entry, on };
})();

if (typeof module !== 'undefined') module.exports = Mask;
