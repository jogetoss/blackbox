// Quick settings in the popup: the choices for the next recording, kept in chrome.storage.local as 'quick'.
// Keys (popup checkbox): apps (Export the apps used), sample (Capture apps data), mask (Mask data), allServer (All
// server log lines), gauges (Capture server load), traces (Capture Glowroot trace files), glowrootData (Capture
// Glowroot data). Every box starts unticked and asks for its part; the server offers only what the plugin settings
// and the user's rights allow (me.options) and checks every choice again.
// Used by the popup, the service worker (recording-start), the recorder (saved with the recording) and the upload form.

const Quick = {
  // Never touched: every box unticked, so nothing optional is captured or exported (apps data, if ticked, is real)
  DEFAULTS: { apps: false, sample: false, mask: false, allServer: false, gauges: false, traces: false, glowrootData: false },

  async get() {
    const { quick } = await chrome.storage.local.get('quick');
    return { ...Quick.DEFAULTS, ...quick };
  },

  async set(changes) {
    await chrome.storage.local.set({ quick: { ...(await Quick.get()), ...changes } });
  },

  // What session.json keeps as recording.options: the app export choices made when the recording started
  options(q) {
    return { apps: q.apps === true, sampleData: !q.sample ? 'none' : q.mask ? 'masked' : 'real' };
  },

  // The app export parameters of recording-stop and create, from recording.options (none saved: the server's defaults)
  exportParams(options) {
    if (!options) return {};
    if (options.apps === false) return { apps: 'false' };
    return options.sampleData ? { sampleData: options.sampleData } : {};
  },
};
