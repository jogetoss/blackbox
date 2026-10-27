// Quick settings in the popup: the choices for the next recording, kept in chrome.storage.local as 'quick'.
// The server ones can only turn a BlackBox plugin setting off; the server checks them again.
// Used by the popup, the service worker (recording-start), the recorder (saved with the recording) and the upload form.

const Quick = {
  // Never touched: as without quick settings (everything the server allows, masked sample data)
  DEFAULTS: { apps: true, sample: true, real: false, allServer: true, glowrootData: true },

  async get() {
    const { quick } = await chrome.storage.local.get('quick');
    return { ...Quick.DEFAULTS, ...quick };
  },

  async set(changes) {
    await chrome.storage.local.set({ quick: { ...(await Quick.get()), ...changes } });
  },

  // What session.json keeps as recording.options: the app export choices made when the recording started
  options(q) {
    return { apps: q.apps !== false, sampleData: !q.sample ? 'none' : q.real ? 'real' : 'masked' };
  },

  // The app export parameters of recording-stop and create, from recording.options (none saved: the server's defaults)
  exportParams(options) {
    if (!options) return {};
    return options.apps === false ? { apps: 'false' } : { sampleData: options.sampleData || 'masked' };
  },
};
