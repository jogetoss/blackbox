const $ = (id) => document.getElementById(id);

let settings = { uploadUrl: '', viewUrl: '' };
Server.getSettings().then((saved) => {
  settings = saved;
  $('url').value = saved.uploadUrl;
  $('viewUrl').value = saved.viewUrl;
  if (saved.uploadUrl) check(saved.uploadUrl);
});

chrome.storage.local.get('theme').then(({ theme }) => { $('theme').value = theme || ''; });
$('theme').onchange = () => chrome.storage.local.set({ theme: $('theme').value });

for (const id of ['url', 'viewUrl']) $(id).onkeydown = (e) => { if (e.key === 'Enter') $('save').click(); };

$('save').onclick = () => {
  let next;
  try {
    next = {
      uploadUrl: $('url').value.trim() ? Server.normalize($('url').value) : '',
      viewUrl: optionalUrl($('viewUrl').value, 'Recording page URL'),
    };
    if (next.viewUrl && !next.viewUrl.includes('{id}')) throw new Error('The Recording page URL needs {id} where the recording id goes.');
  } catch (e) {
    show('error', e.message);
    return;
  }
  if (!next.uploadUrl) {
    // Empty Upload URL: uploads are turned off
    chrome.storage.local.set(next).then(() => { settings = next; show('', ''); });
    return;
  }
  // Ask for access to the server while this click still counts as a user gesture
  Server.requestPermission(next.uploadUrl).then(async (granted) => {
    if (!granted) {
      show('error', 'BlackBox needs access to ' + new URL(next.uploadUrl).origin + ' to upload there.');
      return;
    }
    $('url').value = next.uploadUrl;
    await chrome.storage.local.set(next);
    settings = next;
    check(next.uploadUrl);
  }, (e) => show('error', e.message));
};

// '' stays ''; anything else must be an http(s) URL. Kept as typed ({id} must not be encoded).
function optionalUrl(value, name) {
  value = value.trim();
  if (!value) return '';
  if (!/^https?:\/\/[^/\s]+/.test(value)) throw new Error(`The ${name} must start with http:// or https://`);
  return value;
}

async function check(base) {
  if (!(await Server.hasPermission(base))) {
    show('error', 'Click Save to give BlackBox access to ' + new URL(base).origin + '.');
    return;
  }
  // Only problems are shown; a working connection shows nothing
  show('', '');
  try {
    const me = await Server.me(base);
    if (!me.canUpload) show('error', `Logged in as ${me.user.name}, but you are not allowed to upload. Ask the server's admin.`);
  } catch (e) {
    if (e.status === 401) show('', ''); // logged out right now; uploads work once you log in
    else show('error', e.message);
  }
}

function show(cls, text) {
  $('status').className = cls;
  $('status').textContent = text;
}
