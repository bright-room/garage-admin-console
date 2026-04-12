import { test, expect } from "@playwright/test";

test.describe("Buckets", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(
      page.getByRole("button", { name: "Dashboard" })
    ).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: "Buckets" }).click({ force: true });
  });

  test("displays buckets screen", async ({ page }) => {
    await expect(page.getByText("Create Bucket")).toBeVisible();
  });
});
