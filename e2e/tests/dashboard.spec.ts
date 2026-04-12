import { test, expect } from "@playwright/test";

test.describe("Dashboard", () => {
  test("shows cluster status and node information", async ({ page }) => {
    await page.goto("/");

    // Wait for the Compose WASM app to load
    await expect(page.locator("text=Garage Admin")).toBeVisible({
      timeout: 30_000,
    });

    // Dashboard should be the default screen
    await expect(page.locator("text=Dashboard")).toBeVisible();

    // Should show cluster health information
    await expect(page.locator("text=Connected Nodes")).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.locator("text=Storage Nodes")).toBeVisible();
  });
});
