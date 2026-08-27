# Phase 4（最終パリティ確認と CI 調整）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 旧 UI からのパリティを対応表で確定させ、Phase 2 が e2e に残した穴のうち単一ノードで再現できるものを埋め、覆わないと決めたものは理由とともに閉じて、再構築を完了させる。

**Architecture:** 新しいプロダクトコードは書かない。Phase 4 が足すのは e2e のテストと、判断の根拠を残すドキュメントだけである。パリティ照合は「旧 e2e spec を記録として使う」（spec §10）という定義に従い、削除コミット `71ba945^` から旧 spec を読んで対応表を作る作業であり、旧 UI を動かし直す作業ではない。

**Tech Stack:** Playwright（e2e）、docker compose の Garage v2.3.0 実機、GitHub Actions

**Spec:** `docs/superpowers/specs/2026-08-23-garage-admin-console-rebuild-design.md`（§10 テスト、§12 実装順序の 7）

**前フェーズの計画（申し送り元）:** `docs/superpowers/plans/2026-08-24-rebuild-phase3-cluster.md`

## Global Constraints

- **新しいプロダクトコードを書かない。** Phase 4 で `web/src` `server/src` `shared/src` を変更する必要が出たら、それは Phase 4 のスコープ外である。手を止めて報告すること
- **TDD の形が e2e には合わない。** ここで足すテストは、既に実装済み・実機で動作確認済みの機能に対するカバレッジである。「失敗するテストを先に書く」ステップは意味を持たないため、代わりに各タスクで **テストが本当に対象を叩いていることを、サーバーの状態（API の応答）で確かめる**。この逸脱は意図的なものである
- **ダイアログを跨いだ後に「画面の状態」を検証しない**（P3-17）。Compose のツリーはダイアログを閉じると空になり、`page.reload()` でしか戻らないが、リロードは画面の状態を消す。ダイアログの後に見てよいのは、リロードに耐えるもの（サーバーの状態、URL）だけ
- **`force: true` の click を直接書かない。** `helpers.ts` の `clickWhenReady` を通すこと
- **Compose の入力欄は `<input>` ではない。** `getByLabel` も `inputValue()` も使えない。値は `textContent` で読む。`fill` は既存の値を置き換えず**足す**
- **ダイアログが開き切る前に打ち込むと背景の入力欄に入る。** ダイアログ固有の文言が見えるまで待ってから打ち込むこと
- **`uniqueName` の prefix は spec ファイルごとに固有にする。** spec ファイル同士は並行実行される
- コードコメントに実測値（件数・秒数）を書かない。根拠は commit と PR に置く
- コミットには署名が要る。検証は `git cat-file` で行う（`%G?` は当てにならない）

## 実行の前提

e2e を走らせるには、Garage の実機と管理コンソールが要る。

```bash
docker compose up -d          # dev Garage
mise run run                  # 管理コンソール（fat jar をビルドして起動）
mise run e2e                  # e2e 全件
```

**dev Garage のポートは他のワークツリーと共有される。** `docker compose ps` では見えない衝突が起きうる。既に起動しているスタックがあれば、それを落とさずにそのまま使うこと。

個別の spec だけを走らせるときは、`mise run e2e` がトークンを解決する仕組みを手で真似る。

```bash
cd e2e
E2E_ADMIN_TOKEN="$(mise run -q token)" \
E2E_LIMITED_TOKEN="$(GARAGE_TOKEN_LABEL='Limited-scope token' mise run -q token)" \
E2E_RESTRICTED_TOKEN="$(GARAGE_TOKEN_LABEL='Restricted token' mise run -q token)" \
BASE_URL=http://localhost:8080 npx playwright test tests/buckets.spec.ts
```

**`mise` は `run -- 引数` をタスクの `$1` に渡さない。** 引数が要るタスクは環境変数で受ける（`GARAGE_TOKEN_LABEL` がその形）。

---

## File Structure

**Modify:**

- `e2e/tests/buckets.spec.ts` — 別名の追加・削除（Task 2）、キー権限の付与・全剥奪（Task 3）、未完了アップロードの後始末（Task 5）
- `e2e/tests/keys.spec.ts` — キーのインポートと更新（Task 4）
- `e2e/tests/objects.spec.ts` — `bucket-not-addressable` の縮退（Task 6）
- `docs/superpowers/plans/2026-08-27-rebuild-phase4-parity-ci.md` — パリティ対応表（Task 1）、覆わない 5 項目（Task 7）、CI の判定と完了判定（Task 8）

**変更しない:** `web/src` `server/src` `shared/src` `.github/workflows/` `compose.yaml` `compose.ci.yaml` `Dockerfile`

---

### Task 1: 旧 e2e とのパリティ対応表

spec §10 のパリティチェックリスト 6 件について、旧 spec の各テストが新 e2e のどれに対応するかを表にして、この計画に残す。**新しいテストは書かない。**

**Files:**
- Modify: `docs/superpowers/plans/2026-08-27-rebuild-phase4-parity-ci.md`（「## パリティ対応表」節を追加）

**Interfaces:**
- Consumes: 削除コミット `71ba945`（`chore: 旧実装を削除し dev 用 admin token の発行を追加`）の親に残る旧 e2e
- Produces: Task 8 の完了判定が参照する対応表

- [ ] **Step 1: 旧 spec を読む**

```bash
git show 71ba945^:e2e/tests/navigation.spec.ts
git show 71ba945^:e2e/tests/dashboard.spec.ts
git show 71ba945^:e2e/tests/cluster.spec.ts
git show 71ba945^:e2e/tests/layout.spec.ts
git show 71ba945^:e2e/tests/buckets.spec.ts
git show 71ba945^:e2e/tests/keys.spec.ts
```

旧 spec は削除済みで、ここからしか読めない。**復活させない**（旧 UI が無いので通らない）。

- [ ] **Step 2: 新 e2e のテスト名を洗い出す**

```bash
grep -h 'test(' e2e/tests/*.spec.ts
```

- [ ] **Step 3: 対応表を計画に書く**

`## パリティ対応表` の節を作り、旧 spec のテスト 1 件につき 1 行を書く。列は「旧 spec のテスト」「新 e2e の対応先（ファイル名 + テスト名）」「備考」。

対応先が無い行が出たら、それが Phase 4 で埋めるべき穴である。**その場合は手を止めて報告すること**（Task 2-6 の対象に入っていない穴が見つかったことになる）。

旧 spec の大半は「画面が描画されるか」だけを見ている（例: `cluster.spec.ts` は "displays cluster nodes screen" と "shows connect node button" の 2 件）。新 e2e はいずれも同じ画面に対してより広い検証を持つため、対応先は素直に決まるはずである。

- [ ] **Step 4: コミット**

```bash
git add docs/superpowers/plans/2026-08-27-rebuild-phase4-parity-ci.md
git commit -m "docs: 旧 e2e と新 e2e のパリティ対応表を残す"
```

---

### Task 2: バケットの別名の追加・削除（e2e）

Phase 2 が e2e に残した穴の 1 つ目。`BucketDetailScreen` の `AliasSection` はダイアログを経由しないため、UI だけで完結して検証できる。

**別名を 1 つも持たないバケットを作ってから始める。** 別名が複数あると「別名を削除」ボタンが複数出て、どの行のボタンかを Playwright から特定できない（Compose のツリーは行の親子関係を保たない）。別名が 0 → 1 → 0 と動く形にすれば、ボタンは常に 0 個か 1 個になり、順序に依存しない。

**Files:**
- Modify: `e2e/tests/buckets.spec.ts`

**Interfaces:**
- Consumes: `helpers.ts` の `adminToken` / `openScreen` / `clickWhenReady` / `uniqueName`
- Produces: `createBucketWithoutAlias(request: APIRequestContext): Promise<string>`、`deleteBucketById(request: APIRequestContext, id: string): Promise<void>`、`globalAliases(request: APIRequestContext, id: string): Promise<string[]>` — Task 3 と Task 5 が同じファイル内で使う

- [ ] **Step 1: ヘルパーを足す**

`e2e/tests/buckets.spec.ts` の `deleteBucketIfExists` の下に足す。

```ts
/**
 * 別名を持たないバケットを作り、その ID を返す。
 *
 * 別名が 1 つだけ増減する状態を作るために使う。別名が複数あると
 * 「別名を削除」ボタンが複数出るが、Compose のツリーは行の親子関係を
 * 保たないため、どのボタンがどの別名に対応するかを特定できない。
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
```

- [ ] **Step 2: テストを書く**

`test.describe("Buckets", ...)` の中、最後のテストの後に足す。

```ts
  test("adds and removes a global alias", async ({ page, request }) => {
    const alias = uniqueName("e2e-alias");
    const id = await createBucketWithoutAlias(request);

    try {
      await openScreen(page, `/buckets/${id}`, token);
      await expect(
        page.getByText("グローバル別名がありません。S3 API から参照するには別名が要ります"),
      ).toBeVisible({ timeout: 30_000 });

      // 「追加する別名」は詳細画面で最初の入力欄。概要には入力欄が無く、
      // 設定フォームの入力欄はこれより後ろに並ぶ
      await page.getByRole("textbox").first().fill(alias, { force: true });
      // fill 直後は Compose の状態にまだ入力が反映されていない
      await expect(page.getByText(alias).first()).toBeVisible();
      await clickWhenReady(page.getByRole("button", { name: "追加", exact: true }));

      await expect(page.getByText(`別名 ${alias} を追加しました`)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole("button", { name: "別名を削除" })).toHaveCount(1);

      // 追加がサーバーに届いていることを確かめる。画面の表示だけでは
      // 「描けている」ことしか分からない
      expect(await globalAliases(request, id)).toEqual([alias]);

      await clickWhenReady(page.getByRole("button", { name: "別名を削除" }));

      await expect(page.getByText(`別名 ${alias} を削除しました`)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole("button", { name: "別名を削除" })).toHaveCount(0);

      expect(await globalAliases(request, id)).toEqual([]);
    } finally {
      await deleteBucketById(request, id);
    }
  });
```

- [ ] **Step 3: 走らせる**

```bash
cd e2e
E2E_ADMIN_TOKEN="$(mise run -q token)" \
E2E_LIMITED_TOKEN="$(GARAGE_TOKEN_LABEL='Limited-scope token' mise run -q token)" \
E2E_RESTRICTED_TOKEN="$(GARAGE_TOKEN_LABEL='Restricted token' mise run -q token)" \
BASE_URL=http://localhost:8080 npx playwright test tests/buckets.spec.ts -g "global alias"
```

期待: PASS。

落ちた場合、真っ先に疑うのは以下である。

- 「追加」ボタンが `exact: true` でも複数一致する → 設定フォームに「〜を追加」があるかを `BucketDetailScreen.kt` と `BucketSettingsForm.kt` で確かめる
- 入力欄の順番が違う → `BucketDetailScreen.kt` の `Column` の並び（概要 → 別名 → アクセスキー → 設定 → メンテナンス）を読み直す
- notice の文言が違う → `BucketDetailScreen.kt` の `onAdd` / `onRemove` に渡している `success` の文字列と突き合わせる

- [ ] **Step 4: 後始末を確かめる**

```bash
curl -s -H "Authorization: Bearer $(mise run -q token)" http://localhost:8080/api/buckets | jq 'length'
```

期待: テストの前後で件数が変わらない。`jq '.[].globalAliases'` に `e2e-alias-*` が残っていないこと。

- [ ] **Step 5: コミット**

```bash
git add e2e/tests/buckets.spec.ts
git commit -m "test(e2e): バケットの別名の追加と削除を確かめる"
```

---

### Task 3: キー権限の付与・全剥奪（e2e）

Phase 2 が e2e に残した穴の 2 つ目。付与は `GrantKeyDialog`、剥奪は `ConfirmDialog` を経由するため、**どちらもダイアログを跨ぐ**。跨いだ後に見てよいのはサーバーの状態だけである（P3-17）。

**Files:**
- Modify: `e2e/tests/buckets.spec.ts`

**Interfaces:**
- Consumes: Task 2 の `createBucketWithoutAlias` / `deleteBucketById`、`helpers.ts` の `afterDialog`
- Produces: `createKey(request, name): Promise<string>`、`deleteKeyById(request, id): Promise<void>`、`permittedKeyNames(request, id): Promise<string[]>`（このファイル内でのみ使う）

- [ ] **Step 1: import に `afterDialog` が入っていることを確かめる**

`e2e/tests/buckets.spec.ts` の先頭は既に以下である。足りなければ足す。

```ts
import { adminToken, afterDialog, clickWhenReady, openScreen, uniqueName } from "./helpers";
```

- [ ] **Step 2: ヘルパーを足す**

```ts
/** 権限の付け外しに使う専用のキーを作り、その accessKeyId を返す。 */
async function createKey(request: APIRequestContext, name: string): Promise<string> {
  const response = await request.post("/api/keys", {
    headers: { Authorization: `Bearer ${token}` },
    data: { name },
  });

  if (!response.ok()) throw new Error(`キーを作れません: ${response.status()}`);

  const { id }: { id: string } = await response.json();

  return id;
}

/** ID でキーを消す。 */
async function deleteKeyById(request: APIRequestContext, id: string): Promise<void> {
  await request.delete(`/api/keys/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
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
```

- [ ] **Step 3: テストを書く**

```ts
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
      await clickWhenReady(page.getByRole("radio", { name: keyName }));
      // read だけを付ける。確定ボタンは 1 つでも権限が選ばれるまで無効
      await clickWhenReady(page.getByRole("checkbox").first());
      await clickWhenReady(page.getByRole("button", { name: "権限を付与", exact: true }));

      // ダイアログを跨いだのでツリーが空になっている。画面の状態は見ず、
      // サーバーの状態で付与を確かめる（P3-17）
      await expect(async () => {
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
```

- [ ] **Step 4: 走らせる**

```bash
cd e2e
E2E_ADMIN_TOKEN="$(mise run -q token)" \
E2E_LIMITED_TOKEN="$(GARAGE_TOKEN_LABEL='Limited-scope token' mise run -q token)" \
E2E_RESTRICTED_TOKEN="$(GARAGE_TOKEN_LABEL='Restricted token' mise run -q token)" \
BASE_URL=http://localhost:8080 npx playwright test tests/buckets.spec.ts -g "grants a key"
```

期待: PASS。

落ちた場合に疑う点。

- `getByRole("radio", { name: keyName })` が一致しない → Compose の `RadioButton` はラベルを持たず、キー名は隣の `Text` である。その場合は `page.getByRole("radio").first()`（`available` はこのバケットに権限を持たないキーの全件なので、専用に作ったキーが 1 件目とは限らない点に注意）ではなく、キー名で行を絞れるかを `--debug` で確かめてから決める。どうしても行を特定できなければ、`GET /api/keys` で全件を引き、対象キーが何番目かを求めて `nth()` で取る（P3 の実測 12 件目と同じ手）
- チェックボックスの並びが read / write / owner でない → `GrantKeyDialog` の `Row` を読み直す
- 「権限を外す」の確認ダイアログで strict mode 違反になる → 見出しと本文の両方に同じ語が入っている。`exact: true` で取る（P3 の実測 13 件目と同じ罠）

- [ ] **Step 5: 後始末を確かめる**

```bash
curl -s -H "Authorization: Bearer $(mise run -q token)" http://localhost:8080/api/keys | jq '.[].name'
```

期待: `e2e-grant-*` が残っていない。

- [ ] **Step 6: コミット**

```bash
git add e2e/tests/buckets.spec.ts
git commit -m "test(e2e): キーへの権限の付与と全剥奪を確かめる"
```

---

### Task 4: キーのインポートと更新（e2e）

Phase 2 が e2e に残した穴の 3 つ目であり、**旧 e2e が持っていた導線（`keys.spec.ts` の "has create and import key buttons"）に対応先が無い唯一の箇所**である。

インポートに使う資格情報は、**一度作ったキーの ID と secret を取り出し、そのキーを消してから同じ値でインポートし直す**形で用意する。架空の値を使うと、Garage が受け付けるかどうかが実装依存になる。

**Files:**
- Modify: `e2e/tests/keys.spec.ts`

**Interfaces:**
- Consumes: `helpers.ts` の `adminToken` / `openScreen` / `clickWhenReady` / `uniqueName` / `afterDialog`、既存の `deleteKeyIfExists`
- Produces: なし

- [ ] **Step 1: import に `afterDialog` が入っていることを確かめる**

`e2e/tests/keys.spec.ts` の先頭は既に以下である。足りなければ足す。

```ts
import { adminToken, afterDialog, clickWhenReady, openScreen, uniqueName } from "./helpers";
```

- [ ] **Step 2: テストを書く**

`test.describe("Access keys", ...)` の中、既存のテストの後に足す。

```ts
  test("imports a key and renames it", async ({ page, request }) => {
    const name = uniqueName("e2e-import");
    const renamed = `${name}-renamed`;

    // インポートに使う資格情報を作る。架空の値ではなく Garage が発行した値を使う
    const created = await request.post("/api/keys", {
      headers: { Authorization: `Bearer ${token}` },
      data: { name },
    });
    expect(created.ok()).toBe(true);
    const { id }: { id: string } = await created.json();

    const withSecret = await request.get(`/api/keys/${id}?showSecret=true`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const { secretAccessKey }: { secretAccessKey: string } = await withSecret.json();

    // 同じ ID でインポートし直すため、いったん消す
    await request.delete(`/api/keys/${id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    try {
      await openScreen(page, "/keys", token);
      await expect(page.getByText("dev-key").first()).toBeVisible({ timeout: 30_000 });

      await clickWhenReady(page.getByRole("button", { name: "インポート", exact: true }));
      // ダイアログが開き切るのを、ダイアログ固有の文言で待つ。一覧の
      // ツールバーにも同名のボタンがあるため、開き切る前に確定を押すと
      // 背景側のボタンを押してしまう
      await expect(page.getByText("他のクラスタから持ち込んだキーを登録します")).toBeVisible();

      // 名前・アクセスキー ID・シークレットがこの順に並ぶ
      const fields = page.getByRole("textbox");
      await fields.nth(0).fill(name, { force: true });
      await fields.nth(1).fill(id, { force: true });
      await fields.nth(2).fill(secretAccessKey, { force: true });
      // 入力が状態に取り込まれるのを待ってから確定する。シークレットは
      // マスク表示のため、素の値ではなくアクセスキー ID の反映で待つ
      await expect(page.getByText(id).first()).toBeVisible();

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

      // 更新（ダイアログを経由しない）。Compose の入力欄は fill が既存の値に
      // 足されるため、末尾に足す形で名前を変える
      await clickWhenReady(page.getByText(name).first());
      await expect(page.getByText("設定", { exact: true })).toBeVisible({ timeout: 15_000 });
      await page.getByRole("textbox").first().fill("-renamed", { force: true });
      await expect(page.getByText(renamed).first()).toBeVisible();
      await clickWhenReady(page.getByRole("button", { name: "保存", exact: true }));

      await expect(async () => {
        const response = await request.get(`/api/keys/${id}`, {
          headers: { Authorization: `Bearer ${token}` },
        });
        const key: { name: string } = await response.json();
        expect(key.name).toBe(renamed);
      }).toPass({ timeout: 15_000 });
    } finally {
      await deleteKeyIfExists(request, name);
      await deleteKeyIfExists(request, renamed);
    }
  });
```

- [ ] **Step 3: 走らせる**

```bash
cd e2e
E2E_ADMIN_TOKEN="$(mise run -q token)" \
E2E_LIMITED_TOKEN="$(GARAGE_TOKEN_LABEL='Limited-scope token' mise run -q token)" \
E2E_RESTRICTED_TOKEN="$(GARAGE_TOKEN_LABEL='Restricted token' mise run -q token)" \
BASE_URL=http://localhost:8080 npx playwright test tests/keys.spec.ts -g "imports a key"
```

期待: PASS。

落ちた場合に疑う点。

- 「インポート」が 2 件一致して strict mode 違反 → ダイアログが開き切る前に取っている。待ちの条件を見直す
- Garage が同じ accessKeyId のインポートを拒む → 削除が非同期で完了していない。削除後に `GET /api/keys/{id}` が 404 になるまで待つ処理を足す
- 「設定」がサイドバーの項目とも一致する → `Sidebar.kt` の項目名を確かめ、一致するなら「バケットの作成を許可する」で待つ

- [ ] **Step 4: 後始末を確かめる**

```bash
curl -s -H "Authorization: Bearer $(mise run -q token)" http://localhost:8080/api/keys | jq '.[].name'
```

期待: `e2e-import-*` が残っていない。

- [ ] **Step 5: コミット**

```bash
git add e2e/tests/keys.spec.ts
git commit -m "test(e2e): キーのインポートと名前の更新を確かめる"
```

---

### Task 5: 未完了アップロードの後始末（e2e）

Phase 2 が e2e に残した穴の 4 つ目。**実現可能性に制約がある。** `MaintenanceSection` の後始末は 24 時間より古い未完了アップロードだけを削除する（`DEFAULT_CLEANUP_AGE_SECS`、P2-9）。テストの中で作った未完了アップロードは必ず 24 時間より新しいため、**削除されるところは e2e では作れない**。

したがって覆うのは「導線と確認ダイアログが出ること」と「後始末の API が成功し、削除件数を返すこと」までとする。これは Phase 3 の `maintenance.spec.ts` が取った形（UI は確認ダイアログまで、結果は API で見る）と同じである。

**Files:**
- Modify: `e2e/tests/buckets.spec.ts`

**Interfaces:**
- Consumes: Task 2 の `createBucketWithoutAlias` / `deleteBucketById`
- Produces: なし

- [ ] **Step 1: テストを書く**

```ts
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
      // 24 時間より新しい未完了アップロードは対象外のため、テストの中で
      // 削除されるところは作れない。成功して件数が返ることまでを見る
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
```

- [ ] **Step 2: 走らせる**

```bash
cd e2e
E2E_ADMIN_TOKEN="$(mise run -q token)" \
E2E_LIMITED_TOKEN="$(GARAGE_TOKEN_LABEL='Limited-scope token' mise run -q token)" \
E2E_RESTRICTED_TOKEN="$(GARAGE_TOKEN_LABEL='Restricted token' mise run -q token)" \
BASE_URL=http://localhost:8080 npx playwright test tests/buckets.spec.ts -g "unfinished uploads|cleaned up"
```

期待: 2 件とも PASS。

落ちた場合に疑う点。

- `POST /api/buckets/{id}/cleanup-uploads` が本文の欠落で 400 になる → `data: { olderThanSecs: 86400 }` を明示する
- 確認ダイアログの本文が別名の無いバケットで期待と違う → `displayName` が別名ではなく ID になる。本文の固定部分（「進行中のアップロードには影響しません」）だけで待つ形を守ること

- [ ] **Step 3: コミット**

```bash
git add e2e/tests/buckets.spec.ts
git commit -m "test(e2e): 未完了アップロードの後始末の導線と件数の応答を確かめる"
```

---

### Task 6: `bucket-not-addressable` の縮退（e2e）

Phase 2 が e2e に残した穴の 5 つ目。S3 縮退は 2 経路ある。

- `no-usable-key` — `objects.spec.ts` の "degrades only the object browser for a limited token" が既に覆っている
- `bucket-not-addressable` — **未検証。** グローバル別名を持たないバケットは S3 API からアドレスできない（spec §6.5）。別名の無いバケットを作ってオブジェクト画面を開けば再現できる

**Files:**
- Modify: `e2e/tests/objects.spec.ts`

**Interfaces:**
- Consumes: `helpers.ts` の `adminToken` / `openScreen`
- Produces: なし

- [ ] **Step 1: サーバーが返す文言を確かめる**

```bash
grep -n "BUCKET_NOT_ADDRESSABLE" -B 5 -A 10 server/src/main/kotlin/net/brightroom/garage/server/plugins/StatusPages.kt
```

`title` と `detail` に何が入るかを読む。**Step 2 のテストは、ここで読んだ実際の文言に置き換えてから走らせること。**

- [ ] **Step 2: テストを書く**

`test.describe("Objects", ...)` の中に足す。`objects.spec.ts` は別名を持たないバケットを作るため、既存の `uniqueName` の prefix とは衝突しない。

```ts
  test("degrades the object browser for a bucket with no global alias", async ({ page, request }) => {
    // 別名を持たないバケットは S3 API からアドレスできない（spec §6.5）
    const created = await request.post("/api/buckets", {
      headers: { Authorization: `Bearer ${token}` },
      data: {},
    });
    expect(created.ok()).toBe(true);
    const { id }: { id: string } = await created.json();

    try {
      await openScreen(page, `/objects/${id}`, token);
      // Step 1 で確かめた StatusPages の文言に置き換えること
      await expect(page.getByText("S3 API から参照できません")).toBeVisible({ timeout: 30_000 });
    } finally {
      await request.delete(`/api/buckets/${id}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
    }
  });
```

- [ ] **Step 3: 走らせる**

```bash
cd e2e
E2E_ADMIN_TOKEN="$(mise run -q token)" \
E2E_LIMITED_TOKEN="$(GARAGE_TOKEN_LABEL='Limited-scope token' mise run -q token)" \
E2E_RESTRICTED_TOKEN="$(GARAGE_TOKEN_LABEL='Restricted token' mise run -q token)" \
BASE_URL=http://localhost:8080 npx playwright test tests/objects.spec.ts -g "no global alias"
```

期待: PASS。落ちたら Step 1 で読んだ文言と突き合わせて直す。

- [ ] **Step 4: コミット**

```bash
git add e2e/tests/objects.spec.ts
git commit -m "test(e2e): 別名の無いバケットでオブジェクトが縮退することを確かめる"
```

---

### Task 7: 覆わないと決めたものを閉じる

**新しいテストは書かない。** 単一ノードの dev では状態を作れず、複数ノードの環境も用意しないと決めた 5 項目について、代わりに何が覆っているかを計画に残す。

**複数ノードの compose は用意しない**（Phase 4 の決定）。理由: 5 項目はいずれも `ktor-client-mock` を使ったサーバーの単体テストと `:shared` の直列化テストが既に覆っており、3 ノードの compose は layout の適用と同期待ちを CI に持ち込み、実行時間と不安定さを増やす。

**Files:**
- Modify: `docs/superpowers/plans/2026-08-27-rebuild-phase4-parity-ci.md`（「## e2e で覆わないもの」節を追加）

- [ ] **Step 1: 各項目の代替の担保先を突き止める**

```bash
grep -rn "ConnectClusterNodes\|SkipDeadNodes\|ListBlockErrors\|RetryBlockResync\|PurgeBlocks" server/src/test
grep -rn "throttled\|busy\|done" shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializersTest.kt
```

- [ ] **Step 2: 節を書く**

5 項目それぞれについて 1 行ずつ、「覆っていない状態」「代わりに何が覆っているか（ファイル名とテスト名）」「なぜ e2e に入れないか」を書く。項目は次のとおり。

- `ConnectClusterNodes` の成功（接続先が無い）
- `ClusterLayoutSkipDeadNodes`（死んだノードが無い）
- 非空の `ListBlockErrors` と `GetBlockInfo` / `RetryBlockResync` / `PurgeBlocks` の成功系
- `MultiResponse.error` が非空になる経路
- ワーカーの `busy` / `done` / `throttled`

あわせて、Task 5 の「24 時間より古い未完了アップロードは e2e で作れない」も同じ節に並べる（同じ性質の制約であり、別の場所に書くと見落とされる）。

代替の担保先が見つからない項目があれば、**それは本当の穴である。手を止めて報告すること。**

- [ ] **Step 3: コミット**

```bash
git add docs/superpowers/plans/2026-08-27-rebuild-phase4-parity-ci.md
git commit -m "docs: e2e で覆わない項目と代替の担保先を残す"
```

---

### Task 8: CI の判定と Phase 4 の締め

**CI のワークフローは変更しない。** 実測に基づく判定を記録し、Dockerfile がビルドできることだけを手元で 1 回確かめる。

**Files:**
- Modify: `docs/superpowers/plans/2026-08-27-rebuild-phase4-parity-ci.md`（「## CI の判定」「## Phase 4 の完了判定の根拠」を追加）
- Modify: `/home/tech/.claude/projects/-home-tech-dev-ghq-github-com-bright-room-garage-admin-console/memory/`（Step 5）

- [ ] **Step 1: e2e 全件を走らせる**

```bash
mise run e2e
```

期待: 全件 PASS。**連続 2 回走らせる**（並行実行の不安定さは 1 回では出ない）。

`layout.spec.ts` は別プロジェクトで、`chromium` の全件が終わってから走る。Task 2-6 で足したテストが概況の異常帯を動かさないこと（バケットとキーの増減は `Overview.alerts()` に出ない）を、`overview.spec.ts` が通ることで確認する。

- [ ] **Step 2: Dockerfile がビルドできることを確かめる**

```bash
docker build -t garage-admin-console:phase4-check .
docker image rm garage-admin-console:phase4-check
```

Dockerfile は CI からも compose からも参照されておらず、一度もビルドされていない。**CI には足さない**（毎 PR に gradle のフルビルドがもう 1 本乗る。イメージを公開する計画も無い）。ここで 1 回通ることを確かめ、結果を記録する。

失敗したら、直すのは Phase 4 のスコープ内である（パッケージングは spec §11 の要件）。ただし修正は Dockerfile に閉じること。

- [ ] **Step 3: CI の判定を書く**

`## CI の判定` の節に、判定と根拠を書く。

- **e2e が `on-merge` に無い**（PR にしか無い）→ 現状維持。PR がゲートになっており、main への直接 push は無い
- **Dockerfile が CI でも compose でもビルドされていない** → CI には足さない。Step 2 の手元での確認結果を書く
- 破棄した候補として、e2e ジョブの実行時間に対する `timeout-minutes: 20` の余裕が十分であることを、直近の実測とともに 1 行残す

```bash
gh run list --limit 10 --json name,displayTitle,databaseId
gh run view <databaseId> --json jobs -q '.jobs[] | "\(.name) \(.startedAt) -> \(.completedAt)"'
```

- [ ] **Step 4: 完了判定と申し送りを書く**

`## Phase 4 の完了判定の根拠` の節に、Phase 3 の同名の節と同じ形式で書く。**根拠が「このセッションで確かめたもの」か「以前の確認を引いたもの」かを区別すること。**

少なくとも次を含める。

- パリティ対応表に穴が無いこと（Task 1）
- Phase 2 の未カバー 5 項目それぞれの結末（Task 2-6。Task 5 は「24 時間の下限により削除される経路は作れない」という制約つきの結末）
- e2e で覆わない項目と代替の担保先（Task 7）
- `./gradlew build` と e2e 全件の結果（Step 1・Step 6）
- 後始末: テストが作ったバケット・キーが残っていないこと

再構築が Phase 4 で完了するため、「Phase 5 への申し送り」は書かない。代わりに **今後の運用で引き継ぐ技術的な事実**（Compose と Playwright の噛み合わせに関する既知の罠）が Phase 3 の計画末尾に集約されていることを 1 行で指し示す。

- [ ] **Step 5: メモリを更新する**

`memory/phase3-in-progress.md` は「Phase 3 は完了、Phase 4 は未着手・計画も無い」と書いており、Phase 4 の完了後は誤りになる。内容を Phase 4 完了の事実に差し替え、`MEMORY.md` の該当行も直す。ファイル名（`phase3-in-progress`）も実態に合わなくなるため、名前ごと置き換えて古いファイルを消す。

- [ ] **Step 6: `./gradlew build` を通す**

```bash
CHROME_BIN=$(command -v google-chrome || command -v chromium) ./gradlew build
```

期待: BUILD SUCCESSFUL。**ローカルでは `CHROME_BIN` が要る**（`:shared:wasmJsBrowserTest` が headless Chrome を要求する）。CI では gradle-action が解決するため出ない問題である。

- [ ] **Step 7: コミット**

```bash
git add docs/superpowers/plans/2026-08-27-rebuild-phase4-parity-ci.md
git commit -m "docs: Phase 4 の CI 判定と完了判定の根拠を残す"
```

- [ ] **Step 8: 署名を確かめて PR を出す**

```bash
git log --format='%H' origin/main..HEAD | while read -r sha; do
  if git cat-file commit "$sha" | grep -q gpgsig; then
    echo "signed   $sha"
  else
    echo "UNSIGNED $sha"
  fi
done
```

期待: すべて `signed`。`%G?` は当てにならないため使わない。

PR は 1 本にまとめる（stacked PR にしない）。

---

## 自己レビューの結果

**spec の網羅:** §10 のパリティチェックリスト 6 件は Task 1、e2e の追加は Task 2-6、覆わない範囲の明示は Task 7、CI の踏襲（§10 末尾）とパッケージング（§11 の Dockerfile）は Task 8 が受け持つ。§12 の 7「e2e の書き直しとパリティ確認」がこの計画の全体である。

**プレースホルダ:** Task 6 Step 2 のテストは、Step 1 で読んだ実際の文言に置き換えることを明示している。それ以外に未定の箇所は無い。

**型と名前の一貫性:** `createBucketWithoutAlias` / `deleteBucketById` / `globalAliases` は Task 2 で定義し、Task 3 と Task 5 が使う。`createKey` / `deleteKeyById` / `permittedKeyNames` は Task 3 でのみ使う。`objects.spec.ts`（Task 6）は別ファイルのため `request.post` を自前で書いており、ヘルパーを共有していない。
