import { test, expect, type APIRequestContext } from "@playwright/test";
import { adminToken, afterDialog, clickWhenReady, openScreen, uniqueName } from "./helpers";

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

/** 別名を 1 つ持つバケットを作り、その ID を返す。 */
async function createBucket(request: APIRequestContext, alias: string): Promise<string> {
  const response = await request.post("/api/buckets", {
    headers: { Authorization: `Bearer ${token}` },
    data: { globalAlias: alias },
  });

  if (!response.ok()) throw new Error(`バケットを作れません: ${response.status()}`);

  const { id }: { id: string } = await response.json();

  return id;
}

/**
 * 別名を持たないバケットを作り、その ID を返す。
 *
 * 別名の無いバケットは S3 API からアドレスできない（spec §6.5）。
 * 別名を要しないテストの土台としても使う。
 */
async function createBucketWithoutAlias(request: APIRequestContext): Promise<string> {
  const response = await request.post("/api/buckets", {
    headers: { Authorization: `Bearer ${token}` },
    data: {},
  });

  if (!response.ok()) throw new Error(`バケットを作れません: ${response.status()}`);

  const { id }: { id: string } = await response.json();

  return id;
}

/** ID でバケットを消す。別名の無いバケットは名前で引けないため。 */
async function deleteBucketById(request: APIRequestContext, id: string): Promise<void> {
  await request.delete(`/api/buckets/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

/** バケットのグローバル別名を API から引く。画面の表示ではなくサーバーの状態を見るため。 */
async function globalAliases(request: APIRequestContext, id: string): Promise<string[]> {
  const response = await request.get(`/api/buckets/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (!response.ok()) throw new Error(`バケットを引けません: ${response.status()}`);

  // 別名の無いバケットは globalAliases フィールドが応答から省かれる
  const bucket: { globalAliases?: string[] } = await response.json();

  return bucket.globalAliases ?? [];
}

/**
 * 権限の付け外しに使う専用のキーを作り、その accessKeyId を返す。
 *
 * 一覧（`KeySummary`）は `id`、作成と詳細（`KeyInfo`）は `accessKeyId` を返す。
 */
async function createKey(request: APIRequestContext, name: string): Promise<string> {
  const response = await request.post("/api/keys", {
    headers: { Authorization: `Bearer ${token}` },
    data: { name },
  });

  if (!response.ok()) throw new Error(`キーを作れません: ${response.status()}`);

  const { accessKeyId }: { accessKeyId: string } = await response.json();

  return accessKeyId;
}

/** ID でキーを消す。 */
async function deleteKeyById(request: APIRequestContext, id: string): Promise<void> {
  await request.delete(`/api/keys/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

/** キーの名前を一覧の順に引く。ダイアログに並ぶ順と同じ。 */
async function keyNames(request: APIRequestContext): Promise<string[]> {
  const response = await request.get("/api/keys", {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (!response.ok()) throw new Error(`キーを引けません: ${response.status()}`);

  const keys: { name: string }[] = await response.json();

  return keys.map((it) => it.name);
}

/** バケットに権限を持つキーの名前を API から引く。 */
async function permittedKeyNames(request: APIRequestContext, id: string): Promise<string[]> {
  const response = await request.get(`/api/buckets/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (!response.ok()) throw new Error(`バケットを引けません: ${response.status()}`);

  const bucket: { keys?: { name: string }[] } = await response.json();

  return (bucket.keys ?? []).map((it) => it.name);
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
      await clickWhenReady(page.getByRole("button", { name: "バケットを作成" }));
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
      await clickWhenReady(page.getByRole("button", { name: "作成", exact: true }));
      // click() が返る時点では POST がまだ飛んでいないことがあるため、
      // 応答を待ってから reload する（先に reload すると進行中の fetch を中断しうる）
      expect((await created).ok()).toBe(true);

      // 作成ダイアログが閉じるとアクセシビリティツリーが空になる。
      // リロードして取り戻してから一覧に現れたことを確認する
      await afterDialog(page);
      await expect(page.getByText("dev-bucket").first()).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(name).first()).toBeVisible({ timeout: 15_000 });

      // 詳細へ（ダイアログを経由しない通常の遷移）
      await clickWhenReady(page.getByText(name).first());
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
      await clickWhenReady(page.getByRole("button", { name: "バケットを削除" }));
      await expect(page.getByText(/確認のため/)).toBeVisible();

      const confirm = page.getByRole("button", { name: "実行", exact: true });
      await clickWhenReady(confirm);
      // 名前を打つまでは消えない
      await expect(page.getByText(/確認のため/)).toBeVisible();

      await page.getByRole("textbox").last().fill(name, { force: true });
      // このダイアログは本文と確認メッセージの 2 箇所に既に name を含むため、
      // 入力が反映されたことは出現数が 3 件になったことで確認する
      // （本文 + 確認メッセージ + 入力欄のエコー）
      await expect(page.getByText(name)).toHaveCount(3);
      await clickWhenReady(confirm);

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

  test("adds and removes a global alias", async ({ page, request }) => {
    const name = uniqueName("e2e-alias");
    const extra = `${name}-alt`;
    // Garage は最後の 1 つの別名を外させない（外すのではなくバケットごと
    // 消せ、と返す）。そのため別名 1 つの状態から始め、2 つに増やして戻す
    const id = await createBucket(request, name);

    try {
      await openScreen(page, `/buckets/${id}`, token);
      await expect(page.getByRole("button", { name: "別名を削除" })).toHaveCount(1, {
        timeout: 30_000,
      });

      // 「追加する別名」は詳細画面で最初の入力欄。概要には入力欄が無く、
      // 設定フォームの入力欄はこれより後ろに並ぶ
      await page.getByRole("textbox").first().fill(extra, { force: true });
      // fill 直後は Compose の状態にまだ入力が反映されていない
      await expect(page.getByText(extra).first()).toBeVisible();
      await clickWhenReady(page.getByRole("button", { name: "追加", exact: true }));

      await expect(page.getByText(`別名 ${extra} を追加しました`)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole("button", { name: "別名を削除" })).toHaveCount(2);

      // 追加がサーバーに届いていることを確かめる。画面の表示だけでは
      // 「描けている」ことしか分からない
      expect((await globalAliases(request, id)).sort()).toEqual([name, extra].sort());

      // 別名の行と削除ボタンの対応は Playwright からは取れない（Compose の
      // ツリーは行の親子関係を保たない）。どちらが消えたかはサーバーに聞く
      await clickWhenReady(page.getByRole("button", { name: "別名を削除" }).first());

      await expect(async () => {
        expect(await globalAliases(request, id)).toHaveLength(1);
      }).toPass({ timeout: 15_000 });

      const remaining = await globalAliases(request, id);
      const removed = [name, extra].find((it) => !remaining.includes(it));
      await expect(page.getByText(`別名 ${removed} を削除しました`)).toBeVisible();
      await expect(page.getByRole("button", { name: "別名を削除" })).toHaveCount(1);
    } finally {
      await deleteBucketById(request, id);
    }
  });

  test("grants a key permission on a bucket and revokes all of it", async ({ page, request }) => {
    const keyName = uniqueName("e2e-grant");
    const bucketId = await createBucketWithoutAlias(request);
    const keyId = await createKey(request, keyName);

    try {
      await openScreen(page, `/buckets/${bucketId}`, token);
      await expect(
        page.getByText("このバケットに権限を持つキーがありません。オブジェクトの操作にはキーが要ります"),
      ).toBeVisible({ timeout: 30_000 });

      // 付与（GrantKeyDialog）
      await clickWhenReady(page.getByRole("button", { name: "キーに権限を付与" }));
      // ダイアログが開き切るのを、ダイアログ固有の文言で待つ
      await expect(page.getByText("バケットへのキーの権限付与")).toBeVisible();

      // Compose の RadioButton と Checkbox は、名前を持たない button として
      // ツリーに出る（radio / checkbox のロールにはならない）。ダイアログには
      // 「キーの数だけのラジオ」「read / write / owner の 3 つ」がこの順に並ぶ。
      // このバケットはまだ権限を持つキーが無いので、選べるキーは全件と一致する
      const selectable = await keyNames(request);
      const target = selectable.indexOf(keyName);
      expect(target).toBeGreaterThanOrEqual(0);

      await clickWhenReady(page.getByRole("button").nth(target));
      // read だけを付ける
      await clickWhenReady(page.getByRole("button").nth(selectable.length));

      // 確定ボタンは権限が 1 つも選ばれていないと無効だが、Compose は無効状態を
      // ツリーに出さないため押せてしまう。押しても何も起きなかった場合に備えて、
      // サーバーに反映されるまで押し直す（付与は PUT なので何度押しても同じ）
      await expect(async () => {
        await page.getByRole("button", { name: "権限を付与", exact: true }).click({ force: true });
        expect(await permittedKeyNames(request, bucketId)).toContain(keyName);
      }).toPass({ timeout: 15_000 });

      // 剥奪（ConfirmDialog）。ツリーを取り戻してから押す
      await afterDialog(page);
      await expect(page.getByText(keyName).first()).toBeVisible({ timeout: 30_000 });
      await clickWhenReady(page.getByRole("button", { name: "権限を外す" }));
      // 見出しとボタンに同じ語が入るため exact で取る
      await expect(page.getByText("権限を外す", { exact: true })).toBeVisible();
      await clickWhenReady(page.getByRole("button", { name: "実行", exact: true }));

      await expect(async () => {
        expect(await permittedKeyNames(request, bucketId)).not.toContain(keyName);
      }).toPass({ timeout: 15_000 });
    } finally {
      await deleteKeyById(request, keyId);
      await deleteBucketById(request, bucketId);
    }
  });

  test("asks for confirmation before cleaning up unfinished uploads", async ({ page, request }) => {
    const id = await createBucketWithoutAlias(request);

    try {
      await openScreen(page, `/buckets/${id}`, token);
      await expect(
        page.getByText("24 時間より古い未完了のアップロードを削除します"),
      ).toBeVisible({ timeout: 30_000 });

      await clickWhenReady(page.getByRole("button", { name: "未完了アップロードを削除" }));
      // 見出しと本文の両方に同じ語が入るため exact で取る
      await expect(page.getByText("未完了アップロードの後始末", { exact: true })).toBeVisible();
      await expect(page.getByText(/進行中のアップロードには影響しません/)).toBeVisible();
    } finally {
      await deleteBucketById(request, id);
    }
  });

  test("reports the number of cleaned up uploads over the api", async ({ request }) => {
    const id = await createBucketWithoutAlias(request);

    try {
      // 後始末は 24 時間より古い未完了アップロードだけを消す（P2-9）。
      // テストの中で作ったものは必ず新しいため、実際に消えるところは
      // e2e では作れない。成功して件数が返ることまでを見る
      const response = await request.post(`/api/buckets/${id}/cleanup-uploads`, {
        headers: { Authorization: `Bearer ${token}` },
        data: {},
      });

      expect(response.ok()).toBe(true);
      const { uploadsDeleted }: { uploadsDeleted: number } = await response.json();
      expect(uploadsDeleted).toBe(0);
    } finally {
      await deleteBucketById(request, id);
    }
  });

  test("keeps the bucket route on reload", async ({ page }) => {
    await openScreen(page, "/buckets", token);
    await clickWhenReady(page.getByText("dev-bucket").first());

    await expect(page).toHaveURL(/\/buckets\/[0-9a-f]+$/, { timeout: 15_000 });

    await page.reload();
    await expect(page.getByText("概要", { exact: true })).toBeVisible({ timeout: 30_000 });
  });
});
