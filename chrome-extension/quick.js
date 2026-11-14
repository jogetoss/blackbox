const Quick = {
  DEFAULTS: { apps: false, sample: false, mask: false, allServer: false, gauges: false, traces: false, otherTraces: false,
    glowrootData: false, credentials: false, systemSecrets: false, license: false },

  async get() {
    const { quick } = await chrome.storage.local.get('quick');
    return { ...Quick.DEFAULTS, ...quick };
  },

  async set(changes) {
    await chrome.storage.local.set({ quick: { ...(await Quick.get()), ...changes } });
  },

  options(q) {
    return { apps: q.apps === true, sampleData: !q.sample ? 'none' : q.mask ? 'masked' : 'real' };
  },

  exportParams(options) {
    if (!options) return {};
    if (options.apps === false) return { apps: 'false' };
    return options.sampleData ? { sampleData: options.sampleData } : {};
  },
};
