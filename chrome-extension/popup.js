const $ = (id) => document.getElementById(id);
let timer = null;

async function bg(msg) {
  const res = await chrome.runtime.sendMessage({ target: 'background', ...msg });
  if (res?.error) throw new Error(res.error);
  return res;
}

async function serverDataNote(rec) {
  if (rec) return rec.serverCapture?.status === 'off' ? `Server data off: ${rec.serverCapture.message}` : '';
  const { uploadUrl } = await chrome.storage.local.get('uploadUrl');
  if (!uploadUrl) return 'No Upload URL in Settings: no server data.';
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!/^https?:/.test(tab?.url || '')) return '';
  const server = new URL(uploadUrl).origin;
  const here = new URL(tab.url).origin;
  return server === here ? '' : `Server data comes only from ${server}, but this tab is on ${here}. Change the Upload URL in Settings if needed.`;
}

function linkify(text) {
  const parts = [];
  let at = 0;
  for (const m of text.matchAll(/https?:\/\/\S+?(?=[.,;:!?)]*(?:\s|$))|\bSettings\b/g)) {
    const t = m[0];
    const a = document.createElement('a');
    a.href = '#';
    a.textContent = t;
    a.onclick = (e) => {
      e.preventDefault();
      if (t === 'Settings') chrome.runtime.openOptionsPage();
      else chrome.tabs.create({ url: t });
    };
    parts.push(text.slice(at, m.index), a);
    at = m.index + t.length;
  }
  parts.push(text.slice(at));
  return parts;
}

const QUICK_BOXES = [['qApps', 'apps'], ['qSample', 'sample'], ['qMask', 'mask'], ['qAllServer', 'allServer'],
  ['qGauges', 'gauges'], ['qTraces', 'traces'], ['qOtherTraces', 'otherTraces'], ['qGlowroot', 'glowrootData'],
  ['qCreds', 'credentials'], ['qSysSecrets', 'systemSecrets'], ['qLicense', 'license']];
let offer = null;

async function initQuick() {
  const s = await chrome.storage.local.get(['recordPageAudio', 'recordMic', 'quickOffer']);
  $('qPage').checked = s.recordPageAudio === true;
  $('qMic').checked = !!s.recordMic;
  $('qPage').onchange = () => chrome.storage.local.set({ recordPageAudio: $('qPage').checked });
  $('qMic').onchange = () => chrome.storage.local.set({ recordMic: $('qMic').checked });
  const q = await Quick.get();
  for (const [box, key] of QUICK_BOXES) {
    $(box).checked = !!q[key];
    $(box).onchange = () => { Quick.set({ [key]: $(box).checked }); showQuick(offer); };
  }
  showQuick(s.quickOffer || null);
  const fresh = await serverOffer().catch(() => undefined);
  if (fresh !== undefined) {
    showQuick(fresh);
    await chrome.storage.local.set({ quickOffer: fresh });
  }
}

async function serverOffer() {
  const { uploadUrl } = await Server.getSettings();
  if (!uploadUrl || !(await Server.hasPermission(uploadUrl))) return null;
  try {
    return (await Server.me(uploadUrl)).options || null;
  } catch (e) {
    if (e.status === 401) {
      $('qNote').textContent = `Log in to ${new URL(uploadUrl).origin} for the server's options.`;
      $('qNote').hidden = false;
      return null;
    }
    throw e;
  }
}

function showQuick(o) {
  offer = o;
  $('qAppsRow').hidden = !o?.apps;
  $('qSampleRow').hidden = !o?.sample;
  $('qMaskRow').hidden = !o?.mask;
  $('qSample').disabled = !$('qApps').checked;
  $('qMask').disabled = !$('qApps').checked || !$('qSample').checked;
  $('qRealWarn').hidden = $('qSampleRow').hidden || $('qSample').disabled || !$('qSample').checked
    || (!$('qMaskRow').hidden && $('qMask').checked);
  $('qAllServerRow').hidden = !o?.allServer;
  $('qGaugesRow').hidden = !o?.gauges;
  $('qTracesRow').hidden = !o?.traces;
  $('qOtherTracesRow').hidden = !o?.otherTraces;
  $('qOtherTraces').disabled = !$('qTraces').checked;
  $('qGlowrootRow').hidden = !o?.glowrootData;
  $('qCredsRow').hidden = !o?.credentials;
  $('qSysSecretsRow').hidden = !o?.systemSecrets;
  $('qLicenseRow').hidden = !o?.license;
  armOffered = !!o?.arm;
  showArm();
  $('qServer').hidden = !(o?.apps || o?.allServer || o?.gauges || o?.traces || o?.glowrootData || o?.credentials
    || o?.systemSecrets || o?.license);
}

let armOffered = false;
let recording = false;

async function showArm() {
  const armed = await bg({ type: 'get-armed' }).catch(() => null);
  $('armLink').hidden = !!armed;
  $('armNote').hidden = !armed;
  if (armed) {
    const until = new Date(armed.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const cancel = document.createElement('a');
    cancel.href = '#';
    cancel.id = 'disarm';
    cancel.textContent = 'Cancel';
    cancel.onclick = async (e) => { e.preventDefault(); await bg({ type: 'disarm' }); showArm(); };
    $('armNote').replaceChildren(`Prepared until ${until}. Log out of ${new URL(armed.uploadUrl).origin}, open the page with the problem, then start recording. `, cancel);
  }
  $('arm').hidden = recording || !(armed || armOffered);
}

$('armLink').onclick = async (e) => {
  e.preventDefault();
  $('error').textContent = '';
  try {
    await bg({ type: 'arm' });
  } catch (err) {
    showError(err);
  }
  showArm();
};

function render(rec) {
  recording = !!rec;
  showArm();
  $('quick').hidden = !!rec;
  $('start').hidden = !!rec;
  $('stop').hidden = !rec;
  serverDataNote(rec).then((note) => { $('serverNote').replaceChildren(...linkify(note)); $('serverNote').hidden = !note; }, () => {});
  clearInterval(timer);
  if (!rec) { $('status').textContent = ''; return; }
  if (!rec.startedAt) { $('status').textContent = rec.mode === 'tab' ? 'Starting…' : 'Pick what to record in the recorder window.'; return; }
  const tick = () => {
    const s = Math.floor((Date.now() - rec.startedAt) / 1000);
    $('status').textContent = `Recording… ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  };
  tick();
  timer = setInterval(tick, 1000);
}

function showError(e) {
  $('error').textContent = e.message || String(e);
}

for (const btn of document.querySelectorAll('#start button')) {
  btn.onclick = async () => {
    const mode = btn.dataset.mode;
    for (const b of document.querySelectorAll('#start button')) b.disabled = true;
    $('error').textContent = '';
    try {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      const tabCapture = chrome.tabCapture.getMediaStreamId({ targetTabId: tab.id });
      const streamId = mode === 'tab' ? await tabCapture : await tabCapture.catch(() => undefined);
      await bg({ type: 'start', tabId: tab.id, mode, streamId });
      window.close();
    } catch (e) {
      showError(e);
    } finally {
      for (const b of document.querySelectorAll('#start button')) b.disabled = false;
    }
  };
}

$('stop').onclick = async () => {
  $('stop').disabled = true;
  $('status').textContent = 'Saving…';
  try {
    const res = await bg({ type: 'stop' });
    render(null);
    $('status').textContent = res ? 'Saved. Upload or download it in the viewer.' : 'Nothing was recording.';
  } catch (e) {
    showError(e);
  } finally {
    $('stop').disabled = false;
  }
};

$('viewer').onclick = (e) => {
  e.preventDefault();
  chrome.tabs.create({ url: chrome.runtime.getURL('recordings.html') });
};

$('settings').onclick = (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
};

bg({ type: 'get-state' }).then(render, showError);
initQuick().catch(showError);
if (chrome.extension.inIncognitoContext) {
  $('incognitoNote').textContent = 'Incognito: uploads use this window\'s login. Recordings kept here are lost when you close incognito: upload or download them first.';
  $('incognitoNote').hidden = false;
}
