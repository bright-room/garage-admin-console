import { test, expect } from "@playwright/test";

test.describe("Buckets", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("text=Garage Admin")).toBeVisible({
      timeout: 30_000,
    });
    await page.locator("text=Buckets").click();
  });

  test("shows the dev-bucket created by init", async ({ page }) => {
    // The init script creates a 'dev-bucket'
    await expect(page.locator("text=dev-bucket")).toBeVisible({
      timeout: 15_000,
    });
  });

  test("has create bucket button", async ({ page }) => {
    await expect(page.locator("text=Create Bucket")).toBeVisible();
  });
});
