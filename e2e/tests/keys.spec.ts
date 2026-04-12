import { test, expect } from "@playwright/test";

test.describe("Keys", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("text=Garage Admin")).toBeVisible({
      timeout: 30_000,
    });
    await page.locator("text=Keys").click();
  });

  test("shows the dev-key created by init", async ({ page }) => {
    await expect(page.locator("text=dev-key")).toBeVisible({
      timeout: 15_000,
    });
  });

  test("has create and import key buttons", async ({ page }) => {
    await expect(page.locator("text=Create Key")).toBeVisible();
    await expect(page.locator("text=Import Key")).toBeVisible();
  });
});
