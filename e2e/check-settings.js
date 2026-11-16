// Runs once before the suite: the test user needs these rights from the BlackBox settings.
// No user has built-in rights (admins follow the settings too), so a missing one fails many tests with unclear errors.
const { request } = require('@playwright/test');

const JOGET = process.env.BB_JOGET || 'http://localhost:3128/jw';
const USER = process.env.BB_USER || 'admin';
const PASSWORD = process.env.BB_PASSWORD || USER;

// me.options key -> the setting that gives it
const NEEDED = {
  apps: 'Allow exporting the apps used in the recording + Who can export apps they don\'t administer (the user, e.g. Is Admin)',
  sample: 'Allow capturing apps data',
  mask: 'Allow masking apps data',
  systemSecrets: 'Allow recording System Info secrets unmasked',
};

module.exports = async () => {
  if (process.env.BB_SKIP_SETTINGS_CHECK) return;
  const http = await request.newContext({ ignoreHTTPSErrors: true });
  try {
    await http.get(`${JOGET}/web/login`);
    const token = await (await http.post(`${JOGET}/csrf`, { headers: { 'FETCH-CSRF-TOKEN': '1' } })).text();
    const at = token.indexOf(':');
    const [name, value] = [token.slice(0, at), token.slice(at + 1).trim()];
    await http.post(`${JOGET}/j_spring_security_check`, {
      headers: { [name]: value }, form: { j_username: USER, j_password: PASSWORD, [name]: value }, maxRedirects: 0,
    });
    const res = await http.get(`${JOGET}/api/blackbox?action=me`);
    const me = res.ok() ? await res.json().catch(() => null) : null;
    if (!me || !me.options) {
      throw new Error(`BlackBox e2e: could not read ${JOGET}/api/blackbox?action=me as ${USER} (HTTP ${res.status()}). `
        + 'Is the plugin installed and the login right?');
    }
    const missing = [];
    if (!me.canUpload) missing.push('Who can upload (must include the user)');
    for (const [key, setting] of Object.entries(NEEDED)) {
      if (!me.options[key]) missing.push(setting);
    }
    if (missing.length) {
      throw new Error(`BlackBox e2e: ${USER} lacks rights the suite needs. In Manage Plugins > BlackBox > Configure set:\n- `
        + missing.join('\n- ') + '\nAlso tick "Allow apps data from apps they don\'t administer" unless the user is an '
        + 'admin of iService. See e2e/README.md "Settings the suite needs". BB_SKIP_SETTINGS_CHECK=1 skips this check.');
    }
  } finally {
    await http.dispose();
  }
};
