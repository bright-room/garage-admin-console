import { test, expect } from "@playwright/test";

test.describe("Layout", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(
      page.getByRole("button", { name: "Dashboard" })
    ).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: "Layout" }).click({ force: true });
  });

  test("displays cluster layout screen", async ({ page }) => {
    await expect(page.getByText("Cluster Layout")).toBeVisible();
  });

  test("shows assign node button", async ({ page }) => {
    await expect(page.getByText("Assign Node")).toBeVisible();
  });
});
