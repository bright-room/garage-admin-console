import { test, expect } from "@playwright/test";

test.describe("Cluster", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("text=Garage Admin")).toBeVisible({
      timeout: 30_000,
    });
    await page.locator("text=Cluster").click();
  });

  test("displays node information", async ({ page }) => {
    await expect(page.locator("text=Cluster Nodes")).toBeVisible();

    // Should show the Garage version of the running node
    await expect(page.locator("text=v2.2.0")).toBeVisible({
      timeout: 15_000,
    });
  });

  test("shows connect node button", async ({ page }) => {
    await expect(page.locator("text=Connect Node")).toBeVisible();
  });
});
