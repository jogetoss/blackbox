// Browser tests of the BlackBox extension (see README.md). One worker: the tests share one Joget server.
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  workers: 1,
  timeout: 60_000,
  reporter: [['list']],
  use: { trace: 'retain-on-failure' },
});
