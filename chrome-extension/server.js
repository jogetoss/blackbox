const Server = {
  async getSettings() {
    const s = await chrome.storage.local.get(['uploadUrl', 'viewUrl']);
    return { uploadUrl: s.uploadUrl || '', viewUrl: s.viewUrl || '' };
  },

  notLoggedIn(endpoint) {
    return `Not logged in to ${new URL(endpoint).origin}. Log in there in this browser, then try again.`;
  },

  viewLink(settings, id, serverUrl) {
    return settings.viewUrl ? settings.viewUrl.split('{id}').join(encodeURIComponent(id)) : serverUrl || '';
  },

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

  requestPermission(endpoint) {
    return chrome.permissions.request({ origins: this.origins(endpoint) });
  },

  resolve(endpoint, path) {
    try {
      if (!path) return '';
      const u = new URL(path, endpoint);
      return /^https?:$/.test(u.protocol) && u.host === new URL(endpoint).host ? u.href : '';
    } catch (e) {
      return '';
    }
  },

  request(endpoint, method, params, body, contentType, onProgress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open(method, endpoint + '?' + new URLSearchParams(params));
      xhr.withCredentials = true;
      if (contentType) xhr.setRequestHeader('Content-Type', contentType);
      if (onProgress) xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress(e.loaded, e.total); };
      xhr.onload = () => {
        let res = null;
        try { res = JSON.parse(xhr.responseText); } catch (e) { }
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

  list(endpoint) {
    return this.request(endpoint, 'GET', { action: 'list' });
  },

  recordingsPage(endpoint) {
    const u = new URL(endpoint);
    return /\/api\/blackbox$/.test(u.pathname) ? u.origin + u.pathname.replace(/\/api\/blackbox$/, '/blackbox/') : u.origin + '/';
  },
};

if (typeof module !== 'undefined') module.exports = Server;
