import { test, expect, type APIRequestContext } from "@playwright/test";
import { adminToken, afterDialog, limitedToken, openScreen, signIn, uniqueName } from "./helpers";

const token = adminToken();

/** dev-bucket の ID を API から引く。UI から辿るより速く、壊れにくい。 */
async function devBucketId(request: APIRequestContext): Promise<string> {
  const response = await request.get("/api/buckets", {
    headers: { Authorization: `Bearer ${token}` },
  });
  const buckets: { id: string; globalAliases?: string[] }[] = await response.json();
  // 別名の無いバケットは globalAliases フィールドが応答から省略される
  // （kotlinx serialization が既定値を書かないため）
  const bucket = buckets.find((it) => (it.globalAliases ?? []).includes("dev-bucket"));

  if (!bucket) throw new Error("dev-bucket が見つかりません");

  return bucket.id;
}

/**
 * オブジェクトが残っていれば消す。
 *
 * テストが途中で失敗しても共有の Garage に残骸を残さないための後始末。
 * UI で既に消せていても DELETE は idempotent なので問題ない。
 */
async function deleteObjectIfExists(
  request: APIRequestContext,
  bucketId: string,
  key: string,
): Promise<void> {
  await request.delete(`/api/buckets/${bucketId}/objects?key=${encodeURIComponent(key)}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

test.describe("Objects", () => {
  // buckets / keys と違い、既定のビューポートのままでよい。絞り込みで表示行数を
  // 1 に落とせるため、操作対象のボタンが画面外に出ることが無い
  test("uploads, lists, downloads and deletes an object", async ({ page, request }) => {
    // アップロード + ダウンロード + ダイアログ 2 回分をまとめて 1 ケースに詰めるため、
    // 既定の 60 秒では足りないことがある
    test.setTimeout(120_000);

    const bucketId = await devBucketId(request);
    const fileName = `${uniqueName("e2e-object")}.txt`;

    try {
      await openScreen(page, `/objects/${bucketId}`, token);
      await expect(page.getByRole("button", { name: "アップロード" })).toBeVisible({
        timeout: 30_000,
      });

      // ファイル入力は JS が動的に作ってすぐ捨てるため、セレクタではなく
      // filechooser イベントで受ける
      const chooser = page.waitForEvent("filechooser");
      await page.getByRole("button", { name: "アップロード" }).click({ force: true });
      (await chooser).setFiles({
        name: fileName,
        mimeType: "text/plain",
        buffer: Buffer.from("hello from e2e"),
      });

      // アップロードはダイアログを経由しないため、ツリーは生きたまま notice を確認できる
      await expect(page.getByText(`${fileName} をアップロードしました`)).toBeVisible({
        timeout: 30_000,
      });
      await expect(page.getByText(fileName).first()).toBeVisible();

      // 表の絞り込みで対象を 1 行にする。前のテストの残骸があっても
      // 「取得」「詳細」「削除」が別のオブジェクトを指さない。
      // fill 直後は反映されていないため、絞り込みが効いて行が 1 件になった
      // （「削除」ボタンが 1 個になった）ことを待ってから操作する
      await page.getByRole("textbox").first().fill(fileName, { force: true });
      await expect(page.getByRole("button", { name: "削除", exact: true })).toHaveCount(1);

      // どのキーで見ているかを出す（spec §6.4）
      await expect(page.getByText(/として閲覧中/)).toBeVisible();

      // ダウンロード
      const download = page.waitForEvent("download");
      await page.getByRole("button", { name: "取得" }).first().click({ force: true });
      expect((await download).suggestedFilename()).toBe(fileName);

      // InspectObject
      await page.getByRole("button", { name: "詳細" }).first().click({ force: true });
      await expect(page.getByText(/インライン格納|ブロック/)).toBeVisible({ timeout: 15_000 });
      // InspectionDialog のタイトルは inspection.key なので、対象のファイル名まで確認する
      await expect(page.getByText(fileName).first()).toBeVisible();
      await page.getByRole("button", { name: "閉じる" }).click({ force: true });

      // 詳細ダイアログが閉じてツリーが空になっているので取り戻す。
      // リロードで絞り込みも消えるため、削除の前にもう一度絞り込む
      await afterDialog(page);
      await expect(page.getByRole("button", { name: "アップロード" })).toBeVisible({
        timeout: 30_000,
      });
      await page.getByRole("textbox").first().fill(fileName, { force: true });
      await expect(page.getByRole("button", { name: "削除", exact: true })).toHaveCount(1);

      // 削除
      await page.getByRole("button", { name: "削除", exact: true }).first().click({ force: true });
      const deleted = page.waitForResponse(
        (it) => it.request().method() === "DELETE" && it.url().includes("/objects?key="),
      );
      await page.getByRole("button", { name: "実行", exact: true }).click({ force: true });
      // click() が返る時点では DELETE がまだ飛んでいないことがあるため、
      // 応答を待ってから reload する（先に reload すると進行中の fetch を中断しうる）
      expect((await deleted).ok()).toBe(true);

      // 削除確認ダイアログが閉じてツリーが空になっているので取り戻してから
      // 実際に消えたことを確認する（reload 前の toHaveCount(0) は空のツリーに
      // 対して常に真になるだけで、何も検証しない）
      await afterDialog(page);
      await expect(page.getByRole("button", { name: "アップロード" })).toBeVisible({
        timeout: 30_000,
      });
      await expect(page.getByText(fileName)).toHaveCount(0);
    } finally {
      await deleteObjectIfExists(request, bucketId, fileName);
    }
  });

  test("traverses into a folder and keeps the prefix in the url", async ({ page, request }) => {
    const bucketId = await devBucketId(request);
    const folder = uniqueName("e2e-folder");
    const key = `${folder}/nested.txt`;

    try {
      // オブジェクトブラウザは UI だけでは新しいフォルダ（prefix）を作れない
      // （アップロードの key は現在の prefix + ファイル名で組まれ、file input は
      // スラッシュを含む名前を返さない）ため、API で種を撒いてから辿る
      const seed = await request.put(
        `/api/buckets/${bucketId}/objects?key=${encodeURIComponent(key)}`,
        {
          headers: { Authorization: `Bearer ${token}` },
          data: Buffer.from("nested"),
        },
      );
      expect(seed.ok()).toBe(true);

      await openScreen(page, `/objects/${bucketId}`, token);
      await expect(page.getByRole("button", { name: "アップロード" })).toBeVisible({
        timeout: 30_000,
      });

      // ルート直下にフォルダ行が見えていること
      const folderRow = page.getByRole("button", { name: `📁 ${folder}/` });
      await expect(folderRow).toBeVisible();

      // フォルダへ（ダイアログを経由しない通常の遷移なので afterDialog は不要）
      await folderRow.click({ force: true });
      await expect(page).toHaveURL(new RegExp(`prefix=${encodeURIComponent(`${folder}/`)}`));
      await expect(page.getByText("nested.txt").first()).toBeVisible();

      // リロードとブックマークで同じ場所に戻る
      await page.reload();
      await expect(page).toHaveURL(new RegExp(`prefix=${encodeURIComponent(`${folder}/`)}`));
      await expect(page.getByText("nested.txt").first()).toBeVisible({ timeout: 30_000 });

      // 「ルート」で戻る
      await page.getByRole("button", { name: "ルート" }).click({ force: true });
      await expect(page).toHaveURL(new RegExp(`/objects/${bucketId}$`));
      await expect(page.getByRole("button", { name: `📁 ${folder}/` })).toBeVisible();
    } finally {
      await deleteObjectIfExists(request, bucketId, key);
    }
  });

  test("degrades only the object browser for a limited token", async ({ page, request }) => {
    // scope に GetKeyInfo が無いトークン。S3 資格情報を導出できないため
    // オブジェクトだけが縮退し、他の画面は通常どおり描ける（spec §6.4）
    const bucketId = await devBucketId(request);

    await page.goto("/");
    await signIn(page, limitedToken());

    await page.goto("/buckets");
    await expect(page.getByText("dev-bucket").first()).toBeVisible({ timeout: 30_000 });

    await page.goto(`/objects/${bucketId}`);
    await expect(page.getByText("このトークンでは参照できません")).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText(/GetKeyInfo/)).toBeVisible();
  });
});
