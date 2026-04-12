import { test, expect } from "@playwright/test";

test.describe("Keys", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(
      page.getByRole("button", { name: "Dashboard" })
    ).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: "Keys" }).click({ force: true });
  });

  test("displays access keys screen", async ({ page }) => {
    await expect(page.getByText("Access Keys")).toBeVisible();
  });

  test("has create and import key buttons", async ({ page }) => {
    await expect(page.getByText("Create Key")).toBeVisible();
    await expect(page.getByText("Import Key")).toBeVisible();
  });
});
