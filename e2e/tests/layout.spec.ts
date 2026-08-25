import { test, expect } from "@playwright/test";
import { adminToken, afterDialog, clickButton, openScreen } from "./helpers";

const token = adminToken();

test.describe("Layout", () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 1400 });
    await openScreen(page, "/layout", token);
  });

  // 旧 layout.spec.ts のパリティ: レイアウト画面が出ること
  test("displays the current layout", async ({ page }) => {
    await expect(page.getByText(/現在のレイアウト v\d+/)).toBeVisible();
    await expect(page.getByText(/dc1/).first()).toBeVisible();
  });

  // 旧 layout.spec.ts のパリティ: ロールを割り当てる導線があること
  test("offers a way to stage a role", async ({ page }) => {
    await expect(page.getByRole("button", { name: "割り当てを stage" })).toBeVisible();
  });

  test("shows the layout history", async ({ page }) => {
    await expect(page.getByText("履歴")).toBeVisible();
    await expect(page.getByText(/全ノードが v\d+ までを認識しています/)).toBeVisible();
  });

  test("has nothing staged in a fresh cluster", async ({ page }) => {
    await expect(page.getByText("未適用の変更はありません")).toBeVisible();
  });

  /**
   * stage → preview → 破棄 を通す。
   *
   * apply は踏まない。適用するとレイアウトの版が単調に進み、以降の実行で
   * 前提が変わってしまう（P3 の判断: e2e は stage → preview → revert まで）。
   */
  test("stages a change, previews it, then reverts", async ({ page }) => {
    // フォームの欄はラベルを持たないため番号で取る。
    // 並びは ノード ID / ゾーン / 容量（GiB）/ タグ / 最小ゾーン数（LayoutStageForm）
    const nodeIdField = page.getByRole("textbox").first();
    // Compose の textbox は <input> ではないので inputValue は使えない
    const nodeId = await nodeIdField.textContent();
    expect(nodeId).not.toBe("");

    // ゾーンと容量を入れて stage する
    await page.getByRole("textbox").nth(1).fill("dc1", { force: true });
    await page.getByRole("textbox").nth(2).fill("2", { force: true });
    // 入力が状態に取り込まれるまで stage は無効。欄そのものの描画で待つ
    await expect(page.getByRole("textbox").nth(1)).toHaveText("dc1");
    await expect(page.getByRole("textbox").nth(2)).toHaveText("2");
    await clickButton(page, "割り当てを stage");

    await expect(page.getByText(/stage しました/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("未適用の変更はありません")).toHaveCount(0);

    // 適用の前に preview が出ること（spec §8.6）
    await clickButton(page, "適用");
    await expect(page.getByText("レイアウトの変更を適用")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/適用すると v\d+ になります/)).toBeVisible();

    // 適用せずに閉じる
    await clickButton(page, "キャンセル");
    await afterDialog(page);

    // 破棄して元の状態に戻す
    await clickButton(page, "破棄");
    await expect(page.getByText("stage した変更を破棄", { exact: true })).toBeVisible();
    await clickButton(page, "実行");
    await afterDialog(page);

    await expect(page.getByText("未適用の変更はありません")).toBeVisible({ timeout: 30_000 });
  });

  test("returns the layout over the api", async ({ request }) => {
    const response = await request.get("/api/layout", {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(typeof body.version).toBe("number");
    expect(Array.isArray(body.roles)).toBe(true);
    // 実機の dev クラスタは maximum
    expect(body.parameters.zoneRedundancy).toBe("maximum");
  });

  test("previews without changing anything", async ({ request }) => {
    const before = await request.get("/api/layout", {
      headers: { Authorization: `Bearer ${token}` },
    });
    const beforeVersion = (await before.json()).version;

    const preview = await request.post("/api/layout/preview", {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(preview.status()).toBe(200);
    const body = await preview.json();
    expect(body.newLayout.version).toBe(beforeVersion + 1);

    // preview には副作用が無い
    const after = await request.get("/api/layout", {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect((await after.json()).version).toBe(beforeVersion);
  });
});
