import { test, expect, type APIRequestContext } from "@playwright/test";
import { adminToken, afterDialog, clickWhenReady, openScreen, uniqueName } from "./helpers";

const token = adminToken();

/**
 * 作ったキーが残っていれば消す。
 *
 * テストが途中で失敗しても共有の Garage に残骸を残さないための後始末。
 * UI で既に消せていれば何もしない。
 */
async function deleteKeyIfExists(request: APIRequestContext, name: string): Promise<void> {
  const response = await request.get("/api/keys", {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok()) return;

  const keys: { id: string; name: string }[] = await response.json();
  const key = keys.find((it) => it.name === name);
  if (!key) return;

  await request.delete(`/api/keys/${key.id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

test.describe("Access keys", () => {
  // KeyDetailScreen も縦に長く、既定のビューポートでは「キーを削除」が画面外に
  // 出る。理由は buckets.spec.ts と同じ（wheel でのスクロールはループと待ちを
  // 増やすため、画面全体が収まる高さにして待ちを丸ごと不要にする）
  test.use({ viewport: { width: 1280, height: 1600 } });

  test("creates a key, shows its secret once, then deletes it", async ({ page, request }) => {
    const name = uniqueName("e2e-key");

    try {
      await openScreen(page, "/keys", token);
      await expect(page.getByText("dev-key").first()).toBeVisible({ timeout: 30_000 });

      await clickWhenReady(page.getByRole("button", { name: "キーを作成" }));
      // ダイアログ固有の文言が出るまで待つ。開き切る前に
      // `getByRole("textbox").last()` を使うと、まだ消えていない背景側の
      // 絞り込み欄を掴んでしまうことがある
      await expect(page.getByText("バケットの作成を許可する")).toBeVisible();
      await page.getByRole("textbox").last().fill(name, { force: true });
      // fill 直後は入力がまだ状態に反映されていないため、反映を待ってから確定する
      await expect(page.getByText(name)).toBeVisible();
      await clickWhenReady(page.getByRole("button", { name: "作成", exact: true }));

      // 作成直後だけ平文のシークレットが出る。ラベルではなく実際の値で確認する
      // （Garage が secret を返さなくても SecretOnceDialog のラベル自体は描かれるため）
      await expect(page.getByText(/を作成しました/)).toBeVisible({ timeout: 15_000 });
      const keysResponse = await request.get("/api/keys", {
        headers: { Authorization: `Bearer ${token}` },
      });
      const keys: { id: string; name: string }[] = await keysResponse.json();
      const keyId = keys.find((it) => it.name === name)?.id;
      if (!keyId) throw new Error(`作成した key が見つかりません: ${name}`);
      const secretResponse = await request.get(`/api/keys/${keyId}?showSecret=true`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      const { secretAccessKey }: { secretAccessKey: string } = await secretResponse.json();
      await expect(page.getByText(secretAccessKey)).toBeVisible();
      await clickWhenReady(page.getByRole("button", { name: "閉じる" }));

      // シークレット表示ダイアログが閉じてツリーが空になっているので取り戻す
      await afterDialog(page);
      await expect(page.getByText("dev-key").first()).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(name).first()).toBeVisible({ timeout: 15_000 });

      // 詳細では隠れており、「表示」で取り直す（ダイアログを経由しない）
      await clickWhenReady(page.getByText(name).first());
      await expect(page.getByRole("button", { name: "表示" })).toBeVisible({ timeout: 15_000 });
      await clickWhenReady(page.getByRole("button", { name: "表示" }));
      await expect(page.getByRole("button", { name: "表示" })).toHaveCount(0);

      // 削除
      await clickWhenReady(page.getByRole("button", { name: "キーを削除" }));
      await clickWhenReady(page.getByRole("button", { name: "実行", exact: true }));

      await expect(page).toHaveURL(/\/keys$/, { timeout: 15_000 });

      // 削除確認ダイアログが閉じてツリーが空になっているので取り戻す
      await afterDialog(page);
      await expect(page.getByText("dev-key").first()).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(name)).toHaveCount(0);
    } finally {
      await deleteKeyIfExists(request, name);
    }
  });
});
