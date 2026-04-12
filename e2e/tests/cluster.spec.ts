import { test, expect } from "@playwright/test";

test.describe("Cluster", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(
      page.getByRole("button", { name: "Dashboard" })
    ).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: "Cluster" }).click({ force: true });
  });

  test("displays cluster nodes screen", async ({ page }) => {
    await expect(page.getByText("Cluster Nodes")).toBeVisible();
  });

  test("shows connect node button", async ({ page }) => {
    await expect(page.getByText("Connect Node")).toBeVisible();
  });
});
