import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:8080",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      testIgnore: /layout\.spec\.ts/,
      use: { browserName: "chromium" },
    },
    {
      // レイアウトを stage している間、概況の異常帯に「未適用の変更」が出る。
      // 他の spec と同時に走らせると overview.spec.ts の「異常はありません」が
      // 壊れるため、すべてが終わってから単独で走らせる
      name: "layout",
      testMatch: /layout\.spec\.ts/,
      dependencies: ["chromium"],
      use: { browserName: "chromium" },
    },
  ],
});
