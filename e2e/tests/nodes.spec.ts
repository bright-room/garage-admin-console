import { test, expect, type Page } from "@playwright/test";
import { adminToken, afterDialog, clickButton, openScreen } from "./helpers";

const token = adminToken();

/** ダイアログを跨いだ後にツリーを取り戻し、画面が描き直されるまで待つ。 */
async function reopen(page: Page): Promise<void> {
  await afterDialog(page);
  await expect(page.getByText("クラスタ全体")).toBeVisible({ timeout: 30_000 });
}

test.describe("Nodes", () => {
  test.beforeEach(async ({ page }) => {
    // クラスタ全体の数値は横スクロールの Row にある。全体が収まる幅にする
    await page.setViewportSize({ width: 1600, height: 900 });
    await openScreen(page, "/nodes", token);
  });

  // 旧 cluster.spec.ts のパリティ: クラスタ画面が出ること
  test("displays the cluster screen", async ({ page }) => {
    await expect(page.getByText("クラスタ全体")).toBeVisible();
    await expect(page.getByText("healthy", { exact: true })).toBeVisible();
  });

  // 旧 cluster.spec.ts のパリティ: ノードを接続する導線があること
  test("offers a way to connect a node", async ({ page }) => {
    await expect(page.getByRole("button", { name: "ノードを接続" })).toBeVisible();
  });

  test("lists the dev node with its zone and version", async ({ page }) => {
    await expect(page.getByText("ノード", { exact: true }).first()).toBeVisible();
    await expect(page.getByText(/dc1/)).toBeVisible();
    await expect(page.getByText(/v2\.3\.0/)).toBeVisible();
  });

  test("shows cluster statistics", async ({ page }) => {
    await expect(page.getByText("バケット", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("オブジェクト", { exact: true }).first()).toBeVisible();
  });

  test("polls and can be refreshed", async ({ page }) => {
    await expect(page.getByText(/最終更新 \d+ 秒前/)).toBeVisible();

    await clickButton(page, "更新");

    await expect(page.getByText(/最終更新 [01] 秒前/)).toBeVisible();
  });

  test("asks for confirmation before taking a metadata snapshot", async ({ page }) => {
    await clickButton(page, "スナップショット");

    // 本文にも同じ語が出るため、見出しの完全一致で取る
    await expect(page.getByText("メタデータのスナップショットを作成", { exact: true })).toBeVisible();
    await clickButton(page, "キャンセル");

    // ダイアログを閉じるとアクセシビリティツリーが空になる
    await reopen(page);
  });

  test("does not offer skipping dead nodes when every node is up", async ({ page }) => {
    // 単一ノードの dev クラスタでは停止ノードが無いため、このボタンは出ない
    await expect(page.getByRole("button", { name: "停止ノードを飛ばす" })).toHaveCount(0);
  });

  test("opens the connect dialog and accepts an address", async ({ page }) => {
    await clickButton(page, "ノードを接続");

    await expect(page.getByText(/1 行に 1 件/)).toBeVisible();
    // ダイアログが開くと背景のツリーは消え、textbox はアドレスの欄だけになる。
    // 打ち込んだ後は Compose の入力プロキシが textbox として増えるため、
    // 数が 1 のうちに入れる
    await expect(page.getByRole("textbox")).toHaveCount(1);
    await page.getByRole("textbox").first().fill("0000@127.0.0.1:19999", { force: true });

    // 実行の結果は画面の状態にしか無く、UI からは確かめられない（下の注記）。
    // ここはダイアログが開いて入力を受け付けるところまでを見る
    await clickButton(page, "キャンセル");
    await reopen(page);
  });

  test("reports a connection failure over the api", async ({ request }) => {
    // 到達できないアドレスなので必ず失敗する
    const response = await request.post("/api/cluster/connect", {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        nodes: ["0000000000000000000000000000000000000000000000000000000000000000@127.0.0.1:19999"],
      },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body).toHaveLength(1);
    expect(body[0].success).toBe(false);
    expect(body[0].node).toContain("127.0.0.1:19999");
    expect(typeof body[0].error).toBe("string");
  });

  test("rejects a connect request with no nodes", async ({ request }) => {
    const response = await request.post("/api/cluster/connect", {
      headers: { Authorization: `Bearer ${token}` },
      data: { nodes: [] },
    });

    expect(response.status()).toBe(400);
  });
});
