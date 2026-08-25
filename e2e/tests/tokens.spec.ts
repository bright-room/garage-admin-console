import { test, expect } from "@playwright/test";
import {
  adminToken,
  afterDialog,
  clickButton,
  openScreen,
  restrictedToken,
  uniqueName,
} from "./helpers";

const token = adminToken();

test.describe("Admin tokens", () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await openScreen(page, "/tokens", token);
  });

  test("lists tokens and marks the one in use", async ({ page }) => {
    await expect(page.getByText("Admin token", { exact: true }).first()).toBeVisible();
    // ログイン中のトークン名はヘッダにも出るため first() を取る
    await expect(page.getByText(/dev-console/).first()).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("使用中")).toBeVisible();
  });

  test("shows configuration-derived tokens as read-only", async ({ page }) => {
    // garage.toml に書かれたトークンは id を持たず、API では触れない（P3-6）
    await expect(page.getByText(/from daemon configuration/).first()).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText("設定ファイル由来").first()).toBeVisible();
  });

  test("creates a token from the dialog and lists it", async ({ page, request }) => {
    const name = uniqueName("tok");

    await clickButton(page, "トークンを作成");
    // ダイアログが開くと背景のツリーは消え、残るのは「名前」と「scope」だけになる。
    // 開ききる前に打ち込むと背景の絞り込み欄に入ってしまうため、2 つになるまで待つ
    await expect(page.getByRole("textbox")).toHaveCount(2);
    await page.getByRole("textbox").first().fill(name, { force: true });
    // 入力が状態に取り込まれるまで「作成」は無効。Compose は無効状態を
    // ツリーに出さないため、打ち込んだ文字が描かれるのを待つ（signIn と同じ）
    await expect(page.getByText(name, { exact: true })).toBeVisible();
    await clickButton(page, "作成");

    // ダイアログを跨ぐとツリーが空になる。リロードで取り戻す。
    // 一度だけ表示される secret はこのリロードで消えるため、ここでは確かめない
    // （検証は下の API のテストで行う）
    await afterDialog(page);

    // 一覧は既存のトークンで縦に伸びる。絞り込みで対象の行だけにする。
    // 絞り込みの欄自身も打ち込んだ名前を描くため、行があることは
    // その行の「削除」が 1 つだけ出ることで確かめる
    await page.getByRole("textbox").first().fill(name, { force: true });
    await expect(page.getByRole("button", { name: "削除" })).toHaveCount(1, { timeout: 30_000 });

    // 後始末は API で行う。削除の UI 経路は次のテストが見る
    const list = await request.get("/api/admin-tokens", {
      headers: { Authorization: `Bearer ${token}` },
    });
    const created = (await list.json()).find((it: { name: string }) => it.name === name);
    expect(created).toBeDefined();
    await request.delete(`/api/admin-tokens/${created.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
  });

  test("requires typing the name before deleting", async ({ page, request }) => {
    const name = uniqueName("tok");

    // 削除の対象は API で用意する。作成の UI 経路は前のテストが見ている
    const create = await request.post("/api/admin-tokens", {
      headers: { Authorization: `Bearer ${token}` },
      data: { name, scope: ["GetCurrentAdminTokenInfo"] },
    });
    const id = (await create.json()).token.id;

    await page.reload();
    await expect(page.getByText(/dev-console/).first()).toBeVisible({ timeout: 30_000 });

    // 表の絞り込みで対象の行だけにする。行の番号に頼ると、並行して走る
    // 他の spec が作ったトークンで位置がずれる
    await page.getByRole("textbox").first().fill(name, { force: true });
    await expect(page.getByText(new RegExp(name)).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "削除" })).toHaveCount(1);

    await clickButton(page, "削除");

    await expect(page.getByText("トークンを削除", { exact: true })).toBeVisible();
    await expect(page.getByText(`確認のため「${name}」と入力してください`)).toBeVisible();
    await clickButton(page, "キャンセル");
    await afterDialog(page);

    await request.delete(`/api/admin-tokens/${id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
  });

  /**
   * 一度だけ返る secret を API で確かめる。
   *
   * UI では確かめられない。ダイアログを閉じるとツリーが空になり、取り戻すための
   * リロードで secret の表示（画面の状態）が消えるためである。
   */
  test("returns the secret token exactly once", async ({ request }) => {
    const name = uniqueName("tok");

    const create = await request.post("/api/admin-tokens", {
      headers: { Authorization: `Bearer ${token}` },
      data: { name, scope: ["GetCurrentAdminTokenInfo"] },
    });

    expect(create.status()).toBe(200);
    const created = await create.json();
    expect(typeof created.secretToken).toBe("string");
    expect(created.secretToken.length).toBeGreaterThan(0);
    expect(created.token.name).toBe(name);

    // 取り直しても secret は返らない
    const fetched = await request.get(`/api/admin-tokens/${created.token.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect((await fetched.json()).secretToken).toBeUndefined();

    const deleted = await request.delete(`/api/admin-tokens/${created.token.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(deleted.status()).toBe(204);
  });

  test("rejects a token with no scope over the api", async ({ request }) => {
    const response = await request.post("/api/admin-tokens", {
      headers: { Authorization: `Bearer ${token}` },
      data: { name: "no-scope", scope: [] },
    });

    expect(response.status()).toBe(400);
  });
});

test.describe("Scope degradation", () => {
  test("disables sidebar entries the token cannot use", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    // GetClusterHealth と GetClusterStatus しか持たないトークン
    await openScreen(page, "/", restrictedToken());

    // 使える項目
    await expect(page.getByRole("button", { name: "概況", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "ノード", exact: true })).toBeVisible();

    // 使えない項目には「（権限なし）」が付く
    await expect(page.getByRole("button", { name: "バケット（権限なし）" })).toBeVisible();
    await expect(page.getByRole("button", { name: "アクセスキー（権限なし）" })).toBeVisible();
    await expect(page.getByRole("button", { name: "レイアウト（権限なし）" })).toBeVisible();
    await expect(page.getByRole("button", { name: "ワーカー（権限なし）" })).toBeVisible();
    await expect(page.getByRole("button", { name: "ブロック（権限なし）" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Admin token（権限なし）" })).toBeVisible();
  });

  test("shows a scope message when opening a screen directly", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    // 直接 URL で来ても画面単位で同じメッセージを出す（spec §6.3）
    await openScreen(page, "/layout", restrictedToken());

    await expect(page.getByText("このトークンでは参照できません")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/GetClusterLayout/)).toBeVisible();
  });

  test("returns 403 with the operation name in the problem details", async ({ request }) => {
    const response = await request.get("/api/layout", {
      headers: { Authorization: `Bearer ${restrictedToken()}` },
    });

    expect(response.status()).toBe(403);
    expect(response.headers()["content-type"]).toContain("application/problem+json");

    const body = await response.json();
    expect(body.status).toBe(403);
    expect(body.operation).toBe("GetClusterLayout");
  });
});
