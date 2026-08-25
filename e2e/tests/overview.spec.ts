import { test, expect } from "@playwright/test";
import { adminToken, clickWhenReady, signIn } from "./helpers";

const token = adminToken();

test.describe("Overview", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await signIn(page, token);
  });

  test("shows cluster figures", async ({ page }) => {
    // 押せるカードは中の文字をまとめて 1 つのボタンにするため、getByText では取れない。
    // サイドバーの同名の項目と区別するために、後ろに数値が続くことを条件にする
    await expect(page.getByRole("button", { name: /^ノード\s/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /^状態\s/ })).toBeVisible();
    await expect(page.getByText("ストレージ", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: /^レイアウト\s/ })).toBeVisible();
  });

  test("reports a healthy cluster with no alerts", async ({ page }) => {
    // compose の dev クラスタは単一ノードで healthy な状態にある
    await expect(page.getByRole("button", { name: /^状態\s+healthy/ })).toBeVisible();
    await expect(page.getByText("異常はありません")).toBeVisible();
  });

  test("refreshes on demand", async ({ page }) => {
    await expect(page.getByText(/最終更新 \d+ 秒前/)).toBeVisible();

    await clickWhenReady(page.getByRole("button", { name: "更新" }));

    await expect(page.getByText(/最終更新 [01] 秒前/)).toBeVisible();
  });

  test("serves the overview payload with every section loaded", async ({ request }) => {
    const response = await request.get("/api/overview", {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();

    // すべてのセクションが取得できていること（scope は "*" のため）
    for (const key of ["health", "nodes", "layout", "storage", "blockErrors"]) {
      expect(body[key].type).toBe("loaded");
    }
  });

  test("fails the whole overview with 401 when the token is invalid", async ({ request }) => {
    // 各セクションが Denied に落ちるのではなく、全体が 401 になること。
    // そうでないと失効したトークンでログイン画面に戻れない。
    const response = await request.get("/api/overview", {
      headers: { Authorization: "Bearer not-a-real-token" },
    });

    expect(response.status()).toBe(401);
  });
});
