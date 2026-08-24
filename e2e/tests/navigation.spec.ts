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

  test("has a storage group in the sidebar and drills down from the overview", async ({ page }) => {
    // 概況のカードは横スクロールの Row にあり、既定の幅では後方のカードが
    // 描画領域外に出る。buckets.spec.ts / keys.spec.ts の縦スクロールと同じ理由で、
    // 全体が収まる幅にしてスクロールの待ちを避ける
    await page.setViewportSize({ width: 1600, height: 720 });

    await page.goto("/");
    await signIn(page, token);

    // サイドバーに「ストレージ」グループと 3 項目があること
    await expect(page.getByText("ストレージ", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "バケット", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "アクセスキー", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "オブジェクト", exact: true })).toBeVisible();

    // サイドバーの「バケット」「アクセスキー」で遷移できること
    await page.getByRole("button", { name: "バケット", exact: true }).click({ force: true });
    await expect(page).toHaveURL(/\/buckets$/);

    await page.getByRole("button", { name: "アクセスキー", exact: true }).click({ force: true });
    await expect(page).toHaveURL(/\/keys$/);

    // 概況のカードからもドリルダウンできること。カードごとに概況へ goto し直して
    // まっさらな状態に戻す（自動更新のポーリングが割り込む前に操作を終える）
    await page.goto("/");
    await expect(page.getByRole("button", { name: /^バケット\s/ })).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: /^バケット\s/ }).click({ force: true });
    await expect(page).toHaveURL(/\/buckets$/);

    await page.goto("/");
    await expect(page.getByRole("button", { name: /^アクセスキー\s/ })).toBeVisible({
      timeout: 30_000,
    });
    await page.getByRole("button", { name: /^アクセスキー\s/ }).click({ force: true });
    await expect(page).toHaveURL(/\/keys$/);
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
