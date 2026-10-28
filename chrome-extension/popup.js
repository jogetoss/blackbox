const $ = (id) => document.getElementById(id);
let timer = null;

async function bg(msg) {
  const res = await chrome.runtime.sendMessage({ target: 'background', ...msg });
  if (res?.error) throw new Error(res.error);
  return res;
}

// Warns about the server capture before and during a recording ('' = nothing to say).
// Before: server data comes only from the Upload URL's server, so a tab on another server gets none.
// During: why the server didn't start capturing (background saves rec.serverCapture = { status: 'off', message }).
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

// Like Actions.linkify on the replay page: addresses open in a new tab, "Settings" opens Settings
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

// ---------- quick settings ----------
// Sound: this browser's settings. Server options: only those the server offers
// (me.options), remembered as quickOffer so they show at once the next time the popup opens.

// [checkbox, Quick key, ticked = !value]: Mask data is the inverse of the real key
const QUICK_BOXES = [['qApps', 'apps'], ['qSample', 'sample'], ['qMask', 'real', true], ['qAllServer', 'allServer'], ['qGlowroot', 'glowrootData']];
let offer = null;

async function initQuick() {
  const s = await chrome.storage.local.get(['recordPageAudio', 'recordMic', 'quickOffer']);
  $('qPage').checked = s.recordPageAudio !== false; // on unless turned off
  $('qMic').checked = !!s.recordMic;
  $('qPage').onchange = () => chrome.storage.local.set({ recordPageAudio: $('qPage').checked });
  $('qMic').onchange = () => chrome.storage.local.set({ recordMic: $('qMic').checked });
  const q = await Quick.get();
  for (const [box, key, inverse] of QUICK_BOXES) {
    $(box).checked = inverse ? !q[key] : q[key];
    $(box).onchange = () => { Quick.set({ [key]: inverse ? !$(box).checked : $(box).checked }); showQuick(offer); };
  }
  showQuick(s.quickOffer || null);
  const fresh = await serverOffer().catch(() => undefined); // not reachable: keep what was known
  if (fresh !== undefined) {
    showQuick(fresh);
    await chrome.storage.local.set({ quickOffer: fresh });
  }
}

// The server's me.options, or null when there is no server to ask or the user isn't logged in there
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

// Sample data needs the app export, Mask data needs sample data
function showQuick(o) {
  offer = o;
  $('qAppsRow').hidden = !o?.apps;
  $('qSampleRow').hidden = !o?.sample;
  $('qMaskRow').hidden = !o?.real; // only users who may keep real values get the choice; for the rest it is masked
  $('qSample').disabled = !$('qApps').checked;
  $('qMask').disabled = !$('qApps').checked || !$('qSample').checked;
  $('qMaskWarn').hidden = $('qMaskRow').hidden || $('qMask').disabled || $('qMask').checked;
  $('qAllServerRow').hidden = !o?.allServer;
  $('qGlowrootRow').hidden = !o?.glowrootData;
  $('qServer').hidden = !(o?.apps || o?.allServer || o?.glowrootData);
}

function render(rec) {
  $('quick').hidden = !!rec; // the choices are made: they apply from the start
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
    const mode = btn.dataset.mode; // 'tab' | 'window' | 'screen'
    for (const b of document.querySelectorAll('#start button')) b.disabled = true;
    $('error').textContent = '';
    try {
      // Logs always come from this tab. 'tab' records it directly; 'window'/'screen' show the picker.
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      // Tab capture must be asked for here: opening this popup is the user's permission for this tab.
      // 'window'/'screen' use it only for the tab's sound, so they still start without it.
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
