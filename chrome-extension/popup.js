const $ = (id) => document.getElementById(id);
let timer = null;

async function bg(msg) {
  const res = await chrome.runtime.sendMessage({ target: 'background', ...msg });
  if (res?.error) throw new Error(res.error);
  return res;
}

// Warns about the system log before and during a recording ('' = nothing to say).
// Before: the system log comes only from the Upload URL's server, so a tab on another server gets none.
// During: why the server didn't start capturing (background saves rec.systemLog = { status: 'off', message }).
async function systemLogNote(rec) {
  if (rec) return rec.systemLog?.status === 'off' ? `System logs off: ${rec.systemLog.message}` : '';
  const { uploadUrl } = await chrome.storage.local.get('uploadUrl');
  if (!uploadUrl) return 'No Upload URL in Settings: no system logs.';
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!/^https?:/.test(tab?.url || '')) return '';
  const server = new URL(uploadUrl).origin;
  const here = new URL(tab.url).origin;
  return server === here ? '' : `System logs come only from ${server}, but this tab is on ${here}. Change the Upload URL in Settings if needed.`;
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

function render(rec) {
  $('start').hidden = !!rec;
  $('stop').hidden = !rec;
  systemLogNote(rec).then((note) => { $('systemLog').replaceChildren(...linkify(note)); $('systemLog').hidden = !note; }, () => {});
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
