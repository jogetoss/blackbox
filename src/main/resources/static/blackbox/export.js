window.blackboxPage = 'file';
(async function () {
  const $ = (id) => document.getElementById(id);
  const text = (id) => ($(id) ? $(id).textContent : '');
  try {
    const meta = JSON.parse(text('bb-meta'));
    const report = JSON.parse(text('bb-session'));
    const systemLog = text('bb-systemlog') ? JSON.parse(text('bb-systemlog')) : null;
    const traces = text('bb-traces') ? JSON.parse(text('bb-traces')) : null;
    const gauges = text('bb-gauges') ? JSON.parse(text('bb-gauges')) : null;
    const serverInfo = text('bb-serverinfo') ? JSON.parse(text('bb-serverinfo')) : null;
    const apps = text('bb-apps') ? JSON.parse(text('bb-apps')) : null;

    for (const id of ['dlZip', 'dlHtml', 'dlGlowroot', 'delete', 'back']) if ($(id)) $(id).hidden = true;
    $('where').textContent = 'Downloaded file';
    $('where').title = 'Everything is inside this file. Opening it sends nothing anywhere.';
    document.addEventListener('drop', (e) => { e.preventDefault(); e.stopImmediatePropagation(); }, true);

    if (meta.notes) {
      $('notes').textContent = meta.notes;
      $('notes').hidden = false;
    }

    const b64 = text('bb-video').trim();
    const video = b64 ? await (await fetch('data:video/webm;base64,' + b64)).blob() : null;
    $('serverLegend').hidden = !systemLog;
    window.blackboxTimeInUrl = true;
    const pages = await tracePages(meta.tracesSandboxed === true);
    window.blackboxOpen(video, report, {
      systemLog, traces, gauges, serverInfo, apps, title: meta.title, info: uploadInfo(meta),
      traceUrl: (t) => pages.get(t.file) || null,
    });
    $('recStatus').hidden = false;
  } catch (e) {
    $('loading').textContent = '';
    window.blackboxShowError('This file could not be opened: ' + (e.message || e));
  }

  async function tracePages(sandboxed) {
    const pages = new Map();
    if (typeof DecompressionStream !== 'function') return pages;
    for (const block of document.querySelectorAll('script[type="application/gzip"][id^="bb-trace-"]')) {
      try {
        const gz = await (await fetch('data:application/gzip;base64,' + block.textContent.trim())).blob();
        const html = await new Response(gz.stream().pipeThrough(new DecompressionStream('gzip'))).blob();
        const page = sandboxed ? await sandbox(await html.text()) : html;
        pages.set(block.dataset.file, URL.createObjectURL(new Blob([page], { type: 'text/html' })));
      } catch (e) { }
    }
    return pages;
  }

  function sandbox(html) {
    const attr = html.replace(/&/g, '&amp;').replace(/"/g, '&quot;');
    return '<!doctype html><meta charset="utf-8"><title>Trace<\/title>'
      + '<style>html,body,iframe{margin:0;border:0;width:100%;height:100%;display:block}<\/style>'
      + '<iframe sandbox="allow-scripts allow-popups" srcdoc="' + attr + '"><\/iframe>';
  }

  function uploadInfo(meta) {
    const who = (o) => (o.ownerName && o.ownerName !== o.owner ? o.ownerName + ' (' + o.owner + ')' : o.owner);
    const date = (iso) => {
      if (!iso) return '';
      const d = new Date(iso);
      return isNaN(d) ? String(iso) : d.toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
    };
    const info = { 'Uploaded by': who(meta), 'Uploaded': date(meta.uploadedAt) };
    if (meta.importedFrom) {
      info['First uploaded by'] = who(meta.importedFrom);
      info['First uploaded'] = date(meta.importedFrom.uploadedAt);
    }
    return info;
  }
})();
