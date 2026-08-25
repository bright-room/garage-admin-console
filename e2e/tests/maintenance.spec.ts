import { test, expect, type APIRequestContext } from "@playwright/test";
import { adminToken, afterDialog, clickButton, clickWhenReady, openScreen } from "./helpers";

const token = adminToken();

/**
 * 設定変数の欄は名前の昇順に並ぶ。ラベルを持たないため、名前から番号を引く。
 *
 * Compose の OutlinedTextField は `<input>` ではないので `getByLabel` も
 * `inputValue` も使えない。番号を数値で書き込まず、実際の並びから求める。
 */
async function variableIndex(request: APIRequestContext, name: string): Promise<number> {
  const response = await request.get("/api/workers/variables", {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await response.json();
  const names = Object.values(body.success as Record<string, Record<string, string>>)
    .flatMap((variables) => Object.keys(variables))
    .filter((it, index, all) => all.indexOf(it) === index)
    .sort();

  const index = names.indexOf(name);
  expect(index, `${name} が設定変数に見つからない`).toBeGreaterThanOrEqual(0);

  return index;
}

test.describe("Workers", () => {
  test.beforeEach(async ({ page }) => {
    // ワーカー一覧は設定変数カードの下にある。Compose は描画領域の外に出た
    // 要素を hidden として扱うため、全体が収まる高さにする
    await page.setViewportSize({ width: 1600, height: 2400 });
    await openScreen(page, "/workers", token);
  });

  test("lists workers with their state", async ({ page }) => {
    await expect(page.getByText("ワーカー", { exact: true }).first()).toBeVisible();
    // アイドルなクラスタでは全ワーカーが idle
    await expect(page.getByText("idle").first()).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/Block scrub worker/)).toBeVisible();
  });

  test("shows worker variables", async ({ page }) => {
    await expect(page.getByText("設定変数")).toBeVisible();
    await expect(page.getByText("scrub-tranquility")).toBeVisible();
    await expect(page.getByText("resync-worker-count")).toBeVisible();
  });

  test("asks for confirmation before changing a variable", async ({ page, request }) => {
    await expect(page.getByText("scrub-tranquility")).toBeVisible();

    // 値の欄と「設定」は変数 1 件につき 1 つずつ並ぶため、番号が対応する
    const index = await variableIndex(request, "scrub-tranquility");

    const field = page.getByRole("textbox").nth(index);
    const before = (await field.textContent()) ?? "";

    await field.fill("5", { force: true });
    // 「設定」は値が変わるまで無効。Compose は無効状態をツリーに出さないため、
    // 打ち込みが状態に取り込まれたことを欄の描画で待つ
    await expect(field).not.toHaveText(before);

    await clickWhenReady(page.getByRole("button", { name: "設定" }).nth(index));

    await expect(page.getByText("ワーカーの設定を変更", { exact: true })).toBeVisible();
    await clickButton(page, "キャンセル");
    await afterDialog(page);
  });

  test("returns workers over the api", async ({ request }) => {
    const response = await request.get("/api/workers", {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();
    const nodes = Object.keys(body.success);
    expect(nodes.length).toBeGreaterThan(0);
    // MultiResponse の error は空なら省かれる（explicitNulls = false）
    expect(body.error ?? {}).toEqual({});
    expect(body.success[nodes[0]].length).toBeGreaterThan(0);
  });
});

test.describe("Blocks", () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await openScreen(page, "/blocks", token);
  });

  test("reports a healthy cluster with no block errors", async ({ page }) => {
    await expect(page.getByText("ブロックエラー", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("再同期に失敗しているブロックはありません")).toBeVisible({
      timeout: 30_000,
    });
  });

  test("asks for confirmation before retrying everything", async ({ page }) => {
    await clickButton(page, "全件を再同期");

    await expect(page.getByText("全ブロックの再同期を要求", { exact: true })).toBeVisible();
    await clickButton(page, "キャンセル");
    await afterDialog(page);
  });

  test("rejects a purge with no target", async ({ request }) => {
    const response = await request.post("/api/blocks/purge", {
      headers: { Authorization: `Bearer ${token}` },
      data: { blockHashes: [] },
    });

    expect(response.status()).toBe(400);
    expect(response.headers()["content-type"]).toContain("application/problem+json");
  });

  test("returns block errors over the api", async ({ request }) => {
    const response = await request.get("/api/blocks/errors", {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();
    // MultiResponse の error は空なら省かれる（explicitNulls = false）
    expect(body.error ?? {}).toEqual({});
  });
});
