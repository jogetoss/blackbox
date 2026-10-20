// Start-up code of a downloaded recording (one .html file, see RecordingHtml.java): the recording is inside this file,
// in <script> data blocks written before this one. No server, no extension, no network.
window.blackboxPage = 'file'; // for the Features list
(async function () {
  const $ = (id) => document.getElementById(id);
  const text = (id) => ($(id) ? $(id).textContent : '');
  try {
    const meta = JSON.parse(text('bb-meta'));
    const report = JSON.parse(text('bb-session'));
    const systemLog = text('bb-systemlog') ? JSON.parse(text('bb-systemlog')) : null;
    const traces = text('bb-traces') ? JSON.parse(text('bb-traces')) : null; // summaries; the full pages are in the ZIP
    const gauges = text('bb-gauges') ? JSON.parse(text('bb-gauges')) : null;
    const serverInfo = text('bb-serverinfo') ? JSON.parse(text('bb-serverinfo')) : null;

    // Nothing here talks to a server: no downloads, no delete, no link to the recordings list
    for (const id of ['dlZip', 'dlHtml', 'dlGlowroot', 'delete', 'back']) if ($(id)) $(id).hidden = true;
    $('where').textContent = 'Downloaded file';
    $('where').title = 'Everything is inside this file. Opening it sends nothing anywhere.';
    document.addEventListener('drop', (e) => { e.preventDefault(); e.stopImmediatePropagation(); }, true);

    if (meta.notes) {
      $('notes').textContent = meta.notes;
      $('notes').hidden = false;
    }

    // Base64 in the page -> Blob; fetch() of a data: URL decodes large videos without blocking the page
    const b64 = text('bb-video').trim();
    const video = b64 ? await (await fetch('data:video/webm;base64,' + b64)).blob() : null;
    $('serverLegend').hidden = !systemLog;
    window.blackboxTimeInUrl = true; // blackbox-….html?t=… opens at that moment
    window.blackboxOpen(video, report, { systemLog, traces, gauges, serverInfo, title: meta.title, info: uploadInfo(meta) });
    $('recStatus').hidden = false;
  } catch (e) {
    $('loading').textContent = '';
    window.blackboxShowError('This file could not be opened: ' + (e.message || e));
  }

  // "Uploaded by" rows for the Info tab; the same as BB.uploadInfo on the server (common.js is not in this file)
  function uploadInfo(meta) {
    const who = (o) => (o.ownerName && o.ownerName !== o.owner ? o.ownerName + ' (' + o.owner + ')' : o.owner);
    const date = (iso) => (iso ? new Date(iso).toLocaleString() : '');
    const info = { 'Uploaded by': who(meta), 'Uploaded': date(meta.uploadedAt) };
    if (meta.importedFrom) {
      info['First uploaded by'] = who(meta.importedFrom);
      info['First uploaded'] = date(meta.importedFrom.uploadedAt);
    }
    return info;
  }
})();
