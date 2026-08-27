import { randomBytes } from "node:crypto";
import { test, expect, type APIRequestContext } from "@playwright/test";
import { adminToken, afterDialog, clickWhenReady, openScreen, uniqueName } from "./helpers";

const token = adminToken();

/** 名前を更新したことを示す印。挿し込まれる位置に依らず、含まれることで確かめる。 */
const RENAME_MARK = "renamed";

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

  test("imports a key and renames it", async ({ page, request }) => {
    const name = uniqueName("e2e-import");

    // 他のクラスタから持ち込むキーを模して、この Garage が知らない資格情報を作る。
    // Garage が一度発行した ID は、削除した後でも再利用できない
    // （ImportKey が KeyAlreadyExists を返す）ため、既存のキーは流用できない
    const accessKeyId = `GK${randomBytes(12).toString("hex")}`;
    const secretAccessKey = randomBytes(32).toString("hex");

    try {
      await openScreen(page, "/keys", token);
      await expect(page.getByText("dev-key").first()).toBeVisible({ timeout: 30_000 });

      await clickWhenReady(page.getByRole("button", { name: "インポート", exact: true }));
      // ダイアログが開き切るのを、ダイアログ固有の文言で待つ。一覧の
      // ツールバーにも同名のボタンがあるため、開き切る前に確定を押すと
      // 背景側のボタンを押してしまう
      await expect(page.getByText("他のクラスタから持ち込んだキーを登録します")).toBeVisible();

      // 名前・アクセスキー ID・シークレットがこの順に並ぶ。
      // **1 つ入れるごとに反映を待つ。** force を付けた fill は actionability を
      // 待たないため、Compose が描き直している合間に当たると入力が落ちる。
      // 3 つ続けて入れると、落ちた欄があっても気づけないまま確定に進み、
      // 確定ボタンが無効のままになる（Compose は無効状態をツリーに出さない）
      const fields = page.getByRole("textbox");
      await fields.nth(0).fill(name, { force: true });
      await expect(page.getByText(name).first()).toBeVisible();

      await fields.nth(1).fill(accessKeyId, { force: true });
      await expect(page.getByText(accessKeyId).first()).toBeVisible();

      await fields.nth(2).fill(secretAccessKey, { force: true });
      // シークレットはマスク表示のため、マスクの長さで待つ
      await expect(
        page.getByText("•".repeat(secretAccessKey.length), { exact: true }),
      ).toBeVisible();

      const imported = page.waitForResponse(
        (it) => it.request().method() === "POST" && it.url().endsWith("/api/keys/import"),
      );
      await clickWhenReady(page.getByRole("button", { name: "インポート", exact: true }));
      // click() が返る時点では POST がまだ飛んでいないことがある。応答を
      // 待たずに reload すると進行中の fetch が中断される
      expect((await imported).ok()).toBe(true);

      // インポートのダイアログが閉じてツリーが空になっているので取り戻す
      await afterDialog(page);
      await expect(page.getByText(name).first()).toBeVisible({ timeout: 30_000 });

      // 更新（ダイアログを経由しない）。Compose の入力欄は fill が既存の値を
      // 置き換えず、カーソル位置に挿し込む。挿し込まれる位置は前後どちらにも
      // なるため、変更後の名前を決め打ちにはできない
      await clickWhenReady(page.getByText(name).first());
      await expect(page.getByRole("button", { name: "保存", exact: true })).toBeVisible({
        timeout: 15_000,
      });
      await page.getByRole("textbox").first().fill(RENAME_MARK, { force: true });
      // 打ち込みが状態に取り込まれるのを待つ。Compose は IME 用の隠し
      // <input> を末尾に置いており、そこには編集中の値が入る（画面に描かれる
      // 入力欄自体は <input> ではないため、値を読めるのはこちらだけ）
      await expect(page.getByRole("textbox").last()).toHaveValue(new RegExp(RENAME_MARK));
      await clickWhenReady(page.getByRole("button", { name: "保存", exact: true }));

      await expect(async () => {
        const response = await request.get(`/api/keys/${accessKeyId}`, {
          headers: { Authorization: `Bearer ${token}` },
        });
        const key: { name: string } = await response.json();
        expect(key.name).toContain(RENAME_MARK);
        expect(key.name).toContain(name);
      }).toPass({ timeout: 15_000 });
    } finally {
      // 名前が変わっているため、名前ではなく ID で消す
      await request.delete(`/api/keys/${accessKeyId}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
    }
  });
});
