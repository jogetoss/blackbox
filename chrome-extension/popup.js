const $ = (id) => document.getElementById(id);
let timer = null;

async function bg(msg) {
  const res = await chrome.runtime.sendMessage({ target: 'background', ...msg });
  if (res?.error) throw new Error(res.error);
  return res;
}

function render(rec) {
  $('start').hidden = !!rec;
  $('stop').hidden = !rec;
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
      // Tab capture must be asked for here: opening this popup is the user's permission for this tab
      const streamId = mode === 'tab' ? await chrome.tabCapture.getMediaStreamId({ targetTabId: tab.id }) : undefined;
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
