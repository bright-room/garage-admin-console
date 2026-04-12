import { test, expect } from "@playwright/test";

test.describe("Layout", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("text=Garage Admin")).toBeVisible({
      timeout: 30_000,
    });
    await page.locator("text=Layout").click();
  });

  test("displays layout version and roles", async ({ page }) => {
    await expect(page.locator("text=Cluster Layout")).toBeVisible();

    // Layout was assigned by init script, so version should be visible
    await expect(page.locator("text=Current Roles")).toBeVisible({
      timeout: 15_000,
    });
  });

  test("shows assign node button", async ({ page }) => {
    await expect(page.locator("text=Assign Node")).toBeVisible();
  });
});
