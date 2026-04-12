import { test, expect } from "@playwright/test";

test.describe("Dashboard", () => {
  test("loads the app and shows sidebar", async ({ page }) => {
    await page.goto("/");

    // Wait for the Compose WASM app to load
    await expect(page.getByRole("button", { name: "Dashboard" })).toBeVisible({
      timeout: 30_000,
    });

    // Sidebar should show all navigation items
    await expect(page.getByRole("button", { name: "Cluster" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Buckets" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Keys" })).toBeVisible();
  });
});
