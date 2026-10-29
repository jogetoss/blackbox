// Talks to the upload server: a BlackBox upload endpoint at the "Upload URL" from Settings (see ARCHITECTURE.md "Upload endpoint (API)").
// Requests carry the browser's cookies, so the user's existing login on that server is used.
// Needs host permission for the server's origin (asked for in Settings or on the first upload).

const Server = {
  // { uploadUrl, viewUrl } from Settings; viewUrl is an optional override ('' = use the server's link)
  async getSettings() {
    const s = await chrome.storage.local.get(['uploadUrl', 'viewUrl']);
    return { uploadUrl: s.uploadUrl || '', viewUrl: s.viewUrl || '' };
  },

  // Shown when the server answers 401
  notLoggedIn(endpoint) {
    return `Not logged in to ${new URL(endpoint).origin}. Log in there in this browser, then try again.`;
  },

  viewLink(settings, id, serverUrl) {
    return settings.viewUrl ? settings.viewUrl.split('{id}').join(encodeURIComponent(id)) : serverUrl || '';
  },

  // Drops a trailing slash, query and hash; throws on anything that is not http(s)
  normalize(url) {
    let u;
    try {
      u = new URL(url.trim());
    } catch (e) {
      throw new Error('Enter a URL like https://example.com/api/blackbox');
    }
    if (!/^https?:$/.test(u.protocol)) throw new Error('The Upload URL must start with http:// or https://');
    return (u.origin + u.pathname).replace(/\/+$/, '');
  },

  origins(endpoint) {
    return [new URL(endpoint).origin + '/*'];
  },

  hasPermission(endpoint) {
    return chrome.permissions.contains({ origins: this.origins(endpoint) });
  },

  // Must be called straight from a click (user gesture), before any await.
  requestPermission(endpoint) {
    return chrome.permissions.request({ origins: this.origins(endpoint) });
  },

  // The server sends links as paths (e.g. /app/login); make them absolute
  resolve(endpoint, path) {
    try {
      return path ? new URL(path, endpoint).href : '';
    } catch (e) {
      return ''; // a link the server sent that isn't a URL: show no link
    }
  },

  // GET or POST with progress. Resolves with the JSON body; rejects with an Error that has .status.
  request(endpoint, method, params, body, contentType, onProgress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open(method, endpoint + '?' + new URLSearchParams(params));
      xhr.withCredentials = true;
      if (contentType) xhr.setRequestHeader('Content-Type', contentType);
      if (onProgress) xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress(e.loaded, e.total); };
      xhr.onload = () => {
        let res = null;
        try { res = JSON.parse(xhr.responseText); } catch (e) { /* not JSON */ }
        if (xhr.status >= 200 && xhr.status < 300 && res) return resolve(res);
        let message = res?.error?.message;
        if (!message) {
          message = xhr.status === 404 || !res
            ? 'No BlackBox upload endpoint at ' + endpoint + '. Check the Upload URL in Settings.'
            : 'Request failed (HTTP ' + xhr.status + ').';
        }
        const err = new Error(message);
        err.status = xhr.status;
        reject(err);
      };
      xhr.onerror = () => {
        const err = new Error('Could not reach ' + new URL(endpoint).origin + '.');
        err.status = 0;
        reject(err);
      };
      // GETs are small (me): a server that never answers would otherwise hang until the browser gives up.
      // Uploads (POST) can take minutes, so they have no limit.
      if (method === 'GET') {
        xhr.timeout = 15000;
        xhr.ontimeout = () => {
          const err = new Error('Could not reach ' + new URL(endpoint).origin + ' (no answer in 15 s).');
          err.status = 0;
          reject(err);
        };
      }
      xhr.send(body ?? null);
    });
  },

  me(endpoint) {
    return this.request(endpoint, 'GET', { action: 'me' });
  },
};
