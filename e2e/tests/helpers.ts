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

/**
 * scope を絞った admin token。
 *
 * `docker compose logs garage-init` の "Limited-scope token:" から取る。
 * GetKeyInfo を持たないため、S3 ブラウザだけが縮退する。
 */
export function limitedToken(): string {
  const token = process.env.E2E_LIMITED_TOKEN;

  if (!token) {
    throw new Error(
      "E2E_LIMITED_TOKEN が未設定です。docker compose logs garage-init から取得してください",
    );
  }

  return token;
}

/** ログインしてから目的の画面へ移動する。 */
export async function openScreen(page: Page, path: string, token: string): Promise<void> {
  await page.goto("/");
  await signIn(page, token);
  await page.goto(path);
}

/**
 * テストごとに違う名前を作る。
 *
 * e2e は同じ Garage を使い回すため、前回の残骸と衝突しない名前が要る。
 * prefix は spec ファイルごとに固有にすること。`playwright.config.ts` の
 * `fullyParallel: false` はファイル内の直列化しか意味せず、spec ファイル
 * 同士は並行実行されるため、prefix が同じだと衝突しうる。
 */
export function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}`;
}

/**
 * ダイアログを閉じた後にアクセシビリティツリーを取り戻す。
 *
 * Compose のツリーは AlertDialog を閉じると空になり、リロードするまで復活しない。
 * 画面上はボタンが見えているのにロケータが一致しなくなるため、ダイアログを
 * 跨いだ後は必ずこれを通す。
 */
export async function afterDialog(page: Page): Promise<void> {
  await page.reload();
}
