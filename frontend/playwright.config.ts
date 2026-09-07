import {defineConfig} from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: "http://127.0.0.1:18080",
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: (process.platform === "win32" ? "..\\gradlew.bat" : "../gradlew") + " -p .. --console=plain runWebFixture -PwebPort=18080",
    url: "http://127.0.0.1:18080/api/state",
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
});
