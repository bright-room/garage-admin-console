import { test, expect } from "@playwright/test";
import { adminToken, signIn, waitForLoginScreen } from "./helpers";

const token = adminToken();

test.describe("Navigation", () => {
  test("serves the app for a deep link instead of a 404", async ({ page }) => {
    // SPA フォールバックが効いていることの確認。
    // サーバーが index.html を返さなければ、この時点で Playwright は HTML ではなく
    // JSON エラーを受け取り、ログイン画面が出ない。
    const response = await page.goto("/login");

    expect(response?.status()).toBe(200);
    await waitForLoginScreen(page);
  });

  test("shows the sidebar after signing in", async ({ page }) => {
    await page.goto("/");
    await signIn(page, token);

    await expect(page.getByText("Garage", { exact: true })).toBeVisible();
    // 「概況」はサイドバーと見出しの両方に出る
    await expect(page.getByText("概況", { exact: true }).first()).toBeVisible();
  });

  test("shows a not-found screen for an unknown client route", async ({ page }) => {
    await page.goto("/");
    await signIn(page, token);

    await page.goto("/nope");

    await expect(page.getByText(/画面が見つかりません/)).toBeVisible({
      timeout: 30_000,
    });
  });

  test("returns an RFC 9457 problem for unknown api paths", async ({ request }) => {
    const response = await request.get("/api/does-not-exist");

    expect(response.status()).toBe(404);
    expect(response.headers()["content-type"]).toContain("application/problem+json");

    const body = await response.json();
    expect(body.status).toBe(404);
    expect(body.title).toBe("Not Found");
    // type を省略しているため about:blank とみなされる
    expect(body.type).toBeUndefined();
  });

  test("rejects api access without a token", async ({ request }) => {
    const response = await request.get("/api/overview");

    expect(response.status()).toBe(401);
    expect(response.headers()["content-type"]).toContain("application/problem+json");

    const body = await response.json();
    expect(body.status).toBe(401);
    expect(body.title).toBe("Unauthorized");
    expect(body.type).toBeUndefined();
  });

  test("rejects api access with an invalid token as 401", async ({ request }) => {
    // Garage は無効なトークンに 403 を返すが、コンソールは 401 に正規化する。
    // これがないと web はログイン画面に戻れない。
    const response = await request.get("/api/session", {
      headers: { Authorization: "Bearer not-a-real-token" },
    });

    expect(response.status()).toBe(401);
    const body = await response.json();
    expect(body.status).toBe(401);
    expect(body.operation).toBe("GetCurrentAdminTokenInfo");
  });
});
