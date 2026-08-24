import { test, expect, type APIRequestContext } from "@playwright/test";
import { adminToken, afterDialog, openScreen, uniqueName } from "./helpers";

const token = adminToken();

/**
 * 作ったバケットが残っていれば消す。
 *
 * テストが途中で失敗しても共有の Garage に残骸を残さないための後始末。
 * UI で既に消せていれば何もしない。
 */
async function deleteBucketIfExists(request: APIRequestContext, name: string): Promise<void> {
  const response = await request.get("/api/buckets", {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok()) return;

  const buckets: { id: string; globalAliases?: string[] }[] = await response.json();
  const bucket = buckets.find((it) => (it.globalAliases ?? []).includes(name));
  if (!bucket) return;

  await request.delete(`/api/buckets/${bucket.id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

test.describe("Buckets", () => {
  // BucketDetailScreen は縦に長く、既定のビューポートでは「バケットを削除」等が
  // 画面外に出る。wheel でキャンバス自体はスクロールできるが、そのたびに
  // 「どこまでスクロールしたか」を待つ条件が要り、テストごとにループが増える。
  // 全体が収まる高さのビューポートにすれば、その待ちが丸ごと不要になる
  // （引き換えに、現実的なビューポートでの到達性はこのテストでは見ない）
  test.use({ viewport: { width: 1280, height: 2400 } });

  test("creates, configures and deletes a bucket", async ({ page, request }) => {
    const name = uniqueName("e2e-bucket");

    try {
      await openScreen(page, "/buckets", token);
      await expect(page.getByText("dev-bucket").first()).toBeVisible({ timeout: 30_000 });

      // 作成
      await page.getByRole("button", { name: "バケットを作成" }).click({ force: true });
      // ダイアログ固有の文言が出るまで待つ。ダイアログが開くと背景はツリーから
      // 消えるが、開き切る前に `getByRole("textbox").last()` を使うと、まだ
      // 消えていない背景側の絞り込み欄を掴んでしまうことがある
      await expect(page.getByText("別名を付けないバケットは")).toBeVisible();
      await page.getByRole("textbox").last().fill(name, { force: true });
      // fill 直後は Compose の状態にまだ入力が反映されていないため、
      // ツリーに反映されるのを待ってから確定する
      await expect(page.getByText(name)).toBeVisible();
      const created = page.waitForResponse(
        (it) => it.request().method() === "POST" && it.url().endsWith("/api/buckets"),
      );
      await page.getByRole("button", { name: "作成", exact: true }).click({ force: true });
      // click() が返る時点では POST がまだ飛んでいないことがあるため、
      // 応答を待ってから reload する（先に reload すると進行中の fetch を中断しうる）
      expect((await created).ok()).toBe(true);

      // 作成ダイアログが閉じるとアクセシビリティツリーが空になる。
      // リロードして取り戻してから一覧に現れたことを確認する
      await afterDialog(page);
      await expect(page.getByText("dev-bucket").first()).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(name).first()).toBeVisible({ timeout: 15_000 });

      // 詳細へ（ダイアログを経由しない通常の遷移）
      await page.getByText(name).first().click({ force: true });
      await expect(page.getByText("概要", { exact: true })).toBeVisible({ timeout: 15_000 });

      // 「アクセスキー」はサイドバーの項目と 2 件一致するため、
      // このセクション固有の「キーに権限を付与」で確認する
      await expect(page.getByRole("button", { name: "キーに権限を付与" })).toBeVisible();

      // 設定フォームが揃っていること。保存が他の設定を巻き込まないことは
      // server 側のテスト（UpdateBucket の部分更新）で担保している
      await expect(page.getByText("上限", { exact: true })).toBeVisible();
      await expect(page.getByText("公開", { exact: true })).toBeVisible();
      await expect(page.getByText("CORS", { exact: true })).toBeVisible();
      await expect(page.getByText("ライフサイクル", { exact: true })).toBeVisible();

      // 削除は名前のタイプ入力を要求する（spec §8.6）
      await page.getByRole("button", { name: "バケットを削除" }).click({ force: true });
      await expect(page.getByText(/確認のため/)).toBeVisible();

      const confirm = page.getByRole("button", { name: "実行", exact: true });
      await confirm.click({ force: true });
      // 名前を打つまでは消えない
      await expect(page.getByText(/確認のため/)).toBeVisible();

      await page.getByRole("textbox").last().fill(name, { force: true });
      // このダイアログは本文と確認メッセージの 2 箇所に既に name を含むため、
      // 入力が反映されたことは出現数が 3 件になったことで確認する
      // （本文 + 確認メッセージ + 入力欄のエコー）
      await expect(page.getByText(name)).toHaveCount(3);
      await confirm.click({ force: true });

      // URL の変化はツリーに依らず確認できる
      await expect(page).toHaveURL(/\/buckets$/, { timeout: 15_000 });

      // 削除確認ダイアログが閉じてツリーが空になっているので取り戻す
      await afterDialog(page);
      await expect(page.getByText("dev-bucket").first()).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(name)).toHaveCount(0);
    } finally {
      await deleteBucketIfExists(request, name);
    }
  });

  test("keeps the bucket route on reload", async ({ page }) => {
    await openScreen(page, "/buckets", token);
    await page.getByText("dev-bucket").first().click({ force: true });

    await expect(page).toHaveURL(/\/buckets\/[0-9a-f]+$/, { timeout: 15_000 });

    await page.reload();
    await expect(page.getByText("概要", { exact: true })).toBeVisible({ timeout: 30_000 });
  });
});
