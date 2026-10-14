(function () {
  const { el, fmtDate, fmtDuration, fmtSize } = BB;
  const $ = (id) => document.getElementById(id);
  let recordings = [];

  load();
  $('search').oninput = render;

  async function load() {
    try {
      const data = await BB.getJson('list');
      $('who').textContent = data.user.name;
      recordings = data.recordings;
      $('content').hidden = false;
      render();
    } catch (e) {
      if (!BB.loginNeeded(e, $('error'))) showError(e);
    }
  }

  function render() {
    const q = $('search').value.trim().toLowerCase();
    const shown = recordings.filter((r) => !q || [r.title, r.ownerName, r.owner, r.notes, r.recording?.tab?.url]
      .some((v) => (v || '').toLowerCase().includes(q)));
    $('rows').textContent = '';
    for (const r of shown) $('rows').append(row(r));
    $('table').hidden = shown.length === 0;
    $('empty').hidden = shown.length > 0;
    $('empty').textContent = recordings.length ? 'No recording matches the filter.'
      : 'No recordings yet. Record with the BlackBox Chrome extension and click Upload in its viewer.';
  }

  function row(r) {
    const tr = el('tr');
    const view = 'view?id=' + encodeURIComponent(r.id);

    const title = el('td', 'title');
    const a = el('a', null, r.title);
    a.href = view;
    title.append(a);
    if (r.recording?.tab?.url) title.append(el('div', 'url', r.recording.tab.url));
    if (r.notes) title.append(el('div', 'muted', r.notes.length > 140 ? r.notes.slice(0, 140) + '…' : r.notes));
    tr.append(title);

    const owner = el('td', 'hide-sm', r.ownerName || r.owner);
    owner.title = r.owner;
    tr.append(owner);
    tr.append(el('td', 'num', fmtDate(r.uploadedAt)));
    tr.append(el('td', 'num hide-sm', fmtDuration(r.recording?.durationMs)));

    const issues = el('td', 'num');
    const s = r.summary || {};
    issues.append(el('span', 'badge' + (s.errors ? ' err' : ''), (s.errors || 0) + ' errors'), ' ');
    issues.append(el('span', 'badge' + (s.failedRequests ? ' warn' : ''), (s.failedRequests || 0) + ' failed'));
    tr.append(issues);

    tr.append(el('td', 'num hide-sm', r.videoSize ? fmtSize(r.videoSize) : 'missing'));

    const actions = el('td', 'actions');
    const v = el('a', null, 'View');
    v.href = view;
    actions.append(v);
    if (r.videoSize) {
      const dv = el('a', null, 'Video');
      dv.href = BB.url('video', { id: r.id, download: 1 });
      dv.title = 'Download recording.webm';
      actions.append(dv);
    }
    const ds = el('a', null, 'JSON');
    ds.href = BB.url('session', { id: r.id, download: 1 });
    ds.title = 'Download session.json';
    actions.append(ds);
    if (r.canDelete) {
      const del = el('button', 'del', 'Delete');
      del.onclick = () => remove(r, del);
      actions.append(del);
    }
    tr.append(actions);
    return tr;
  }

  async function remove(r, button) {
    if (!confirm('Delete "' + r.title + '"? This cannot be undone.')) return;
    button.disabled = true;
    try {
      await BB.post('delete', { id: r.id });
      recordings = recordings.filter((x) => x.id !== r.id);
      render();
    } catch (e) {
      button.disabled = false;
      showError(e);
    }
  }

  function showError(e) {
    $('error').textContent = e.message || String(e);
    $('error').hidden = false;
  }
})();
