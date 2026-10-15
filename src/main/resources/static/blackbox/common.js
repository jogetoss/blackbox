// Shared helpers for the BlackBox pages. All recorded text is set with textContent, never innerHTML.
(function () {
  const ctx = location.pathname.slice(0, location.pathname.indexOf('/blackbox'));
  const service = ctx + '/api/blackbox';
  let token = null;

  async function csrf() {
    if (!token) {
      const r = await fetch(ctx + '/web/json/directory/user/csrfToken', { credentials: 'same-origin' });
      if (!r.ok) throw new Error('Could not get a CSRF token (HTTP ' + r.status + ').');
      token = await r.json();
    }
    return token;
  }

  class ApiError extends Error {
    // Error bodies: {"error":{"code":"403","message":"..."}}
    constructor(status, body) {
      super((body && body.error && body.error.message) || 'Request failed (HTTP ' + status + ').');
      this.status = status;
    }
  }

  function url(action, params) {
    const q = new URLSearchParams(Object.assign({ action }, params || {}));
    return service + '?' + q;
  }

  async function getJson(action, params) {
    const r = await fetch(url(action, params), { credentials: 'same-origin' });
    const body = await r.json().catch(() => null);
    if (!r.ok) throw new ApiError(r.status, body);
    return body;
  }

  // POST with XMLHttpRequest so uploads can report progress.
  async function post(action, params, body, contentType, onProgress) {
    const t = await csrf();
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', url(action, params));
      xhr.setRequestHeader(t.tokenName, t.tokenValue);
      if (contentType) xhr.setRequestHeader('Content-Type', contentType);
      if (onProgress) xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress(e.loaded, e.total); };
      xhr.onload = () => {
        let res = null;
        try { res = JSON.parse(xhr.responseText); } catch (e) { /* not JSON */ }
        if (xhr.status >= 200 && xhr.status < 300) resolve(res);
        else reject(new ApiError(xhr.status, res));
      };
      xhr.onerror = () => reject(new Error('Network error while uploading.'));
      xhr.send(body);
    });
  }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function fmtDuration(ms) {
    if (!ms && ms !== 0) return '';
    const s = Math.round(ms / 1000);
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  }

  function fmtSize(bytes) {
    if (!bytes) return '—';
    const mb = bytes / (1024 * 1024);
    return mb >= 1 ? mb.toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB';
  }

  function fmtDate(iso) {
    return iso ? new Date(iso).toLocaleString() : '';
  }

  // Shows "log in" help when the API says 401; returns true if it did.
  function loginNeeded(e, box) {
    if (!(e instanceof ApiError) || e.status !== 401) return false;
    box.textContent = '';
    box.append('You are not logged in to Joget. ');
    const a = el('a', null, 'Log in');
    a.href = ctx + '/web/login';
    a.target = '_blank';
    box.append(a, ' (opens a new tab), then ');
    const retry = el('a', null, 'try again');
    retry.href = '#';
    retry.onclick = (ev) => { ev.preventDefault(); token = null; location.reload(); };
    box.append(retry, '.');
    box.hidden = false;
    return true;
  }

  // "Uploaded by" rows for the Info tab (the replay page and the downloaded HTML file)
  function uploadInfo(meta) {
    const who = (o) => (o.ownerName && o.ownerName !== o.owner ? o.ownerName + ' (' + o.owner + ')' : o.owner);
    const info = { 'Uploaded by': who(meta), 'Uploaded': fmtDate(meta.uploadedAt) };
    if (meta.importedFrom) {
      info['First uploaded by'] = who(meta.importedFrom);
      info['First uploaded'] = fmtDate(meta.importedFrom.uploadedAt);
    }
    return info;
  }

  window.BB = { ctx, service, url, getJson, post, el, fmtDuration, fmtSize, fmtDate, loginNeeded, ApiError, uploadInfo };
})();
