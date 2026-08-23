import { expect, type Page } from "@playwright/test";

/**
 * E2E に使う admin token。
 *
 * `docker compose logs garage-init` の "Console login token:" から取る。
 */
export function adminToken(): string {
  const token = process.env.E2E_ADMIN_TOKEN;

  if (!token) {
    throw new Error(
      "E2E_ADMIN_TOKEN が未設定です。docker compose logs garage-init から取得してください",
    );
  }

  return token;
}

/** ログイン画面が描画されるまで待つ。wasm の読み込みがあるため長めに取る。 */
export async function waitForLoginScreen(page: Page): Promise<void> {
  await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
    timeout: 30_000,
  });
}

/**
 * トークンを入力してログインする。
 *
 * Compose はキャンバスに描画するため、fill の直後に click すると
 * 入力がまだ状態に取り込まれておらず、ボタンが無効なままクリックが落ちる。
 * Compose は無効状態をアクセシビリティツリーに出さないので toBeEnabled では
 * 待てない。代わりにマスク表示の長さが入力と一致するのを待つ。
 */
export async function signIn(page: Page, token: string): Promise<void> {
  await waitForLoginScreen(page);

  await page.getByRole("textbox").first().fill(token, { force: true });
  await expect(page.getByText("•".repeat(token.length), { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "ログイン" }).click({ force: true });

  await expect(page.getByRole("button", { name: "ログアウト" })).toBeVisible({
    timeout: 15_000,
  });
}
