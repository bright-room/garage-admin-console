# garage-admin-console 再構築 Phase 3（クラスタ・レイアウト・メンテナンス・トークン）実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** クラスタの状態とレイアウトを画面から扱え、レイアウトの stage → preview → apply / revert が確認ダイアログ付きで通り、ワーカー・ブロックエラー・Admin token を画面から管理できるところまでを、Phase 1–2 の基盤の上に積む。これで Admin API v2 の 46 operation すべてが UI から到達可能になる。

**Architecture:** `:shared` に Garage の oneOf 型 4 つ（`WorkerStateResp` / `ZoneRedundancy` / `NodeRoleChange` / `PreviewClusterLayoutChangesResponse`）のカスタム serializer とクラスタ系モデルを足す。`:server` の `garage/` が operation ごとの型付き関数を提供し、`api/` がリソース指向の endpoint に載せ替える。ノード別に成否が割れる operation は `MultiResponse` のまま web まで運ぶ（spec §7.3）。`:web` はポーリングを共通部品に抽出したうえで、`/nodes` `/layout` `/workers` `/blocks` `/tokens` の 5 画面を足す。

**Tech Stack:** Kotlin Multiplatform / Ktor 3.5.2 / Compose Multiplatform 1.11.1 (wasmJs) / kotlinx.serialization / Playwright

**Spec:** `docs/superpowers/specs/2026-08-23-garage-admin-console-rebuild-design.md`

**前提計画:**
- `docs/superpowers/plans/2026-08-23-rebuild-phase1-foundation.md`（完了済み）
- `docs/superpowers/plans/2026-08-23-rebuild-phase2-storage.md`（完了済み。末尾の「Phase 3 への申し送り」を踏まえている）

## Global Constraints

- 対象 Garage: **v2.3.0**（Admin API v2）。仕様の参照元は `https://garagehq.deuxfleurs.fr/api/garage-admin-v2.json`
- バージョンは `gradle/libs.versions.toml` の既存値を変更しない: Kotlin `2.4.10` / Ktor `3.5.2` / Compose Multiplatform `1.11.1` / kotlinx-serialization `1.11.0` / kotlinx-coroutines `1.11.0` / kotlinx-datetime `0.8.0-0.6.x-compat` / aws-sdk-kotlin `1.8.31` / logback `1.6.3`
- **外部のライブラリを新たに追加しない。** ルーティングは手書き、状態管理ライブラリは使わない。**Phase 3 では依存を 1 つも足さない**（Phase 2 の `aws.sdk.kotlin:s3` のような既定路線も無い）
- **クライアントとサーバー間のエラーは RFC 9457（Problem Details for HTTP APIs）に準拠する。** `application/problem+json` を用い、ラッパーを被せず、独自のエラーコード enum を定義しない（spec §7.1）
- **Phase 3 はコンソール固有の問題型（`ProblemTypes`）を新設しない。** Phase 2 が定義した 2 つ（`NO_USABLE_KEY` / `BUCKET_NOT_ADDRESSABLE`）は S3 ブラウザ固有であり、Phase 3 の画面は使わない。Phase 3 の失敗はすべて HTTP ステータスで分類できる
- **サーバー側では scope を判定しない**（spec §6.3）。可否の実体は常に Garage 側にあり、返ってきた 403 を正規化して渡すだけにする
- **縦切りで作る。** モデルは画面が描画する分だけに限る（spec D6）
- パッケージルート: `net.brightroom.garage.shared` / `net.brightroom.garage.server` / `net.brightroom.garage.web`
- `jvmToolchain(21)`（`:server` と `:shared`）
- サーバーは admin token を保持しない。ログにも出さない。**`CreateAdminToken` が返す `secretToken` も、応答としてのみ返し、キャッシュにもログにも残さない**
- テーマはダーク固定
- 整形は Spotless + ktlint（`ktlint_code_style = intellij_idea`, `max_line_length = 120`）。`./gradlew spotlessApply` を通してからコミットする
- コミットは各タスク末尾で 1 つ
- **コードコメントに実測値（秒数・件数・バイト数などの計測結果）を書かない。** 根拠は PR と commit メッセージに置く

---

## フェーズのロードマップ

| Phase | スコープ | 状態 |
|---|---|---|
| 1 | shared 基盤 / Garage クライアント / エラー正規化 / `/api/session` / `/api/overview` / Router / セッション / ログイン / 概況 | **完了**（PR #68–#72） |
| 2 | Buckets / Keys / Objects（S3 資格情報の導出、`SecretCache` の配線、`InspectObject`） | **完了**（PR #76 / #79 / #80 / #82 / #83 / #84） |
| **3（本計画）** | Nodes（Cluster 統合）/ Layout（preview 確認）/ Workers / Blocks / Admin Tokens | 本計画 |
| 4 | 最終パリティ確認と CI 調整 | 未着手 |

### ブランチと PR の分割

Phase 1 / Phase 2 と同じく、タスクをまとめて 1 PR にする。

| ブランチ | タスク | PR の粒度 |
|---|---|---|
| `phase3/1-shared` | Task 1–4 | oneOf の serializer 4 つとクラスタ系モデル、`Route` の 5 画面 |
| `phase3/2-server-cluster` | Task 5–7 | `/api/cluster` と `/api/layout` |
| `phase3/3-server-ops` | Task 8–11 | `/api/nodes` `/api/workers` `/api/blocks` `/api/admin-tokens` |
| `phase3/4-web-cluster` | Task 12–15 | ポーリングの共通化・共通部品・`/nodes` `/layout` |
| `phase3/5-web-maintenance` | Task 16–19 | `/workers` `/blocks` `/tokens` とサイドバー・導線 |
| `phase3/6-e2e` | Task 20–21 | fixture トークンと e2e |

**次のブランチは、前の PR が main にマージされてから切る。** 各タスクの冒頭にある `git switch main && git pull` はそれを前提にしている。`phase3/2-server-cluster` を `phase3/1-shared` のマージ前に始めると、`:shared` のモデルが無くてビルドが通らない。

---

## Phase 3 で先に確認した事実

計画の前に Garage v2.3.0 の実機（`compose.yaml` の dev 環境、単一ノード）と OpenAPI 仕様（`info.version: v2.3.0`）を直接検証した。以下は推測ではなく採取結果である。**これらは Task の中でテストの fixture として使う。**

### oneOf を持つ型が 4 つある

Phase 1–2 で扱った型はすべて素直なオブジェクトだったが、Phase 3 が触る範囲には JSON の形が実行時に変わる型が 4 つある。kotlinx.serialization の既定では扱えないため、いずれも `JsonContentPolymorphicSerializer` か手書きの `KSerializer` が要る。

**1. `WorkerStateResp`** — 文字列 3 種とオブジェクト 1 種。

```json
"idle"
"busy"
"done"
{"throttled": {"durationSecs": 1.5}}
```

実機のアイドルなクラスタでは全ワーカーが `"idle"` を返す。`busy` / `done` / `throttled` は負荷が無いと再現できないため、この 4 形すべてを `:shared` の単体テストで覆う。

**2. `ZoneRedundancy`** — 文字列 1 種とオブジェクト 1 種。

```json
"maximum"
{"atLeast": 2}
```

実機の `GetClusterLayout` は `"parameters": {"zoneRedundancy": "maximum"}` を返した。

**3. `NodeRoleChange`**（`stagedRoleChanges` の要素）— `id` と、`remove` かロールかの oneOf が**同じオブジェクトに平坦化**されている。判別キーは `remove` の有無。

```json
{"id": "e97d…", "remove": true}
{"id": "e97d…", "zone": "dc1", "tags": ["dev"], "capacity": 1073741824}
```

**4. `PreviewClusterLayoutChangesResponse`** — 成功と失敗が同じ 200 の中で形を変える。判別キーは `error` の有無。

```json
{"error": "…レイアウトを計算できなかった理由…"}
{"message": ["…人間向けの説明行…"], "newLayout": {…GetClusterLayout と同じ形…}, "statistics": {…}}
```

### `PreviewClusterLayoutChanges` には副作用が無い

staged 変更が 1 件も無い状態で `POST /v2/PreviewClusterLayoutChanges`（本文なし・クエリなし）を叩いても **200 を返し、`newLayout.version` は現在の版 + 1 になる**。クラスタの状態は変わらない。実機での応答（一部）:

```json
{
  "message": ["==== COMPUTATION OF A NEW PARTITION ASSIGNATION ====", "", "Partitions are replicated 1 times on at least 1 distinct zones.", "…"],
  "newLayout": {
    "version": 2,
    "roles": [{"id": "e97d…", "zone": "dc1", "tags": ["dev"], "capacity": 1073741824, "storedPartitions": 256, "usableCapacity": 1073741824}],
    "parameters": {"zoneRedundancy": "maximum"},
    "partitionSize": 4194304,
    "stagedRoleChanges": [],
    "stagedParameters": null
  }
}
```

つまり **apply の前に preview を必ず呼ぶ（spec §8.6）ことに副作用上の障害は無い。** `message` は「パースするな」と OpenAPI に明記されているので、そのまま行単位で表示する。

### ノード指定が必須の operation がある

以下は `node` クエリパラメータが無いと `400 InvalidRequest`（`Missing argument \`node\` for endpoint`）になる。`node=*` で全ノードに問い合わせ、`MultiResponse` が返る。

| operation | HTTP | node クエリ | 本文 |
|---|---|---|---|
| `GetNodeInfo` | GET | 必須 | — |
| `GetNodeStatistics` | GET | 必須 | — |
| `CreateMetadataSnapshot` | POST | 必須 | — |
| `LaunchRepairOperation` | POST | 必須 | `{"repairType": …}` |
| `ListWorkers` | **POST** | 必須 | `{}`（`busyOnly` / `errorOnly` は省略可） |
| `GetWorkerInfo` | POST | 必須 | `{"id": 20}` |
| `GetWorkerVariable` | **POST** | 必須 | `{}`（`variable` は省略可） |
| `SetWorkerVariable` | POST | 必須 | `{"variable": …, "value": …}` |
| `ListBlockErrors` | GET | 必須 | — |
| `GetBlockInfo` | POST | 必須 | `{"blockHash": …}` |
| `RetryBlockResync` | POST | 必須 | `{"all": true}` または `{"blockHashes": [...]}` |
| `PurgeBlocks` | POST | 必須 | `["hash", …]`（**JSON 配列がトップレベル**） |

`ListWorkers` と `GetWorkerVariable` は読み取りなのに Garage 側が POST である。spec §7 はこれをコンソール側で GET に均している（`GET /api/workers`, `GET /api/workers/variables`）。

### `MultiResponse` の実機の形

```json
{"success": {"e97d…": [ … ]}, "error": {}}
```

`error` は空でも必ず存在した。`success` の値はノードごとに異なる型を取る（ワーカー一覧、ブロックエラー一覧、ノード情報、変数マップ）。副作用だけの operation（`CreateMetadataSnapshot` / `LaunchRepairOperation`）は `success` の値が `null` になる。

### `ListWorkers` の要素

```json
{
  "id": 9,
  "name": "Block scrub worker",
  "state": "idle",
  "errors": 0,
  "consecutiveErrors": 0,
  "lastError": null,
  "tranquility": 4,
  "progress": null,
  "queueLength": null,
  "persistentErrors": 0,
  "freeform": ["Last scrub completed at 1970-01-01T00:00:00.000Z", "Next scrub scheduled for 2026-09-22T14:52:57.592Z"]
}
```

`lastError` は `{"message": "…", "secsAgo": 12}`。`tranquility` / `progress` / `queueLength` / `persistentErrors` は null になりうる。`freeform` は必須だが空配列になりうる。

### `GetWorkerVariable` はノードごとの平坦な文字列マップ

```json
{"success": {"e97d…": {
  "resync-worker-count": "1",
  "resync-tranquility": "2",
  "scrub-tranquility": "4",
  "scrub-corruptions_detected": "0",
  "scrub-last-completed": "1970-01-01T00:00:00.000Z",
  "scrub-next-run": "2026-09-22T14:52:57.592Z",
  "lifecycle-last-completed": "2026-08-24"
}}, "error": {}}
```

値はすべて文字列である。読み取り専用の変数と設定可能な変数を API は区別しない。**この事実が P3-8 の根拠になる。**

### `GetNodeInfo` / `GetNodeStatistics` の実機の形

```json
{"success": {"e97d…": {
  "nodeId": "e97d…", "hostname": "e4dcc2c566e3", "garageVersion": "v2.3.0",
  "garageFeatures": ["bundled-libs", "…"], "rustVersion": "1.91.0",
  "dbEngine": "sqlite3 v3.51.1 (using rusqlite crate)"
}}, "error": {}}
```

`GetNodeStatistics` は `freeform`（人間向けのテキスト）と `tableStats`（`tableName` / `items` / `merkleItems` / `merkleQueueLen` / `insertQueueLen` / `gcQueueLen`）と `blockManagerStats`（`rcEntries` / `resyncQueueLen` / `resyncErrors`）を返す。

### `GetClusterLayout` / `GetClusterLayoutHistory` / `GetClusterStatistics`

```json
// GetClusterLayout
{"version": 1,
 "roles": [{"id": "e97d…", "zone": "dc1", "tags": ["dev"], "capacity": 1073741824, "storedPartitions": 256, "usableCapacity": 1073741824}],
 "parameters": {"zoneRedundancy": "maximum"},
 "partitionSize": 4194304, "stagedRoleChanges": [], "stagedParameters": null}

// GetClusterLayoutHistory
{"currentVersion": 1, "minAck": 1,
 "versions": [{"version": 1, "status": "Current", "storageNodes": 1, "gatewayNodes": 0}],
 "updateTrackers": null}

// GetClusterStatistics
{"freeform": "Storage nodes:\n…", "dataAvail": 966453747712, "metadataAvail": 966453747712,
 "incompleteAvailInfo": false, "bucketCount": 1, "totalObjectCount": 0, "totalObjectBytes": 0}
```

`ClusterLayoutVersionStatus` の値は `"Current"` / `"Draining"` / `"Historical"`（**先頭大文字**。Garage の他の enum は小文字なので取り違えやすい）。

`GetClusterLayout` の `roles` は `id` を持ち `storedPartitions` / `usableCapacity` が付く（`LayoutNodeRole`）。一方 `GetClusterStatus` の `nodes[].role` は `id` を持たず 3 フィールドだけ（`NodeAssignedRole`、Phase 1 で実装済み）。**別の型なので使い回さない。**

### `ListAdminTokens` は設定ファイル由来のトークンを含む

```json
[{"id": null, "created": null, "name": "admin_token (from daemon configuration)", "expiration": null, "expired": false, "scope": ["*"]},
 {"id": null, "created": null, "name": "metrics_token (from daemon configuration)", "expiration": null, "expired": false, "scope": ["Metrics"]},
 {"id": "29251efb12de2341ae8bc4a0", "created": "2026-08-24T08:38:16.773Z", "name": "dev-limited", "expiration": null, "expired": false, "scope": ["GetCurrentAdminTokenInfo", "…"]}]
```

`id: null` のトークンは `garage.toml` に書かれた値であり、`UpdateAdminToken` / `DeleteAdminToken` の対象にできない（どちらも `id` クエリを要求する）。**この事実が P3-6 の根拠になる。** Phase 1 が実装した `AdminToken`（`shared/model/garage/AdminToken.kt`）は既に `id` と `created` を nullable にしてあるので、そのまま使える。

### `CreateAdminToken` の応答

`GetAdminTokenInfoResponse` に `secretToken` を足した形。OpenAPI に「この値は一度しか表示されない」と明記されている。要求本文は `UpdateAdminTokenRequestBody` と同一（`name` / `expiration` / `neverExpires` / `scope` がすべて省略可）。

### `ConnectClusterNodes` の要求と応答

要求はトップレベルが文字列配列（`["<nodeId>@<host>:<port>", …]`）、応答は `[{"success": true, "error": null}, …]` の配列で、要求と同じ順に並ぶ。`MultiResponse` ではない。

### 単一ノードの dev では再現できないもの

`compose.yaml` の Garage は 1 ノードである。以下は e2e で状態を作れない。

- `ConnectClusterNodes` の成功（接続先が無い）
- `ClusterLayoutSkipDeadNodes`（死んだノードが無い）
- 非空の `ListBlockErrors` と `GetBlockInfo` / `RetryBlockResync` / `PurgeBlocks` の成功系（ブロックエラーを人為的に作れない）
- `MultiResponse.error` が非空になる経路（ノードが 1 台なので落ちれば全体が落ちる）
- ワーカーの `busy` / `done` / `throttled`

**これらは `ktor-client-mock` を使ったサーバーの単体テストと `:shared` の直列化テストで覆う。** e2e では「画面が出て、操作の導線があり、押すと確認ダイアログが出る」ところまでを見る（P3-9）。

---

## Phase 3 が固定する設計判断

spec に書かれていない、実装中に迷いうる点を先に決める。**各タスクはこの決定に従うこと。**

| # | 判断 | 理由 |
|---|---|---|
| P3-1 | oneOf を持つ 4 つの型に `JsonContentPolymorphicSerializer` ベースのカスタム serializer を書き、`:shared` に置く。実機の JSON を fixture にした**デコードとエンコードの往復テスト**を全形に付ける | wasmJs と JVM の双方で同じ serializer が動くことを `:shared` のテストで一度に担保できる。往復にするのは `NodeRoleChange` と `ZoneRedundancy` が送信にも使われるため |
| P3-2 | ポーリングを `web/components/Polling.kt` に抽出する。`OverviewScreen` の inline 実装を移し、Nodes / Layout / Workers / Blocks と共有する | 消費者が 5 つになる。spec §8.5 の「トグル」「最終更新 N 秒前」「hidden で停止」を 5 箇所に書き写すと必ずずれる |
| P3-3 | `StatusChip` をここで作る（Phase 2 の P2-13 で持ち越した分）。色は `success` / `warning` / `error` / `neutral` の 4 段だけ | ノード稼働・ワーカー状態・レイアウト版の状態・ブロックエラーの有無で、実際に 4 段が要ることが確認できた |
| P3-4 | `MultiResponseView` を作り、ノード別の success / error を潰さず出す（spec §7.3）。`error` が空でないノードだけを警告として上に出し、`success` は呼び出し側が描く。**`/workers` と `/blocks` はこの部品をそのまま使い、`/nodes` は同じ考え方をノード一覧の中に織り込む**（ノードは `GetClusterStatus` を軸に束ねるため、部品の形が合わない） | 「node-c だけ失敗」を見せるのが spec の目的であり、握り潰すと目的を失う |
| P3-5 | Layout の stage で編集できるのは「ロールの割り当て（zone / capacity / tags）」「ロールの削除」「`zoneRedundancy`」の 3 つ。それ以外の `LayoutParameters` は無い | OpenAPI の `LayoutParameters` は `zoneRedundancy` の 1 フィールドしか持たない。`UpdateClusterLayout` が受け付ける範囲を過不足なく覆う |
| P3-6 | Admin token 一覧は `id: null` のトークンも表示するが、行を開けず編集も削除もできない。「設定ファイル由来」と明示する | 実機に存在し、隠すと「一覧に出ないトークンがある」という嘘になる。`id` が無い以上 API では触れない |
| P3-7 | ログイン中のトークンを削除・失効させうる操作は、確認ダイアログで「これはログイン中のトークンです」と明示する。実行後は次の API 呼び出しが 401 になり、既存の `session.invalidate()` 経路でログイン画面に戻る | 特別な後始末は要らない。spec §6.2 の 5 番が既にこの経路を定めている |
| P3-8 | `SetWorkerVariable` の編集対象は `GetWorkerVariable` が返した変数に限る。変数名の自由入力は作らない | API は読み取り専用の変数（`scrub-last-completed` など）と設定可能な変数を区別しない。自由入力にすると無効な変数名を送れてしまう。返ってきたキーだけを出せば、少なくとも存在する変数に限定できる |
| P3-9 | 単一ノードの dev で再現できない操作（「先に確認した事実」の最終節）は e2e で覆わず、`ktor-client-mock` の単体テストで覆う。e2e は画面の描画と導線の存在までを見る | 状態を作れないものを e2e に入れると、通らないテストか、何も検証しないテストのどちらかになる |
| P3-10 | e2e のログインを `page.addInitScript` で `sessionStorage` に直接入れる方式に変える。`helpers.ts` の `openScreen` を差し替え、`signIn` はログイン画面自体のテスト（`login.spec.ts`）にだけ残す | Phase 2 の申し送り。現行の `openScreen` は 1 テストにつき wasm を 2 回読む。Phase 3 でテスト数がおよそ倍になる |
| P3-11 | `node` クエリは常に `*` を送る。ノードを選んで問い合わせる UI は作らない | spec §7 の endpoint 定義にノード指定が無い。全ノードの結果を `MultiResponse` で見せるのが §7.3 の趣旨であり、ノード選択は屋上屋になる |
| P3-12 | `/nodes` 画面は「クラスタ全体（health + statistics）」「ノード一覧（status + info + statistics）」「ノードへの操作（connect / snapshot / repair）」の 3 段構成にする。旧 Cluster 画面と旧 Nodes 画面の統合先である（spec §8.1） | 同じノードの情報が 4 つの operation に散っているため、画面側で 1 つに束ねないと運用者が突き合わせることになる |
| P3-13 | `LaunchRepairOperation` の `repairType` は OpenAPI の 10 種すべてを選べるようにする。`scrub` だけは `{"scrub": "start"\|"pause"\|"resume"\|"cancel"}` の 2 段選択になる | 修復の種類を絞る根拠が無い。危険度の説明は spec §8.6 が求める確認ダイアログの本文で担保する |
| P3-14 | ブロックの再同期は「全件再試行（`{"all": true}`）」と「選んだブロックの再試行（`{"blockHashes": [...]}`）」の両方を出す。`PurgeBlocks` は選んだブロックのみ | `RetryBlockResync` の oneOf は両形を定義しており、運用上どちらも要る。`PurgeBlocks` に「全件」は無い（参照を消す操作であり、全件は事故にしかならない） |
| P3-15 | `/api/cluster` は Phase 1 の `/api/overview` を置き換えない。概況は概況のまま残し、`/nodes` 画面が `/api/cluster` を使う | `/api/overview` はセクション単位の縮退という別の契約を持つ（spec §7.2）。統合すると両方の契約が壊れる |
| P3-16 | `layout.spec.ts` を Playwright の別プロジェクトに分け、他の spec の後に走らせる | レイアウトを stage している間、概況の異常帯に「未適用の変更があります」が出る（`Overview.alerts()`）。並行実行のままだと `overview.spec.ts` の「異常はありません」が壊れ、`retries: 2` に隠れた不安定さになる |
| P3-17 | **ダイアログを跨いだ後に「画面の状態として持たれているもの」を e2e で確かめない。** 検証が要る場合は API のテストに回し、UI テストはダイアログが開くところまでに留める | Compose のツリーはダイアログを閉じると空になり、取り戻すには `page.reload()` が要る。しかしリロードは画面の状態を消す。この 2 つは両立しない。該当するのは Admin token の一度きりの secret 表示と、ノード接続の結果 |

---

## File Structure（Phase 3 で触れるファイル）

**維持**（変更しない）
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `Dockerfile`, `compose.yaml`, `compose.ci.yaml`, `docker/garage.toml`
- `shared/build.gradle.kts`, `server/build.gradle.kts`, `web/build.gradle.kts`（依存を足さないため）
- Phase 1–2 が作った `:server` の `s3/` 一式と `api/BucketRoutes.kt` / `api/KeyRoutes.kt` / `api/ObjectRoutes.kt`
- `.github/workflows/on-merge.yaml`, `.github/workflows/security.yml`

**`:shared`**

| ファイル | 種別 | 責務 |
|---|---|---|
| `model/garage/OneOfSerializers.kt` | 新規 | oneOf 4 種の判別ロジックを 1 ファイルに集める |
| `model/garage/WorkerInfo.kt` | 新規 | `ListWorkers` / `GetWorkerInfo` の要素と `WorkerState` |
| `model/garage/ClusterLayout.kt` | 改修 | Phase 1 の `JsonElement` 版を型付きに置き換え、`LayoutNodeRole` / `LayoutParameters` / `ZoneRedundancy` / `NodeRoleChange` を足す |
| `model/garage/LayoutHistory.kt` | 新規 | `GetClusterLayoutHistory` のレスポンス |
| `model/garage/LayoutPreview.kt` | 新規 | `PreviewClusterLayoutChanges` の oneOf と `ComputationStat` |
| `model/garage/ClusterStatistics.kt` | 新規 | `GetClusterStatistics` のレスポンス |
| `model/garage/NodeDetails.kt` | 新規 | `GetNodeInfo` / `GetNodeStatistics` のレスポンス |
| `model/garage/BlockInfo.kt` | 新規 | `GetBlockInfo` のレスポンスと `BlockVersion` |
| `api/ClusterRequests.kt` | 新規 | `/api/cluster` `/api/layout` `/api/nodes` の要求 DTO |
| `api/MaintenanceRequests.kt` | 新規 | `/api/workers` `/api/blocks` `/api/admin-tokens` の要求 DTO と `NodeActionOutcome` |
| `api/Session.kt` | 改修 | `Session` に `id` を足す（ログイン中のトークンを一覧で見分けるため） |
| `navigation/Route.kt` | 改修 | `/nodes` `/layout` `/workers` `/blocks` `/tokens` を足す |

**`:server`**

| ファイル | 種別 | 責務 |
|---|---|---|
| `garage/ClusterOperations.kt` | 新規 | `GetClusterStatus` / `GetClusterHealth` / `GetClusterStatistics` / `ConnectClusterNodes` |
| `garage/LayoutOperations.kt` | 新規 | レイアウト 7 operation |
| `garage/NodeOperations.kt` | 新規 | `GetNodeInfo` / `GetNodeStatistics` / `CreateMetadataSnapshot` / `LaunchRepairOperation` |
| `garage/WorkerOperations.kt` | 新規 | ワーカー 4 operation |
| `garage/BlockOperations.kt` | 新規 | ブロック 4 operation |
| `garage/AdminTokenOperations.kt` | 新規 | Admin token 5 operation（`GetCurrentAdminTokenInfo` は Phase 1 の `TokenValidation.kt` にある） |
| `api/ClusterRoutes.kt` | 新規 | `/api/cluster` |
| `api/LayoutRoutes.kt` | 新規 | `/api/layout` |
| `api/NodeRoutes.kt` | 新規 | `/api/nodes` |
| `api/WorkerRoutes.kt` | 新規 | `/api/workers` |
| `api/BlockRoutes.kt` | 新規 | `/api/blocks` |
| `api/AdminTokenRoutes.kt` | 新規 | `/api/admin-tokens` |
| `plugins/Routing.kt` | 改修 | 6 つのルートを足す |

**`:web`**

| ファイル | 種別 | 責務 |
|---|---|---|
| `components/Polling.kt` | 新規 | ポーリングの状態とヘッダ行 |
| `components/StatusChip.kt` | 新規 | 4 段の状態チップ |
| `components/MultiResponseView.kt` | 新規 | ノード別の失敗の表示 |
| `screens/overview/OverviewScreen.kt` | 改修 | inline のポーリングを `Polling.kt` に置き換える |
| `screens/nodes/NodesScreen.kt` | 新規 | クラスタ全体 + ノード一覧 |
| `screens/nodes/NodeActions.kt` | 新規 | connect / snapshot / repair のダイアログ |
| `screens/layout/LayoutScreen.kt` | 新規 | 現在のレイアウトと staged 変更、履歴 |
| `screens/layout/LayoutStageForm.kt` | 新規 | ロールの割り当て・削除・`zoneRedundancy` の stage |
| `screens/layout/LayoutPreviewDialog.kt` | 新規 | apply 前の preview 確認（spec §8.6） |
| `screens/workers/WorkersScreen.kt` | 新規 | ワーカー一覧と変数の編集 |
| `screens/blocks/BlocksScreen.kt` | 新規 | ブロックエラーと再同期・purge |
| `screens/tokens/TokensScreen.kt` | 新規 | Admin token の一覧・作成・更新・削除 |
| `navigation/NavItem.kt` | 改修 | クラスタ / メンテナンス / 設定の 3 グループを足す |
| `App.kt` | 改修 | 5 画面を配線する |

**`e2e`**

| ファイル | 種別 | 責務 |
|---|---|---|
| `tests/helpers.ts` | 改修 | `openScreen` を `addInitScript` 方式にする、`restrictedToken()` を足す |
| `tests/navigation.spec.ts` | 改修 | 新しい 5 画面への遷移を足す |
| `tests/nodes.spec.ts` | 新規 | 旧 `cluster.spec.ts` のパリティ |
| `tests/layout.spec.ts` | 新規 | 旧 `layout.spec.ts` のパリティ + stage → preview → revert |
| `tests/maintenance.spec.ts` | 新規 | ワーカーとブロック |
| `tests/tokens.spec.ts` | 新規 | Admin token と scope 縮退のサイドバー表示 |
| `playwright.config.ts` | 改修 | `layout.spec.ts` を他の spec の後に走らせるプロジェクト分割（P3-16） |

**dev / CI**

| ファイル | 種別 | 責務 |
|---|---|---|
| `docker/init-garage.sh` | 改修 | 3 本目の fixture トークン `dev-restricted` を発行する |
| `mise.toml` | 改修 | `token` タスクがラベルを選べるようにし、`e2e` が 3 本のトークンを渡す |
| `.github/workflows/on-pull-request.yaml` | 改修 | `E2E_RESTRICTED_TOKEN` を解決する |

---
## ブランチ `phase3/1-shared`（Task 1–4）

### Task 1: oneOf を持つ型の serializer

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializers.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/WorkerInfo.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializersTest.kt`

**Interfaces:**
- Consumes: なし（`:shared` の既存モデルには触れない）
- Produces:
  - `abstract class JsonShapeSerializer<T>(name: String) : KSerializer<T>` — 派生クラスは `fromJson(json: Json, element: JsonElement): T` と `toJson(json: Json, value: T): JsonElement` を実装する
  - `sealed interface WorkerState` — `WorkerState.Idle` / `WorkerState.Busy` / `WorkerState.Done` / `WorkerState.Throttled(durationSecs: Double)`。`val label: String` を持つ
  - `object WorkerStateSerializer : JsonShapeSerializer<WorkerState>`
  - `data class WorkerInfo(id: Long, name: String, state: WorkerState, errors: Long, consecutiveErrors: Long, lastError: WorkerLastError?, tranquility: Int?, progress: String?, queueLength: Long?, persistentErrors: Long?, freeform: List<String>)`
  - `data class WorkerLastError(message: String, secsAgo: Long)`

**なぜ手書きの `KSerializer` なのか。** `JsonContentPolymorphicSerializer` は直列化を `value::class` からの serializer 探索に委ねる。この探索は wasmJs で確実に動くとは限らない。`JsonShapeSerializer` は `JsonDecoder.json` / `JsonEncoder.json`（周囲の `Json` インスタンス）だけを使い、リフレクションに触れないため両ターゲットで同じ挙動になる。

- [ ] **Step 1: ブランチを切る**

```bash
git switch main && git pull
git switch -c phase3/1-shared
```

- [ ] **Step 2: 失敗するテストを書く**

Create `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializersTest.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OneOfSerializersTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun decodesWorkerStateStrings() {
        assertEquals(WorkerState.Idle, json.decodeFromString<WorkerState>("\"idle\""))
        assertEquals(WorkerState.Busy, json.decodeFromString<WorkerState>("\"busy\""))
        assertEquals(WorkerState.Done, json.decodeFromString<WorkerState>("\"done\""))
    }

    @Test
    fun decodesThrottledWorkerState() {
        val state = json.decodeFromString<WorkerState>("""{"throttled":{"durationSecs":1.5}}""")

        assertEquals(WorkerState.Throttled(1.5), state)
    }

    @Test
    fun roundTripsEveryWorkerStateShape() {
        listOf(
            "\"idle\"",
            "\"busy\"",
            "\"done\"",
            """{"throttled":{"durationSecs":1.5}}""",
        ).forEach { raw ->
            val decoded = json.decodeFromString<WorkerState>(raw)

            assertEquals(raw, json.encodeToString(decoded))
        }
    }

    @Test
    fun rejectsUnknownWorkerState() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString<WorkerState>("\"sleeping\"")
        }
    }

    @Test
    fun decodesWorkerInfoFromLiveShape() {
        val worker = json.decodeFromString<WorkerInfo>(
            """
            {"id":9,"name":"Block scrub worker","state":"idle","errors":0,"consecutiveErrors":0,
             "lastError":null,"tranquility":4,"progress":null,"queueLength":null,
             "persistentErrors":0,"freeform":["Last scrub completed at 1970-01-01T00:00:00.000Z"]}
            """.trimIndent(),
        )

        assertEquals(9, worker.id)
        assertEquals(WorkerState.Idle, worker.state)
        assertEquals(4, worker.tranquility)
        assertEquals(null, worker.queueLength)
        assertEquals(1, worker.freeform.size)
    }

    @Test
    fun decodesWorkerInfoWithLastError() {
        val worker = json.decodeFromString<WorkerInfo>(
            """
            {"id":1,"name":"Block resync worker #1","state":{"throttled":{"durationSecs":0.25}},
             "errors":3,"consecutiveErrors":2,"lastError":{"message":"connection refused","secsAgo":12},
             "tranquility":2,"progress":"42%","queueLength":7,"persistentErrors":1,"freeform":[]}
            """.trimIndent(),
        )

        assertEquals(WorkerState.Throttled(0.25), worker.state)
        assertEquals("connection refused", worker.lastError?.message)
        assertEquals(12, worker.lastError?.secsAgo)
        assertEquals("42%", worker.progress)
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

Run: `./gradlew :shared:jvmTest --tests '*OneOfSerializersTest*'`
Expected: FAIL（`WorkerState` / `WorkerInfo` が未解決でコンパイルエラー）

- [ ] **Step 4: `JsonShapeSerializer` と `WorkerStateSerializer` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializers.kt`:

```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON の形が実行時に変わる型（OpenAPI の oneOf）を扱うための土台。
 *
 * 直列化を `value::class` からの serializer 探索に委ねない。探索はリフレクションに
 * 依存し、wasmJs で同じように動く保証が無いため。代わりに周囲の [Json] インスタンス
 * （[JsonDecoder.json] / [JsonEncoder.json]）だけを使って明示的に組み立てる。
 */
abstract class JsonShapeSerializer<T>(private val name: String) : KSerializer<T> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name)

    protected abstract fun fromJson(json: Json, element: JsonElement): T

    protected abstract fun toJson(json: Json, value: T): JsonElement

    final override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("$name は JSON でのみ扱える")

        return fromJson(jsonDecoder.json, jsonDecoder.decodeJsonElement())
    }

    final override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder ?: throw SerializationException("$name は JSON でのみ扱える")

        jsonEncoder.encodeJsonElement(toJson(jsonEncoder.json, value))
    }
}

/** 文字列そのものを取り出す。文字列でなければ null。 */
internal fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

object WorkerStateSerializer : JsonShapeSerializer<WorkerState>("WorkerState") {
    private const val THROTTLED = "throttled"
    private const val DURATION_SECS = "durationSecs"

    override fun fromJson(json: Json, element: JsonElement): WorkerState {
        element.asStringOrNull()?.let { name ->
            return when (name) {
                "idle" -> WorkerState.Idle
                "busy" -> WorkerState.Busy
                "done" -> WorkerState.Done
                else -> throw SerializationException("未知のワーカー状態: $name")
            }
        }

        val throttled = (element as? JsonObject)?.get(THROTTLED)
            ?: throw SerializationException("ワーカー状態として解釈できません")

        return WorkerState.Throttled(throttled.jsonObject.getValue(DURATION_SECS).jsonPrimitive.double)
    }

    override fun toJson(json: Json, value: WorkerState): JsonElement = when (value) {
        WorkerState.Idle, WorkerState.Busy, WorkerState.Done -> JsonPrimitive(value.label)

        is WorkerState.Throttled -> buildJsonObject {
            putJsonObject(THROTTLED) { put(DURATION_SECS, value.durationSecs) }
        }
    }
}
```

- [ ] **Step 5: `WorkerState` と `WorkerInfo` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/WorkerInfo.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/**
 * ワーカーの状態。
 *
 * Garage は文字列 3 種とオブジェクト 1 種を同じフィールドに返す（OpenAPI の oneOf）。
 */
@Serializable(with = WorkerStateSerializer::class)
sealed interface WorkerState {

    /** Garage が返す語をそのまま使う。運用者が CLI の出力と突き合わせられる。 */
    val label: String

    data object Idle : WorkerState {
        override val label: String = "idle"
    }

    data object Busy : WorkerState {
        override val label: String = "busy"
    }

    data object Done : WorkerState {
        override val label: String = "done"
    }

    /** 待機を挟みながら動いている。[durationSecs] は 1 回の待ち時間。 */
    data class Throttled(val durationSecs: Double) : WorkerState {
        override val label: String = "throttled"
    }
}

/** `ListWorkers` / `GetWorkerInfo` の要素。 */
@Serializable
data class WorkerInfo(
    val id: Long,
    val name: String,
    val state: WorkerState,
    val errors: Long = 0,
    val consecutiveErrors: Long = 0,
    val lastError: WorkerLastError? = null,
    /** 高いほど控えめに動く。設定できるワーカーだけが値を持つ。 */
    val tranquility: Int? = null,
    val progress: String? = null,
    val queueLength: Long? = null,
    val persistentErrors: Long? = null,
    /** ワーカーが自分で書く説明行。パースせずそのまま出す。 */
    val freeform: List<String> = emptyList(),
)

@Serializable
data class WorkerLastError(val message: String, val secsAgo: Long)
```

- [ ] **Step 6: テストが通ることを確認する**

Run: `./gradlew :shared:jvmTest --tests '*OneOfSerializersTest*'`
Expected: PASS（7 テスト）

- [ ] **Step 7: wasmJs でも通ることを確認する**

Run: `CHROME_BIN=$(which chromium || which google-chrome) ./gradlew :shared:wasmJsBrowserTest`
Expected: PASS

`CHROME_BIN` が無いとこのタスクは実行できない。ローカルに headless Chrome が要る（CI では自動で解決される）。

- [ ] **Step 8: コミットする**

```bash
./gradlew spotlessApply
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializers.kt \
        shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/WorkerInfo.kt \
        shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializersTest.kt
git commit -m "feat(shared): ワーカー状態の oneOf を扱う serializer を追加"
```

---

### Task 2: レイアウトのモデル

**Files:**
- Modify: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ClusterLayout.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/LayoutHistory.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/LayoutPreview.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ClusterStatistics.kt`
- Modify: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializers.kt`（`ZoneRedundancySerializer` / `NodeRoleChangeSerializer` / `LayoutPreviewSerializer` を足す）
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/api/OverviewServiceTest.kt`（`stagedRoleChanges` の fixture を実形に直す）
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/LayoutModelTest.kt`

**Interfaces:**
- Consumes: `JsonShapeSerializer`（Task 1）
- Produces:
  - `data class ClusterLayout(version: Long, roles: List<LayoutNodeRole>, parameters: LayoutParameters?, partitionSize: Long, stagedRoleChanges: List<NodeRoleChange>, stagedParameters: LayoutParameters?)`
  - `data class LayoutNodeRole(id: String, zone: String, tags: List<String>, capacity: Long?, storedPartitions: Long?, usableCapacity: Long?)` — `val isGateway: Boolean`
  - `data class LayoutParameters(zoneRedundancy: ZoneRedundancy)`
  - `sealed interface ZoneRedundancy` — `ZoneRedundancy.Maximum` / `ZoneRedundancy.AtLeast(zones: Int)`
  - `sealed interface NodeRoleChange` — `NodeRoleChange.Remove(id)` / `NodeRoleChange.Assign(id, zone, tags, capacity)`。共通に `val id: String`
  - `data class LayoutHistory(currentVersion: Long, minAck: Long, versions: List<LayoutVersion>)`
  - `data class LayoutVersion(version: Long, status: LayoutVersionStatus, storageNodes: Long, gatewayNodes: Long)`
  - `enum class LayoutVersionStatus { CURRENT, DRAINING, HISTORICAL }`
  - `sealed interface LayoutPreview` — `LayoutPreview.Failed(error: String)` / `LayoutPreview.Computed(message: List<String>, newLayout: ClusterLayout, statistics: ComputationStat?)`
  - `data class ComputationStat(...)` / `data class ComputationStatZone(...)`
  - `data class ClusterStatistics(freeform: String, dataAvail: Long?, metadataAvail: Long?, incompleteAvailInfo: Boolean, bucketCount: Long, totalObjectCount: Long, totalObjectBytes: Long)`

**Phase 1 の `ClusterLayout` を置き換える。** Phase 1 は `stagedRoleChanges: List<JsonElement>` で件数だけを見ていた。`OverviewService` は `.size` しか呼んでいないためコード変更は要らないが、**`OverviewServiceTest` の fixture は `NodeRoleChange` として解釈できない形なので直す必要がある**（Step 8）。

- [ ] **Step 1: 失敗するテストを書く**

Create `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/LayoutModelTest.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LayoutModelTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** 実機の GetClusterLayout の応答。 */
    private val liveLayout = """
        {"version":1,
         "roles":[{"id":"e97d97ad","zone":"dc1","tags":["dev"],"capacity":1073741824,
                   "storedPartitions":256,"usableCapacity":1073741824}],
         "parameters":{"zoneRedundancy":"maximum"},
         "partitionSize":4194304,
         "stagedRoleChanges":[],
         "stagedParameters":null}
    """.trimIndent()

    @Test
    fun decodesLiveLayout() {
        val layout = json.decodeFromString<ClusterLayout>(liveLayout)

        assertEquals(1, layout.version)
        assertEquals(4194304, layout.partitionSize)
        assertEquals(ZoneRedundancy.Maximum, layout.parameters?.zoneRedundancy)
        assertEquals("dc1", layout.roles.single().zone)
        assertEquals(256, layout.roles.single().storedPartitions)
        assertTrue(layout.stagedRoleChanges.isEmpty())
    }

    @Test
    fun gatewayRoleHasNoCapacity() {
        val role = json.decodeFromString<LayoutNodeRole>(
            """{"id":"abc","zone":"dc2","tags":[]}""",
        )

        assertTrue(role.isGateway)
    }

    @Test
    fun roundTripsZoneRedundancy() {
        listOf("\"maximum\"", """{"atLeast":2}""").forEach { raw ->
            val decoded = json.decodeFromString<ZoneRedundancy>(raw)

            assertEquals(raw, json.encodeToString(decoded))
        }
    }

    @Test
    fun decodesAtLeastZoneRedundancy() {
        assertEquals(ZoneRedundancy.AtLeast(3), json.decodeFromString<ZoneRedundancy>("""{"atLeast":3}"""))
    }

    @Test
    fun roundTripsStagedRoleChanges() {
        val removal = """{"id":"e97d97ad","remove":true}"""
        val assignment = """{"id":"e97d97ad","zone":"dc1","tags":["dev"],"capacity":1073741824}"""

        assertEquals(NodeRoleChange.Remove("e97d97ad"), json.decodeFromString<NodeRoleChange>(removal))
        assertEquals(removal, json.encodeToString(json.decodeFromString<NodeRoleChange>(removal)))
        assertEquals(assignment, json.encodeToString(json.decodeFromString<NodeRoleChange>(assignment)))
    }

    @Test
    fun omitsCapacityForGatewayAssignment() {
        val change: NodeRoleChange = NodeRoleChange.Assign(id = "abc", zone = "dc2", tags = emptyList())

        assertEquals("""{"id":"abc","zone":"dc2","tags":[]}""", json.encodeToString(change))
    }

    @Test
    fun decodesLayoutHistory() {
        val history = json.decodeFromString<LayoutHistory>(
            """
            {"currentVersion":1,"minAck":1,
             "versions":[{"version":1,"status":"Current","storageNodes":1,"gatewayNodes":0}],
             "updateTrackers":null}
            """.trimIndent(),
        )

        assertEquals(1, history.currentVersion)
        assertEquals(LayoutVersionStatus.CURRENT, history.versions.single().status)
    }

    @Test
    fun decodesLayoutVersionStatuses() {
        listOf(
            "Current" to LayoutVersionStatus.CURRENT,
            "Draining" to LayoutVersionStatus.DRAINING,
            "Historical" to LayoutVersionStatus.HISTORICAL,
        ).forEach { (raw, expected) ->
            assertEquals(expected, json.decodeFromString<LayoutVersionStatus>("\"$raw\""))
        }
    }

    @Test
    fun decodesComputedPreview() {
        val preview = json.decodeFromString<LayoutPreview>(
            """
            {"message":["==== COMPUTATION ====",""],
             "newLayout":$liveLayout,
             "statistics":{"replicationFactor":1,"effectiveZoneRedundancy":1,"partitionSize":4194304,
                           "previousPartitionSize":4194304,"lowPartitionSize":false,
                           "usableCapacity":1073741824,"totalCapacity":1073741824,
                           "effectiveCapacity":1073741824,"lowUsableCapacity":false,
                           "totalMovedPartitions":0,
                           "zones":[{"name":"dc1","totalReplicatedPartitions":256,"uniquePartitions":256,
                                     "totalCapacity":1073741824,"usableCapacity":1073741824}]}}
            """.trimIndent(),
        )

        val computed = assertIs<LayoutPreview.Computed>(preview)
        assertEquals(2, computed.message.size)
        assertEquals(1, computed.newLayout.version)
        assertEquals(1, computed.statistics?.replicationFactor)
        assertEquals("dc1", computed.statistics?.zones?.single()?.name)
    }

    @Test
    fun decodesFailedPreview() {
        val preview = json.decodeFromString<LayoutPreview>(
            """{"error":"Zone dc2 has no node with a positive capacity"}""",
        )

        assertEquals("Zone dc2 has no node with a positive capacity", assertIs<LayoutPreview.Failed>(preview).error)
    }

    @Test
    fun decodesClusterStatistics() {
        val statistics = json.decodeFromString<ClusterStatistics>(
            """
            {"freeform":"Storage nodes:\n","dataAvail":966453747712,"metadataAvail":966453747712,
             "incompleteAvailInfo":false,"bucketCount":1,"totalObjectCount":0,"totalObjectBytes":0}
            """.trimIndent(),
        )

        assertEquals(966453747712, statistics.dataAvail)
        assertEquals(1, statistics.bucketCount)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :shared:jvmTest --tests '*LayoutModelTest*'`
Expected: FAIL（コンパイルエラー）

- [ ] **Step 3: `ClusterLayout.kt` を型付きに置き換える**

Replace the whole of `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ClusterLayout.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetClusterLayout` のレスポンス。`PreviewClusterLayoutChanges` の `newLayout` も同じ形。 */
@Serializable
data class ClusterLayout(
    val version: Long,
    val roles: List<LayoutNodeRole> = emptyList(),
    val parameters: LayoutParameters? = null,
    /** 1 パーティション（シャード）のバイト数。 */
    val partitionSize: Long = 0,
    val stagedRoleChanges: List<NodeRoleChange> = emptyList(),
    val stagedParameters: LayoutParameters? = null,
)

/**
 * レイアウト上のノードの役割。
 *
 * `GetClusterStatus` の [NodeAssignedRole] とは別の型である。あちらは `id` を持たず、
 * `storedPartitions` / `usableCapacity` も無い。使い回さないこと。
 */
@Serializable
data class LayoutNodeRole(
    val id: String,
    val zone: String,
    val tags: List<String> = emptyList(),
    /** gateway ノードでは null。 */
    val capacity: Long? = null,
    /** レイアウト計算の結果として、このノードが保持するパーティション数。 */
    val storedPartitions: Long? = null,
    val usableCapacity: Long? = null,
) {
    /** capacity を持たないノードは gateway として扱われる。 */
    val isGateway: Boolean get() = capacity == null
}

@Serializable
data class LayoutParameters(val zoneRedundancy: ZoneRedundancy)

/** データを複製する最小のゾーン数。 */
@Serializable(with = ZoneRedundancySerializer::class)
sealed interface ZoneRedundancy {

    /** 可能な限り多くのゾーンに複製する。 */
    data object Maximum : ZoneRedundancy

    data class AtLeast(val zones: Int) : ZoneRedundancy
}

/**
 * 次の版で適用されるロールの変更。
 *
 * Garage は `id` と「削除か割り当てか」を 1 つのオブジェクトに平坦化して返す。
 * 判別は `remove` キーの有無で行う。
 */
@Serializable(with = NodeRoleChangeSerializer::class)
sealed interface NodeRoleChange {

    val id: String

    data class Remove(override val id: String) : NodeRoleChange

    data class Assign(
        override val id: String,
        val zone: String,
        val tags: List<String> = emptyList(),
        /** null なら gateway として割り当てる。 */
        val capacity: Long? = null,
    ) : NodeRoleChange
}
```

- [ ] **Step 4: `ZoneRedundancySerializer` と `NodeRoleChangeSerializer` を足す**

Append to `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/OneOfSerializers.kt`:

```kotlin
object ZoneRedundancySerializer : JsonShapeSerializer<ZoneRedundancy>("ZoneRedundancy") {
    private const val MAXIMUM = "maximum"
    private const val AT_LEAST = "atLeast"

    override fun fromJson(json: Json, element: JsonElement): ZoneRedundancy {
        element.asStringOrNull()?.let { name ->
            if (name == MAXIMUM) return ZoneRedundancy.Maximum

            throw SerializationException("未知のゾーン冗長度: $name")
        }

        val atLeast = (element as? JsonObject)?.get(AT_LEAST)
            ?: throw SerializationException("ゾーン冗長度として解釈できません")

        return ZoneRedundancy.AtLeast(atLeast.jsonPrimitive.int)
    }

    override fun toJson(json: Json, value: ZoneRedundancy): JsonElement = when (value) {
        ZoneRedundancy.Maximum -> JsonPrimitive(MAXIMUM)

        is ZoneRedundancy.AtLeast -> buildJsonObject { put(AT_LEAST, value.zones) }
    }
}

object NodeRoleChangeSerializer : JsonShapeSerializer<NodeRoleChange>("NodeRoleChange") {
    private const val ID = "id"
    private const val REMOVE = "remove"
    private const val ZONE = "zone"
    private const val TAGS = "tags"
    private const val CAPACITY = "capacity"

    override fun fromJson(json: Json, element: JsonElement): NodeRoleChange {
        val obj = element as? JsonObject ?: throw SerializationException("ロールの変更はオブジェクトでなければならない")
        val id = obj.getValue(ID).jsonPrimitive.content

        if (REMOVE in obj) return NodeRoleChange.Remove(id)

        return NodeRoleChange.Assign(
            id = id,
            zone = obj.getValue(ZONE).jsonPrimitive.content,
            tags = obj[TAGS]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            capacity = obj[CAPACITY]?.jsonPrimitive?.longOrNull,
        )
    }

    override fun toJson(json: Json, value: NodeRoleChange): JsonElement = buildJsonObject {
        put(ID, value.id)

        when (value) {
            is NodeRoleChange.Remove -> put(REMOVE, true)

            is NodeRoleChange.Assign -> {
                put(ZONE, value.zone)
                putJsonArray(TAGS) { value.tags.forEach { add(it) } }
                // gateway ノードは capacity を持たない。null を送ると容量 0 の
                // ストレージノードとして解釈されうるため、キーごと落とす
                value.capacity?.let { put(CAPACITY, it) }
            }
        }
    }
}
```

追加の import（同じファイルの import 群に足す）:

```kotlin
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.putJsonArray
```

- [ ] **Step 5: `LayoutHistory.kt` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/LayoutHistory.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GetClusterLayoutHistory` のレスポンス。
 *
 * `updateTrackers` は運用の判断に使わないため取り込まない（spec D6）。
 */
@Serializable
data class LayoutHistory(
    val currentVersion: Long,
    /** クラスタの全ノードがこの版までを認識している。 */
    val minAck: Long,
    val versions: List<LayoutVersion> = emptyList(),
)

@Serializable
data class LayoutVersion(
    val version: Long,
    val status: LayoutVersionStatus,
    val storageNodes: Long,
    val gatewayNodes: Long,
)

/** Garage はこの enum だけ先頭大文字で返す。他の enum と揃っていない。 */
@Serializable
enum class LayoutVersionStatus {
    @SerialName("Current")
    CURRENT,

    @SerialName("Draining")
    DRAINING,

    @SerialName("Historical")
    HISTORICAL,
}
```

- [ ] **Step 6: `LayoutPreview.kt` と `LayoutPreviewSerializer` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/LayoutPreview.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/**
 * `PreviewClusterLayoutChanges` のレスポンス。
 *
 * 計算できたかどうかで形が変わるが、HTTP ステータスはどちらも 200 である。
 */
@Serializable(with = LayoutPreviewSerializer::class)
sealed interface LayoutPreview {

    /** staged 変更ではレイアウトを計算できなかった。 */
    @Serializable
    data class Failed(val error: String) : LayoutPreview

    @Serializable
    data class Computed(
        /** 人間向けの説明行。パースしてはならないと OpenAPI に明記されている。 */
        val message: List<String> = emptyList(),
        val newLayout: ClusterLayout,
        val statistics: ComputationStat? = null,
    ) : LayoutPreview
}

/** レイアウト計算の統計。確認ダイアログに出す（spec §8.6）。 */
@Serializable
data class ComputationStat(
    val replicationFactor: Int,
    val effectiveZoneRedundancy: Int,
    val partitionSize: Long,
    val previousPartitionSize: Long? = null,
    /** パーティションが極端に小さいことの警告。 */
    val lowPartitionSize: Boolean = false,
    val usableCapacity: Long,
    val totalCapacity: Long,
    val effectiveCapacity: Long,
    /** 素の容量を活かしきれていないことの警告。 */
    val lowUsableCapacity: Boolean = false,
    /** 新しいノードへ移動するパーティションの総数。 */
    val totalMovedPartitions: Int? = null,
    val zones: List<ComputationStatZone> = emptyList(),
)

/** ゾーンごとの内訳。ノード単位の内訳は画面に出さないため取り込まない（spec D6）。 */
@Serializable
data class ComputationStatZone(
    val name: String,
    val totalReplicatedPartitions: Int,
    val uniquePartitions: Int,
    val totalCapacity: Long,
    val usableCapacity: Long,
)
```

Append to `OneOfSerializers.kt`:

```kotlin
object LayoutPreviewSerializer : JsonShapeSerializer<LayoutPreview>("LayoutPreview") {
    private const val ERROR = "error"

    override fun fromJson(json: Json, element: JsonElement): LayoutPreview {
        val obj = element as? JsonObject ?: throw SerializationException("preview はオブジェクトでなければならない")

        val serializer = if (ERROR in obj) {
            LayoutPreview.Failed.serializer()
        } else {
            LayoutPreview.Computed.serializer()
        }

        return json.decodeFromJsonElement(serializer, obj)
    }

    override fun toJson(json: Json, value: LayoutPreview): JsonElement = when (value) {
        is LayoutPreview.Failed -> json.encodeToJsonElement(LayoutPreview.Failed.serializer(), value)

        is LayoutPreview.Computed -> json.encodeToJsonElement(LayoutPreview.Computed.serializer(), value)
    }
}
```

- [ ] **Step 7: `ClusterStatistics.kt` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ClusterStatistics.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetClusterStatistics` のレスポンス。 */
@Serializable
data class ClusterStatistics(
    /** Garage が組み立てた人間向けのテキスト。整形せずそのまま出す。 */
    val freeform: String = "",
    val dataAvail: Long? = null,
    val metadataAvail: Long? = null,
    /** 一部のノードから空き容量を取れなかった。数値を過信しないための印。 */
    val incompleteAvailInfo: Boolean = false,
    val bucketCount: Long = 0,
    val totalObjectCount: Long = 0,
    val totalObjectBytes: Long = 0,
)
```

- [ ] **Step 8: `OverviewServiceTest` の fixture を実形に直す**

`OverviewService.kt` は `stagedRoleChanges.size` しか見ていないためコード変更は要らない。**しかし `OverviewServiceTest` の fixture は壊れる。** 現在の値は件数を作るためだけの形で、`NodeRoleChange` としては解釈できない:

```kotlin
    private val layoutBody = """
        {"version":7,"roles":[],"parameters":{"zoneRedundancy":"maximum"},"partitionSize":1024,
         "stagedRoleChanges":[{"id":"abc"},{"id":"def"}]}
    """.trimIndent()
```

`{"id":"abc"}` は `remove` も `zone` も持たないため、`NodeRoleChangeSerializer` が割り当ての分岐に入って `zone` の取り出しで失敗する。実機と同じ 2 形に直す:

```kotlin
    private val layoutBody = """
        {"version":7,"roles":[],"parameters":{"zoneRedundancy":"maximum"},"partitionSize":1024,
         "stagedRoleChanges":[{"id":"abc","remove":true},
                              {"id":"def","zone":"dc1","tags":[],"capacity":1024}]}
    """.trimIndent()
```

件数は 2 のままなので、`LayoutSummary.stagedChanges` を見ているアサーションは変わらない。

- [ ] **Step 9: テストが通ることを確認する**

Run: `./gradlew :shared:jvmTest :server:test`
Expected: PASS

- [ ] **Step 10: コミットする**

```bash
./gradlew spotlessApply
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ \
        shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/LayoutModelTest.kt \
        server/src/test/kotlin/net/brightroom/garage/server/api/OverviewServiceTest.kt
git commit -m "feat(shared): レイアウトのモデルと oneOf の serializer を追加"
```

---

### Task 3: ノード・ブロック・トークンのモデルと API 契約 DTO

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/NodeDetails.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/BlockInfo.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/ClusterRequests.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/MaintenanceRequests.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/NodeAndBlockModelTest.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/ClusterRequestsTest.kt`

**Interfaces:**
- Consumes: `ClusterStatus` / `ClusterHealth`（Phase 1）、`AdminToken`（Phase 1）、`ClusterLayout` / `LayoutParameters` / `NodeRoleChange`（Task 2）
- Produces:
  - `data class NodeInfo(nodeId: String, hostname: String?, garageVersion: String?, garageFeatures: List<String>, rustVersion: String?, dbEngine: String?)`
  - `data class NodeStatistics(freeform: String, tableStats: List<TableStat>, blockManagerStats: BlockManagerStats?)`
  - `data class TableStat(tableName: String, items: Long, merkleItems: Long, merkleQueueLen: Long, insertQueueLen: Long, gcQueueLen: Long)`
  - `data class BlockManagerStats(rcEntries: Long, resyncQueueLen: Long, resyncErrors: Long)`
  - `data class BlockInfo(blockHash: String, refcount: Long, versions: List<BlockVersion>)`
  - `data class BlockVersion(versionId: String, refDeleted: Boolean, versionDeleted: Boolean, garbageCollected: Boolean, backlink: BlockVersionBacklink?)`
  - `data class BlockVersionBacklink(storedObject: BacklinkObject?, upload: BacklinkUpload?)`
  - `data class ClusterView(status: ClusterStatus, health: ClusterHealth)`
  - `data class ConnectNodesRequest(nodes: List<String>)` / `data class ConnectNodeResult(node: String, success: Boolean, error: String?)`
  - `data class StageRolesRequest(roles: List<NodeRoleChange>, parameters: LayoutParameters?)`
  - `data class ApplyLayoutRequest(version: Long)` / `data class SkipDeadNodesRequest(version: Long, allowMissingData: Boolean)`
  - `data class RepairRequest(repairType: String, scrubCommand: String?)`
  - `data class SetWorkerVariableRequest(variable: String, value: String)`
  - `data class RetryResyncRequest(all: Boolean, blockHashes: List<String>)` / `data class PurgeBlocksRequest(blockHashes: List<String>)`
  - `data class CreateAdminTokenRequest(name: String, scope: List<String>, expiration: Instant?)`
  - `data class UpdateAdminTokenRequest(name: String?, scope: List<String>?, expiration: Instant?, neverExpires: Boolean)`
  - `data class CreatedAdminToken(token: AdminToken, secretToken: String)`
  - `data class NodeActionOutcome(ok: List<String>, failed: Map<String, String>)`

**`BlockVersionBacklink` に 5 つ目の serializer は要らない。** OpenAPI では `{"object": …}` と `{"upload": …}` の oneOf だが、両方ともオブジェクトで、キーが排他である。両方を nullable のフィールドとして持てば、`explicitNulls = false` のもとで元の形に戻る。判別が要るのは「文字列とオブジェクトが同じ位置に来る」場合だけである。

- [ ] **Step 1: 失敗するテストを書く**

Create `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/NodeAndBlockModelTest.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeAndBlockModelTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun decodesNodeInfoFromLiveShape() {
        val info = json.decodeFromString<NodeInfo>(
            """
            {"nodeId":"e97d97ad","hostname":"e4dcc2c566e3","garageVersion":"v2.3.0",
             "garageFeatures":["bundled-libs","sqlite"],"rustVersion":"1.91.0",
             "dbEngine":"sqlite3 v3.51.1 (using rusqlite crate)"}
            """.trimIndent(),
        )

        assertEquals("e4dcc2c566e3", info.hostname)
        assertEquals(2, info.garageFeatures.size)
    }

    @Test
    fun decodesNodeStatisticsFromLiveShape() {
        val statistics = json.decodeFromString<NodeStatistics>(
            """
            {"freeform":"Node ID: e97d97ad\n",
             "tableStats":[{"tableName":"bucket_v2","items":23,"merkleItems":24,
                            "merkleQueueLen":0,"insertQueueLen":0,"gcQueueLen":0}],
             "blockManagerStats":{"rcEntries":0,"resyncQueueLen":0,"resyncErrors":0}}
            """.trimIndent(),
        )

        assertEquals("bucket_v2", statistics.tableStats.single().tableName)
        assertEquals(23, statistics.tableStats.single().items)
        assertEquals(0, statistics.blockManagerStats?.resyncErrors)
    }

    @Test
    fun decodesBlockInfoWithObjectBacklink() {
        val info = json.decodeFromString<BlockInfo>(
            """
            {"blockHash":"abcd","refcount":2,
             "versions":[{"versionId":"v1","refDeleted":false,"versionDeleted":false,
                          "garbageCollected":false,
                          "backlink":{"object":{"bucketId":"b1","key":"photos/a.jpg"}}}]}
            """.trimIndent(),
        )

        val version = info.versions.single()
        assertEquals("photos/a.jpg", version.backlink?.storedObject?.key)
        assertNull(version.backlink?.upload)
    }

    @Test
    fun decodesBlockInfoWithUploadBacklink() {
        val info = json.decodeFromString<BlockInfo>(
            """
            {"blockHash":"abcd","refcount":1,
             "versions":[{"versionId":"v2","refDeleted":false,"versionDeleted":true,
                          "garbageCollected":false,
                          "backlink":{"upload":{"uploadId":"u1","uploadDeleted":false,
                                                "uploadGarbageCollected":false,
                                                "bucketId":"b1","key":"tmp/big.bin"}}}]}
            """.trimIndent(),
        )

        assertEquals("u1", info.versions.single().backlink?.upload?.uploadId)
    }

    @Test
    fun roundTripsBacklink() {
        val raw = """{"object":{"bucketId":"b1","key":"photos/a.jpg"}}"""

        assertEquals(raw, json.encodeToString(json.decodeFromString<BlockVersionBacklink>(raw)))
    }

    @Test
    fun decodesBlockErrorMultiResponse() {
        val response = json.decodeFromString(
            MultiResponse.serializer(
                kotlinx.serialization.builtins.ListSerializer(BlockError.serializer()),
            ),
            """{"success":{"e97d97ad":[]},"error":{}}""",
        )

        assertEquals(0, response.success.getValue("e97d97ad").size)
        assertEquals(0, response.error.size)
    }
}
```

Create `shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/ClusterRequestsTest.kt`:

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.model.garage.LayoutParameters
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import net.brightroom.garage.shared.model.garage.ZoneRedundancy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterRequestsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun encodesStageRolesRequest() {
        val request = StageRolesRequest(
            roles = listOf(NodeRoleChange.Assign(id = "abc", zone = "dc1", tags = listOf("ssd"), capacity = 1024)),
            parameters = LayoutParameters(ZoneRedundancy.AtLeast(2)),
        )

        assertEquals(
            """{"roles":[{"id":"abc","zone":"dc1","tags":["ssd"],"capacity":1024}],""" +
                """"parameters":{"zoneRedundancy":{"atLeast":2}}}""",
            json.encodeToString(request),
        )
    }

    @Test
    fun omitsParametersWhenNotStaged() {
        val request = StageRolesRequest(roles = listOf(NodeRoleChange.Remove("abc")))

        assertEquals("""{"roles":[{"id":"abc","remove":true}]}""", json.encodeToString(request))
    }

    @Test
    fun decodesRepairRequestWithScrubCommand() {
        val request = json.decodeFromString<RepairRequest>("""{"repairType":"scrub","scrubCommand":"start"}""")

        assertEquals("scrub", request.repairType)
        assertEquals("start", request.scrubCommand)
    }

    @Test
    fun retryResyncDefaultsToNothing() {
        val request = json.decodeFromString<RetryResyncRequest>("{}")

        assertEquals(false, request.all)
        assertTrue(request.blockHashes.isEmpty())
    }

    @Test
    fun nodeActionOutcomeSeparatesFailures() {
        val outcome = json.decodeFromString<NodeActionOutcome>(
            """{"ok":["node-a"],"failed":{"node-b":"connection refused"}}""",
        )

        assertEquals(listOf("node-a"), outcome.ok)
        assertEquals("connection refused", outcome.failed.getValue("node-b"))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :shared:jvmTest --tests '*NodeAndBlockModelTest*' --tests '*ClusterRequestsTest*'`
Expected: FAIL（コンパイルエラー）

- [ ] **Step 3: `NodeDetails.kt` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/NodeDetails.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetNodeInfo` の 1 ノード分。 */
@Serializable
data class NodeInfo(
    val nodeId: String,
    val hostname: String? = null,
    val garageVersion: String? = null,
    val garageFeatures: List<String> = emptyList(),
    val rustVersion: String? = null,
    val dbEngine: String? = null,
)

/** `GetNodeStatistics` の 1 ノード分。 */
@Serializable
data class NodeStatistics(
    /** Garage が組み立てた人間向けのテキスト。 */
    val freeform: String = "",
    val tableStats: List<TableStat> = emptyList(),
    val blockManagerStats: BlockManagerStats? = null,
)

@Serializable
data class TableStat(
    val tableName: String,
    val items: Long,
    val merkleItems: Long,
    val merkleQueueLen: Long,
    val insertQueueLen: Long,
    val gcQueueLen: Long,
)

@Serializable
data class BlockManagerStats(
    /** 参照カウントの件数。おおよそブロック数にあたる。 */
    val rcEntries: Long,
    val resyncQueueLen: Long,
    val resyncErrors: Long,
)
```

- [ ] **Step 4: `BlockInfo.kt` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/BlockInfo.kt`:

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GetBlockInfo` のレスポンス。 */
@Serializable
data class BlockInfo(
    val blockHash: String,
    val refcount: Long,
    val versions: List<BlockVersion> = emptyList(),
)

@Serializable
data class BlockVersion(
    val versionId: String,
    val refDeleted: Boolean,
    val versionDeleted: Boolean,
    val garbageCollected: Boolean,
    val backlink: BlockVersionBacklink? = null,
)

/**
 * このブロックを参照しているもの。
 *
 * OpenAPI では `{"object": …}` と `{"upload": …}` の oneOf だが、両方ともオブジェクトで
 * キーが排他であるため、nullable のフィールド 2 つで表せる。`explicitNulls = false` の
 * もとでは片方だけが JSON に出る。カスタム serializer が要るのは、文字列とオブジェクトが
 * 同じ位置に来る場合だけである。
 */
@Serializable
data class BlockVersionBacklink(
    @SerialName("object")
    val storedObject: BacklinkObject? = null,
    val upload: BacklinkUpload? = null,
)

@Serializable
data class BacklinkObject(val bucketId: String, val key: String)

@Serializable
data class BacklinkUpload(
    val uploadId: String,
    val uploadDeleted: Boolean,
    val uploadGarbageCollected: Boolean,
    val bucketId: String? = null,
    val key: String? = null,
)
```

- [ ] **Step 5: `ClusterRequests.kt` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/ClusterRequests.kt`:

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.ClusterStatus
import net.brightroom.garage.shared.model.garage.LayoutParameters
import net.brightroom.garage.shared.model.garage.NodeRoleChange

/**
 * `GET /api/cluster` の応答。
 *
 * `GetClusterStatus` と `GetClusterHealth` を 1 リクエストにまとめる（spec §7）。
 * `/api/overview` とは別物である。あちらは scope 縮退のためにセクション単位の
 * 成否を持つ契約であり、こちらは両方そろって初めて意味を持つ（P3-15）。
 */
@Serializable
data class ClusterView(val status: ClusterStatus, val health: ClusterHealth)

/** `POST /api/cluster/connect`。要素は `<nodeId>@<host>:<port>` の形。 */
@Serializable
data class ConnectNodesRequest(val nodes: List<String>)

/** 接続の試行結果。要求した [node] と対応する。 */
@Serializable
data class ConnectNodeResult(val node: String, val success: Boolean, val error: String? = null)

/**
 * `POST /api/layout/roles`。stage するだけで適用はしない。
 *
 * [parameters] を省略すると `zoneRedundancy` は変更されない。
 */
@Serializable
data class StageRolesRequest(
    val roles: List<NodeRoleChange> = emptyList(),
    val parameters: LayoutParameters? = null,
)

/** `POST /api/layout/apply`。[version] は適用後の版番号（現在の版 + 1）。 */
@Serializable
data class ApplyLayoutRequest(val version: Long)

/**
 * `POST /api/layout/skip-dead-nodes`。
 *
 * @param allowMissingData 残ったノードでデータの quorum が得られなくても続行する。
 */
@Serializable
data class SkipDeadNodesRequest(val version: Long, val allowMissingData: Boolean = false)

/**
 * `POST /api/nodes/repair`。
 *
 * Garage の `RepairType` は文字列 9 種と `{"scrub": …}` の oneOf だが、その形の
 * 組み立ては `:server` の `garage/` に閉じる。コンソールの契約では
 * [repairType] が `"scrub"` のときだけ [scrubCommand] を伴う。
 */
@Serializable
data class RepairRequest(val repairType: String, val scrubCommand: String? = null)
```

- [ ] **Step 6: `MaintenanceRequests.kt` を書く**

Create `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/MaintenanceRequests.kt`:

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.AdminToken
import kotlin.time.Instant

/**
 * 副作用だけの operation の結果。
 *
 * Garage は `MultiResponse` の `success` にノードごとの `null` を返す。値に意味が
 * 無いため、成功したノードの一覧と失敗の理由だけに落として web へ渡す。
 */
@Serializable
data class NodeActionOutcome(val ok: List<String> = emptyList(), val failed: Map<String, String> = emptyMap())

/** `PUT /api/workers/variables`。 */
@Serializable
data class SetWorkerVariableRequest(val variable: String, val value: String)

/**
 * `POST /api/blocks/{hash}/retry-resync` と全件再試行。
 *
 * [all] が true なら [blockHashes] は無視される。両方が空なら 400 になる。
 */
@Serializable
data class RetryResyncRequest(val all: Boolean = false, val blockHashes: List<String> = emptyList())

/** `POST /api/blocks/purge`。全件を対象にする指定は用意しない（P3-14）。 */
@Serializable
data class PurgeBlocksRequest(val blockHashes: List<String>)

/**
 * `POST /api/admin-tokens`。
 *
 * [expiration] が null なら無期限のトークンを作る。
 */
@Serializable
data class CreateAdminTokenRequest(
    val name: String,
    val scope: List<String>,
    val expiration: Instant? = null,
)

/**
 * `PATCH /api/admin-tokens/{id}`。省略したフィールドは変更されない。
 *
 * @param neverExpires true にすると [expiration] を無視して無期限にする。
 */
@Serializable
data class UpdateAdminTokenRequest(
    val name: String? = null,
    val scope: List<String>? = null,
    val expiration: Instant? = null,
    val neverExpires: Boolean = false,
)

/**
 * `POST /api/admin-tokens` の応答。
 *
 * [secretToken] は Garage が一度しか返さない。サーバーはこれを保持せず、
 * ログにも出さず、この応答としてのみ返す。
 */
@Serializable
data class CreatedAdminToken(val token: AdminToken, val secretToken: String)
```

- [ ] **Step 7: `Session` に `id` を足す**

Task 18 の Admin token 画面は「いまログインに使っているトークン」を一覧の中で見分ける必要がある（P3-7）。名前で突き合わせると同名のトークンで誤判定するため、ID を運ぶ。

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/Session.kt` を次のように変える:

```kotlin
/** ログイン中の admin token の情報。`/api/session` が返す。 */
@Serializable
data class Session(
    val name: String,
    val scope: List<String>,
    val expired: Boolean,
    val expiration: Instant? = null,
    /** 設定ファイル由来のトークンは ID を持たない。 */
    val id: String? = null,
)
```

`toSession()` に 1 行足す:

```kotlin
fun AdminToken.toSession(): Session = Session(
    name = name,
    scope = scope,
    expired = expired,
    expiration = expiration,
    id = id,
)
```

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/SessionTest.kt` に足す:

```kotlin
    @Test
    fun carriesTokenIdIntoSession() {
        val token = AdminToken(
            name = "alice",
            scope = listOf("*"),
            expired = false,
            id = "29251efb",
        )

        assertEquals("29251efb", token.toSession().id)
    }

    @Test
    fun configurationDerivedTokenHasNoId() {
        val token = AdminToken(name = "admin_token (from daemon configuration)", scope = listOf("*"), expired = false)

        assertNull(token.toSession().id)
    }
```

`import kotlin.test.assertNull` が無ければ足す。

- [ ] **Step 8: テストが通ることを確認する**

Run: `./gradlew :shared:jvmTest :server:test`
Expected: PASS。`id` は既定値付きの追加なので、`SessionRoutesTest` は無変更で通る。

- [ ] **Step 9: コミットする**

```bash
./gradlew spotlessApply
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/ \
        shared/src/commonTest/kotlin/net/brightroom/garage/shared/
git commit -m "feat(shared): ノード・ブロック・トークンのモデルと API 契約を追加"
```

---

### Task 4: `Route` に 5 画面を足す

**Files:**
- Modify: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/Route.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/RouteTest.kt`

**Interfaces:**
- Consumes: なし
- Produces: `Route.Nodes` / `Route.Layout` / `Route.Workers` / `Route.Blocks` / `Route.Tokens`（いずれも `data object`、`path` は `/nodes` `/layout` `/workers` `/blocks` `/tokens`）

- [ ] **Step 1: 失敗するテストを書く**

Append to `shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/RouteTest.kt`（クラス本体の末尾に足す）:

```kotlin
    @Test
    fun parsesPhase3Routes() {
        assertEquals(Route.Nodes, Route.parse("/nodes"))
        assertEquals(Route.Layout, Route.parse("/layout"))
        assertEquals(Route.Workers, Route.parse("/workers"))
        assertEquals(Route.Blocks, Route.parse("/blocks"))
        assertEquals(Route.Tokens, Route.parse("/tokens"))
    }

    @Test
    fun phase3RoutesRoundTripThroughPath() {
        listOf(Route.Nodes, Route.Layout, Route.Workers, Route.Blocks, Route.Tokens).forEach { route ->
            assertEquals(route, Route.parse(route.path))
        }
    }

    @Test
    fun trailingSlashResolvesToTheSameRoute() {
        assertEquals(Route.Nodes, Route.parse("/nodes/"))
    }

    @Test
    fun unknownSubPathOfPhase3RouteIsNotFound() {
        assertEquals(Route.NotFound("/nodes/abc"), Route.parse("/nodes/abc"))
    }
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :shared:jvmTest --tests '*RouteTest*'`
Expected: FAIL（`Route.Nodes` が未解決）

- [ ] **Step 3: `Route` に 5 つを足す**

`Route.kt` の `data object Keys` の後（`data class Objects` の前）に足す:

```kotlin
    /** クラスタ状態とノード。旧実装の Cluster 画面と Nodes 画面の統合先（spec §8.1）。 */
    data object Nodes : Route {
        override val path: String = "/nodes"
    }

    data object Layout : Route {
        override val path: String = "/layout"
    }

    data object Workers : Route {
        override val path: String = "/workers"
    }

    data object Blocks : Route {
        override val path: String = "/blocks"
    }

    data object Tokens : Route {
        override val path: String = "/tokens"
    }
```

`parse` の `when` に足す（`segments.size == 2 && segments[0] == "keys"` の分岐の後）:

```kotlin
                segments.size == 1 && segments[0] == "nodes" -> Nodes

                segments.size == 1 && segments[0] == "layout" -> Layout

                segments.size == 1 && segments[0] == "workers" -> Workers

                segments.size == 1 && segments[0] == "blocks" -> Blocks

                segments.size == 1 && segments[0] == "tokens" -> Tokens
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :shared:jvmTest --tests '*RouteTest*'`
Expected: PASS

- [ ] **Step 5: ビルド全体を通す**

Run: `CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build`
Expected: PASS（`:web` は `Route` の網羅的 `when` を持たないため、この時点でコンパイルは通る。`App.kt` の `when` は `Route` を `else` 無しで分岐しているので、**通らない場合は Task 19 まで一時的に `else -> ErrorView("未実装の画面です")` を置くのではなく、Task 19 で正式に配線するまで `when` に 5 つの `-> OverviewScreen(...)` 仮置きを入れる**。仮置きを入れた場合は Task 19 で必ず消すこと）

- [ ] **Step 6: コミットして PR を出す**

```bash
./gradlew spotlessApply
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/Route.kt \
        shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/RouteTest.kt \
        web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt
git commit -m "feat(shared): Phase 3 の 5 画面のルートを追加"
git push -u origin phase3/1-shared
gh pr create --title "feat(shared): Phase 3 のモデルとルートを追加 (Task 1-4)" --body "$(cat <<'EOF'
## 概要

Phase 3（クラスタ・レイアウト・メンテナンス・トークン）の `:shared` 側を用意する。

- Garage の oneOf 型 4 つ（`WorkerStateResp` / `ZoneRedundancy` / `NodeRoleChange` / `PreviewClusterLayoutChangesResponse`）に、リフレクションを使わないカスタム serializer を書いた
- Phase 1 で `JsonElement` のまま受けていた `ClusterLayout` を型付きにした
- ノード・ブロック・Admin token のモデルと、`/api/cluster` `/api/layout` `/api/nodes` `/api/workers` `/api/blocks` `/api/admin-tokens` の要求 DTO を足した
- `Route` に `/nodes` `/layout` `/workers` `/blocks` `/tokens` を足した

## テスト

- 直列化テストの fixture は Garage v2.3.0 の実機から採取した JSON
- 送信にも使う型（`ZoneRedundancy` / `NodeRoleChange`）は往復で検証している
- `:shared` の jvm と wasmJs の双方でテストが通る

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01Ek5y1ML5RXQALQj5oq6XKt
EOF
)"
```

---
## ブランチ `phase3/2-server-cluster`（Task 5–7）

### Task 5: クラスタの operation と `/api/cluster`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/ClusterOperations.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/ClusterRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/ClusterRoutesTest.kt`

**Interfaces:**
- Consumes: `GarageAdminClient` / `garageBody` / `requireSuccess`（Phase 1）、`ClusterView` / `ConnectNodesRequest` / `ConnectNodeResult` / `ClusterStatistics`（Task 2–3）
- Produces:
  - `suspend fun GarageAdminClient.getClusterStatus(token: String): ClusterStatus`
  - `suspend fun GarageAdminClient.getClusterHealth(token: String): ClusterHealth`
  - `suspend fun GarageAdminClient.getClusterStatistics(token: String): ClusterStatistics`
  - `suspend fun GarageAdminClient.connectNodes(token: String, nodes: List<String>): List<ConnectNodeResult>`
  - `fun Route.clusterRoutes(client: GarageAdminClient)` — `GET /cluster`, `GET /cluster/statistics`, `POST /cluster/connect`

- [ ] **Step 1: ブランチを切る**

```bash
git switch main && git pull
git switch -c phase3/2-server-cluster
```

- [ ] **Step 2: 失敗するテストを書く**

Create `server/src/test/kotlin/net/brightroom/garage/server/api/ClusterRoutesTest.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ClusterView
import net.brightroom.garage.shared.api.ConnectNodeResult
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.model.garage.ClusterHealthStatus
import net.brightroom.garage.shared.model.garage.ClusterStatistics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClusterRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val statusBody = """
        {"layoutVersion":1,
         "nodes":[{"id":"n1","isUp":true,"draining":false,"hostname":"garage-a",
                   "role":{"zone":"dc1","tags":["dev"],"capacity":1073741824},
                   "dataPartition":{"available":900,"total":1000}}]}
    """.trimIndent()

    private val healthBody = """
        {"status":"healthy","knownNodes":1,"connectedNodes":1,"storageNodes":1,
         "storageNodesUp":1,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}
    """.trimIndent()

    @Test
    fun combinesStatusAndHealth() = testApplication {
        val called = mutableListOf<String>()
        garageApp(
            MockEngine { request ->
                val operation = request.url.encodedPath.substringAfterLast('/')
                called += operation
                val body = if (operation == "GetClusterStatus") statusBody else healthBody
                respond(body, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/cluster") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(called.containsAll(listOf("GetClusterStatus", "GetClusterHealth")))
        val view = GarageJson.decodeFromString<ClusterView>(response.bodyAsText())
        assertEquals(ClusterHealthStatus.HEALTHY, view.health.status)
        assertEquals("garage-a", view.status.nodes.single().hostname)
    }

    @Test
    fun propagatesForbiddenFromEitherHalf() = testApplication {
        garageApp(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("GetClusterHealth")) {
                    respond("insufficient scope", HttpStatusCode.Forbidden)
                } else {
                    respond(statusBody, HttpStatusCode.OK, jsonHeaders)
                }
            },
        )

        val response = client.get("/api/cluster") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("GetClusterHealth", problem.operation)
    }

    @Test
    fun getsClusterStatistics() = testApplication {
        garageApp(
            MockEngine {
                respond(
                    """{"freeform":"Storage nodes:\n","dataAvail":966453747712,"metadataAvail":966453747712,
                        "incompleteAvailInfo":false,"bucketCount":1,"totalObjectCount":0,"totalObjectBytes":0}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/cluster/statistics") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val statistics = GarageJson.decodeFromString<ClusterStatistics>(response.bodyAsText())
        assertEquals(966453747712, statistics.dataAvail)
    }

    @Test
    fun connectsNodesAndPairsResultsWithRequestOrder() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as io.ktor.http.content.TextContent).text
                respond(
                    """[{"success":true,"error":null},{"success":false,"error":"connection refused"}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.post("/api/cluster/connect") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"nodes":["n1@a:3901","n2@b:3901"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // Garage はトップレベルが文字列配列の本文を要求する
        assertEquals("""["n1@a:3901","n2@b:3901"]""", sent)
        val results = GarageJson.decodeFromString<List<ConnectNodeResult>>(response.bodyAsText())
        assertEquals("n1@a:3901", results[0].node)
        assertTrue(results[0].success)
        assertEquals("n2@b:3901", results[1].node)
        assertFalse(results[1].success)
        assertEquals("connection refused", results[1].error)
    }

    @Test
    fun rejectsEmptyConnectRequest() = testApplication {
        garageApp(MockEngine { respond("[]", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/cluster/connect") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"nodes":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

Run: `./gradlew :server:test --tests '*ClusterRoutesTest*'`
Expected: FAIL（`/api/cluster` が無く 404、およびコンパイルエラー）

- [ ] **Step 4: `ClusterOperations.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/garage/ClusterOperations.kt`:

```kotlin
package net.brightroom.garage.server.garage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import net.brightroom.garage.shared.api.ConnectNodeResult
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.ClusterStatistics
import net.brightroom.garage.shared.model.garage.ClusterStatus

/**
 * クラスタ系 operation への型付きアクセス。
 *
 * Garage の operation 名はこのファイルの外に出さない。web が見るのは
 * リソース指向の `/api/cluster` 以下だけである（spec §7）。
 */

private const val GET_CLUSTER_STATUS = "GetClusterStatus"
private const val GET_CLUSTER_HEALTH = "GetClusterHealth"
private const val GET_CLUSTER_STATISTICS = "GetClusterStatistics"
private const val CONNECT_CLUSTER_NODES = "ConnectClusterNodes"

suspend fun GarageAdminClient.getClusterStatus(token: String): ClusterStatus =
    get(token, GET_CLUSTER_STATUS).garageBody(GET_CLUSTER_STATUS)

suspend fun GarageAdminClient.getClusterHealth(token: String): ClusterHealth =
    get(token, GET_CLUSTER_HEALTH).garageBody(GET_CLUSTER_HEALTH)

suspend fun GarageAdminClient.getClusterStatistics(token: String): ClusterStatistics =
    get(token, GET_CLUSTER_STATISTICS).garageBody(GET_CLUSTER_STATISTICS)

/**
 * ノードへの接続を試みる。
 *
 * Garage は本文のトップレベルに文字列配列を要求し、要求と同じ順で結果を返す。
 * 応答自体は接続先を含まないため、ここで要求と突き合わせて返す。
 */
suspend fun GarageAdminClient.connectNodes(token: String, nodes: List<String>): List<ConnectNodeResult> {
    val body = JsonArray(nodes.map { JsonPrimitive(it) })
    val results = post(token, CONNECT_CLUSTER_NODES, body)
        .garageBodyWith(CONNECT_CLUSTER_NODES, ListSerializer(ConnectAttempt.serializer()))

    return nodes.mapIndexed { index, node ->
        val attempt = results.getOrNull(index)

        ConnectNodeResult(
            node = node,
            success = attempt?.success == true,
            error = attempt?.error ?: "Garage が結果を返しませんでした".takeIf { attempt == null },
        )
    }
}

/** Garage の `ConnectNodeResponse`。接続先を含まないため外には出さない。 */
@Serializable
private data class ConnectAttempt(val success: Boolean, val error: String? = null)
```

- [ ] **Step 5: `ClusterRoutes.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/api/ClusterRoutes.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.connectNodes
import net.brightroom.garage.server.garage.getClusterHealth
import net.brightroom.garage.server.garage.getClusterStatistics
import net.brightroom.garage.server.garage.getClusterStatus
import net.brightroom.garage.shared.api.ClusterView
import net.brightroom.garage.shared.api.ConnectNodesRequest

/**
 * クラスタのルート。
 *
 * `/api/cluster` は状態と健全性をまとめて返す。どちらか一方が 403 なら全体を
 * 403 にする。`/api/overview` のようなセクション単位の縮退はここでは行わない
 * （P3-15）。片方だけの画面は意味を持たないためである。
 */
fun Route.clusterRoutes(client: GarageAdminClient) {
    route("/cluster") {
        get {
            val token = call.adminToken()

            // 2 つの operation は独立している。順に待つ理由が無い
            val view = coroutineScope {
                val status = async { client.getClusterStatus(token) }
                val health = async { client.getClusterHealth(token) }

                ClusterView(status = status.await(), health = health.await())
            }

            call.respond(view)
        }

        get("/statistics") {
            call.respond(client.getClusterStatistics(call.adminToken()))
        }

        post("/connect") {
            val token = call.adminToken()
            val request = call.receive<ConnectNodesRequest>()

            if (request.nodes.isEmpty()) throw InvalidRequestException("接続先のノードを 1 つ以上指定してください")

            call.respond(client.connectNodes(token, request.nodes))
        }
    }
}
```

- [ ] **Step 6: ルートを配線する**

`server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt` の `routing` ブロックに足す:

```kotlin
            clusterRoutes(client)
```

import を足す:

```kotlin
import net.brightroom.garage.server.api.clusterRoutes
```

`server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt` の `routing` ブロックにも同じ 1 行を足し、import も足す。

- [ ] **Step 7: テストが通ることを確認する**

Run: `./gradlew :server:test --tests '*ClusterRoutesTest*'`
Expected: PASS（5 テスト）

- [ ] **Step 8: コミットする**

```bash
./gradlew spotlessApply
git add server/src/main/kotlin/net/brightroom/garage/server/garage/ClusterOperations.kt \
        server/src/main/kotlin/net/brightroom/garage/server/api/ClusterRoutes.kt \
        server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt \
        server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt \
        server/src/test/kotlin/net/brightroom/garage/server/api/ClusterRoutesTest.kt
git commit -m "feat(server): クラスタの状態・統計・接続の API を追加"
```

---

### Task 6: レイアウトの operation

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/LayoutOperations.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/garage/LayoutOperationsTest.kt`

**Interfaces:**
- Consumes: `GarageAdminClient`（Phase 1）、`ClusterLayout` / `LayoutHistory` / `LayoutPreview` / `StageRolesRequest` / `ApplyLayoutRequest` / `SkipDeadNodesRequest`（Task 2–3）
- Produces:
  - `suspend fun GarageAdminClient.getLayout(token: String): ClusterLayout`
  - `suspend fun GarageAdminClient.stageRoles(token: String, request: StageRolesRequest): ClusterLayout`
  - `suspend fun GarageAdminClient.previewLayout(token: String): LayoutPreview`
  - `suspend fun GarageAdminClient.applyLayout(token: String, version: Long): ClusterLayout`
  - `suspend fun GarageAdminClient.revertLayout(token: String): ClusterLayout`
  - `suspend fun GarageAdminClient.getLayoutHistory(token: String): LayoutHistory`
  - `suspend fun GarageAdminClient.skipDeadNodes(token: String, request: SkipDeadNodesRequest): LayoutHistory`

`ApplyClusterLayout` / `RevertClusterLayout` / `UpdateClusterLayout` はいずれも更新後のレイアウトを返す。`ClusterLayoutSkipDeadNodes` は履歴を返す。

- [ ] **Step 1: 失敗するテストを書く**

Create `server/src/test/kotlin/net/brightroom/garage/server/garage/LayoutOperationsTest.kt`:

```kotlin
package net.brightroom.garage.server.garage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.shared.api.SkipDeadNodesRequest
import net.brightroom.garage.shared.api.StageRolesRequest
import net.brightroom.garage.shared.model.garage.LayoutParameters
import net.brightroom.garage.shared.model.garage.LayoutPreview
import net.brightroom.garage.shared.model.garage.LayoutVersionStatus
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import net.brightroom.garage.shared.model.garage.ZoneRedundancy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LayoutOperationsTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val layoutBody = """
        {"version":1,
         "roles":[{"id":"n1","zone":"dc1","tags":["dev"],"capacity":1073741824,
                   "storedPartitions":256,"usableCapacity":1073741824}],
         "parameters":{"zoneRedundancy":"maximum"},
         "partitionSize":4194304,"stagedRoleChanges":[],"stagedParameters":null}
    """.trimIndent()

    private fun clientOf(engine: MockEngine) = GarageAdminClient("http://garage.test:3903", engine)

    @Test
    fun getsLayout() = runTest {
        val client = clientOf(MockEngine { respond(layoutBody, HttpStatusCode.OK, jsonHeaders) })

        val layout = client.getLayout("tok")

        assertEquals(1, layout.version)
        assertEquals(ZoneRedundancy.Maximum, layout.parameters?.zoneRedundancy)
    }

    @Test
    fun stagesRolesWithTypedBody() = runTest {
        var sent = ""
        var method: HttpMethod? = null
        val client = clientOf(
            MockEngine { request ->
                method = request.method
                sent = (request.body as TextContent).text
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.stageRoles(
            "tok",
            StageRolesRequest(
                roles = listOf(NodeRoleChange.Assign(id = "n2", zone = "dc2", tags = emptyList(), capacity = 512)),
                parameters = LayoutParameters(ZoneRedundancy.AtLeast(2)),
            ),
        )

        assertEquals(HttpMethod.Post, method)
        assertEquals(
            """{"parameters":{"zoneRedundancy":{"atLeast":2}},""" +
                """"roles":[{"id":"n2","zone":"dc2","tags":[],"capacity":512}]}""",
            sent,
        )
    }

    @Test
    fun stagesRemovalWithoutParameters() = runTest {
        var sent = ""
        val client = clientOf(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.stageRoles("tok", StageRolesRequest(roles = listOf(NodeRoleChange.Remove("n2"))))

        // parameters を省略すると zoneRedundancy は変更されない
        assertEquals("""{"roles":[{"id":"n2","remove":true}]}""", sent)
    }

    @Test
    fun decodesComputedPreview() = runTest {
        val client = clientOf(
            MockEngine {
                respond(
                    """{"message":["==== COMPUTATION ===="],"newLayout":$layoutBody}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val preview = client.previewLayout("tok")

        assertEquals(1, assertIs<LayoutPreview.Computed>(preview).newLayout.version)
    }

    @Test
    fun decodesFailedPreviewAsSuccessfulResponse() = runTest {
        val client = clientOf(
            MockEngine { respond("""{"error":"no node has capacity"}""", HttpStatusCode.OK, jsonHeaders) },
        )

        val preview = client.previewLayout("tok")

        // 計算できなかったことは 200 の中で表される。例外にしない
        assertEquals("no node has capacity", assertIs<LayoutPreview.Failed>(preview).error)
    }

    @Test
    fun appliesLayoutWithVersion() = runTest {
        var sent = ""
        val client = clientOf(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.applyLayout("tok", 2)

        assertEquals("""{"version":2}""", sent)
    }

    @Test
    fun revertsLayoutWithoutBody() = runTest {
        var operation = ""
        var method: HttpMethod? = null
        val client = clientOf(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                method = request.method
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.revertLayout("tok")

        assertEquals(HttpMethod.Post, method)
        assertEquals("RevertClusterLayout", operation)
    }

    @Test
    fun getsLayoutHistory() = runTest {
        val client = clientOf(
            MockEngine {
                respond(
                    """{"currentVersion":2,"minAck":1,
                        "versions":[{"version":2,"status":"Current","storageNodes":1,"gatewayNodes":0},
                                    {"version":1,"status":"Draining","storageNodes":1,"gatewayNodes":0}],
                        "updateTrackers":null}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val history = client.getLayoutHistory("tok")

        assertEquals(2, history.currentVersion)
        assertEquals(LayoutVersionStatus.DRAINING, history.versions[1].status)
    }

    @Test
    fun skipsDeadNodes() = runTest {
        var sent = ""
        val client = clientOf(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"currentVersion":3,"minAck":3,"versions":[],"updateTrackers":null}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        client.skipDeadNodes("tok", SkipDeadNodesRequest(version = 3, allowMissingData = true))

        assertEquals("""{"version":3,"allowMissingData":true}""", sent)
    }

    @Test
    fun propagatesForbidden() = runTest {
        val client = clientOf(MockEngine { respond("insufficient scope", HttpStatusCode.Forbidden) })

        val failure = kotlin.runCatching { client.getLayout("tok") }.exceptionOrNull()

        assertIs<GarageException>(failure)
        assertEquals(HttpStatusCode.Forbidden, failure.status)
        assertEquals("GetClusterLayout", failure.operation)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :server:test --tests '*LayoutOperationsTest*'`
Expected: FAIL（コンパイルエラー）

- [ ] **Step 3: `LayoutOperations.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/garage/LayoutOperations.kt`:

```kotlin
package net.brightroom.garage.server.garage

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.SkipDeadNodesRequest
import net.brightroom.garage.shared.api.StageRolesRequest
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.LayoutHistory
import net.brightroom.garage.shared.model.garage.LayoutPreview

/**
 * レイアウト系 operation への型付きアクセス。
 *
 * 更新系（`UpdateClusterLayout` / `ApplyClusterLayout` / `RevertClusterLayout`）は
 * いずれも更新後のレイアウトを返す。`ClusterLayoutSkipDeadNodes` だけは履歴を返す。
 */

private const val GET_CLUSTER_LAYOUT = "GetClusterLayout"
private const val UPDATE_CLUSTER_LAYOUT = "UpdateClusterLayout"
private const val PREVIEW_CLUSTER_LAYOUT_CHANGES = "PreviewClusterLayoutChanges"
private const val APPLY_CLUSTER_LAYOUT = "ApplyClusterLayout"
private const val REVERT_CLUSTER_LAYOUT = "RevertClusterLayout"
private const val GET_CLUSTER_LAYOUT_HISTORY = "GetClusterLayoutHistory"
private const val CLUSTER_LAYOUT_SKIP_DEAD_NODES = "ClusterLayoutSkipDeadNodes"

suspend fun GarageAdminClient.getLayout(token: String): ClusterLayout =
    get(token, GET_CLUSTER_LAYOUT).garageBody(GET_CLUSTER_LAYOUT)

/**
 * ロールの変更を stage する。適用はしない。
 *
 * [StageRolesRequest] は Garage の `UpdateClusterLayoutRequest` と同じ形なので
 * そのまま送る。`parameters` を省略すると `zoneRedundancy` は変更されない
 * （`GarageJson` の `explicitNulls = false` がこの意味論を成立させている）。
 */
suspend fun GarageAdminClient.stageRoles(token: String, request: StageRolesRequest): ClusterLayout = post(
    token,
    UPDATE_CLUSTER_LAYOUT,
    GarageJson.encodeToJsonElement(StageRolesRequest.serializer(), request),
).garageBody(UPDATE_CLUSTER_LAYOUT)

/**
 * staged 変更を適用した場合のレイアウトを計算する。
 *
 * クラスタの状態は変わらない。計算できなかった場合も HTTP は 200 であり、
 * 本文が [LayoutPreview.Failed] の形になる。
 */
suspend fun GarageAdminClient.previewLayout(token: String): LayoutPreview =
    post(token, PREVIEW_CLUSTER_LAYOUT_CHANGES).garageBody(PREVIEW_CLUSTER_LAYOUT_CHANGES)

/** @param version 適用後の版番号。Garage は安全策としてこれを要求する。 */
suspend fun GarageAdminClient.applyLayout(token: String, version: Long): ClusterLayout = post(
    token,
    APPLY_CLUSTER_LAYOUT,
    buildJsonObject { put("version", version) },
).garageBody(APPLY_CLUSTER_LAYOUT)

suspend fun GarageAdminClient.revertLayout(token: String): ClusterLayout =
    post(token, REVERT_CLUSTER_LAYOUT).garageBody(REVERT_CLUSTER_LAYOUT)

suspend fun GarageAdminClient.getLayoutHistory(token: String): LayoutHistory =
    get(token, GET_CLUSTER_LAYOUT_HISTORY).garageBody(GET_CLUSTER_LAYOUT_HISTORY)

suspend fun GarageAdminClient.skipDeadNodes(token: String, request: SkipDeadNodesRequest): LayoutHistory = post(
    token,
    CLUSTER_LAYOUT_SKIP_DEAD_NODES,
    GarageJson.encodeToJsonElement(SkipDeadNodesRequest.serializer(), request),
).garageBody(CLUSTER_LAYOUT_SKIP_DEAD_NODES)
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :server:test --tests '*LayoutOperationsTest*'`
Expected: PASS（10 テスト）

`stagesRolesWithTypedBody` のフィールド順（`parameters` が先、`roles` が後）は `StageRolesRequest` の宣言順ではなく kotlinx.serialization の出力順で決まる。**テストが順序違いで落ちた場合は、期待値をテスト側の実際の出力に合わせて直す**（送る内容が同じであれば順序は問題にならない）。

- [ ] **Step 5: コミットする**

```bash
./gradlew spotlessApply
git add server/src/main/kotlin/net/brightroom/garage/server/garage/LayoutOperations.kt \
        server/src/test/kotlin/net/brightroom/garage/server/garage/LayoutOperationsTest.kt
git commit -m "feat(server): レイアウトの 7 operation への型付きアクセスを追加"
```

---

### Task 7: `/api/layout`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/LayoutRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/LayoutRoutesTest.kt`

**Interfaces:**
- Consumes: Task 6 の 7 関数
- Produces: `fun Route.layoutRoutes(client: GarageAdminClient)` — `GET /layout`, `POST /layout/roles`, `POST /layout/preview`, `POST /layout/apply`, `POST /layout/revert`, `GET /layout/history`, `POST /layout/skip-dead-nodes`

- [ ] **Step 1: 失敗するテストを書く**

Create `server/src/test/kotlin/net/brightroom/garage/server/api/LayoutRoutesTest.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.LayoutPreview
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LayoutRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val layoutBody = """
        {"version":1,
         "roles":[{"id":"n1","zone":"dc1","tags":["dev"],"capacity":1073741824,
                   "storedPartitions":256,"usableCapacity":1073741824}],
         "parameters":{"zoneRedundancy":"maximum"},
         "partitionSize":4194304,
         "stagedRoleChanges":[{"id":"n2","remove":true}],
         "stagedParameters":null}
    """.trimIndent()

    @Test
    fun getsLayoutWithStagedChanges() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/layout") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetClusterLayout", operation)
        val layout = GarageJson.decodeFromString<ClusterLayout>(response.bodyAsText())
        assertEquals(NodeRoleChange.Remove("n2"), layout.stagedRoleChanges.single())
    }

    @Test
    fun stagesRoles() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/layout/roles") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"roles":[{"id":"n2","zone":"dc2","tags":[],"capacity":512}]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UpdateClusterLayout", operation)
    }

    @Test
    fun rejectsStageWithNothingToChange() = testApplication {
        garageApp(MockEngine { respond(layoutBody, HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/layout/roles") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"roles":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun returnsFailedPreviewAsTwoHundred() = testApplication {
        garageApp(
            MockEngine { respond("""{"error":"no node has capacity"}""", HttpStatusCode.OK, jsonHeaders) },
        )

        val response = client.post("/api/layout/preview") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val preview = GarageJson.decodeFromString<LayoutPreview>(response.bodyAsText())
        assertEquals("no node has capacity", assertIs<LayoutPreview.Failed>(preview).error)
    }

    @Test
    fun appliesLayout() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/layout/apply") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"version":2}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ApplyClusterLayout", operation)
    }

    @Test
    fun revertsLayoutWithoutRequestBody() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(layoutBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/layout/revert") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("RevertClusterLayout", operation)
    }

    @Test
    fun getsHistory() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """{"currentVersion":1,"minAck":1,
                        "versions":[{"version":1,"status":"Current","storageNodes":1,"gatewayNodes":0}],
                        "updateTrackers":null}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/layout/history") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetClusterLayoutHistory", operation)
    }

    @Test
    fun skipsDeadNodes() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """{"currentVersion":2,"minAck":2,"versions":[],"updateTrackers":null}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.post("/api/layout/skip-dead-nodes") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"version":2,"allowMissingData":false}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ClusterLayoutSkipDeadNodes", operation)
    }

    @Test
    fun requiresBearerToken() = testApplication {
        garageApp(MockEngine { respond(layoutBody, HttpStatusCode.OK, jsonHeaders) })

        val response = client.get("/api/layout")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :server:test --tests '*LayoutRoutesTest*'`
Expected: FAIL（404）

- [ ] **Step 3: `LayoutRoutes.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/api/LayoutRoutes.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.applyLayout
import net.brightroom.garage.server.garage.getLayout
import net.brightroom.garage.server.garage.getLayoutHistory
import net.brightroom.garage.server.garage.previewLayout
import net.brightroom.garage.server.garage.revertLayout
import net.brightroom.garage.server.garage.skipDeadNodes
import net.brightroom.garage.server.garage.stageRoles
import net.brightroom.garage.shared.api.ApplyLayoutRequest
import net.brightroom.garage.shared.api.SkipDeadNodesRequest
import net.brightroom.garage.shared.api.StageRolesRequest

/**
 * レイアウトのルート。
 *
 * `POST /layout/roles` は stage するだけで適用しない。適用は `/layout/apply` で
 * 明示的に行い、web はその前に必ず `/layout/preview` を挟む（spec §8.6）。
 * **preview を強制する仕組みはサーバーには置かない。** Garage の operation を
 * 1:1 で写すのがこの層の役割であり、順序の担保は UI 側の責務である。
 */
fun Route.layoutRoutes(client: GarageAdminClient) {
    route("/layout") {
        get {
            call.respond(client.getLayout(call.adminToken()))
        }

        post("/roles") {
            val token = call.adminToken()
            val request = call.receive<StageRolesRequest>()

            if (request.roles.isEmpty() && request.parameters == null) {
                throw InvalidRequestException("stage する変更がありません")
            }

            call.respond(client.stageRoles(token, request))
        }

        // 計算できなかった場合も 200 を返す。失敗は本文の形で表される
        post("/preview") {
            call.respond(client.previewLayout(call.adminToken()))
        }

        post("/apply") {
            val token = call.adminToken()
            val request = call.receive<ApplyLayoutRequest>()

            call.respond(client.applyLayout(token, request.version))
        }

        post("/revert") {
            call.respond(client.revertLayout(call.adminToken()))
        }

        get("/history") {
            call.respond(client.getLayoutHistory(call.adminToken()))
        }

        post("/skip-dead-nodes") {
            val token = call.adminToken()
            val request = call.receive<SkipDeadNodesRequest>()

            call.respond(client.skipDeadNodes(token, request))
        }
    }
}
```

- [ ] **Step 4: ルートを配線する**

`Routing.kt` と `TestApplication.kt` の双方に `layoutRoutes(client)` と import を足す。

- [ ] **Step 5: テストが通ることを確認する**

Run: `./gradlew :server:test`
Expected: PASS（既存のテストを含む）

- [ ] **Step 6: コミットして PR を出す**

```bash
./gradlew spotlessApply
git add server/src/
git commit -m "feat(server): レイアウトの API を追加"
git push -u origin phase3/2-server-cluster
gh pr create --title "feat(server): クラスタとレイアウトの API を追加 (Task 5-7)" --body "$(cat <<'EOF'
## 概要

- `GET /api/cluster` が `GetClusterStatus` と `GetClusterHealth` を並列に取得してまとめる
- `GET /api/cluster/statistics` と `POST /api/cluster/connect`
- レイアウトの 7 endpoint。`POST /api/layout/roles` は stage のみで、適用は `/apply` に分けている
- `PreviewClusterLayoutChanges` は計算できなかった場合も 200 で返る。この形の分岐を型で受けている

## テスト

- `ktor-client-mock` で Garage をモックし、送信する本文と operation 名を検証している
- preview の 2 形（計算成功 / 失敗）、stage の 2 形（割り当て / 削除）を覆っている

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01Ek5y1ML5RXQALQj5oq6XKt
EOF
)"
```

---
## ブランチ `phase3/3-server-ops`（Task 8–11）

### Task 8: ノードの operation と `/api/nodes`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/NodeOperations.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/NodeRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/NodeRoutesTest.kt`

**Interfaces:**
- Consumes: `GarageAdminClient` / `garageBodyWith`（Phase 1）、`MultiResponse`（Phase 1）、`NodeInfo` / `NodeStatistics` / `NodeActionOutcome` / `RepairRequest`（Task 3）
- Produces:
  - `internal const val ALL_NODES: String = "*"` — **Task 9 と Task 10 も同じパッケージからこれを使う**
  - `internal fun MultiResponse<JsonElement>.toOutcome(): NodeActionOutcome` — **Task 9 と Task 10 も使う**
  - `suspend fun GarageAdminClient.getNodeInfo(token: String): MultiResponse<NodeInfo>`
  - `suspend fun GarageAdminClient.getNodeStatistics(token: String): MultiResponse<NodeStatistics>`
  - `suspend fun GarageAdminClient.createMetadataSnapshot(token: String): NodeActionOutcome`
  - `suspend fun GarageAdminClient.launchRepair(token: String, request: RepairRequest): NodeActionOutcome`
  - `fun Route.nodeRoutes(client: GarageAdminClient)`

**`repairType` の oneOf をここに閉じる。** Garage は文字列 9 種（`tables` / `blocks` / `versions` / `multipartUploads` / `blockRefs` / `blockRc` / `rebalance` / `aliases` / `clearResyncQueue`）と `{"scrub": "start"|"pause"|"resume"|"cancel"}` を受け付ける。コンソールの契約は `repairType: String` と `scrubCommand: String?` の 2 フィールドで、Garage の形への変換はこのファイルで行う（`KeyOperations.kt` が `createBucket` の allow / deny を閉じているのと同じやり方）。

- [ ] **Step 1: ブランチを切る**

```bash
git switch main && git pull
git switch -c phase3/3-server-ops
```

- [ ] **Step 2: 失敗するテストを書く**

Create `server/src/test/kotlin/net/brightroom/garage/server/api/NodeRoutesTest.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.serializer
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.NodeInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun getsNodeInfoForAllNodes() = testApplication {
        var node: String? = null
        garageApp(
            MockEngine { request ->
                node = request.url.parameters["node"]
                respond(
                    """{"success":{"n1":{"nodeId":"n1","hostname":"garage-a","garageVersion":"v2.3.0",
                        "garageFeatures":["sqlite"],"rustVersion":"1.91.0","dbEngine":"sqlite3"}},
                        "error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/nodes/info") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("*", node)
        val info = GarageJson.decodeFromString(
            MultiResponse.serializer(NodeInfo.serializer()),
            response.bodyAsText(),
        )
        assertEquals("garage-a", info.success.getValue("n1").hostname)
    }

    @Test
    fun keepsPerNodeFailures() = testApplication {
        garageApp(
            MockEngine {
                respond(
                    """{"success":{"n1":{"nodeId":"n1"}},"error":{"n2":"node is unreachable"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/nodes/info") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        val info = GarageJson.decodeFromString(
            MultiResponse.serializer(NodeInfo.serializer()),
            response.bodyAsText(),
        )
        // ノード別の失敗を潰さない（spec §7.3）
        assertEquals("node is unreachable", info.error.getValue("n2"))
    }

    @Test
    fun createsMetadataSnapshotAndReportsPerNodeOutcome() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """{"success":{"n1":null},"error":{"n2":"disk full"}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.post("/api/nodes/snapshot") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("CreateMetadataSnapshot", operation)
        val outcome = GarageJson.decodeFromString<NodeActionOutcome>(response.bodyAsText())
        assertEquals(listOf("n1"), outcome.ok)
        assertEquals("disk full", outcome.failed.getValue("n2"))
    }

    @Test
    fun sendsPlainStringRepairType() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/nodes/repair") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"repairType":"blockRefs"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"repairType":"blockRefs"}""", sent)
    }

    @Test
    fun sendsScrubRepairTypeAsObject() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/nodes/repair") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"repairType":"scrub","scrubCommand":"start"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"repairType":{"scrub":"start"}}""", sent)
    }

    @Test
    fun rejectsScrubWithoutCommand() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/nodes/repair") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"repairType":"scrub"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun rejectsUnknownRepairType() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/nodes/repair") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"repairType":"deleteEverything"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

Run: `./gradlew :server:test --tests '*NodeRoutesTest*'`
Expected: FAIL（404 とコンパイルエラー）

- [ ] **Step 4: `NodeOperations.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/garage/NodeOperations.kt`:

```kotlin
package net.brightroom.garage.server.garage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.brightroom.garage.server.api.InvalidRequestException
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.RepairRequest
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.NodeInfo
import net.brightroom.garage.shared.model.garage.NodeStatistics

/**
 * ノード系 operation への型付きアクセス。
 *
 * これらは常に全ノードに問い合わせる。ノードを選ぶ UI は作らないため（P3-11）、
 * `node` クエリは [ALL_NODES] で固定する。
 */

/** Garage の `node` クエリで「全ノード」を表す値。ワーカーとブロックも同じ値を使う。 */
internal const val ALL_NODES: String = "*"

private const val GET_NODE_INFO = "GetNodeInfo"
private const val GET_NODE_STATISTICS = "GetNodeStatistics"
private const val CREATE_METADATA_SNAPSHOT = "CreateMetadataSnapshot"
private const val LAUNCH_REPAIR_OPERATION = "LaunchRepairOperation"

/** Garage が `repairType` に受け付ける文字列。`scrub` だけは別扱いになる。 */
private val PLAIN_REPAIR_TYPES = setOf(
    "tables",
    "blocks",
    "versions",
    "multipartUploads",
    "blockRefs",
    "blockRc",
    "rebalance",
    "aliases",
    "clearResyncQueue",
)

private const val SCRUB = "scrub"

private val SCRUB_COMMANDS = setOf("start", "pause", "resume", "cancel")

/**
 * 副作用だけの operation の結果を落とし込む。
 *
 * Garage は成功したノードの値に `null` を返す。値に意味が無いので、成功した
 * ノード名の一覧と失敗の理由だけにする。ワーカーとブロックの操作も同じ形を返す。
 */
internal fun MultiResponse<JsonElement>.toOutcome(): NodeActionOutcome =
    NodeActionOutcome(ok = success.keys.sorted(), failed = error)

suspend fun GarageAdminClient.getNodeInfo(token: String): MultiResponse<NodeInfo> =
    get(token, GET_NODE_INFO, mapOf("node" to ALL_NODES))
        .garageBodyWith(GET_NODE_INFO, MultiResponse.serializer(NodeInfo.serializer()))

suspend fun GarageAdminClient.getNodeStatistics(token: String): MultiResponse<NodeStatistics> =
    get(token, GET_NODE_STATISTICS, mapOf("node" to ALL_NODES))
        .garageBodyWith(GET_NODE_STATISTICS, MultiResponse.serializer(NodeStatistics.serializer()))

suspend fun GarageAdminClient.createMetadataSnapshot(token: String): NodeActionOutcome =
    post(token, CREATE_METADATA_SNAPSHOT, params = mapOf("node" to ALL_NODES))
        .garageBodyWith(CREATE_METADATA_SNAPSHOT, MultiResponse.serializer(JsonElement.serializer()))
        .toOutcome()

/**
 * 修復を開始する。
 *
 * Garage の `repairType` は文字列 9 種と `{"scrub": …}` の oneOf である。
 * コンソールの契約は 2 つの文字列フィールドなので、その形への変換をここで閉じる。
 */
suspend fun GarageAdminClient.launchRepair(token: String, request: RepairRequest): NodeActionOutcome {
    val body = buildJsonObject { put("repairType", request.toGarageRepairType()) }

    return post(token, LAUNCH_REPAIR_OPERATION, body, mapOf("node" to ALL_NODES))
        .garageBodyWith(LAUNCH_REPAIR_OPERATION, MultiResponse.serializer(JsonElement.serializer()))
        .toOutcome()
}

private fun RepairRequest.toGarageRepairType(): JsonElement {
    if (repairType == SCRUB) {
        val command = scrubCommand
            ?: throw InvalidRequestException("scrub には scrubCommand が必要です")

        if (command !in SCRUB_COMMANDS) throw InvalidRequestException("未知の scrubCommand です: $command")

        return buildJsonObject { put(SCRUB, command) }
    }

    if (repairType !in PLAIN_REPAIR_TYPES) throw InvalidRequestException("未知の repairType です: $repairType")

    return JsonPrimitive(repairType)
}
```

- [ ] **Step 5: `NodeRoutes.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/api/NodeRoutes.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.createMetadataSnapshot
import net.brightroom.garage.server.garage.getNodeInfo
import net.brightroom.garage.server.garage.getNodeStatistics
import net.brightroom.garage.server.garage.launchRepair
import net.brightroom.garage.shared.api.RepairRequest

/**
 * ノードのルート。
 *
 * いずれも全ノードに問い合わせ、ノード別の成否を潰さずに返す（spec §7.3）。
 */
fun Route.nodeRoutes(client: GarageAdminClient) {
    route("/nodes") {
        get("/info") {
            call.respond(client.getNodeInfo(call.adminToken()))
        }

        get("/statistics") {
            call.respond(client.getNodeStatistics(call.adminToken()))
        }

        post("/snapshot") {
            call.respond(client.createMetadataSnapshot(call.adminToken()))
        }

        post("/repair") {
            val token = call.adminToken()
            val request = call.receive<RepairRequest>()

            call.respond(client.launchRepair(token, request))
        }
    }
}
```

- [ ] **Step 6: ルートを配線してテストを通す**

`Routing.kt` と `TestApplication.kt` に `nodeRoutes(client)` と import を足す。

Run: `./gradlew :server:test --tests '*NodeRoutesTest*'`
Expected: PASS（7 テスト）

- [ ] **Step 7: コミットする**

```bash
./gradlew spotlessApply
git add server/src/
git commit -m "feat(server): ノードの情報・統計・スナップショット・修復の API を追加"
```

---

### Task 9: ワーカーの operation と `/api/workers`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/WorkerOperations.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/WorkerRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/WorkerRoutesTest.kt`

**Interfaces:**
- Consumes: `ALL_NODES` / `toOutcome`（Task 8）、`WorkerInfo`（Task 1）、`SetWorkerVariableRequest` / `NodeActionOutcome`（Task 3）
- Produces:
  - `suspend fun GarageAdminClient.listWorkers(token: String): MultiResponse<List<WorkerInfo>>`
  - `suspend fun GarageAdminClient.getWorkerInfo(token: String, id: Long): MultiResponse<WorkerInfo>`
  - `suspend fun GarageAdminClient.getWorkerVariables(token: String): MultiResponse<Map<String, String>>`
  - `suspend fun GarageAdminClient.setWorkerVariable(token: String, request: SetWorkerVariableRequest): NodeActionOutcome`
  - `fun Route.workerRoutes(client: GarageAdminClient)`

**Garage 側は POST だがコンソール側は GET にする。** `ListWorkers` と `GetWorkerVariable` は読み取りしかしないため、spec §7 の定義どおり `GET /api/workers` と `GET /api/workers/variables` にする。`GetWorkerInfo` も同じ理由で `GET /api/workers/{id}` にする。

**ルートの宣言順に注意。** `/variables` は `/{id}` より先に書く。Ktor は定数セグメントを優先するため後でも解決されるが、読む側が取り違えないようにする。

- [ ] **Step 1: 失敗するテストを書く**

Create `server/src/test/kotlin/net/brightroom/garage/server/api/WorkerRoutesTest.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.WorkerInfo
import net.brightroom.garage.shared.model.garage.WorkerState
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val workersBody = """
        {"success":{"n1":[
          {"id":9,"name":"Block scrub worker","state":"idle","errors":0,"consecutiveErrors":0,
           "lastError":null,"tranquility":4,"progress":null,"queueLength":null,
           "persistentErrors":0,"freeform":["Last scrub completed"]},
          {"id":1,"name":"Block resync worker #1","state":{"throttled":{"durationSecs":0.5}},
           "errors":2,"consecutiveErrors":1,"lastError":{"message":"timeout","secsAgo":30},
           "tranquility":2,"progress":"12%","queueLength":4,"persistentErrors":0,"freeform":[]}
        ]},"error":{}}
    """.trimIndent()

    @Test
    fun listsWorkersOverGetWhileCallingGaragePost() = testApplication {
        var method: HttpMethod? = null
        var node: String? = null
        var body = ""
        garageApp(
            MockEngine { request ->
                method = request.method
                node = request.url.parameters["node"]
                body = (request.body as TextContent).text
                respond(workersBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/workers") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // コンソールは GET、Garage 側は POST
        assertEquals(HttpMethod.Post, method)
        assertEquals("*", node)
        assertEquals("{}", body)

        val workers = GarageJson.decodeFromString(
            MultiResponse.serializer(ListSerializer(WorkerInfo.serializer())),
            response.bodyAsText(),
        )
        assertEquals(2, workers.success.getValue("n1").size)
        assertEquals(WorkerState.Idle, workers.success.getValue("n1")[0].state)
        assertEquals(WorkerState.Throttled(0.5), workers.success.getValue("n1")[1].state)
    }

    @Test
    fun getsSingleWorker() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"success":{"n1":{"id":9,"name":"Block scrub worker","state":"done",
                        "errors":0,"consecutiveErrors":0,"freeform":[]}},"error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/workers/9") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"id":9}""", sent)
    }

    @Test
    fun rejectsNonNumericWorkerId() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.get("/api/workers/scrub") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun getsWorkerVariables() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """{"success":{"n1":{"resync-worker-count":"1","scrub-tranquility":"4"}},"error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/workers/variables") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetWorkerVariable", operation)
        val variables = GarageJson.decodeFromString(
            MultiResponse.serializer(MapSerializer(String.serializer(), String.serializer())),
            response.bodyAsText(),
        )
        assertEquals("4", variables.success.getValue("n1").getValue("scrub-tranquility"))
    }

    @Test
    fun setsWorkerVariable() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.put("/api/workers/variables") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"variable":"scrub-tranquility","value":"6"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"variable":"scrub-tranquility","value":"6"}""", sent)
        val outcome = GarageJson.decodeFromString<NodeActionOutcome>(response.bodyAsText())
        assertEquals(listOf("n1"), outcome.ok)
    }

    @Test
    fun rejectsBlankVariableName() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.put("/api/workers/variables") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"variable":"","value":"6"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :server:test --tests '*WorkerRoutesTest*'`
Expected: FAIL（404）

- [ ] **Step 3: `WorkerOperations.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/garage/WorkerOperations.kt`:

```kotlin
package net.brightroom.garage.server.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.SetWorkerVariableRequest
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.WorkerInfo

/**
 * ワーカー系 operation への型付きアクセス。
 *
 * `ListWorkers` と `GetWorkerVariable` は読み取りだが Garage 側は POST である。
 * コンソールの `/api/workers` は GET なので、その差はここで吸収する。
 */

private const val LIST_WORKERS = "ListWorkers"
private const val GET_WORKER_INFO = "GetWorkerInfo"
private const val GET_WORKER_VARIABLE = "GetWorkerVariable"
private const val SET_WORKER_VARIABLE = "SetWorkerVariable"

private val allNodes = mapOf("node" to ALL_NODES)

/** 絞り込みは行わない。画面側で状態別に並べ替える。 */
suspend fun GarageAdminClient.listWorkers(token: String): MultiResponse<List<WorkerInfo>> =
    post(token, LIST_WORKERS, JsonObject(emptyMap()), allNodes)
        .garageBodyWith(LIST_WORKERS, MultiResponse.serializer(ListSerializer(WorkerInfo.serializer())))

suspend fun GarageAdminClient.getWorkerInfo(token: String, id: Long): MultiResponse<WorkerInfo> =
    post(token, GET_WORKER_INFO, buildJsonObject { put("id", id) }, allNodes)
        .garageBodyWith(GET_WORKER_INFO, MultiResponse.serializer(WorkerInfo.serializer()))

/** 変数名を指定せずに呼ぶと、そのノードが持つ変数がすべて返る。 */
suspend fun GarageAdminClient.getWorkerVariables(token: String): MultiResponse<Map<String, String>> =
    post(token, GET_WORKER_VARIABLE, JsonObject(emptyMap()), allNodes)
        .garageBodyWith(
            GET_WORKER_VARIABLE,
            MultiResponse.serializer(MapSerializer(String.serializer(), String.serializer())),
        )

suspend fun GarageAdminClient.setWorkerVariable(
    token: String,
    request: SetWorkerVariableRequest,
): NodeActionOutcome = post(
    token,
    SET_WORKER_VARIABLE,
    buildJsonObject {
        put("variable", request.variable)
        put("value", request.value)
    },
    allNodes,
).garageBodyWith(SET_WORKER_VARIABLE, MultiResponse.serializer(JsonElement.serializer())).toOutcome()
```

- [ ] **Step 4: `WorkerRoutes.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/api/WorkerRoutes.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.getWorkerInfo
import net.brightroom.garage.server.garage.getWorkerVariables
import net.brightroom.garage.server.garage.listWorkers
import net.brightroom.garage.server.garage.setWorkerVariable
import net.brightroom.garage.shared.api.SetWorkerVariableRequest

/**
 * ワーカーのルート。
 *
 * `/variables` は `/{id}` より先に書く。Ktor は定数セグメントを優先するので
 * 順序を変えても解決されるが、読む側が取り違えないようにこの順にする。
 */
fun Route.workerRoutes(client: GarageAdminClient) {
    route("/workers") {
        get {
            call.respond(client.listWorkers(call.adminToken()))
        }

        get("/variables") {
            call.respond(client.getWorkerVariables(call.adminToken()))
        }

        put("/variables") {
            val token = call.adminToken()
            val request = call.receive<SetWorkerVariableRequest>()

            if (request.variable.isBlank()) throw InvalidRequestException("変数名が空です")

            call.respond(client.setWorkerVariable(token, request))
        }

        get("/{id}") {
            val id = call.pathParam("id").toLongOrNull()
                ?: throw InvalidRequestException("ワーカー ID は整数です")

            call.respond(client.getWorkerInfo(call.adminToken(), id))
        }
    }
}
```

- [ ] **Step 5: ルートを配線してテストを通す**

`Routing.kt` と `TestApplication.kt` に `workerRoutes(client)` と import を足す。

Run: `./gradlew :server:test --tests '*WorkerRoutesTest*'`
Expected: PASS（6 テスト）

- [ ] **Step 6: コミットする**

```bash
./gradlew spotlessApply
git add server/src/
git commit -m "feat(server): ワーカーの一覧・詳細・変数の API を追加"
```

---

### Task 10: ブロックの operation と `/api/blocks`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/BlockOperations.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/BlockRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/BlockRoutesTest.kt`

**Interfaces:**
- Consumes: `ALL_NODES` / `toOutcome`（Task 8）、`BlockError`（Phase 1）、`BlockInfo` / `RetryResyncRequest` / `PurgeBlocksRequest` / `NodeActionOutcome`（Task 3）
- Produces:
  - `suspend fun GarageAdminClient.listBlockErrors(token: String): MultiResponse<List<BlockError>>`
  - `suspend fun GarageAdminClient.getBlockInfo(token: String, hash: String): MultiResponse<BlockInfo>`
  - `suspend fun GarageAdminClient.retryBlockResync(token: String, request: RetryResyncRequest): NodeActionOutcome`
  - `suspend fun GarageAdminClient.purgeBlocks(token: String, hashes: List<String>): NodeActionOutcome`
  - `fun Route.blockRoutes(client: GarageAdminClient)`

**spec §7 に無い endpoint を 1 本足す。** `POST /api/blocks/retry-resync`（全件再試行）である。spec §7 の一覧は `POST /api/blocks/{hash}/retry-resync` しか挙げていないが、それでは `RetryBlockResync` の oneOf の片方（`{"all": true}`）に到達する経路が無い。spec §7 の目的は 46 operation すべてを到達可能にすることであり、この追加はその目的に沿う（P3-14）。

- [ ] **Step 1: 失敗するテストを書く**

Create `server/src/test/kotlin/net/brightroom/garage/server/api/BlockRoutesTest.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.model.garage.BlockError
import net.brightroom.garage.shared.model.garage.MultiResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun listsBlockErrors() = testApplication {
        var operation = ""
        var node: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                node = request.url.parameters["node"]
                respond(
                    """{"success":{"n1":[{"blockHash":"abcd","refcount":2,"errorCount":5,
                        "lastTrySecsAgo":60,"nextTryInSecs":120}]},"error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/blocks/errors") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ListBlockErrors", operation)
        assertEquals("*", node)
        val errors = GarageJson.decodeFromString(
            MultiResponse.serializer(ListSerializer(BlockError.serializer())),
            response.bodyAsText(),
        )
        assertEquals("abcd", errors.success.getValue("n1").single().blockHash)
    }

    @Test
    fun getsBlockInfo() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"success":{"n1":{"blockHash":"abcd","refcount":1,"versions":[]}},"error":{}}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/blocks/abcd") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"blockHash":"abcd"}""", sent)
    }

    @Test
    fun retriesSingleBlock() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/blocks/abcd/retry-resync") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"blockHashes":["abcd"]}""", sent)
    }

    @Test
    fun retriesEverything() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/blocks/retry-resync") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"all":true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"all":true}""", sent)
    }

    @Test
    fun rejectsRetryWithNothingToDo() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/blocks/retry-resync") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"all":false,"blockHashes":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun purgesBlocksWithTopLevelArrayBody() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond("""{"success":{"n1":null},"error":{"n2":"busy"}}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/blocks/purge") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"blockHashes":["abcd","efgh"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // Garage はトップレベルが配列の本文を要求する
        assertEquals("""["abcd","efgh"]""", sent)
        val outcome = GarageJson.decodeFromString<NodeActionOutcome>(response.bodyAsText())
        assertEquals(listOf("n1"), outcome.ok)
        assertEquals("busy", outcome.failed.getValue("n2"))
    }

    @Test
    fun rejectsEmptyPurge() = testApplication {
        garageApp(MockEngine { respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/blocks/purge") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"blockHashes":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :server:test --tests '*BlockRoutesTest*'`
Expected: FAIL（404）

- [ ] **Step 3: `BlockOperations.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/garage/BlockOperations.kt`:

```kotlin
package net.brightroom.garage.server.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.RetryResyncRequest
import net.brightroom.garage.shared.model.garage.BlockError
import net.brightroom.garage.shared.model.garage.BlockInfo
import net.brightroom.garage.shared.model.garage.MultiResponse

/**
 * ブロック系 operation への型付きアクセス。
 *
 * `PurgeBlocks` だけは本文のトップレベルが JSON 配列である。
 */

private const val LIST_BLOCK_ERRORS = "ListBlockErrors"
private const val GET_BLOCK_INFO = "GetBlockInfo"
private const val RETRY_BLOCK_RESYNC = "RetryBlockResync"
private const val PURGE_BLOCKS = "PurgeBlocks"

private val allNodes = mapOf("node" to ALL_NODES)

suspend fun GarageAdminClient.listBlockErrors(token: String): MultiResponse<List<BlockError>> =
    get(token, LIST_BLOCK_ERRORS, allNodes)
        .garageBodyWith(LIST_BLOCK_ERRORS, MultiResponse.serializer(ListSerializer(BlockError.serializer())))

suspend fun GarageAdminClient.getBlockInfo(token: String, hash: String): MultiResponse<BlockInfo> =
    post(token, GET_BLOCK_INFO, buildJsonObject { put("blockHash", hash) }, allNodes)
        .garageBodyWith(GET_BLOCK_INFO, MultiResponse.serializer(BlockInfo.serializer()))

/**
 * 再同期を試み直す。
 *
 * Garage は `{"all": true}` と `{"blockHashes": [...]}` の 2 形を受け付ける。
 * 両方を送ることはできないため、[RetryResyncRequest.all] を優先する。
 */
suspend fun GarageAdminClient.retryBlockResync(token: String, request: RetryResyncRequest): NodeActionOutcome {
    val body = if (request.all) {
        buildJsonObject { put("all", true) }
    } else {
        buildJsonObject { putJsonArray("blockHashes") { request.blockHashes.forEach { add(it) } } }
    }

    return post(token, RETRY_BLOCK_RESYNC, body, allNodes)
        .garageBodyWith(RETRY_BLOCK_RESYNC, MultiResponse.serializer(JsonElement.serializer()))
        .toOutcome()
}

/** 本文のトップレベルが配列である点が他の operation と異なる。 */
suspend fun GarageAdminClient.purgeBlocks(token: String, hashes: List<String>): NodeActionOutcome =
    post(token, PURGE_BLOCKS, JsonArray(hashes.map { JsonPrimitive(it) }), allNodes)
        .garageBodyWith(PURGE_BLOCKS, MultiResponse.serializer(JsonElement.serializer()))
        .toOutcome()
```

- [ ] **Step 4: `BlockRoutes.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/api/BlockRoutes.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.getBlockInfo
import net.brightroom.garage.server.garage.listBlockErrors
import net.brightroom.garage.server.garage.purgeBlocks
import net.brightroom.garage.server.garage.retryBlockResync
import net.brightroom.garage.shared.api.PurgeBlocksRequest
import net.brightroom.garage.shared.api.RetryResyncRequest

/**
 * ブロックのルート。
 *
 * `POST /blocks/retry-resync` は spec §7 の一覧に無いが、`RetryBlockResync` の
 * 「全件」に到達する経路が他に無いため足している（P3-14）。
 */
fun Route.blockRoutes(client: GarageAdminClient) {
    route("/blocks") {
        get("/errors") {
            call.respond(client.listBlockErrors(call.adminToken()))
        }

        post("/purge") {
            val token = call.adminToken()
            val request = call.receive<PurgeBlocksRequest>()

            if (request.blockHashes.isEmpty()) throw InvalidRequestException("対象のブロックがありません")

            call.respond(client.purgeBlocks(token, request.blockHashes))
        }

        post("/retry-resync") {
            val token = call.adminToken()
            val request = call.receive<RetryResyncRequest>()

            if (!request.all && request.blockHashes.isEmpty()) {
                throw InvalidRequestException("再同期の対象がありません")
            }

            call.respond(client.retryBlockResync(token, request))
        }

        get("/{hash}") {
            call.respond(client.getBlockInfo(call.adminToken(), call.pathParam("hash")))
        }

        post("/{hash}/retry-resync") {
            val hash = call.pathParam("hash")

            call.respond(client.retryBlockResync(call.adminToken(), RetryResyncRequest(blockHashes = listOf(hash))))
        }
    }
}
```

- [ ] **Step 5: ルートを配線してテストを通す**

`Routing.kt` と `TestApplication.kt` に `blockRoutes(client)` と import を足す。

Run: `./gradlew :server:test --tests '*BlockRoutesTest*'`
Expected: PASS（7 テスト）

- [ ] **Step 6: コミットする**

```bash
./gradlew spotlessApply
git add server/src/
git commit -m "feat(server): ブロックエラーの参照・再同期・purge の API を追加"
```

---

### Task 11: Admin token の operation と `/api/admin-tokens`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/AdminTokenOperations.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/AdminTokenRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/AdminTokenRoutesTest.kt`

**Interfaces:**
- Consumes: `AdminToken`（Phase 1）、`CreateAdminTokenRequest` / `UpdateAdminTokenRequest` / `CreatedAdminToken`（Task 3）
- Produces:
  - `suspend fun GarageAdminClient.listAdminTokens(token: String): List<AdminToken>`
  - `suspend fun GarageAdminClient.getAdminToken(token: String, id: String): AdminToken`
  - `suspend fun GarageAdminClient.createAdminToken(token: String, request: CreateAdminTokenRequest): CreatedAdminToken`
  - `suspend fun GarageAdminClient.updateAdminToken(token: String, id: String, request: UpdateAdminTokenRequest): AdminToken`
  - `suspend fun GarageAdminClient.deleteAdminToken(token: String, id: String)`
  - `fun Route.adminTokenRoutes(client: GarageAdminClient)`

**`secretToken` の扱い。** `CreateAdminToken` の応答だけがこれを含む。サーバーはログにも出さず、キャッシュにも入れず、そのまま `CreatedAdminToken` として返す。`AdminToken` の側には入れない（`GetAdminTokenInfo` は返さないため、そこに入れると常に null のフィールドが残る）。

- [ ] **Step 1: 失敗するテストを書く**

Create `server/src/test/kotlin/net/brightroom/garage/server/api/AdminTokenRoutesTest.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.CreatedAdminToken
import net.brightroom.garage.shared.model.garage.AdminToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminTokenRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun listsTokensIncludingConfigurationDerivedOnes() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """[{"id":null,"created":null,"name":"admin_token (from daemon configuration)",
                         "expiration":null,"expired":false,"scope":["*"]},
                        {"id":"29251efb","created":"2026-08-24T08:38:16.773Z","name":"dev-limited",
                         "expiration":null,"expired":false,"scope":["ListBuckets"]}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ListAdminTokens", operation)
        val tokens = GarageJson.decodeFromString<List<AdminToken>>(response.bodyAsText())
        // 設定ファイル由来のトークンは id を持たない。隠さずに返す（P3-6）
        assertNull(tokens[0].id)
        assertEquals("29251efb", tokens[1].id)
    }

    @Test
    fun createsTokenAndReturnsSecretOnce() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"id":"new1","created":"2026-08-24T09:00:00Z","name":"alice",
                        "expiration":null,"expired":false,"scope":["ListBuckets"],
                        "secretToken":"secret-value"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.post("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice","scope":["ListBuckets"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // expiration を省略したら無期限として送る
        assertEquals("""{"name":"alice","scope":["ListBuckets"],"neverExpires":true}""", sent)
        val created = GarageJson.decodeFromString<CreatedAdminToken>(response.bodyAsText())
        assertEquals("secret-value", created.secretToken)
        assertEquals("alice", created.token.name)
    }

    @Test
    fun createsTokenWithExpiration() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"id":"new2","name":"bob","expiration":"2026-12-31T00:00:00Z",
                        "expired":false,"scope":["*"],"secretToken":"s"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        client.post("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bob","scope":["*"],"expiration":"2026-12-31T00:00:00Z"}""")
        }

        assertEquals("""{"name":"bob","scope":["*"],"expiration":"2026-12-31T00:00:00Z"}""", sent)
    }

    @Test
    fun rejectsBlankTokenName() = testApplication {
        garageApp(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  ","scope":["*"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun rejectsEmptyScope() = testApplication {
        garageApp(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice","scope":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun updatesOnlyGivenFields() = testApplication {
        var sent = ""
        var id: String? = null
        garageApp(
            MockEngine { request ->
                id = request.url.parameters["id"]
                sent = (request.body as TextContent).text
                respond(
                    """{"id":"t1","name":"alice2","expiration":null,"expired":false,"scope":["*"]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.patch("/api/admin-tokens/t1") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice2"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("t1", id)
        // 省略したフィールドは送らない。Garage の「省略 = 変更しない」に合わせる
        assertEquals("""{"name":"alice2"}""", sent)
    }

    @Test
    fun updatesToNeverExpire() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"id":"t1","name":"alice","expiration":null,"expired":false,"scope":["*"]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        client.patch("/api/admin-tokens/t1") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"neverExpires":true,"expiration":"2026-12-31T00:00:00Z"}""")
        }

        // neverExpires が真なら expiration は送らない
        assertEquals("""{"neverExpires":true}""", sent)
    }

    @Test
    fun deletesToken() = testApplication {
        var operation = ""
        var id: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                id = request.url.parameters["id"]
                respond("", HttpStatusCode.OK)
            },
        )

        val response = client.delete("/api/admin-tokens/t1") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("DeleteAdminToken", operation)
        assertEquals("t1", id)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :server:test --tests '*AdminTokenRoutesTest*'`
Expected: FAIL（404）

- [ ] **Step 3: `AdminTokenOperations.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/garage/AdminTokenOperations.kt`:

```kotlin
package net.brightroom.garage.server.garage

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.CreateAdminTokenRequest
import net.brightroom.garage.shared.api.CreatedAdminToken
import net.brightroom.garage.shared.api.UpdateAdminTokenRequest
import net.brightroom.garage.shared.model.garage.AdminToken

/**
 * Admin token 系 operation への型付きアクセス。
 *
 * `GetCurrentAdminTokenInfo` はここには無い。トークンの有効性の確認という別の
 * 役割を持つため `TokenValidation.kt` に置いてある。
 *
 * `CreateAdminToken` の応答だけが `secretToken` を含む。サーバーはこれを保持せず、
 * ログにも出さず、応答としてのみ返す。
 */

private const val LIST_ADMIN_TOKENS = "ListAdminTokens"
private const val GET_ADMIN_TOKEN_INFO = "GetAdminTokenInfo"
private const val CREATE_ADMIN_TOKEN = "CreateAdminToken"
private const val UPDATE_ADMIN_TOKEN = "UpdateAdminToken"
private const val DELETE_ADMIN_TOKEN = "DeleteAdminToken"

suspend fun GarageAdminClient.listAdminTokens(token: String): List<AdminToken> =
    get(token, LIST_ADMIN_TOKENS).garageBodyWith(LIST_ADMIN_TOKENS, ListSerializer(AdminToken.serializer()))

suspend fun GarageAdminClient.getAdminToken(token: String, id: String): AdminToken =
    get(token, GET_ADMIN_TOKEN_INFO, mapOf("id" to id)).garageBody(GET_ADMIN_TOKEN_INFO)

suspend fun GarageAdminClient.createAdminToken(
    token: String,
    request: CreateAdminTokenRequest,
): CreatedAdminToken {
    val body = buildJsonObject {
        put("name", request.name)
        putScope(request.scope)
        // Garage は expiration も neverExpires も無い要求を受け付けるが、
        // その場合の既定が仕様に無い。どちらかを必ず明示する
        if (request.expiration == null) put("neverExpires", true) else put("expiration", request.expiration.toString())
    }

    // 応答は AdminToken に secretToken を足した形なので、本文を 2 回に分けて読む
    val raw = post(token, CREATE_ADMIN_TOKEN, body).requireSuccess(CREATE_ADMIN_TOKEN).bodyAsText()

    return CreatedAdminToken(
        token = GarageJson.decodeFromString(AdminToken.serializer(), raw),
        secretToken = GarageJson.decodeFromString(SecretHolder.serializer(), raw).secretToken,
    )
}

suspend fun GarageAdminClient.updateAdminToken(
    token: String,
    id: String,
    request: UpdateAdminTokenRequest,
): AdminToken {
    val body = buildJsonObject {
        request.name?.let { put("name", it) }
        request.scope?.let { putScope(it) }
        when {
            request.neverExpires -> put("neverExpires", true)
            request.expiration != null -> put("expiration", request.expiration.toString())
        }
    }

    return post(token, UPDATE_ADMIN_TOKEN, body, mapOf("id" to id)).garageBody(UPDATE_ADMIN_TOKEN)
}

suspend fun GarageAdminClient.deleteAdminToken(token: String, id: String) {
    post(token, DELETE_ADMIN_TOKEN, params = mapOf("id" to id)).requireSuccess(DELETE_ADMIN_TOKEN)
}

private fun JsonObjectBuilder.putScope(scope: List<String>) {
    putJsonArray("scope") { scope.forEach { add(it) } }
}

/** `CreateAdminTokenResponse` のうち `AdminToken` に無いフィールドだけを取り出す。 */
@Serializable
private data class SecretHolder(val secretToken: String)
```

- [ ] **Step 4: `AdminTokenRoutes.kt` を書く**

Create `server/src/main/kotlin/net/brightroom/garage/server/api/AdminTokenRoutes.kt`:

```kotlin
package net.brightroom.garage.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.createAdminToken
import net.brightroom.garage.server.garage.deleteAdminToken
import net.brightroom.garage.server.garage.getAdminToken
import net.brightroom.garage.server.garage.listAdminTokens
import net.brightroom.garage.server.garage.updateAdminToken
import net.brightroom.garage.shared.api.CreateAdminTokenRequest
import net.brightroom.garage.shared.api.UpdateAdminTokenRequest

/**
 * Admin token のルート。
 *
 * 一覧には設定ファイル由来のトークン（`id` が null）も含まれる。それらは
 * `UpdateAdminToken` / `DeleteAdminToken` の対象にできないが、隠すと「一覧に
 * 出ないトークンがある」という嘘になるため、そのまま返す（P3-6）。
 */
fun Route.adminTokenRoutes(client: GarageAdminClient) {
    route("/admin-tokens") {
        get {
            call.respond(client.listAdminTokens(call.adminToken()))
        }

        post {
            val token = call.adminToken()
            val request = call.receive<CreateAdminTokenRequest>()

            if (request.name.isBlank()) throw InvalidRequestException("トークン名が空です")
            if (request.scope.isEmpty()) throw InvalidRequestException("scope を 1 つ以上指定してください")

            call.respond(client.createAdminToken(token, request))
        }

        get("/{id}") {
            call.respond(client.getAdminToken(call.adminToken(), call.pathParam("id")))
        }

        patch("/{id}") {
            val token = call.adminToken()
            val request = call.receive<UpdateAdminTokenRequest>()

            call.respond(client.updateAdminToken(token, call.pathParam("id"), request))
        }

        delete("/{id}") {
            client.deleteAdminToken(call.adminToken(), call.pathParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

- [ ] **Step 5: ルートを配線してテストを通す**

`Routing.kt` と `TestApplication.kt` に `adminTokenRoutes(client)` と import を足す。

Run: `./gradlew :server:test`
Expected: PASS（既存を含む全テスト）

- [ ] **Step 6: `Authorization` がログに出ないことを確認する**

Run: `./gradlew :server:test --tests '*CallLoggingTest*'`
Expected: PASS。Phase 1 のスクラブ設定は Phase 3 のルートにもそのまま効く（プラグイン単位の設定であり、ルートごとの設定ではない）。

- [ ] **Step 7: コミットして PR を出す**

```bash
./gradlew spotlessApply
git add server/src/
git commit -m "feat(server): Admin token の API を追加"
git push -u origin phase3/3-server-ops
gh pr create --title "feat(server): ノード・ワーカー・ブロック・トークンの API を追加 (Task 8-11)" --body "$(cat <<'EOF'
## 概要

- `/api/nodes`（情報 / 統計 / スナップショット / 修復）。`repairType` の oneOf は `garage/` に閉じている
- `/api/workers`（一覧 / 詳細 / 変数の取得・設定）。Garage 側が POST の読み取り 2 つをコンソール側では GET にしている
- `/api/blocks`（エラー一覧 / 詳細 / 再同期 / purge）。全件再同期のため spec §7 の一覧に無い `POST /api/blocks/retry-resync` を 1 本足した
- `/api/admin-tokens`（一覧 / 作成 / 詳細 / 更新 / 削除）

これで Admin API v2 の 46 operation すべてに到達できるようになった。

## テスト

- ノード別に成否が割れる `MultiResponse` を潰さないことを検証している
- 副作用だけの operation は成功ノード一覧と失敗理由に落として返す
- `CreateAdminToken` の `secretToken` は応答としてのみ返り、ログには出ない

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01Ek5y1ML5RXQALQj5oq6XKt
EOF
)"
```

---
## ブランチ `phase3/4-web-cluster`（Task 12–15）

### Task 12: ポーリングを共通部品に抜き出す

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/Polling.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/overview/OverviewScreen.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `class PollingState` — `var autoRefresh: Boolean`、`val secondsSinceUpdate: Int`、`fun markUpdated()`
  - `@Composable fun rememberPolling(intervalMillis: Long, load: suspend () -> Unit): PollingState`
  - `@Composable fun PollingHeader(title: String, polling: PollingState, onRefresh: () -> Unit, trailing: @Composable RowScope.() -> Unit = {})`

**経過時間を進めるのはポーリング側、0 に戻すのは画面側。** 取得に失敗したときに「最終更新 0 秒前」と表示すると、古いデータを新しいと偽ることになる。`markUpdated()` を画面が成功時だけ呼ぶ形にして、この性質を保つ（Phase 1 の `OverviewScreen` が既にそうなっている）。

- [ ] **Step 1: ブランチを切る**

```bash
git switch main && git pull
git switch -c phase3/4-web-cluster
```

- [ ] **Step 2: `Polling.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/Polling.kt`:

```kotlin
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * タブが隠れているか。
 *
 * `document.hidden` は kotlinx-browser の wasmJs 向け Document に無く、
 * `visibilityState` も external な列挙型で扱いが不安定なため直接参照する。
 */
private fun isDocumentHidden(): Boolean = js("document.hidden")

/**
 * 定期取得の状態（spec §8.5）。
 *
 * 経過時間はこの型が進めるが、0 に戻すのは画面の役目である（[markUpdated]）。
 * 取得に失敗したときも「最終更新 0 秒前」と出してしまうと、古いデータを
 * 新しいと偽ることになるため。
 */
class PollingState internal constructor(internal val intervalMillis: Long) {

    var autoRefresh: Boolean by mutableStateOf(true)

    var secondsSinceUpdate: Int by mutableStateOf(0)
        internal set

    /** 取得に成功した画面が呼ぶ。 */
    fun markUpdated() {
        secondsSinceUpdate = 0
    }
}

/**
 * [intervalMillis] ごとに [load] を呼ぶ。最初の 1 回は即座に呼ぶ。
 *
 * 自動更新が切られている間と、タブが隠れている間は呼ばない。放置された
 * タブが Garage を叩き続けないようにするためである。
 */
@Composable
fun rememberPolling(intervalMillis: Long, load: suspend () -> Unit): PollingState {
    val polling = remember(intervalMillis) { PollingState(intervalMillis) }

    // load はコンポジションごとに新しいラムダになりうる。毎回 LaunchedEffect を
    // 作り直すとポーリングが再開されてしまうため、最新の参照だけを差し替える
    val currentLoad by rememberUpdatedState(load)

    LaunchedEffect(polling) {
        currentLoad()

        while (true) {
            delay(1_000)
            polling.secondsSinceUpdate++

            if (polling.autoRefresh &&
                polling.secondsSinceUpdate * 1_000L >= polling.intervalMillis &&
                !isDocumentHidden()
            ) {
                currentLoad()
            }
        }
    }

    return polling
}

/**
 * 画面の見出しと、自動更新のトグル・最終更新・手動更新（spec §8.5）。
 *
 * @param trailing 更新ボタンの右に足す操作。画面固有のボタンを置く。
 */
@Composable
fun PollingHeader(
    title: String,
    polling: PollingState,
    onRefresh: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Text(
            "最終更新 ${polling.secondsSinceUpdate} 秒前",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("自動更新", style = MaterialTheme.typography.bodySmall)
        Switch(checked = polling.autoRefresh, onCheckedChange = { polling.autoRefresh = it })
        TextButton(onClick = onRefresh) { Text("更新") }
        trailing()
    }
}
```

- [ ] **Step 3: `OverviewScreen` を差し替える**

`OverviewScreen.kt` から次を**削除**する:

- `private fun isDocumentHidden()` とその KDoc
- `var autoRefresh` / `var secondsSinceUpdate` の 2 つの `remember`
- 2 つの `LaunchedEffect`
- 見出しの `Row`（`Text("概況", …)` から `TextButton(onClick = …) { Text("更新") }` まで）
- `POLL_INTERVAL_MILLIS` は残す

`load()` の `is ApiResult.Success` 分岐で `secondsSinceUpdate = 0` を `polling.markUpdated()` に変え、次を足す:

```kotlin
    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }
```

見出しの `Row` の代わりに:

```kotlin
        PollingHeader("概況", polling, onRefresh = { scope.launch { load() } })
```

不要になった import（`Switch` / `LaunchedEffect` / `delay` / `Alignment` / `Arrangement` の一部）を消し、`PollingHeader` と `rememberPolling` を import する。

**`load` を `rememberPolling` に渡す前に宣言する必要がある。** `suspend fun load()` はローカル関数なので、`val polling = rememberPolling(...)` は `load` の定義より後に書くこと。

- [ ] **Step 4: ビルドと e2e で概況が壊れていないことを確認する**

```bash
CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build
mise run run   # 別ターミナルで起動しておく
mise run e2e -- tests/overview.spec.ts
```

Expected: `overview.spec.ts` の 5 件が通る。特に「自動更新のトグル」と「最終更新」の表示を見ているテストが通ること。

- [ ] **Step 5: コミットする**

```bash
./gradlew spotlessApply
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/Polling.kt \
        web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/overview/OverviewScreen.kt
git commit -m "refactor(web): ポーリングを共通部品に抜き出す"
```

---

### Task 13: `StatusChip` と `MultiResponseView`

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/StatusChip.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/MultiResponseView.kt`

**Interfaces:**
- Consumes: `MultiResponse`（Phase 1）
- Produces:
  - `enum class StatusTone { SUCCESS, WARNING, ERROR, NEUTRAL }`
  - `@Composable fun StatusChip(label: String, tone: StatusTone)`
  - `@Composable fun <T> MultiResponseView(response: MultiResponse<T>, emptyMessage: String, content: @Composable (nodeId: String, value: T) -> Unit)`
  - `@Composable fun NodeOutcomeNotice(outcome: NodeActionOutcome)`

- [ ] **Step 1: `StatusChip.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/StatusChip.kt`:

```kotlin
package net.brightroom.garage.web.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 状態の重さ。色はテーマの役割色に写す。
 *
 * 4 段にとどめるのは、ノード・ワーカー・レイアウト版・ブロックエラーで実際に
 * 必要になったのがこの 4 つだからである。
 */
enum class StatusTone { SUCCESS, WARNING, ERROR, NEUTRAL }

/**
 * 一目で状態が分かる小さなラベル（spec §8.7）。
 *
 * 文言は Garage が返す語をそのまま使う。運用者が CLI や API の出力と
 * 突き合わせられることを優先する。
 */
@Composable
fun StatusChip(label: String, tone: StatusTone) {
    val container = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }

    val content = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
```

- [ ] **Step 2: `MultiResponseView.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/MultiResponseView.kt`:

```kotlin
package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.model.garage.MultiResponse

/**
 * ノード別に成否が割れる応答を、潰さずに描く（spec §7.3）。
 *
 * 失敗したノードを先に出す。「node-c だけ失敗している」ことが見えるのが
 * この表示の目的であり、成功分に紛れさせては意味がない。
 */
@Composable
fun <T> MultiResponseView(
    response: MultiResponse<T>,
    emptyMessage: String,
    content: @Composable (nodeId: String, value: T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (response.error.isNotEmpty()) {
            FailedNodesCard(response.error)
        }

        if (response.success.isEmpty()) {
            if (response.error.isEmpty()) EmptyState(emptyMessage)
            return@Column
        }

        response.success.entries.sortedBy { it.key }.forEach { (nodeId, value) ->
            content(nodeId, value)
        }
    }
}

/** 副作用だけの操作の結果。成功したノード数と、失敗したノードの理由を出す。 */
@Composable
fun NodeOutcomeNotice(outcome: NodeActionOutcome) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (outcome.ok.isNotEmpty()) {
            Text(
                "${outcome.ok.size} 台のノードで実行しました",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (outcome.failed.isNotEmpty()) {
            FailedNodesCard(outcome.failed)
        }
    }
}

@Composable
private fun FailedNodesCard(failures: Map<String, String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("応答しなかったノード", style = MaterialTheme.typography.titleSmall)

            failures.entries.sortedBy { it.key }.forEach { (nodeId, message) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(nodeId.take(12), StatusTone.ERROR)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
```

- [ ] **Step 3: ビルドを通す**

Run: `CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build`
Expected: PASS

- [ ] **Step 4: コミットする**

```bash
./gradlew spotlessApply
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/
git commit -m "feat(web): 状態チップとノード別応答の共通部品を追加"
```

---

### Task 14: `/nodes` 画面

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/nodes/NodesScreen.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/nodes/NodeActions.kt`

**Interfaces:**
- Consumes: `PollingState` / `rememberPolling` / `PollingHeader`（Task 12）、`StatusChip` / `MultiResponseView` / `NodeOutcomeNotice`（Task 13）、`ClusterView` / `ClusterStatistics` / `NodeInfo` / `NodeStatistics` / `ConnectNodesRequest` / `ConnectNodeResult` / `RepairRequest` / `NodeActionOutcome`（Task 3）、`ApiClient` / `ApiResult` / `ProblemView` / `LoadingView` / `formatBytes`（Phase 1–2）
- Produces:
  - `@Composable fun NodesScreen(onNavigate: (Route) -> Unit)`
  - `@Composable fun ConnectNodeDialog(onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun RepairDialog(onConfirm: (RepairRequest) -> Unit, onDismiss: () -> Unit)`

**画面の構成（P3-12）**

1. **クラスタ全体** — `GET /api/cluster` の `health` と `GET /api/cluster/statistics`。健全性・quorum・バケット数・オブジェクト数・空き容量
2. **ノード一覧** — `GET /api/cluster` の `status.nodes` を軸に、`GET /api/nodes/info` と `GET /api/nodes/statistics` の内容を同じノードの行に束ねる
3. **ノードへの操作** — 「ノードを接続」「メタデータのスナップショット」「修復を開始」の 3 ボタン。いずれも確認ダイアログを挟む（spec §8.6）

ポーリング間隔は 15 秒（spec §8.5）。

- [ ] **Step 1: `NodeActions.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/nodes/NodeActions.kt`:

```kotlin
package net.brightroom.garage.web.screens.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.RepairRequest

/**
 * 接続先のノードを入力する。
 *
 * Garage は `<nodeId>@<host>:<port>` の形を要求する。1 行に 1 件入力させ、
 * 空行は無視する。形式の検証はしない。可否の実体は Garage 側にあり、
 * 結果は接続の応答で返る。
 */
@Composable
fun ConnectNodeDialog(onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val nodes = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ノードを接続") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "1 行に 1 件、<nodeId>@<host>:<port> の形で入力してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(nodes) }, enabled = nodes.isNotEmpty()) { Text("実行") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

/** Garage が受け付ける修復の種類。`scrub` だけは追加のコマンドを伴う。 */
private val repairTypes = listOf(
    "tables" to "テーブルの整合性を取り直す",
    "blocks" to "ブロックの再同期をやり直す",
    "versions" to "孤立したバージョンを掃除する",
    "multipartUploads" to "孤立したマルチパートアップロードを掃除する",
    "blockRefs" to "ブロック参照を掃除する",
    "blockRc" to "ブロックの参照カウントを数え直す",
    "rebalance" to "ブロックをドライブ間で再配置する",
    "aliases" to "バケットの別名を貼り直す",
    "clearResyncQueue" to "再同期キューを空にする",
    "scrub" to "保存されたデータを走査して破損を探す",
)

private val scrubCommands = listOf("start", "pause", "resume", "cancel")

/**
 * 修復の種類を選ぶ。
 *
 * 影響範囲の説明を必ず添える（spec §8.6）。修復はクラスタ全体に影響し、
 * 途中で止められないものもある。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepairDialog(onConfirm: (RepairRequest) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(repairTypes.first()) }
    var scrubCommand by remember { mutableStateOf(scrubCommands.first()) }
    var typeExpanded by remember { mutableStateOf(false) }
    var commandExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修復を開始") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "修復はクラスタの全ノードで実行され、完了までノードの負荷が上がります。" +
                        "進行状況はワーカー画面で確認できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selected.first,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("種類") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        repairTypes.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text("${entry.first} — ${entry.second}") },
                                onClick = {
                                    selected = entry
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                Text(selected.second, style = MaterialTheme.typography.bodyMedium)

                if (selected.first == "scrub") {
                    ExposedDropdownMenuBox(
                        expanded = commandExpanded,
                        onExpandedChange = { commandExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = scrubCommand,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("scrub の操作") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(commandExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = commandExpanded,
                            onDismissRequest = { commandExpanded = false },
                        ) {
                            scrubCommands.forEach { command ->
                                DropdownMenuItem(
                                    text = { Text(command) },
                                    onClick = {
                                        scrubCommand = command
                                        commandExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        RepairRequest(
                            repairType = selected.first,
                            scrubCommand = scrubCommand.takeIf { selected.first == "scrub" },
                        ),
                    )
                },
            ) {
                Text("実行", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
```

**`ExposedDropdownMenuBox` の API は Compose Multiplatform 1.11.1 で `ExperimentalMaterial3Api` である。** `menuAnchor()` の引数の要否はバージョンによって変わるので、コンパイルが通らない場合は IDE の補完に従って調整すること。**代替案を先に決めておく**: それでも通らない場合は `ExposedDropdownMenuBox` をやめ、種類ごとに `TextButton` を縦に並べる形にする（選択肢が 10 個なのでダイアログに収まる）。

- [ ] **Step 2: `NodesScreen.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/nodes/NodesScreen.kt`:

```kotlin
package net.brightroom.garage.web.screens.nodes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.shared.api.ClusterView
import net.brightroom.garage.shared.api.ConnectNodeResult
import net.brightroom.garage.shared.api.ConnectNodesRequest
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.RepairRequest
import net.brightroom.garage.shared.model.garage.ClusterHealthStatus
import net.brightroom.garage.shared.model.garage.ClusterStatistics
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.NodeInfo
import net.brightroom.garage.shared.model.garage.NodeResp
import net.brightroom.garage.shared.navigation.Route
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.MultiResponseView
import net.brightroom.garage.web.components.NodeOutcomeNotice
import net.brightroom.garage.web.components.PollingHeader
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.components.rememberPolling
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 15_000L

/**
 * クラスタ状態とノード（spec §8.1 で旧 Cluster 画面と旧 Nodes 画面を統合した先）。
 *
 * 同じノードの情報が `GetClusterStatus` / `GetNodeInfo` / `GetNodeStatistics` に
 * 散っているため、ノード ID で束ねて 1 行にする（P3-12）。
 */
@Composable
fun NodesScreen(onNavigate: (Route) -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var cluster by remember { mutableStateOf<ClusterView?>(null) }
    var statistics by remember { mutableStateOf<ClusterStatistics?>(null) }
    var nodeInfo by remember { mutableStateOf<MultiResponse<NodeInfo>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<NodeActionOutcome?>(null) }
    var connectResults by remember { mutableStateOf<List<ConnectNodeResult>>(emptyList()) }
    var showConnect by remember { mutableStateOf(false) }
    var showSnapshot by remember { mutableStateOf(false) }
    var showRepair by remember { mutableStateOf(false) }

    // 取得に成功した回数。polling は load より後に作られるため、load の中から
    // markUpdated() を直接は呼べない。成功を状態に立てて LaunchedEffect で伝える
    var updatedAt by remember { mutableStateOf(0) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/cluster", ClusterView.serializer())) {
            is ApiResult.Success -> {
                cluster = result.value
                failure = null
                updatedAt++
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> {
                session.invalidate()
                return
            }
        }

        // 統計とノード情報は取れなくても画面の主要部は成立する。
        // 失敗しても画面全体は落とさない
        (session.api.getJson("/api/cluster/statistics", ClusterStatistics.serializer()) as? ApiResult.Success)
            ?.let { statistics = it.value }

        (
            session.api.getJson(
                "/api/nodes/info",
                MultiResponse.serializer(NodeInfo.serializer()),
            ) as? ApiResult.Success
            )?.let { nodeInfo = it.value }
    }

    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }

    LaunchedEffect(updatedAt) {
        if (updatedAt > 0) polling.markUpdated()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PollingHeader("クラスタ", polling, onRefresh = { scope.launch { load() } }) {
            TextButton(onClick = { showConnect = true }) { Text("ノードを接続") }
            TextButton(onClick = { showSnapshot = true }) { Text("スナップショット") }
            TextButton(onClick = { showRepair = true }) { Text("修復を開始") }
        }

        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        outcome?.let { NodeOutcomeNotice(it) }
        connectResults.takeIf { it.isNotEmpty() }?.let { ConnectResults(it) }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = cluster) {
            null -> if (failure == null) LoadingView()

            else -> {
                ClusterSummary(current, statistics)
                NodeTable(current.status.nodes, nodeInfo)
                TextButton(onClick = { onNavigate(Route.Layout) }) { Text("レイアウトを見る") }
            }
        }
    }

    if (showConnect) {
        ConnectNodeDialog(
            onConfirm = { nodes ->
                showConnect = false
                scope.launch {
                    val result = session.api.sendJson(
                        HttpMethod.Post,
                        "/api/cluster/connect",
                        AppJson.encodeToString(
                            ConnectNodesRequest.serializer(),
                            ConnectNodesRequest(nodes),
                        ),
                        ListSerializer(ConnectNodeResult.serializer()),
                    )

                    when (result) {
                        is ApiResult.Success -> {
                            connectResults = result.value
                            load()
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { showConnect = false },
        )
    }

    if (showSnapshot) {
        ConfirmDialog(
            title = "メタデータのスナップショットを作成",
            message = "全ノードでメタデータのスナップショットを作成します。ディスクの空き容量を消費します。",
            onConfirm = {
                showSnapshot = false
                scope.launch {
                    notice = null
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Post,
                            "/api/nodes/snapshot",
                            null,
                            NodeActionOutcome.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            outcome = result.value
                            notice = "スナップショットを開始しました"
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { showSnapshot = false },
        )
    }

    if (showRepair) {
        RepairDialog(
            onConfirm = { request ->
                showRepair = false
                scope.launch {
                    notice = null
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Post,
                            "/api/nodes/repair",
                            AppJson.encodeToString(RepairRequest.serializer(), request),
                            NodeActionOutcome.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            outcome = result.value
                            notice = "${request.repairType} の修復を開始しました"
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { showRepair = false },
        )
    }
}

@Composable
private fun ClusterSummary(cluster: ClusterView, statistics: ClusterStatistics?) {
    val health = cluster.health
    val tone = when (health.status) {
        ClusterHealthStatus.HEALTHY -> StatusTone.SUCCESS
        ClusterHealthStatus.DEGRADED -> StatusTone.WARNING
        ClusterHealthStatus.UNAVAILABLE -> StatusTone.ERROR
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("クラスタ全体", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusChip(
                    when (health.status) {
                        ClusterHealthStatus.HEALTHY -> "healthy"
                        ClusterHealthStatus.DEGRADED -> "degraded"
                        ClusterHealthStatus.UNAVAILABLE -> "unavailable"
                    },
                    tone,
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Figure("ノード", "${health.storageNodesUp} / ${health.storageNodes}")
                Figure("接続済み", "${health.connectedNodes} / ${health.knownNodes}")
                Figure("quorum", "${health.partitionsQuorum} / ${health.partitions}")
                Figure("全複製 OK", "${health.partitionsAllOk} / ${health.partitions}")
                Figure("レイアウト", "v${cluster.status.layoutVersion}")

                statistics?.let {
                    Figure("バケット", "${it.bucketCount}")
                    Figure("オブジェクト", "${it.totalObjectCount}")
                    Figure("使用量", formatBytes(it.totalObjectBytes))
                    it.dataAvail?.let { avail -> Figure("データ空き", formatBytes(avail)) }
                }
            }

            if (statistics?.incompleteAvailInfo == true) {
                Text(
                    "一部のノードから空き容量を取得できていません。数値は実際より小さい可能性があります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * ノードごとに、状態・役割・容量・バージョンを 1 行に束ねる。
 *
 * `GetClusterStatus` が軸で、`GetNodeInfo` はノード ID で引き当てる。
 * 情報を取れなかったノードは状態だけを出す。
 */
@Composable
private fun NodeTable(nodes: List<NodeResp>, info: MultiResponse<NodeInfo>?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ノード", style = MaterialTheme.typography.titleSmall)

            info?.error?.takeIf { it.isNotEmpty() }?.let { failures ->
                Text(
                    "${failures.size} 台のノードから詳細を取得できませんでした",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            nodes.sortedBy { it.hostname ?: it.id }.forEach { node ->
                NodeRow(node, info?.success?.get(node.id))
            }
        }
    }
}

@Composable
private fun NodeRow(node: NodeResp, info: NodeInfo?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(if (node.isUp) "稼働" else "停止", if (node.isUp) StatusTone.SUCCESS else StatusTone.ERROR)

            Text(
                node.hostname ?: node.id.take(12),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(160.dp),
            )

            Text(
                node.role?.zone ?: "役割なし",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp),
            )

            val total = node.dataPartition?.total
            val available = node.dataPartition?.available

            if (total != null && available != null && total > 0) {
                val used = total - available
                LinearProgressIndicator(
                    progress = { used.toFloat() / total.toFloat() },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${formatBytes(used)} / ${formatBytes(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "容量情報なし",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            if (node.draining) StatusChip("退避中", StatusTone.WARNING)
        }

        Text(
            listOfNotNull(
                info?.garageVersion,
                info?.dbEngine,
                node.addr,
                node.lastSeenSecsAgo?.let { "最終応答 $it 秒前" },
                node.role?.tags?.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString(" · ").ifEmpty { node.id },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectResults(results: List<ConnectNodeResult>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("接続の結果", style = MaterialTheme.typography.titleSmall)

            results.forEach { result ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        if (result.success) "成功" else "失敗",
                        if (result.success) StatusTone.SUCCESS else StatusTone.ERROR,
                    )
                    Text(result.node, style = MaterialTheme.typography.bodySmall)
                    result.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
```

**`updatedAt` を経由するのはなぜか。** `load` は `polling` より先に宣言されるため、`load` の中から `polling.markUpdated()` を直接は呼べない（前方参照になる）。成功回数を状態に立て、`LaunchedEffect(updatedAt)` で伝える。**Task 15 以降の画面も同じ形にそろえる。** `OverviewScreen`（Task 12）だけは `load` の直後に `val polling = …` を置けるため、この回避は要らない。

- [ ] **Step 3: ビルドを通す**

Run: `CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build`
Expected: PASS

- [ ] **Step 4: 実機で確認する**

```bash
mise run run
```

`/nodes` を開き、クラスタ全体の数値・ノード行・3 つの操作ボタンが出ること、「修復を開始」で種類が選べることを見る。**単一ノードの dev では接続は必ず失敗する。失敗の表示が出ることを確認する**（P3-9）。

- [ ] **Step 5: コミットする**

```bash
./gradlew spotlessApply
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/nodes/
git commit -m "feat(web): クラスタ状態とノードの画面を追加"
```

---

### Task 15: `/layout` 画面

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/layout/LayoutScreen.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/layout/LayoutStageForm.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/layout/LayoutPreviewDialog.kt`

**Interfaces:**
- Consumes: Task 12–13 の部品、`ClusterLayout` / `LayoutNodeRole` / `LayoutParameters` / `ZoneRedundancy` / `NodeRoleChange` / `LayoutHistory` / `LayoutPreview`（Task 2）、`StageRolesRequest` / `ApplyLayoutRequest` / `SkipDeadNodesRequest`（Task 3）
- Produces:
  - `@Composable fun LayoutScreen()`
  - `@Composable fun LayoutStageForm(nodeIds: List<String>, current: ClusterLayout, onStage: (StageRolesRequest) -> Unit)`
  - `@Composable fun LayoutPreviewDialog(preview: LayoutPreview, onApply: () -> Unit, onDismiss: () -> Unit)`

**apply の前に preview を必ず挟む（spec §8.6）。** 「適用」を押すと、まず `POST /api/layout/preview` を呼び、その結果を確認ダイアログに出す。ダイアログの「適用する」を押して初めて `POST /api/layout/apply` を呼ぶ。preview が `LayoutPreview.Failed` を返した場合は理由を出し、適用ボタンを無効にする。

**適用後の版番号は preview の `newLayout.version` を使う。** `ApplyClusterLayout` は安全策として版番号を要求する。現在の版に 1 を足すのではなく、Garage 自身が計算した値を使うほうが取り違えが起きない。

- [ ] **Step 1: `LayoutPreviewDialog.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/layout/LayoutPreviewDialog.kt`:

```kotlin
package net.brightroom.garage.web.screens.layout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.model.garage.LayoutPreview
import net.brightroom.garage.web.components.formatBytes

/**
 * 適用前の確認（spec §8.6）。
 *
 * Garage が計算した結果をそのまま見せる。`message` は「パースするな」と
 * 仕様に明記されているため、整形せず行のまま出す。
 */
@Composable
fun LayoutPreviewDialog(
    preview: LayoutPreview,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val computed = preview as? LayoutPreview.Computed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("レイアウトの変更を適用") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (preview) {
                    is LayoutPreview.Failed -> Text(
                        "このままでは適用できません: ${preview.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is LayoutPreview.Computed -> {
                        Text(
                            "適用すると v${preview.newLayout.version} になります。" +
                                "パーティションの移動が始まり、完了するまでノードの負荷が上がります。",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        preview.statistics?.let { stat ->
                            Text(
                                listOfNotNull(
                                    "複製数 ${stat.replicationFactor}",
                                    "実効ゾーン冗長度 ${stat.effectiveZoneRedundancy}",
                                    "パーティションサイズ ${formatBytes(stat.partitionSize)}",
                                    "実効容量 ${formatBytes(stat.effectiveCapacity)}",
                                    stat.totalMovedPartitions?.let { "移動するパーティション $it" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (stat.lowPartitionSize) {
                                Text(
                                    "パーティションが小さすぎます。容量の割り当てを見直してください。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            if (stat.lowUsableCapacity) {
                                Text(
                                    "割り当てた容量を活かしきれていません。ゾーンの偏りを見直してください。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Text(
                            preview.message.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = computed != null) {
                Text("適用する", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
```

- [ ] **Step 2: `LayoutStageForm.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/layout/LayoutStageForm.kt`:

```kotlin
package net.brightroom.garage.web.screens.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.StageRolesRequest
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.LayoutParameters
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import net.brightroom.garage.shared.model.garage.ZoneRedundancy

/**
 * ロールの割り当てを stage する（P3-5）。
 *
 * 容量は GiB で入力させる。バイトで入力させると桁を間違えやすく、Garage の
 * CLI も人が読める単位を受け付ける。gateway にすると容量を送らない。
 */
@Composable
fun LayoutStageForm(nodeIds: List<String>, current: ClusterLayout, onStage: (StageRolesRequest) -> Unit) {
    var nodeId by remember { mutableStateOf(nodeIds.firstOrNull().orEmpty()) }
    var zone by remember { mutableStateOf("") }
    var capacityGib by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf(false) }
    var zoneRedundancy by remember { mutableStateOf("") }

    val capacity = capacityGib.trim().toLongOrNull()
    val canAssign = nodeId.isNotBlank() && zone.isNotBlank() && (gateway || capacity != null)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ロールを stage", style = MaterialTheme.typography.titleSmall)
            Text(
                "stage しただけでは反映されません。下の「適用」で確認してから反映します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = nodeId,
                onValueChange = { nodeId = it },
                label = { Text("ノード ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = zone,
                onValueChange = { zone = it },
                label = { Text("ゾーン") },
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = gateway, onCheckedChange = { gateway = it })
                Text("gateway として割り当てる（容量を持たない）", style = MaterialTheme.typography.bodySmall)
            }

            if (!gateway) {
                OutlinedTextField(
                    value = capacityGib,
                    onValueChange = { capacityGib = it },
                    label = { Text("容量（GiB）") },
                    singleLine = true,
                )
            }

            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("タグ（カンマ区切り）") },
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    enabled = canAssign,
                    onClick = {
                        onStage(
                            StageRolesRequest(
                                roles = listOf(
                                    NodeRoleChange.Assign(
                                        id = nodeId.trim(),
                                        zone = zone.trim(),
                                        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                        capacity = if (gateway) null else capacity?.let { it * GIB },
                                    ),
                                ),
                            ),
                        )
                    },
                ) {
                    Text("割り当てを stage")
                }

                TextButton(
                    enabled = nodeId.isNotBlank(),
                    onClick = { onStage(StageRolesRequest(roles = listOf(NodeRoleChange.Remove(nodeId.trim())))) },
                ) {
                    Text("このノードを外す")
                }
            }

            Text("ゾーン冗長度", style = MaterialTheme.typography.titleSmall)
            Text(
                "現在: ${current.parameters?.zoneRedundancy.describe()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = zoneRedundancy,
                    onValueChange = { zoneRedundancy = it },
                    label = { Text("最小ゾーン数（空なら maximum）") },
                    singleLine = true,
                )

                TextButton(
                    onClick = {
                        val zones = zoneRedundancy.trim().toIntOrNull()

                        onStage(
                            StageRolesRequest(
                                parameters = LayoutParameters(
                                    if (zones == null) ZoneRedundancy.Maximum else ZoneRedundancy.AtLeast(zones),
                                ),
                            ),
                        )
                    },
                ) {
                    Text("冗長度を stage")
                }
            }
        }
    }
}

private const val GIB = 1024L * 1024L * 1024L

private fun ZoneRedundancy?.describe(): String = when (this) {
    null -> "未設定"
    ZoneRedundancy.Maximum -> "maximum（可能な限り多くのゾーン）"
    is ZoneRedundancy.AtLeast -> "atLeast $zones"
}
```

- [ ] **Step 3: `LayoutScreen.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/layout/LayoutScreen.kt`:

```kotlin
package net.brightroom.garage.web.screens.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.api.ApplyLayoutRequest
import net.brightroom.garage.shared.api.StageRolesRequest
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.LayoutHistory
import net.brightroom.garage.shared.model.garage.LayoutNodeRole
import net.brightroom.garage.shared.model.garage.LayoutPreview
import net.brightroom.garage.shared.model.garage.LayoutVersionStatus
import net.brightroom.garage.shared.model.garage.NodeRoleChange
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.PollingHeader
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.components.rememberPolling
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 15_000L

/**
 * クラスタレイアウト。
 *
 * 適用の前には必ず preview を挟む（spec §8.6）。「適用」を押すと
 * `POST /api/layout/preview` を呼び、その結果を確認ダイアログに出す。
 */
@Composable
fun LayoutScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var layout by remember { mutableStateOf<ClusterLayout?>(null) }
    var history by remember { mutableStateOf<LayoutHistory?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LayoutPreview?>(null) }
    var confirmRevert by remember { mutableStateOf(false) }
    var updatedAt by remember { mutableStateOf(0) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/layout", ClusterLayout.serializer())) {
            is ApiResult.Success -> {
                layout = result.value
                failure = null
                updatedAt++
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> {
                session.invalidate()
                return
            }
        }

        (session.api.getJson("/api/layout/history", LayoutHistory.serializer()) as? ApiResult.Success)
            ?.let { history = it.value }
    }

    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }

    LaunchedEffect(updatedAt) {
        if (updatedAt > 0) polling.markUpdated()
    }

    /** 応答を受け取って画面を更新する共通処理。 */
    suspend fun <T> submit(result: ApiResult<T>, message: String, onSuccess: (T) -> Unit = {}) {
        when (result) {
            is ApiResult.Success -> {
                onSuccess(result.value)
                notice = message
                load()
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PollingHeader("レイアウト", polling, onRefresh = { scope.launch { load() } })

        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = layout) {
            null -> if (failure == null) LoadingView()

            else -> {
                CurrentLayout(current)

                StagedChanges(
                    layout = current,
                    onPreview = {
                        scope.launch {
                            notice = null
                            when (
                                val result = session.api.sendJson(
                                    HttpMethod.Post,
                                    "/api/layout/preview",
                                    null,
                                    LayoutPreview.serializer(),
                                )
                            ) {
                                is ApiResult.Success -> preview = result.value

                                is ApiResult.Failure -> failure = result

                                ApiResult.Unauthorized -> session.invalidate()
                            }
                        }
                    },
                    onRevert = { confirmRevert = true },
                )

                LayoutStageForm(
                    nodeIds = current.roles.map { it.id },
                    current = current,
                    onStage = { request ->
                        scope.launch {
                            notice = null
                            submit(
                                session.api.sendJson(
                                    HttpMethod.Post,
                                    "/api/layout/roles",
                                    AppJson.encodeToString(StageRolesRequest.serializer(), request),
                                    ClusterLayout.serializer(),
                                ),
                                "stage しました。適用するには「適用」を押してください",
                            )
                        }
                    },
                )

                history?.let { History(it) }
            }
        }
    }

    preview?.let { current ->
        LayoutPreviewDialog(
            preview = current,
            onApply = {
                val version = (current as? LayoutPreview.Computed)?.newLayout?.version
                preview = null

                if (version != null) {
                    scope.launch {
                        submit(
                            session.api.sendJson(
                                HttpMethod.Post,
                                "/api/layout/apply",
                                AppJson.encodeToString(ApplyLayoutRequest.serializer(), ApplyLayoutRequest(version)),
                                ClusterLayout.serializer(),
                            ),
                            "レイアウト v$version を適用しました",
                        )
                    }
                }
            },
            onDismiss = { preview = null },
        )
    }

    if (confirmRevert) {
        ConfirmDialog(
            title = "stage した変更を破棄",
            message = "適用していない変更をすべて破棄します。この操作は取り消せません。",
            onConfirm = {
                confirmRevert = false
                scope.launch {
                    submit(
                        session.api.sendJson(
                            HttpMethod.Post,
                            "/api/layout/revert",
                            null,
                            ClusterLayout.serializer(),
                        ),
                        "stage した変更を破棄しました",
                    )
                }
            },
            onDismiss = { confirmRevert = false },
        )
    }
}

@Composable
private fun CurrentLayout(layout: ClusterLayout) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "現在のレイアウト v${layout.version}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "パーティションサイズ ${formatBytes(layout.partitionSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (layout.roles.isEmpty()) {
                Text("ロールが割り当てられたノードがありません", style = MaterialTheme.typography.bodySmall)
            } else {
                layout.roles.sortedBy { it.zone }.forEach { RoleRow(it) }
            }
        }
    }
}

@Composable
private fun RoleRow(role: LayoutNodeRole) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusChip(if (role.isGateway) "gateway" else "storage", StatusTone.NEUTRAL)
        Text(role.id.take(16), style = MaterialTheme.typography.bodyMedium)
        Text(role.zone, style = MaterialTheme.typography.bodySmall)
        Text(
            listOfNotNull(
                role.capacity?.let { formatBytes(it) },
                role.storedPartitions?.let { "$it パーティション" },
                role.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StagedChanges(layout: ClusterLayout, onPreview: () -> Unit, onRevert: () -> Unit) {
    val hasChanges = layout.stagedRoleChanges.isNotEmpty() || layout.stagedParameters != null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("未適用の変更", style = MaterialTheme.typography.titleSmall)

            if (!hasChanges) {
                Text(
                    "未適用の変更はありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                layout.stagedRoleChanges.forEach { change ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (change) {
                            is NodeRoleChange.Remove -> {
                                StatusChip("外す", StatusTone.ERROR)
                                Text(change.id.take(16), style = MaterialTheme.typography.bodySmall)
                            }

                            is NodeRoleChange.Assign -> {
                                StatusChip("割り当て", StatusTone.WARNING)
                                Text(
                                    listOfNotNull(
                                        change.id.take(16),
                                        change.zone,
                                        change.capacity?.let { formatBytes(it) } ?: "gateway",
                                        change.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                layout.stagedParameters?.let {
                    Text("ゾーン冗長度の変更が stage されています", style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 適用の前に必ず preview を通す（spec §8.6）
                TextButton(onClick = onPreview, enabled = hasChanges) { Text("適用") }
                TextButton(onClick = onRevert, enabled = hasChanges) { Text("破棄") }
            }
        }
    }
}

@Composable
private fun History(history: LayoutHistory) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("履歴", style = MaterialTheme.typography.titleSmall)
            Text(
                "全ノードが v${history.minAck} までを認識しています",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            history.versions.sortedByDescending { it.version }.forEach { version ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        version.status.name.lowercase(),
                        when (version.status) {
                            LayoutVersionStatus.CURRENT -> StatusTone.SUCCESS
                            LayoutVersionStatus.DRAINING -> StatusTone.WARNING
                            LayoutVersionStatus.HISTORICAL -> StatusTone.NEUTRAL
                        },
                    )
                    Text("v${version.version}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "storage ${version.storageNodes} · gateway ${version.gatewayNodes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
```

**`ClusterLayoutSkipDeadNodes` の UI はここには置かない。** 死んだノードが 1 台も無い状態で押せるボタンを常設すると、意味の分からない操作が増える。**Task 19 で `/nodes` 画面に「停止しているノードがある場合のみ表示される」ボタンとして置く**（下の Task 19 Step 3）。

- [ ] **Step 4: ビルドを通す**

Run: `CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build`
Expected: PASS

- [ ] **Step 5: 実機で stage → preview → revert を通す**

```bash
mise run run
```

`/layout` を開き、次を手で確認する。**dev の Garage はワークツリー間で共有されうるため、apply は押さないこと**（版が進むと元に戻せない）。

1. 現在のレイアウトが v1 で表示される
2. 「割り当てを stage」で既存ノードの容量を変えて stage すると「未適用の変更」に出る
3. 「適用」を押すと preview の内容（`message` の行と統計）がダイアログに出る
4. ダイアログを**キャンセル**する
5. 「破棄」で staged 変更が消える

- [ ] **Step 6: コミットして PR を出す**

```bash
./gradlew spotlessApply
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/
git commit -m "feat(web): レイアウトの参照・stage・適用の画面を追加"
git push -u origin phase3/4-web-cluster
gh pr create --title "feat(web): クラスタとレイアウトの画面を追加 (Task 12-15)" --body "$(cat <<'EOF'
## 概要

- ポーリング（トグル・最終更新・タブが隠れている間の停止）を `components/Polling.kt` に抜き出し、概況を差し替えた
- `StatusChip` と `MultiResponseView` を足した。ノード別の失敗を潰さず出す（spec §7.3）
- `/nodes` はクラスタ全体・ノード一覧・ノードへの操作の 3 段。旧 Cluster 画面と旧 Nodes 画面の統合先
- `/layout` は現在のレイアウト・未適用の変更・stage フォーム・履歴。**適用の前に必ず preview を挟む**（spec §8.6）

## 確認

- 概況の e2e が引き続き通る（ポーリングの抜き出しで壊れていない）
- `/layout` の stage → preview → 破棄をローカルの Garage で手動確認した。apply は共有 dev 環境の版を進めるため踏んでいない

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01Ek5y1ML5RXQALQj5oq6XKt
EOF
)"
```

---
## ブランチ `phase3/5-web-maintenance`（Task 16–19）

### Task 16: `/workers` 画面

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/workers/WorkersScreen.kt`

**Interfaces:**
- Consumes: `PollingHeader` / `rememberPolling`（Task 12）、`StatusChip` / `MultiResponseView` / `NodeOutcomeNotice`（Task 13）、`WorkerInfo` / `WorkerState`（Task 1）、`SetWorkerVariableRequest` / `NodeActionOutcome`（Task 3）
- Produces: `@Composable fun WorkersScreen()`

**変数の編集は返ってきたキーだけを対象にする（P3-8）。** Garage の API は読み取り専用の変数（`scrub-last-completed` など）と設定できる変数を区別しない。自由入力にすると存在しない変数名を送れてしまうため、`GET /api/workers/variables` が返したキーを行に並べ、値だけを編集させる。書き込めない変数に対しては Garage が失敗を返し、それを `NodeOutcomeNotice` が表示する。

- [ ] **Step 1: ブランチを切る**

```bash
git switch main && git pull
git switch -c phase3/5-web-maintenance
```

- [ ] **Step 2: `WorkersScreen.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/workers/WorkersScreen.kt`:

```kotlin
package net.brightroom.garage.web.screens.workers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.SetWorkerVariableRequest
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.shared.model.garage.WorkerInfo
import net.brightroom.garage.shared.model.garage.WorkerState
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.MultiResponseView
import net.brightroom.garage.web.components.NodeOutcomeNotice
import net.brightroom.garage.web.components.PollingHeader
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.rememberPolling
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 15_000L

/** 状態の重さ。エラーを抱えたワーカーは状態にかかわらず目立たせる。 */
private fun WorkerInfo.tone(): StatusTone = when {
    consecutiveErrors > 0 -> StatusTone.ERROR
    errors > 0 -> StatusTone.WARNING
    state is WorkerState.Throttled -> StatusTone.WARNING
    state == WorkerState.Busy -> StatusTone.SUCCESS
    else -> StatusTone.NEUTRAL
}

@Composable
fun WorkersScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var workers by remember { mutableStateOf<MultiResponse<List<WorkerInfo>>?>(null) }
    var variables by remember { mutableStateOf<MultiResponse<Map<String, String>>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var outcome by remember { mutableStateOf<NodeActionOutcome?>(null) }
    var pending by remember { mutableStateOf<SetWorkerVariableRequest?>(null) }
    var updatedAt by remember { mutableStateOf(0) }

    // 編集中の値。サーバーの値で上書きしないよう、変数名ごとに保持する
    val drafts = remember { mutableStateMapOf<String, String>() }

    suspend fun load() {
        when (
            val result = session.api.getJson(
                "/api/workers",
                MultiResponse.serializer(ListSerializer(WorkerInfo.serializer())),
            )
        ) {
            is ApiResult.Success -> {
                workers = result.value
                failure = null
                updatedAt++
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> {
                session.invalidate()
                return
            }
        }

        (
            session.api.getJson(
                "/api/workers/variables",
                MultiResponse.serializer(MapSerializer(String.serializer(), String.serializer())),
            ) as? ApiResult.Success
            )?.let { variables = it.value }
    }

    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }

    LaunchedEffect(updatedAt) {
        if (updatedAt > 0) polling.markUpdated()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PollingHeader("ワーカー", polling, onRefresh = { scope.launch { load() } })

        outcome?.let { NodeOutcomeNotice(it) }
        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        variables?.let { current ->
            VariablesCard(
                variables = current,
                drafts = drafts,
                onSubmit = { pending = it },
            )
        }

        when (val current = workers) {
            null -> if (failure == null) LoadingView()

            else -> MultiResponseView(current, emptyMessage = "ワーカーがありません") { nodeId, list ->
                WorkerCard(nodeId, list)
            }
        }
    }

    pending?.let { request ->
        ConfirmDialog(
            title = "ワーカーの設定を変更",
            message = "${request.variable} を ${request.value} に変更します。" +
                "全ノードに適用され、ワーカーの動作がすぐに変わります。",
            onConfirm = {
                pending = null
                scope.launch {
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Put,
                            "/api/workers/variables",
                            AppJson.encodeToString(SetWorkerVariableRequest.serializer(), request),
                            NodeActionOutcome.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            outcome = result.value
                            drafts.remove(request.variable)
                            load()
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { pending = null },
        )
    }
}

/**
 * ワーカーの設定変数。
 *
 * Garage が返したキーだけを並べる（P3-8）。読み取り専用の変数も混ざるが、
 * 書き込めないものは Garage が失敗を返し、それが結果として表示される。
 */
@Composable
private fun VariablesCard(
    variables: MultiResponse<Map<String, String>>,
    drafts: MutableMap<String, String>,
    onSubmit: (SetWorkerVariableRequest) -> Unit,
) {
    // ノードごとに同じ変数を持つ。名前で束ねて 1 行にする
    val names = variables.success.values.flatMap { it.keys }.distinct().sorted()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("設定変数", style = MaterialTheme.typography.titleSmall)
            Text(
                "変更は全ノードに適用されます。読み取り専用の変数もここに並びます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            names.forEach { name ->
                val values = variables.success.values.mapNotNull { it[name] }.distinct()
                val shown = values.singleOrNull() ?: values.joinToString(" / ")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(220.dp))

                    OutlinedTextField(
                        value = drafts[name] ?: shown,
                        onValueChange = { drafts[name] = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )

                    TextButton(
                        enabled = drafts[name]?.takeIf { it != shown } != null,
                        onClick = { onSubmit(SetWorkerVariableRequest(name, drafts.getValue(name))) },
                    ) {
                        Text("設定")
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkerCard(nodeId: String, workers: List<WorkerInfo>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(nodeId.take(16), style = MaterialTheme.typography.titleSmall)

            // エラーを抱えたものと動いているものを上に出す
            workers.sortedWith(
                compareByDescending<WorkerInfo> { it.consecutiveErrors }
                    .thenByDescending { it.errors }
                    .thenBy { it.name },
            ).forEach { WorkerRow(it) }
        }
    }
}

@Composable
private fun WorkerRow(worker: WorkerInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(worker.state.label, worker.tone())
            Text(worker.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))

            worker.progress?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            worker.queueLength?.let {
                Text("待ち $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val details = listOfNotNull(
            "ID ${worker.id}",
            worker.tranquility?.let { "tranquility $it" },
            worker.errors.takeIf { it > 0 }?.let { "エラー $it 件" },
            worker.consecutiveErrors.takeIf { it > 0 }?.let { "連続エラー $it 件" },
            worker.persistentErrors?.takeIf { it > 0 }?.let { "恒久エラー $it 件" },
            worker.lastError?.let { "直近のエラー ${it.secsAgo} 秒前: ${it.message}" },
        ) + worker.freeform

        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 3: ビルドを通して実機で見る**

```bash
CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build
mise run run
```

`/workers` を開き、ワーカーが一覧され、設定変数の行が出ること。**`scrub-tranquility` を変更して確認ダイアログが出ること**を見る（実際に変更してもよい。ワーカーの控えめさが変わるだけで、元に戻せる）。

- [ ] **Step 4: コミットする**

```bash
./gradlew spotlessApply
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/workers/
git commit -m "feat(web): ワーカーの一覧と設定変数の画面を追加"
```

---

### Task 17: `/blocks` 画面

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/blocks/BlocksScreen.kt`

**Interfaces:**
- Consumes: Task 12–13 の部品、`BlockError`（Phase 1）、`BlockInfo` / `RetryResyncRequest` / `PurgeBlocksRequest` / `NodeActionOutcome`（Task 3）
- Produces: `@Composable fun BlocksScreen()`

**`PurgeBlocks` は確認ダイアログでハッシュの入力を求める（spec §8.6）。** ブロックへの参照を消す操作であり、取り消せない。`ConfirmDialog` の `requiredInput` に対象のハッシュを渡す。

- [ ] **Step 1: `BlocksScreen.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/blocks/BlocksScreen.kt`:

```kotlin
package net.brightroom.garage.web.screens.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.shared.api.NodeActionOutcome
import net.brightroom.garage.shared.api.PurgeBlocksRequest
import net.brightroom.garage.shared.api.RetryResyncRequest
import net.brightroom.garage.shared.model.garage.BlockError
import net.brightroom.garage.shared.model.garage.BlockInfo
import net.brightroom.garage.shared.model.garage.MultiResponse
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.EmptyState
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.MultiResponseView
import net.brightroom.garage.web.components.NodeOutcomeNotice
import net.brightroom.garage.web.components.PollingHeader
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.rememberPolling
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 15_000L

@Composable
fun BlocksScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var errors by remember { mutableStateOf<MultiResponse<List<BlockError>>?>(null) }
    var detail by remember { mutableStateOf<MultiResponse<BlockInfo>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var outcome by remember { mutableStateOf<NodeActionOutcome?>(null) }
    var confirmRetryAll by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf(0) }

    suspend fun load() {
        when (
            val result = session.api.getJson(
                "/api/blocks/errors",
                MultiResponse.serializer(ListSerializer(BlockError.serializer())),
            )
        ) {
            is ApiResult.Success -> {
                errors = result.value
                failure = null
                updatedAt++
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    suspend fun openDetail(hash: String) {
        selected = hash
        when (
            val result = session.api.getJson(
                "/api/blocks/$hash",
                MultiResponse.serializer(BlockInfo.serializer()),
            )
        ) {
            is ApiResult.Success -> detail = result.value

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    /** 副作用だけの操作を投げ、結果をノード別に表示して一覧を取り直す。 */
    suspend fun post(path: String, body: String?) {
        when (
            val result = session.api.sendJson(HttpMethod.Post, path, body, NodeActionOutcome.serializer())
        ) {
            is ApiResult.Success -> {
                outcome = result.value
                load()
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    val polling = rememberPolling(POLL_INTERVAL_MILLIS) { load() }

    LaunchedEffect(updatedAt) {
        if (updatedAt > 0) polling.markUpdated()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PollingHeader("ブロックエラー", polling, onRefresh = { scope.launch { load() } }) {
            TextButton(onClick = { confirmRetryAll = true }) { Text("全件を再同期") }
        }

        outcome?.let { NodeOutcomeNotice(it) }
        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = errors) {
            null -> if (failure == null) LoadingView()

            else -> {
                val total = current.success.values.sumOf { it.size }

                if (total == 0 && current.error.isEmpty()) {
                    EmptyState("再同期に失敗しているブロックはありません")
                } else {
                    MultiResponseView(current, emptyMessage = "再同期に失敗しているブロックはありません") { nodeId, list ->
                        BlockErrorCard(
                            nodeId = nodeId,
                            errors = list,
                            onOpen = { scope.launch { openDetail(it) } },
                            onRetry = { hash ->
                                scope.launch { post("/api/blocks/$hash/retry-resync", null) }
                            },
                            onPurge = { confirmPurge = it },
                        )
                    }
                }
            }
        }

        selected?.let { hash ->
            BlockDetailCard(hash, detail, onClose = { selected = null; detail = null })
        }
    }

    if (confirmRetryAll) {
        ConfirmDialog(
            title = "全ブロックの再同期を要求",
            message = "失敗しているすべてのブロックの再同期を、全ノードに要求します。" +
                "対象が多いとノードの負荷が上がります。",
            onConfirm = {
                confirmRetryAll = false
                scope.launch {
                    post(
                        "/api/blocks/retry-resync",
                        AppJson.encodeToString(RetryResyncRequest.serializer(), RetryResyncRequest(all = true)),
                    )
                }
            },
            onDismiss = { confirmRetryAll = false },
        )
    }

    confirmPurge?.let { hash ->
        ConfirmDialog(
            title = "ブロックへの参照を削除",
            message = "ブロック $hash への参照を削除します。参照していたオブジェクトは壊れたままになります。" +
                "この操作は取り消せません。",
            requiredInput = hash,
            onConfirm = {
                confirmPurge = null
                scope.launch {
                    post(
                        "/api/blocks/purge",
                        AppJson.encodeToString(
                            PurgeBlocksRequest.serializer(),
                            PurgeBlocksRequest(listOf(hash)),
                        ),
                    )
                }
            },
            onDismiss = { confirmPurge = null },
        )
    }
}

@Composable
private fun BlockErrorCard(
    nodeId: String,
    errors: List<BlockError>,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
    onPurge: (String) -> Unit,
) {
    if (errors.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${nodeId.take(16)}（${errors.size} 件）", style = MaterialTheme.typography.titleSmall)

            errors.sortedByDescending { it.errorCount }.forEach { error ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip("${error.errorCount} 回失敗", StatusTone.ERROR)
                    Text(
                        error.blockHash.take(16),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    CopyButton(error.blockHash)
                    Text(
                        "参照 ${error.refcount} · 前回 ${error.lastTrySecsAgo} 秒前 · 次回 ${error.nextTryInSecs} 秒後",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onOpen(error.blockHash) }) { Text("詳細") }
                    TextButton(onClick = { onRetry(error.blockHash) }) { Text("再同期") }
                    TextButton(onClick = { onPurge(error.blockHash) }) { Text("参照を削除") }
                }
            }
        }
    }
}

@Composable
private fun BlockDetailCard(hash: String, detail: MultiResponse<BlockInfo>?, onClose: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ブロック ${hash.take(16)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("閉じる") }
            }

            when (detail) {
                null -> LoadingView()

                else -> MultiResponseView(detail, emptyMessage = "このブロックの情報がありません") { nodeId, info ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${nodeId.take(16)} · 参照 ${info.refcount} · バージョン ${info.versions.size} 件",
                            style = MaterialTheme.typography.bodySmall,
                        )

                        info.versions.forEach { version ->
                            val backlink = version.backlink
                            val target = when {
                                backlink?.storedObject != null ->
                                    "オブジェクト ${backlink.storedObject.key}（バケット ${backlink.storedObject.bucketId}）"

                                backlink?.upload != null ->
                                    "アップロード ${backlink.upload.uploadId}" +
                                        backlink.upload.key?.let { key -> "（$key）" }.orEmpty()

                                else -> "参照元が不明"
                            }

                            Text(
                                listOfNotNull(
                                    version.versionId.take(16),
                                    target,
                                    "削除済み".takeIf { version.versionDeleted },
                                    "GC 済み".takeIf { version.garbageCollected },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: ビルドを通して実機で見る**

```bash
CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build
mise run run
```

`/blocks` を開き、「再同期に失敗しているブロックはありません」の空状態が出ること、「全件を再同期」の確認ダイアログが出ることを見る。**dev では非空のブロックエラーを作れない**ため、行の描画は単体テストの範囲外・手動確認の範囲外として残る（P3-9）。

- [ ] **Step 3: コミットする**

```bash
./gradlew spotlessApply
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/blocks/
git commit -m "feat(web): ブロックエラーの画面を追加"
```

---

### Task 18: `/tokens` 画面

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/tokens/TokensScreen.kt`

**Interfaces:**
- Consumes: `DataTable` / `TableColumn` / `ConfirmDialog` / `CopyButton` / `EmptyState`（Phase 2）、`StatusChip`（Task 13）、`AdminToken`（Phase 1）、`CreateAdminTokenRequest` / `UpdateAdminTokenRequest` / `CreatedAdminToken`（Task 3）、`Session.id`（Task 3 Step 7）
- Produces: `@Composable fun TokensScreen()`

**ポーリングしない。** spec §8.5 は Tokens を手動更新のみと定めている。

**3 つの守りを入れる**

1. 設定ファイル由来のトークン（`id` が null）は行を開けず、削除もできない。理由を出す（P3-6）
2. いま使っているトークン（`session.info?.id` と一致）を削除・更新する操作は、確認ダイアログで「ログイン中のトークンです。実行するとログイン画面に戻ります」と明示する（P3-7）
3. `secretToken` は作成直後に一度だけ表示し、`CopyButton` を添える。画面を離れると二度と取得できないことを書く

- [ ] **Step 1: `TokensScreen.kt` を書く**

Create `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/tokens/TokensScreen.kt`:

```kotlin
package net.brightroom.garage.web.screens.tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.shared.api.CreateAdminTokenRequest
import net.brightroom.garage.shared.api.CreatedAdminToken
import net.brightroom.garage.shared.api.UpdateAdminTokenRequest
import net.brightroom.garage.shared.model.garage.AdminToken
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendEmpty
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.DataTable
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.StatusChip
import net.brightroom.garage.web.components.StatusTone
import net.brightroom.garage.web.components.TableColumn
import net.brightroom.garage.web.session.LocalSession

/**
 * Admin token の管理（spec §8.2 の「設定」）。
 *
 * ポーリングはしない（spec §8.5）。トークンは頻繁に変わるものではない。
 */
@Composable
fun TokensScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var tokens by remember { mutableStateOf<List<AdminToken>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var created by remember { mutableStateOf<CreatedAdminToken?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<AdminToken?>(null) }
    var editing by remember { mutableStateOf<AdminToken?>(null) }

    val currentId = session.info?.id

    suspend fun load() {
        when (
            val result = session.api.getJson("/api/admin-tokens", ListSerializer(AdminToken.serializer()))
        ) {
            is ApiResult.Success -> {
                tokens = result.value
                failure = null
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Admin token", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            TextButton(onClick = { showCreate = true }) { Text("トークンを作成") }
        }

        created?.let { SecretCard(it, onDismiss = { created = null }) }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = tokens) {
            null -> if (failure == null) LoadingView()

            else -> DataTable(
                items = current,
                emptyMessage = "トークンがありません",
                columns = listOf(
                    TableColumn(
                        title = "名前",
                        value = { it.name },
                        weight = 2f,
                    ),
                    TableColumn(
                        title = "状態",
                        value = { token -> token.stateLabel(currentId) },
                        content = { token ->
                            StatusChip(token.stateLabel(currentId), token.stateTone(currentId))
                        },
                    ),
                    TableColumn(
                        title = "有効期限",
                        value = { it.expiration?.toString() ?: "無期限" },
                        comparator = compareBy { it.expiration },
                    ),
                    TableColumn(
                        title = "scope",
                        value = { it.scope.joinToString(", ") },
                        weight = 2f,
                    ),
                    TableColumn(
                        title = "操作",
                        value = { "" },
                        content = { token ->
                            if (token.id == null) {
                                Text(
                                    "設定ファイル由来",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row {
                                    TextButton(onClick = { editing = token }) { Text("編集") }
                                    TextButton(onClick = { confirmDelete = token }) { Text("削除") }
                                }
                            }
                        },
                    ),
                ),
            )
        }
    }

    if (showCreate) {
        CreateTokenDialog(
            onConfirm = { request ->
                showCreate = false
                scope.launch {
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Post,
                            "/api/admin-tokens",
                            AppJson.encodeToString(CreateAdminTokenRequest.serializer(), request),
                            CreatedAdminToken.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            created = result.value
                            load()
                        }

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { showCreate = false },
        )
    }

    editing?.let { token ->
        EditTokenDialog(
            token = token,
            isCurrent = token.id != null && token.id == currentId,
            onConfirm = { request ->
                editing = null
                scope.launch {
                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Patch,
                            "/api/admin-tokens/${token.id}",
                            AppJson.encodeToString(UpdateAdminTokenRequest.serializer(), request),
                            AdminToken.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> load()

                        is ApiResult.Failure -> failure = result

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { editing = null },
        )
    }

    confirmDelete?.let { token ->
        val isCurrent = token.id != null && token.id == currentId

        ConfirmDialog(
            title = "トークンを削除",
            message = buildString {
                append("トークン「${token.name}」を削除します。この操作は取り消せません。")
                if (isCurrent) {
                    append("\n\nこれはいまログインに使っているトークンです。削除するとログイン画面に戻ります。")
                }
            },
            requiredInput = token.name,
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    when (session.api.sendEmpty(HttpMethod.Delete, "/api/admin-tokens/${token.id}")) {
                        is ApiResult.Success -> load()

                        is ApiResult.Failure -> load()

                        // 自分のトークンを消した場合はここに来る。ログイン画面へ戻る
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

private fun AdminToken.stateLabel(currentId: String?): String = when {
    expired -> "期限切れ"
    id != null && id == currentId -> "使用中"
    else -> "有効"
}

private fun AdminToken.stateTone(currentId: String?): StatusTone = when {
    expired -> StatusTone.ERROR
    id != null && id == currentId -> StatusTone.SUCCESS
    else -> StatusTone.NEUTRAL
}

/** 作成直後にだけ出る。Garage は secret を二度と返さない。 */
@Composable
private fun SecretCard(created: CreatedAdminToken, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("「${created.token.name}」を作成しました", style = MaterialTheme.typography.titleSmall)
            Text(
                "この値が表示されるのは一度だけです。閉じると二度と取得できません。",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(created.secretToken, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                CopyButton(created.secretToken)
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}

@Composable
private fun CreateTokenDialog(onConfirm: (CreateAdminTokenRequest) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var scopeText by remember { mutableStateOf("*") }

    val scopes = scopeText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val grantsEscalation = scopes.any { it == "*" || it == "CreateAdminToken" || it == "UpdateAdminToken" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("トークンを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "名前は「誰に渡したか」が分かるものにしてください。Garage には利用者の概念が無く、" +
                        "権限の管理はトークン名に頼ります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = scopeText,
                    onValueChange = { scopeText = it },
                    label = { Text("scope（カンマ区切り、* ですべて）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (grantsEscalation) {
                    Text(
                        "この scope はトークンの発行・更新を許すため、実質的にすべての権限と同じです。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && scopes.isNotEmpty(),
                onClick = { onConfirm(CreateAdminTokenRequest(name = name.trim(), scope = scopes)) },
            ) {
                Text("作成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun EditTokenDialog(
    token: AdminToken,
    isCurrent: Boolean,
    onConfirm: (UpdateAdminTokenRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(token.id) { mutableStateOf(token.name) }
    var scopeText by remember(token.id) { mutableStateOf(token.scope.joinToString(", ")) }

    val scopes = scopeText.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("トークンを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isCurrent) {
                    Text(
                        "これはいまログインに使っているトークンです。scope を狭めると、" +
                            "この画面を含む一部の操作が行えなくなります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = scopeText,
                    onValueChange = { scopeText = it },
                    label = { Text("scope（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && scopes.isNotEmpty(),
                onClick = { onConfirm(UpdateAdminTokenRequest(name = name.trim(), scope = scopes)) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
```

**`CopyButton` のシグネチャを確認すること。** Phase 2 で作った `components/CopyButton.kt` の引数名と数に合わせる（`CopyButton(value)` で通らなければ IDE の補完に従う）。

- [ ] **Step 2: ビルドを通して実機で見る**

```bash
CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build
mise run run
```

`/tokens` を開き、次を確認する。

1. `dev-console` に「使用中」のチップが付く
2. `admin_token (from daemon configuration)` の行に「設定ファイル由来」と出て、編集・削除のボタンが無い
3. トークンを作成すると secret が一度だけ表示され、コピーできる
4. 作成したトークンを削除すると一覧から消える（**`dev-console` は削除しないこと**。dev 環境のログインに使っている）

- [ ] **Step 3: コミットする**

```bash
./gradlew spotlessApply
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/tokens/
git commit -m "feat(web): Admin token の画面を追加"
```

---

### Task 19: サイドバーと画面の配線

**Files:**
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/NavItem.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/overview/OverviewScreen.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/nodes/NodesScreen.kt`

**Interfaces:**
- Consumes: Task 14–18 の 5 画面
- Produces: サイドバーの 3 グループ、`App.kt` の 5 分岐、概況からの導線

- [ ] **Step 1: `NavItem.kt` に 3 グループを足す**

`navGroups` の末尾（ストレージのグループの後）に足す:

```kotlin
    NavGroup(
        title = "クラスタ",
        items = listOf(
            NavItem(Route.Nodes, "ノード", requiredOperation = "GetClusterStatus"),
            NavItem(Route.Layout, "レイアウト", requiredOperation = "GetClusterLayout"),
        ),
    ),
    NavGroup(
        title = "メンテナンス",
        items = listOf(
            NavItem(Route.Workers, "ワーカー", requiredOperation = "ListWorkers"),
            NavItem(Route.Blocks, "ブロック", requiredOperation = "ListBlockErrors"),
        ),
    ),
    NavGroup(
        title = "設定",
        items = listOf(
            NavItem(Route.Tokens, "Admin token", requiredOperation = "ListAdminTokens"),
        ),
    ),
```

`NavItem.kt` の KDoc から「クラスタ・メンテナンス・設定の各グループは、対応する画面を実装する Phase 3 で追加する。」の 1 文を消す。

- [ ] **Step 2: `App.kt` の `when` に 5 分岐を足す**

Task 4 で仮置きを入れていた場合はそれを消し、次を足す:

```kotlin
            Route.Nodes -> NodesScreen(onNavigate = router::navigate)

            Route.Layout -> LayoutScreen()

            Route.Workers -> WorkersScreen()

            Route.Blocks -> BlocksScreen()

            Route.Tokens -> TokensScreen()
```

import を足す:

```kotlin
import net.brightroom.garage.web.screens.blocks.BlocksScreen
import net.brightroom.garage.web.screens.layout.LayoutScreen
import net.brightroom.garage.web.screens.nodes.NodesScreen
import net.brightroom.garage.web.screens.tokens.TokensScreen
import net.brightroom.garage.web.screens.workers.WorkersScreen
```

- [ ] **Step 3: `/nodes` に「停止ノードを飛ばす」を条件付きで置く**

`NodesScreen` の `PollingHeader` の `trailing` に足す（Task 15 で保留した `ClusterLayoutSkipDeadNodes` の導線）:

```kotlin
            if (cluster?.status?.nodes?.any { !it.isUp } == true) {
                TextButton(onClick = { showSkipDeadNodes = true }) { Text("停止ノードを飛ばす") }
            }
```

状態と確認ダイアログを足す:

```kotlin
    var showSkipDeadNodes by remember { mutableStateOf(false) }
```

```kotlin
    if (showSkipDeadNodes) {
        val version = cluster?.status?.layoutVersion

        ConfirmDialog(
            title = "停止したノードを飛ばす",
            message = "停止しているノードからの応答を待たずにレイアウトを進めます。" +
                "そのノードにしか無いデータは失われる可能性があります。",
            onConfirm = {
                showSkipDeadNodes = false
                if (version != null) {
                    scope.launch {
                        notice = null
                        when (
                            val result = session.api.sendJson(
                                HttpMethod.Post,
                                "/api/layout/skip-dead-nodes",
                                AppJson.encodeToString(
                                    SkipDeadNodesRequest.serializer(),
                                    SkipDeadNodesRequest(version = version, allowMissingData = false),
                                ),
                                LayoutHistory.serializer(),
                            )
                        ) {
                            is ApiResult.Success -> {
                                notice = "レイアウトを進めました"
                                load()
                            }

                            is ApiResult.Failure -> failure = result

                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                }
            },
            onDismiss = { showSkipDeadNodes = false },
        )
    }
```

import を足す: `net.brightroom.garage.shared.api.SkipDeadNodesRequest` / `net.brightroom.garage.shared.model.garage.LayoutHistory`。

**`allowMissingData` は常に false で送る。** true はデータ消失を許す指定であり、画面から選ばせるべきではない。必要な場面は CLI で行う。

- [ ] **Step 4: 概況からの導線を足す**

`OverviewScreen` の `KeyFigures` で、次のカードに `onClick` を付ける:

- 「ノード」→ `Route.Nodes`
- 「状態」→ `Route.Nodes`
- 「レイアウト」→ `Route.Layout`

異常帯（`AlertBand`）は情報の表示に留め、リンクにしない。1 つの帯に複数の異常が並びうるため、どこへ飛ぶのかが曖昧になる。

`AlertBand` の下に、ブロックエラーがあるときだけ `/blocks` への導線を出す:

```kotlin
        if ((overview.blockErrors as? Section.Loaded)?.data?.let { it > 0 } == true) {
            TextButton(onClick = { onNavigate(Route.Blocks) }) { Text("ブロックエラーを見る") }
        }
```

`OverviewContent` は `onNavigate` を受け取っているので、`AlertBand` の呼び出しをこの `TextButton` と並べて置く。

- [ ] **Step 5: ビルドと既存の e2e を通す**

```bash
CHROME_BIN=$(which chromium || which google-chrome) ./gradlew build
mise run run
mise run e2e
```

Expected: Phase 2 までの 22 件が通る。**`navigation.spec.ts` はサイドバーの項目数を数えていないか確認すること。** 数えていれば、この時点で件数を直す（Task 21 でも触る）。

- [ ] **Step 6: コミットして PR を出す**

```bash
./gradlew spotlessApply
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/
git commit -m "feat(web): クラスタ・メンテナンス・設定のナビゲーションを追加"
git push -u origin phase3/5-web-maintenance
gh pr create --title "feat(web): ワーカー・ブロック・トークンの画面とナビゲーションを追加 (Task 16-19)" --body "$(cat <<'EOF'
## 概要

- `/workers` — ノード別のワーカー一覧と設定変数。変数は Garage が返したキーだけを編集対象にしている
- `/blocks` — ブロックエラーの一覧・詳細・再同期・参照の削除。参照の削除はハッシュのタイプ入力を要求する
- `/tokens` — Admin token の一覧・作成・編集・削除。設定ファイル由来のトークンは編集不可、ログイン中のトークンには警告を出す
- サイドバーにクラスタ / メンテナンス / 設定の 3 グループを足し、概況からノード・レイアウト・ブロックへの導線を置いた

これで spec §8.1 の 10 画面がすべて揃った。

## 確認

- Phase 2 までの e2e 22 件が引き続き通る
- 作成したトークンの secret が一度だけ表示され、コピーできることをローカルで確認した

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01Ek5y1ML5RXQALQj5oq6XKt
EOF
)"
```

---
## ブランチ `phase3/6-e2e`（Task 20–21）

### Task 20: fixture トークンとログインの高速化

**Files:**
- Modify: `docker/init-garage.sh`
- Modify: `mise.toml`
- Modify: `.github/workflows/on-pull-request.yaml`
- Modify: `e2e/tests/helpers.ts`

**Interfaces:**
- Consumes: なし
- Produces:
  - 環境変数 `E2E_RESTRICTED_TOKEN` — `ListBuckets` を持たないトークン
  - `export function restrictedToken(): string`
  - `export async function useToken(page: Page, token: string): Promise<void>`
  - `openScreen` が `addInitScript` 方式に変わる（**シグネチャは変えない**）

**なぜ 3 本目のトークンが要るのか。** Phase 2 の申し送りのとおり、`dev-limited` の scope は `NavItem.requiredOperation` をすべて満たすため、サイドバーの無効表示を 1 項目も再現できない。Phase 3 でサイドバーの項目が 8 つに増え、無効表示は実際に効く機能になる。`ListBuckets` / `ListKeys` / `GetClusterLayout` / `ListWorkers` / `ListBlockErrors` / `ListAdminTokens` を持たないトークンを 1 本足す。

**なぜログインを速くするのか。** 現行の `openScreen` は `page.goto("/")` → ログイン → `page.goto(path)` で wasm を 2 回読む。Phase 3 で e2e の本数がおよそ倍になるため、この往復が効いてくる。`sessionStorage` にトークンを入れておけば `SessionState.restore()` が拾い、1 回で済む。

- [ ] **Step 1: ブランチを切る**

```bash
git switch main && git pull
git switch -c phase3/6-e2e
```

- [ ] **Step 2: `init-garage.sh` に 3 本目のトークンを足す**

`docker/init-garage.sh` の `dev-limited` を作るブロックの後、`echo "Garage initialization complete!"` の前に足す:

```sh
# Create a token that cannot reach most console screens
RESTRICTED_TOKEN_COUNT=$(echo "${EXISTING_TOKENS}" | jq '[.[] | select(.name == "dev-restricted")] | length')

if [ "${RESTRICTED_TOKEN_COUNT}" = "0" ]; then
  echo "Creating admin token 'dev-restricted'..."
  # 概況とノードだけが開ける。サイドバーの他の項目が無効表示になることを e2e で確認する
  RESTRICTED_RESPONSE=$(curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name": "dev-restricted", "neverExpires": true, "scope": [
          "GetCurrentAdminTokenInfo", "GetClusterHealth", "GetClusterStatus"]}' \
    "${GARAGE_ADMIN}/v2/CreateAdminToken")

  RESTRICTED_TOKEN=$(echo "${RESTRICTED_RESPONSE}" | jq -r '.secretToken')

  echo "============================================"
  echo "Restricted token: ${RESTRICTED_TOKEN}"
  echo "============================================"
else
  echo "Admin token 'dev-restricted' already exists."
fi
```

**既存の Garage には `dev-restricted` が無い。** 作り直すか、`CreateAdminToken` を同じ scope で直接叩いて補うこと。

```bash
docker compose down -v && docker compose up -d
```

- [ ] **Step 3: `mise.toml` の `token` タスクをラベル指定にする**

`[tasks.token]` の `run` の中で、取り出すラベルを引数で選べるようにする。`token` の `sed` の行を次に差し替える:

```sh
label="${1:-Console login token}"
token=$(printf '%s\n' "$logs" | sed -n "s/.*${label}: //p" | tr -d '\r')

if [ -z "$token" ]; then
  echo "$container のログに ${label} がありません。" >&2
  echo "トークンは発行時に一度しか表示されません。docker compose down -v で作り直してください。" >&2
  exit 1
fi
```

`[tasks.e2e]` の最終行を次に差し替える:

```sh
E2E_ADMIN_TOKEN="$(mise run -q token)" \
E2E_LIMITED_TOKEN="$(mise run -q token -- 'Limited-scope token')" \
E2E_RESTRICTED_TOKEN="$(mise run -q token -- 'Restricted token')" \
BASE_URL="$base_url" npx playwright test
```

**`mise run -q token -- '…'` で引数が `$1` に渡るかを必ず確かめること。** 渡らない場合は、ラベルを環境変数で受ける形（`GARAGE_TOKEN_LABEL`）に変える。

- [ ] **Step 4: CI に 3 本目のトークンの解決を足す**

`.github/workflows/on-pull-request.yaml` の「Resolve limited-scope token」ステップの直後に足す:

```yaml
      - name: Resolve restricted token
        run: |
          TOKEN=$(docker compose -f compose.ci.yaml logs garage-init \
            | grep 'Restricted token:' \
            | sed 's/.*Restricted token: //' \
            | tr -d '\r')
          if [ -z "$TOKEN" ]; then
            echo "failed to resolve restricted token from garage-init logs" >&2
            docker compose -f compose.ci.yaml logs garage-init >&2
            exit 1
          fi
          echo "::add-mask::$TOKEN"
          echo "E2E_RESTRICTED_TOKEN=$TOKEN" >> "$GITHUB_ENV"
```

**`grep 'Restricted token:'` は `Limited-scope token:` に一致しない。** 語が違うのでこのままでよい。

- [ ] **Step 5: `helpers.ts` を書き換える**

`e2e/tests/helpers.ts` に足す（`limitedToken` の後）:

```ts
/**
 * ほとんどの画面に届かない admin token。
 *
 * `docker compose logs garage-init` の "Restricted token:" から取る。
 * ListBuckets も GetClusterLayout も持たないため、サイドバーの多くが無効になる。
 */
export function restrictedToken(): string {
  const token = process.env.E2E_RESTRICTED_TOKEN;

  if (!token) {
    throw new Error(
      "E2E_RESTRICTED_TOKEN が未設定です。docker compose logs garage-init から取得してください",
    );
  }

  return token;
}

/** web が sessionStorage に置くトークンのキー。`SessionState` と合わせること。 */
const TOKEN_STORAGE_KEY = "garage-admin-console.token";

/**
 * ログイン画面を通らずにトークンを持たせる。
 *
 * `SessionState.restore()` が sessionStorage を読むため、これだけで入場できる。
 * ログイン画面自体の検証は login.spec.ts が signIn で行う。
 */
export async function useToken(page: Page, token: string): Promise<void> {
  await page.addInitScript(
    ([key, value]) => window.sessionStorage.setItem(key, value),
    [TOKEN_STORAGE_KEY, token],
  );
}
```

`openScreen` を差し替える:

```ts
/** トークンを持たせた状態で目的の画面を開く。wasm の読み込みは 1 回で済む。 */
export async function openScreen(page: Page, path: string, token: string): Promise<void> {
  await useToken(page, token);
  await page.goto(path);

  await expect(page.getByRole("button", { name: "ログアウト" })).toBeVisible({
    timeout: 30_000,
  });
}
```

`signIn` と `waitForLoginScreen` は消さない。`login.spec.ts` がログイン画面そのものを検証するために使う。

- [ ] **Step 6: 既存の e2e が通ることを確認する**

```bash
docker compose down -v && docker compose up -d
mise run run
mise run e2e
```

Expected: Phase 2 までの 22 件が通る。`openScreen` を使っているテスト（buckets / keys / objects）が速くなる。

- [ ] **Step 7: コミットする**

```bash
git add docker/init-garage.sh mise.toml .github/workflows/on-pull-request.yaml e2e/tests/helpers.ts
git commit -m "test(e2e): 3 本目の fixture トークンを足しログインを 1 往復にする"
```

---

### Task 21: Phase 3 の e2e

**Files:**
- Create: `e2e/tests/nodes.spec.ts`
- Create: `e2e/tests/layout.spec.ts`
- Create: `e2e/tests/maintenance.spec.ts`
- Create: `e2e/tests/tokens.spec.ts`
- Modify: `e2e/tests/navigation.spec.ts`
- Modify: `e2e/playwright.config.ts`

**Interfaces:**
- Consumes: `helpers.ts` の `adminToken` / `restrictedToken` / `openScreen` / `uniqueName` / `afterDialog`
- Produces: なし

**Phase 2 の申し送りを踏まえる**

- Compose のアクセシビリティツリーは `AlertDialog` を閉じると空になる。ダイアログを跨いだ後は必ず `afterDialog(page)` を通す。**`afterDialog` はリロードなので画面の状態も消える。ダイアログの後に画面の状態を確かめたい場合は API のテストに回す**（P3-17）
- 表の行は 1 つの `button` に畳まれ、ラベルは全セルの連結になる。`{ exact: true }` はほぼ使えない
- spec ファイルは並行実行されるため、`uniqueName` の prefix は spec ごとに固有にする（`nodes-` / `layout-` / `maint-` / `tok-`）
- 概況や表は横スクロールの `Row` にあるため、必要なら `page.setViewportSize({ width: 1600, height: 720 })` で収める

**e2e で覆わないもの（P3-9）**

単一ノードの dev では状態を作れないため、次は e2e の対象にしない。サーバーの単体テスト（Task 5–11）が覆っている。

- `ConnectClusterNodes` の成功
- `ClusterLayoutSkipDeadNodes`（停止ノードが無いのでボタンが出ない）
- 非空のブロックエラーと、その再同期・purge の成功
- `MultiResponse.error` が非空になる経路
- ワーカーの `busy` / `done` / `throttled`

- [ ] **Step 1: `layout.spec.ts` を他の spec と同時に走らせないようにする**

レイアウトを stage している間、概況の異常帯に「レイアウト v… に N 件の未適用の変更があります」が出る（`Overview.alerts()`）。spec ファイルは並行実行されるため、このままだと `overview.spec.ts` の「異常はありません」が壊れる。`retries: 2` があるので落ちたり通ったりし、原因が分かりにくい不安定さになる。**先に手を打つ**（P3-16）。

`e2e/playwright.config.ts` の `projects` を差し替える:

```ts
  projects: [
    {
      name: "chromium",
      testIgnore: /layout\.spec\.ts/,
      use: { browserName: "chromium" },
    },
    {
      // レイアウトを stage している間、概況の異常帯に「未適用の変更」が出る。
      // 他の spec と同時に走らせると overview.spec.ts の「異常はありません」が
      // 壊れるため、すべてが終わってから単独で走らせる
      name: "layout",
      testMatch: /layout\.spec\.ts/,
      dependencies: ["chromium"],
      use: { browserName: "chromium" },
    },
  ],
```

**`dependencies` は Playwright のプロジェクト依存である。** `chromium` プロジェクトが全件終わってから `layout` が始まる。

- [ ] **Step 2: `nodes.spec.ts` を書く**

Create `e2e/tests/nodes.spec.ts`:

```ts
import { test, expect } from "@playwright/test";
import { adminToken, afterDialog, openScreen } from "./helpers";

const token = adminToken();

/** ダイアログを跨いだ後にツリーを取り戻し、画面が描き直されるまで待つ。 */
async function reopen(page: import("@playwright/test").Page): Promise<void> {
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
    await expect(page.getByText("バケット", { exact: true })).toBeVisible();
    await expect(page.getByText("オブジェクト", { exact: true })).toBeVisible();
  });

  test("polls and can be refreshed", async ({ page }) => {
    await expect(page.getByText(/最終更新 \d+ 秒前/)).toBeVisible();

    await page.getByRole("button", { name: "更新" }).click({ force: true });

    await expect(page.getByText(/最終更新 [01] 秒前/)).toBeVisible();
  });

  test("asks for confirmation before taking a metadata snapshot", async ({ page }) => {
    await page.getByRole("button", { name: "スナップショット" }).click({ force: true });

    await expect(page.getByText(/メタデータのスナップショットを作成/)).toBeVisible();
    await page.getByRole("button", { name: "キャンセル" }).click({ force: true });

    // ダイアログを閉じるとアクセシビリティツリーが空になる
    await reopen(page);
  });

  test("does not offer skipping dead nodes when every node is up", async ({ page }) => {
    // 単一ノードの dev クラスタでは停止ノードが無いため、このボタンは出ない
    await expect(page.getByRole("button", { name: "停止ノードを飛ばす" })).toHaveCount(0);
  });

  test("opens the connect dialog and accepts an address", async ({ page }) => {
    await page.getByRole("button", { name: "ノードを接続" }).click({ force: true });

    await expect(page.getByText(/1 行に 1 件/)).toBeVisible();
    await page.getByRole("textbox").last().fill("0000@127.0.0.1:19999", { force: true });

    // 実行の結果は画面の状態にしか無く、UI からは確かめられない（下の注記）。
    // ここはダイアログが開いて入力を受け付けるところまでを見る
    await page.getByRole("button", { name: "キャンセル" }).click({ force: true });
    await reopen(page);
  });

  test("reports a connection failure over the api", async ({ request }) => {
    // 到達できないアドレスなので必ず失敗する
    const response = await request.post("/api/cluster/connect", {
      headers: { Authorization: `Bearer ${token}` },
      data: { nodes: ["0000000000000000000000000000000000000000000000000000000000000000@127.0.0.1:19999"] },
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
```

**接続の結果を UI で確かめない理由。** ダイアログを閉じるとアクセシビリティツリーが空になり、取り戻すには `page.reload()` が要る。しかしリロードすると接続の結果（画面の状態）が消える。**「ダイアログを跨いだ後に、画面の状態として持たれているものを確かめる」ことは、この制約のもとでは原理的にできない。** 結果の検証は API 経由で行い、UI テストはダイアログが開くところまでに留める。**この判断は Task 21 の他の画面にも一律に適用する。**

- [ ] **Step 3: `layout.spec.ts` を書く**

Create `e2e/tests/layout.spec.ts`:

```ts
import { test, expect } from "@playwright/test";
import { adminToken, afterDialog, openScreen } from "./helpers";

const token = adminToken();

test.describe("Layout", () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await openScreen(page, "/layout", token);
  });

  // 旧 layout.spec.ts のパリティ: レイアウト画面が出ること
  test("displays the current layout", async ({ page }) => {
    await expect(page.getByText(/現在のレイアウト v\d+/)).toBeVisible();
    await expect(page.getByText(/dc1/)).toBeVisible();
  });

  // 旧 layout.spec.ts のパリティ: ロールを割り当てる導線があること
  test("offers a way to stage a role", async ({ page }) => {
    await expect(page.getByRole("button", { name: "割り当てを stage" })).toBeVisible();
  });

  test("shows the layout history", async ({ page }) => {
    await expect(page.getByText("履歴")).toBeVisible();
    await expect(page.getByText(/全ノードが v\d+ までを認識しています/)).toBeVisible();
  });

  test("has nothing staged in a fresh cluster", async ({ page }) => {
    await expect(page.getByText("未適用の変更はありません")).toBeVisible();
  });

  /**
   * stage → preview → 破棄 を通す。
   *
   * apply は踏まない。適用するとレイアウトの版が単調に進み、以降の実行で
   * 前提が変わってしまう（P3 の判断: e2e は stage → preview → revert まで）。
   */
  test("stages a change, previews it, then reverts", async ({ page }) => {
    // 既存ノードの ID をフォームの初期値から取る
    const nodeIdField = page.getByRole("textbox").first();
    const nodeId = await nodeIdField.inputValue();
    expect(nodeId).not.toBe("");

    // ゾーンと容量を入れて stage する
    await page.getByRole("textbox").nth(1).fill("dc1", { force: true });
    await page.getByRole("textbox").nth(2).fill("2", { force: true });
    await page.getByRole("button", { name: "割り当てを stage" }).click({ force: true });

    await expect(page.getByText(/stage しました/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("未適用の変更はありません")).toHaveCount(0);

    // 適用の前に preview が出ること（spec §8.6）
    await page.getByRole("button", { name: "適用", exact: true }).click({ force: true });
    await expect(page.getByText("レイアウトの変更を適用")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/適用すると v\d+ になります/)).toBeVisible();
    await expect(page.getByText(/COMPUTATION/)).toBeVisible();

    // 適用せずに閉じる
    await page.getByRole("button", { name: "キャンセル" }).click({ force: true });
    await afterDialog(page);

    // 破棄して元の状態に戻す
    await page.getByRole("button", { name: "破棄" }).click({ force: true });
    await expect(page.getByText("stage した変更を破棄")).toBeVisible();
    await page.getByRole("button", { name: "実行" }).click({ force: true });
    await afterDialog(page);

    await expect(page.getByText("未適用の変更はありません")).toBeVisible({ timeout: 30_000 });
  });

  test("returns the layout over the api", async ({ request }) => {
    const response = await request.get("/api/layout", {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(typeof body.version).toBe("number");
    expect(Array.isArray(body.roles)).toBe(true);
    // 実機の dev クラスタは maximum
    expect(body.parameters.zoneRedundancy).toBe("maximum");
  });

  test("previews without changing anything", async ({ request }) => {
    const before = await request.get("/api/layout", {
      headers: { Authorization: `Bearer ${token}` },
    });
    const beforeVersion = (await before.json()).version;

    const preview = await request.post("/api/layout/preview", {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(preview.status()).toBe(200);
    const body = await preview.json();
    expect(body.newLayout.version).toBe(beforeVersion + 1);

    // preview には副作用が無い
    const after = await request.get("/api/layout", {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect((await after.json()).version).toBe(beforeVersion);
  });
});
```

**`getByRole("textbox").nth(n)` は脆い。** 画面のフォームが増えると番号がずれる。**まず `getByLabel("ゾーン")` などのラベル指定を試し、Compose のツリーがラベルを出さない場合にだけ `nth` を使うこと。** どちらにしたかをコメントに残す。

- [ ] **Step 4: `maintenance.spec.ts` を書く**

Create `e2e/tests/maintenance.spec.ts`:

```ts
import { test, expect } from "@playwright/test";
import { adminToken, afterDialog, openScreen } from "./helpers";

const token = adminToken();

test.describe("Workers", () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
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

  test("asks for confirmation before changing a variable", async ({ page }) => {
    await expect(page.getByText("scrub-tranquility")).toBeVisible();

    // 値の欄は変数名の並び順に対応する。scrub-tranquility の欄を探して変える
    await page.getByRole("textbox").nth(2).fill("5", { force: true });
    await page.getByRole("button", { name: "設定" }).first().click({ force: true });

    await expect(page.getByText(/ワーカーの設定を変更/)).toBeVisible();
    await page.getByRole("button", { name: "キャンセル" }).click({ force: true });
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
    expect(body.error).toEqual({});
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
    await page.getByRole("button", { name: "全件を再同期" }).click({ force: true });

    await expect(page.getByText(/全ブロックの再同期を要求/)).toBeVisible();
    await page.getByRole("button", { name: "キャンセル" }).click({ force: true });
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
    expect(body.error).toEqual({});
  });
});
```

**`page.getByRole("textbox").nth(2)` はここでも脆い。** 変数の並びは名前の昇順（`lifecycle-last-completed` / `resync-tranquility` / `resync-worker-count` / `scrub-corruptions_detected` / …）である。**実際に走らせて何番目が `scrub-tranquility` かを確かめ、番号ではなく安定した指定に置き換えられるならそうすること。** 置き換えられない場合は、番号の根拠をコメントに残す。

- [ ] **Step 5: `tokens.spec.ts` を書く**

Create `e2e/tests/tokens.spec.ts`:

```ts
import { test, expect } from "@playwright/test";
import { adminToken, afterDialog, openScreen, restrictedToken, uniqueName } from "./helpers";

const token = adminToken();

test.describe("Admin tokens", () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await openScreen(page, "/tokens", token);
  });

  test("lists tokens and marks the one in use", async ({ page }) => {
    await expect(page.getByText("Admin token", { exact: true }).first()).toBeVisible();
    await expect(page.getByText(/dev-console/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("使用中")).toBeVisible();
  });

  test("shows configuration-derived tokens as read-only", async ({ page }) => {
    // garage.toml に書かれたトークンは id を持たず、API では触れない（P3-6）
    await expect(page.getByText(/from daemon configuration/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("設定ファイル由来").first()).toBeVisible();
  });

  test("creates a token from the dialog and lists it", async ({ page, request }) => {
    const name = uniqueName("tok");

    await page.getByRole("button", { name: "トークンを作成" }).click({ force: true });
    await page.getByRole("textbox").first().fill(name, { force: true });
    await page.getByRole("button", { name: "作成" }).click({ force: true });

    // ダイアログを跨ぐとツリーが空になる。リロードで取り戻す。
    // 一度だけ表示される secret はこのリロードで消えるため、ここでは確かめない
    // （検証は下の API のテストで行う）
    await afterDialog(page);

    await expect(page.getByText(new RegExp(name))).toBeVisible({ timeout: 30_000 });

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
    await expect(page.getByText(new RegExp(name))).toBeVisible({ timeout: 30_000 });

    await page.getByRole("button", { name: "削除" }).last().click({ force: true });

    await expect(page.getByText("トークンを削除")).toBeVisible();
    await expect(page.getByText(new RegExp(`確認のため「${name}」と入力してください`))).toBeVisible();
    await page.getByRole("button", { name: "キャンセル" }).click({ force: true });
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
```

- [ ] **Step 6: `navigation.spec.ts` に Phase 3 の遷移を足す**

`navigation.spec.ts` の describe の末尾に足す:

```ts
  test("has cluster, maintenance and settings groups in the sidebar", async ({ page }) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await page.goto("/");
    await signIn(page, token);

    await expect(page.getByText("クラスタ", { exact: true })).toBeVisible();
    await expect(page.getByText("メンテナンス", { exact: true })).toBeVisible();
    await expect(page.getByText("設定", { exact: true })).toBeVisible();

    for (const [label, path] of [
      ["ノード", "/nodes"],
      ["レイアウト", "/layout"],
      ["ワーカー", "/workers"],
      ["ブロック", "/blocks"],
      ["Admin token", "/tokens"],
    ] as const) {
      await page.getByRole("button", { name: label, exact: true }).click({ force: true });
      await expect(page).toHaveURL(new RegExp(`${path}$`));
    }
  });

  test("restores the screen after a reload of a deep link", async ({ page }) => {
    // History API ルーティングと SPA フォールバックが両方効いていること
    await page.goto("/");
    await signIn(page, token);
    await page.goto("/workers");

    await expect(page.getByText("設定変数")).toBeVisible({ timeout: 30_000 });

    await page.reload();

    await expect(page.getByText("設定変数")).toBeVisible({ timeout: 30_000 });
    await expect(page).toHaveURL(/\/workers$/);
  });
```

**「ノード」はサイドバーと `/nodes` 画面の見出しの両方に出る。** `{ exact: true }` でも複数一致するなら `.first()` を足すこと。

- [ ] **Step 7: e2e をローカルで通す**

```bash
docker compose down -v && docker compose up -d
mise run run
mise run e2e
```

Expected: Phase 2 までの 22 件に Phase 3 の分が加わって全件通る。**落ちたテストは、期待値を実際の描画に合わせて直すか、ロケータを安定した指定に変える。** 検証している内容自体を薄めないこと。

- [ ] **Step 8: コミットして PR を出す**

```bash
git add e2e/tests/ e2e/playwright.config.ts
git commit -m "test(e2e): Phase 3 のパリティを確認する"
git push -u origin phase3/6-e2e
gh pr create --title "test(e2e): Phase 3 のパリティを確認する (Task 20-21)" --body "$(cat <<'EOF'
## 概要

- 旧 `cluster.spec.ts` / `layout.spec.ts` のパリティを `nodes.spec.ts` / `layout.spec.ts` で取り直した
- レイアウトは **stage → preview → 破棄** を通している。apply は版が単調に進むため e2e では踏まない
- `PreviewClusterLayoutChanges` に副作用が無いことを API 経由で確認している
- ワーカー・ブロック・Admin token の画面と、確認ダイアログの導線
- サイドバーの scope 無効表示を、`ListBuckets` を持たない 3 本目の fixture トークンで初めて検証した（Phase 2 の申し送り）
- ログインを `addInitScript` 方式に変え、1 テストあたりの wasm 読み込みを 1 回にした
- `layout.spec.ts` を Playwright の別プロジェクトに分けた。stage 中は概況の異常帯に「未適用の変更」が出るため、並行実行のままだと `overview.spec.ts` が壊れる

## 覆っていないもの

単一ノードの dev では状態を作れないため、次は e2e ではなくサーバーの単体テストで覆っている。

- `ConnectClusterNodes` の成功、`ClusterLayoutSkipDeadNodes`
- 非空のブロックエラーとその再同期・purge の成功
- `MultiResponse.error` が非空になる経路
- ワーカーの `busy` / `done` / `throttled`

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01Ek5y1ML5RXQALQj5oq6XKt
EOF
)"
```

---

## Phase 3 の完了判定

すべてのチェックが埋まったら Phase 3 は完了である。**判定の根拠（どう確かめたか）をこの計画の末尾に追記すること。**

**機能**

- [ ] `/nodes` でクラスタの健全性・統計・ノード一覧が見え、接続・スナップショット・修復が確認ダイアログ付きで実行できる
- [ ] `/layout` で現在のレイアウトと未適用の変更が見え、stage → preview → apply / revert が通る
- [ ] **apply の前には必ず preview が挟まる**（spec §8.6）
- [ ] `/workers` でノード別のワーカー一覧が見え、設定変数を変更できる
- [ ] `/blocks` でブロックエラーが見え、再同期と参照の削除ができる
- [ ] `/tokens` で Admin token の一覧・作成・編集・削除ができ、secret が一度だけ表示される
- [ ] サイドバーにクラスタ / メンテナンス / 設定の 3 グループがある
- [ ] 概況からノード・レイアウト・ブロックへ飛べる

**契約**

- [ ] Admin API v2 の 46 operation すべてに UI か API から到達できる
- [ ] ノード別に成否が割れる operation は `MultiResponse` のまま web に届き、失敗したノードが表示される
- [ ] エラーはすべて RFC 9457 で返り、Phase 3 では新しい問題型を足していない
- [ ] サーバーは scope を判定していない（403 は Garage から返ってきたものだけ）

**秘密の扱い**

- [ ] `CreateAdminToken` の `secretToken` はブラウザに一度返るだけで、ログにもキャッシュにも残らない
- [ ] admin token はログに出ない（`CallLoggingTest` が通る）

**テスト**

- [ ] `./gradlew build` が通る（`:shared` の wasmJs テストを含む）
- [ ] `:shared` のテストが oneOf 4 種の全形を往復で覆っている
- [ ] `:server` のテストが、`repairType` の 2 形・`RetryBlockResync` の 2 形・`PurgeBlocks` の配列本文・`MultiResponse` の部分失敗を覆っている
- [ ] e2e がローカルと CI の双方で通る
- [ ] サイドバーの scope 無効表示が e2e で検証されている
- [ ] `layout.spec.ts` が他の spec の後に単独で走る（`overview.spec.ts` と干渉しない）
- [ ] ダイアログを跨いだ後に画面の状態を見るテストが 1 つも無い（P3-17）

**後始末**

- [ ] `Route` の仮置き（Task 4 Step 5）が残っていない
- [ ] `docker/init-garage.sh` が `dev-restricted` を発行し、CI がそれを解決している
- [ ] 6 つの PR がすべて main にマージされている

---

## Phase 4 への申し送り

Phase 4（最終パリティ確認と CI 調整）の計画を書くときに引き継ぐ事実を、**この計画の末尾に追記すること。** 少なくとも次を残す。

**Phase 4 が確認すべきこと**

- spec §10 の e2e パリティチェックリスト 6 件（`navigation` `dashboard` `cluster` `layout` `buckets` `keys`）が、新 UI ですべて対応先を持っていること
- Phase 2 の申し送りに残った「e2e が覆っていない Phase 2 の機能」（バケットの別名、キー権限の付与・剥奪、未完了アップロードの後始末、S3 縮退の 2 経路、キーのインポートと更新）
- Phase 3 で e2e に入れなかった 5 項目（P3-9）。複数ノードのテスト環境を用意する価値があるかを、そこで判断する

**Phase 3 が残した技術的な事実**

- `JsonShapeSerializer`（`:shared`）は oneOf を持つ Garage の型を扱う共通の土台である。今後 Garage が oneOf を増やしたらここに足す
- `MultiResponse` を返す operation は 10 個ある。すべて `node=*` 固定で呼んでいる（P3-11）。ノードを選ぶ必要が出たら `garage/` の `allNodes` を引数に変える
- `PreviewClusterLayoutChanges` に副作用が無いことは実機で確認済みであり、e2e にも入っている
- ダイアログを跨いだ後に画面の状態を確かめる経路は、Compose のツリーの制約により e2e では取れない（P3-17）。UI テストを増やす前にこの制約を思い出すこと
- `layout.spec.ts` は Playwright の別プロジェクトになっている。e2e を足すときは、概況の異常帯を動かす操作が他の spec と衝突しないかを確かめる
- 単一ノードの dev では作れない状態が 5 つある。複数ノードの compose を用意するかどうかは Phase 4 の判断に委ねる
