import { test, expect } from "@playwright/test";

const token = process.env.E2E_ADMIN_TOKEN;

test.beforeAll(() => {
  if (!token) {
    throw new Error(
      "E2E_ADMIN_TOKEN が未設定です。docker compose logs garage-init から取得してください",
    );
  }
});

test.describe("Login", () => {
  test("shows the login screen when no token is stored", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });
  });

  test("rejects an invalid token", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("textbox").first().fill("not-a-real-token", { force: true });
    await page.getByRole("button", { name: "ログイン" }).click({ force: true });

    await expect(page.getByText(/受け付けられませんでした/)).toBeVisible();
  });

  test("signs in with a valid token and survives a reload", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("textbox").first().fill(token!, { force: true });
    await page.getByRole("button", { name: "ログイン" }).click({ force: true });

    await expect(page.getByRole("button", { name: "ログアウト" })).toBeVisible({
      timeout: 15_000,
    });

    // sessionStorage に保持されるため、リロードでもログイン状態が保たれる
    await page.reload();
    await expect(page.getByRole("button", { name: "ログアウト" })).toBeVisible({
      timeout: 30_000,
    });
  });

  test("signs out and returns to the login screen", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });
    await page.getByRole("textbox").first().fill(token!, { force: true });
    await page.getByRole("button", { name: "ログイン" }).click({ force: true });
    await expect(page.getByRole("button", { name: "ログアウト" })).toBeVisible({
      timeout: 15_000,
    });

    await page.getByRole("button", { name: "ログアウト" }).click({ force: true });

    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible();

    await page.reload();
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });
  });
});
