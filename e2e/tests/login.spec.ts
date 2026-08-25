import { test, expect } from "@playwright/test";
import { adminToken, clickButton, signIn, waitForLoginScreen } from "./helpers";

const token = adminToken();

test.describe("Login", () => {
  test("shows the login screen when no token is stored", async ({ page }) => {
    await page.goto("/");

    await waitForLoginScreen(page);
  });

  test("rejects an invalid token", async ({ page }) => {
    const invalid = "not-a-real-token";

    await page.goto("/");
    await waitForLoginScreen(page);

    await page.getByRole("textbox").first().fill(invalid, { force: true });
    await expect(page.getByText("•".repeat(invalid.length), { exact: true })).toBeVisible();
    await clickButton(page, "ログイン");

    await expect(page.getByText(/受け付けられませんでした/)).toBeVisible();
  });

  test("signs in with a valid token and survives a reload", async ({ page }) => {
    await page.goto("/");
    await signIn(page, token);

    // sessionStorage に保持されるため、リロードでもログイン状態が保たれる
    await page.reload();
    await expect(page.getByRole("button", { name: "ログアウト" })).toBeVisible({
      timeout: 30_000,
    });
  });

  test("signs out and returns to the login screen", async ({ page }) => {
    await page.goto("/");
    await signIn(page, token);

    await clickButton(page, "ログアウト");

    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible();

    await page.reload();
    await waitForLoginScreen(page);
  });
});
