# garage-admin-console 再構築 設計書

- 日付: 2026-08-23
- 対象: Garage v2.3.0（Admin API v2）
- 状態: 承認済み

## 1. 背景と目的

既存の garage-admin-console は「どこまで実装したか分からない」状態で放置されていた。実際に調査したところ、Admin API のプロキシ層はほぼ完成していたが、UI の情報設計と認証方式に構造的な問題があった。

本設計では **`:shared` / `:server` / `:web` の 3 モジュールを完全にスクラッチで書き直す**。言語とフレームワークは踏襲する（Kotlin Multiplatform + Ktor + Compose Multiplatform for Web / wasmJs）。

なお本プロジェクトは MinIO からの移行の一部である。MinIO が突然開発終了した経緯があるため、Garage に対しても同種のリスクを想定し、**移植性の方針**（第 9 節）を設計に含める。

## 2. 調査で判明した現状（書き直し前）

| カテゴリ | v2.3.0 の operation 数 | 旧実装でプロキシ済み | 欠けていたもの |
|---|---|---|---|
| Cluster | 4 | 4 | — |
| Cluster layout | 7 | 7 | — |
| Bucket | 7 | 6 | `InspectObject` |
| Bucket alias | 2 | 2 | — |
| Permission | 2 | 2 | — |
| Access key | 6 | 6 | — |
| Admin API token | 6 | 6 | — |
| Node | 4 | 4 | — |
| Worker | 4 | 4 | — |
| Block | 4 | 4 | — |
| 特殊 (`/health` `/metrics` `/check`) | 3 | 0 | 3 つとも（本設計では非スコープ） |

v2 の 46 operation のうち 45 が実装済みだった。旧実装の問題は API カバレッジではなく以下の 3 点である。

1. **認証が無い** — サーバーが env の admin token を保持し、到達できる人は全員フル管理権限を持つ
2. **URL ルーティングが無い** — リロード・ブックマーク・戻るボタンが機能しない
3. **サーバーが型を持たない** — 生 JSON を素通しするため、Garage のエラー形と API の癖が web 側に漏れる

## 3. スコープ

### 対象

- Admin API v2 の 46 operation すべて（`InspectObject` を含む）
- S3 オブジェクトブラウザ（一覧 / アップロード / ダウンロード / 削除）
- Admin token によるログイン

### 非対象

- `/health` `/metrics` `/check`（それぞれ外形監視・Prometheus・TLS ドメイン検証用。コンソールが再実装する価値がない）
- ライトテーマ（ダーク固定を継続）
- コンソール独自のユーザー管理・SSO・監査ログ
- S3 専用利用者向けの UI（S3 のみを利用する人は AWS CLI を使う運用に決定済み）

## 4. 決定事項

| # | 決定 | 理由 |
|---|---|---|
| D1 | 3 モジュールを完全にスクラッチで書き直す。ブランチを切り、先に旧ソースを全削除してから書く | 新旧が混ざらない。旧コードは git 履歴に残る |
| D2 | 言語・フレームワークは踏襲（Kotlin MP / Ktor 3.5.2 / Compose MP 1.11.1 wasmJs） | ユーザー決定 |
| D3 | 利用者が自分の admin token を画面で入力してログインする。サーバーはトークンを保持しない | Garage には admin token を人数分発行し scope と期限を個別に設定できる。権限管理を Garage 側に委ねられる |
| D4 | S3 資格情報は admin token から `GetKeyInfo?showSecretKey=true` で自動導出する | 追加入力が不要。コンソール利用者は管理者のみのため、導出に伴う権限集中は許容範囲 |
| D5 | サーバーは型付き end-to-end（`.body<T>()` でパースして型で返す） | エラー形式を正規化でき、Garage の API の癖をサーバーに閉じ込められる |
| D6 | `:shared` のモデルは UI が描画するフィールドだけの手書きサブセット。OpenAPI コード生成はビルドに組み込まない | 使わない型が residue として残るのを避ける |
| D7 | サイドバーは役割でグループ化（ストレージ / クラスタ / メンテナンス / 設定） | 使用頻度と危険度が構造に出る |
| D8 | 概況画面は「異常ファースト」 | 「一目で状態が分かる」ことを UI の軸に決定したため |
| D9 | 画面ごとの定期ポーリングで鮮度を保つ（トグルで停止可） | 同上。SSE はサーバー側の複雑さに見合わない |
| D10 | `:server` と `:shared` を `s3/`（ポータブル）と `garage/`（Garage 固有）にパッケージ分離する。ただし抽象化 interface は作らない | 乗り換え時に捨てる範囲が明確になる。2 実装目が無い状態の interface は中途半端になる |
| D11 | `:web` の画面はバックエンド依存性で分けず、機能単位で切る | ユーザー決定 |

## 5. アーキテクチャ

```
:shared  (jvm + wasmJs)   model/garage/  Garage Admin API のドメインモデル
                          model/s3/      S3 オブジェクトのモデル
                          api/           コンソール API の契約 DTO（Overview, Section など）

:server  (JVM / Ktor)     garage/        型付き Admin API クライアント（Garage 固有）
                          s3/            S3 資格情報の導出とオブジェクト操作（ポータブル）
                          api/           ブラウザ向け /api/** エンドポイント
                          plugins/       DI / Serialization / StatusPages / CallLogging / StaticFiles
                                         （StaticFiles は /api/** 以外を index.html に
                                           フォールバックさせる。History API ルーティングの前提）

:web     (wasmJs/Compose) router/        History API ベースの薄い Router
                          session/       セッション（トークンと scope）
                          screens/       画面（機能単位）
                          components/    共通コンポーネント
                          theme/
```

データの流れは 2 経路。

- **管理操作**: ブラウザ → `/api/**`（`Authorization: Bearer <利用者の admin token>`）→ server の型付きクライアント → Garage Admin API
- **オブジェクト操作**: ブラウザ → `/api/buckets/{id}/objects/**` → server が admin token で S3 キーを導出 → AWS SDK for Kotlin → Garage の S3 API

`garage-admin-v2.json`（`info.version: v2.3.0`）を仕様の参照元とする。

## 6. 認証と資格情報

### 6.1 Garage 側の前提（調査で確認済み）

- Garage に**ユーザーアカウントの概念は無い**。認証の単位は token / key である
- **Admin API token**: 複数発行可。名前・有効期限・scope（API operation 名のリスト、または `*`）を持つ
- **S3 access key**: 複数発行可。名前・有効期限・`createBucket` 権限に加え、バケットごとに read / write / owner を設定できる
- scope に `CreateAdminToken` / `UpdateAdminToken` を含めると権限昇格が自明に可能であり、仕様上 `*` と等価である（OpenAPI の記述に明記）
- scope はバケット単位・キー単位には絞れない。`GetKeyInfo` を許可すると、そのトークンで全キーの secret を読める
- 監査ログは公式ドキュメントに記載が無い

運用上は「トークン名を人名にする」ことで、誰にどの権限を渡したかを管理する。

### 6.2 ログインフロー

1. トークン未保持なら `/login`（トークン入力画面）
2. 入力値で `GET /api/session` を呼ぶ（内部は `GetCurrentAdminTokenInfo`）。成功したらトークン名・scope・有効期限を表示して入場
3. ブラウザは **sessionStorage** に保持する（タブを閉じれば消え、リロードでは残る）。localStorage を使わないのは共有端末での残留を避けるため
4. サーバーは受け取った `Authorization` を**保持せず転送するだけ**。CallLogging で `Authorization` ヘッダをスクラブし、ログにもディスクにも残さない
5. Garage が 401 を返したら web はセッションを破棄して `/login` へ戻す

### 6.3 scope 縮退

scope 制限があるため **403 は正常系**として扱う。

- `/api/overview` はセクションごとに成否を持ち、403 のセクションだけ「このトークンでは参照できません」と表示して残りは通常描画する。1 本の 403 で画面全体を落とさない
- サイドバーは `/api/session` の scope を見て、使えない項目を無効表示にする
- 直接 URL で scope 外の画面に来た場合も、画面単位で同じメッセージを出す
- **サーバー側では scope を判定しない**。判定の実体は常に Garage 側に置き、返ってきた 403 を正規化して渡すだけにする（判定を二重に持つと乖離するため）

### 6.4 S3 資格情報の導出

1. サーバーが `GetBucketInfo` でそのバケットに権限を持つキーを取得する
2. **owner > read+write > read** の優先度で自動選択する。同順位が複数ある場合は accessKeyId の昇順で決定的に選ぶ
3. 選んだキーの secret を `GetKeyInfo?showSecretKey=true` で取得する
4. **サーバー内メモリに TTL 5 分でキャッシュ**する。キーは `(admin token のハッシュ, bucketId)`。オブジェクト操作ごとに `GetKeyInfo` を呼ばないため
5. secret は**ブラウザに返さず、ログにも出さない**
6. 選ばれたキーは画面に「`dev-key` として閲覧中」と表示する（切替 UI は作らない）

**縮退動作**
- 導出には `GetBucketInfo` と `GetKeyInfo` の両方が必要になる。どちらかが 403 を返した場合、S3 ブラウザのみ「このトークンでは利用できません」と表示する（他画面は通常どおり）。6.3 の方針どおり scope 文字列で事前判定はせず、**返ってきた 403 で判断する**
- どのキーもアクセス権を持たない場合、「このバケットにアクセスできるキーがありません」の空状態を出し、キー作成と権限付与への導線を置く

### 6.5 S3 のバケット名解決

`/api/buckets/{id}` の `{id}` は bucket ID だが、S3 API はバケット名を要求する。サーバーは `GetBucketInfo` の global alias を使い、無ければ**導出したキーの local alias** を使う。どちらも無いバケットは S3 でアドレスできないため、その旨を空状態で表示する。

### 6.6 ログアウトとセッションの寿命

サーバーはトークンを保持しないため、ログアウトは原理的にはブラウザ側の処理だけで完結する。ただしサーバーには S3 secret のキャッシュ（6.4）が残るため、明示的に破棄する。

**ログアウト**
1. `POST /api/session/logout` を呼び、サーバーは当該トークンハッシュ配下の S3 secret キャッシュを破棄する
2. ブラウザは sessionStorage を破棄して `/login` へ戻る

キャッシュを引けるのは同じ admin token を提示できる者だけなので、この破棄は機密性の担保というより後始末である。処理は Map からの削除のみで済む。

**セッションの寿命は 3 層で決まる**

| 層 | 寿命 | 期限到達時の挙動 |
|---|---|---|
| sessionStorage | タブを閉じるまで（リロードでは維持） | ブラウザを閉じれば消える |
| admin token 自体 | Garage 側で設定した有効期限 | Garage が 401 を返し `/login` へ戻る |
| アイドルタイムアウト | **最終操作から 30 分** | セッションを破棄して `/login` へ戻る |

**アイドルタイムアウトの判定**
- **利用者の操作（クリック / キー入力）を基準**とする。ポーリングによる通信はアイドル解除の根拠にしない（自動更新が走り続けるため、通信の有無では放置を検出できない）
- 期限の 1 分前に警告を出し、操作があれば延長する
- タイマーは web 側のセッション層（8.4）に置く。サーバーはこれを関知しない

**トークン残り期限の表示**
`/api/session` が返す expiration をヘッダに表示する。期限が近い場合は警告表示にする。追加の API 呼び出しは不要である（8.3 で概況の異常帯から除外したのは他者のトークンとキーの期限監視であり、ログイン中の自分自身のトークンとは別の話である）。

## 7. サーバー API

Garage の operation 名を晒さず、リソース指向で設計する。

```
GET    /api/session                          現在のトークン情報（名前 / scope / 期限）
POST   /api/session/logout                   S3 secret キャッシュの破棄（Garage は呼ばない）
GET    /api/overview                         概況の集約

GET    /api/cluster                          GetClusterStatus + GetClusterHealth
GET    /api/cluster/statistics               GetClusterStatistics
POST   /api/cluster/connect                  ConnectClusterNodes

GET    /api/layout                           GetClusterLayout（current + staged）
POST   /api/layout/roles                     UpdateClusterLayout（stage のみ）
POST   /api/layout/preview                   PreviewClusterLayoutChanges
POST   /api/layout/apply                     ApplyClusterLayout
POST   /api/layout/revert                    RevertClusterLayout
GET    /api/layout/history                   GetClusterLayoutHistory
POST   /api/layout/skip-dead-nodes           ClusterLayoutSkipDeadNodes

GET    /api/buckets                          ListBuckets
POST   /api/buckets                          CreateBucket
GET    /api/buckets/{id}                     GetBucketInfo
PATCH  /api/buckets/{id}                     UpdateBucket
DELETE /api/buckets/{id}                     DeleteBucket
POST   /api/buckets/{id}/aliases             AddBucketAlias
DELETE /api/buckets/{id}/aliases             RemoveBucketAlias
PUT    /api/buckets/{id}/keys/{keyId}        AllowBucketKey
DELETE /api/buckets/{id}/keys/{keyId}        DenyBucketKey
POST   /api/buckets/{id}/cleanup-uploads     CleanupIncompleteUploads
GET    /api/buckets/{id}/objects/inspect     InspectObject

GET    /api/buckets/{id}/objects             S3 ListObjectsV2
PUT    /api/buckets/{id}/objects             S3 PutObject
DELETE /api/buckets/{id}/objects             S3 DeleteObject
GET    /api/buckets/{id}/objects/content     S3 GetObject

GET    /api/keys                             ListKeys
POST   /api/keys                             CreateKey
POST   /api/keys/import                      ImportKey
GET    /api/keys/{id}                        GetKeyInfo
PATCH  /api/keys/{id}                        UpdateKey
DELETE /api/keys/{id}                        DeleteKey

GET    /api/admin-tokens                     ListAdminTokens
POST   /api/admin-tokens                     CreateAdminToken
GET    /api/admin-tokens/{id}                GetAdminTokenInfo
PATCH  /api/admin-tokens/{id}                UpdateAdminToken
DELETE /api/admin-tokens/{id}                DeleteAdminToken

GET    /api/nodes/info                       GetNodeInfo
GET    /api/nodes/statistics                 GetNodeStatistics
POST   /api/nodes/snapshot                   CreateMetadataSnapshot
POST   /api/nodes/repair                     LaunchRepairOperation

GET    /api/workers                          ListWorkers（Garage 側は POST）
GET    /api/workers/{id}                     GetWorkerInfo
GET    /api/workers/variables                GetWorkerVariable
PUT    /api/workers/variables                SetWorkerVariable

GET    /api/blocks/errors                    ListBlockErrors
GET    /api/blocks/{hash}                    GetBlockInfo
POST   /api/blocks/{hash}/retry-resync       RetryBlockResync
POST   /api/blocks/purge                     PurgeBlocks
```

`GetCurrentAdminTokenInfo` は `/api/session` が使う。これで v2 の 46 operation すべてが到達可能になる。

### 7.1 エラーの正規化

全エラーを 1 形式に統一し、StatusPages で一元処理する。HTTP ステータスは Garage のものを踏襲する。

```json
{ "error": { "code": "FORBIDDEN", "message": "...", "operation": "GetKeyInfo" } }
```

`code` は `UNAUTHORIZED` / `FORBIDDEN` / `NOT_FOUND` / `BAD_REQUEST` / `GARAGE_ERROR` / `INTERNAL` を用いる。

### 7.2 `/api/overview`

```kotlin
@Serializable
sealed interface Section<out T> {
    @Serializable data class Loaded<T>(val data: T) : Section<T>
    @Serializable data class Denied(val operation: String) : Section<Nothing>
    @Serializable data class Failed(val message: String) : Section<Nothing>
}

@Serializable
data class Overview(
    val health: Section<ClusterHealth>,
    val nodes: Section<List<NodeSummary>>,
    val layout: Section<LayoutSummary>,      // version と staged の有無
    val storage: Section<StorageSummary>,    // bucket 数 / key 数
    val blockErrors: Section<Int>,
)
```

サーバーは各セクションを**並列取得**し、403 は `Denied`、その他の失敗は `Failed` に落とす。全体は常に 200 を返す。

なお generic な sealed interface の serializer が kotlinx.serialization で問題なく生成されるか（特に wasmJs ターゲット）は実装の最初に検証する。生成できない場合は、ジェネリクスをやめてセクションごとの具体型（`HealthSection` / `NodesSection` …）に展開する。**表現方法が変わるだけで、「セクション単位で成否を持ち全体は 200」という契約は変えない。**

### 7.3 `MultiResponse` の扱い

Node / Worker / Block 系は Garage がノード別の success / error マップ（`MultiResponse_*`）を返す。これを潰さず型で受け、UI に「node-c だけ失敗」を表示できるようにする。

## 8. web の構成

### 8.1 ルーティング

History API と `popstate` を扱う薄い Router を手書きする。ライブラリは導入しない。

| URL | 画面 |
|---|---|
| `/` | 概況 |
| `/buckets`, `/buckets/{id}` | バケット一覧 / 詳細 |
| `/keys`, `/keys/{id}` | アクセスキー一覧 / 詳細 |
| `/objects/{bucketId}?prefix=` | オブジェクトブラウザ |
| `/nodes` | クラスタ状態 + ノード |
| `/layout` | レイアウト |
| `/workers` | ワーカー |
| `/blocks` | ブロックエラー |
| `/tokens` | Admin token |
| `/login` | トークン入力 |

旧実装の Cluster 画面と Nodes 画面は `/nodes` に統合する（どちらもノードの状態を別角度で見ていただけで、分ける必然性がない）。

`/buckets/{id}` などへの直接アクセスとリロードを成立させるため、**サーバーは `/api/**` 以外のパスをすべて `index.html` にフォールバックさせる**。旧実装は URL ルーティングを持たなかったため不要だった処理である。

### 8.2 サイドバー

```
概況
─ ストレージ    Buckets / Keys / Objects
─ クラスタ      Nodes / Layout
─ メンテナンス  Workers / Blocks
─ 設定          Admin Tokens
```

### 8.3 概況画面（異常ファースト）

1. **最上段は「いま気にすべきこと」専用の帯**。正常時は 1 行の静かな表示になり、異常時のみ主張する
   - 対象: layout が staged のまま未適用 / block resync エラー / ノードダウン / quorum 不足
   - いずれも 7.2 の `Overview` が運ぶ情報だけで判定できる範囲に留める。トークンやキーの期限切れ監視は、`ListAdminTokens` の追加取得が必要になるため対象外とする
   - ワーカーの状態も概況には出さない（異常帯の対象外であり、`WorkerStateResp` は文字列とオブジェクトの oneOf でカスタム serializer を要するため）。ワーカーは `/workers` 画面で扱う
2. 主要数値（nodes up、health、bucket 数、key 数）
3. ノード一覧（ゾーン・容量バー付き）

各項目は該当画面へドリルダウンできる。

### 8.4 状態管理

画面ごとに `remember` + `LaunchedEffect` でロードする。グローバルに持つのは**セッション（トークンと scope）だけ**とし、CompositionLocal で配る。状態管理ライブラリは導入しない。

### 8.5 ポーリング

| 画面 | 間隔 |
|---|---|
| 概況 | 10 秒 |
| Nodes / Layout / Workers / Blocks | 15 秒 |
| Buckets / Keys / Tokens / Objects | 手動更新のみ |

- 各ポーリング画面にトグルと「最終更新 N 秒前」を表示する
- `document.visibilityState === "hidden"` の間は停止する（放置したタブが Garage を叩き続けない）

### 8.6 破壊的操作のガード

| 操作 | ガード |
|---|---|
| `ApplyClusterLayout` | **必ず `PreviewClusterLayoutChanges` を先に呼び、その結果を確認ダイアログに表示する** |
| `DeleteBucket` | バケット名のタイプ入力を要求。Garage が空でないバケットを拒否した場合はその理由を明示する |
| `PurgeBlocks` / `DeleteKey` / `DeleteAdminToken` / `DenyBucketKey` | 対象名を明示した確認ダイアログ |
| `LaunchRepairOperation` / `SetWorkerVariable` | 影響範囲の説明文つき確認ダイアログ |

### 8.7 共通コンポーネント

DataTable（ソート・検索）、確認ダイアログ、コピーボタン、サイズ表示、ステータスチップ、空状態、エラー表示、ローディング。

### 8.8 テーマ

ダーク固定を継続する。ライトテーマ対応は要求に含まれないため実装しない。

## 9. 移植性の方針

Garage が開発終了した場合を想定する。ただし**管理レイヤは本質的に移植できない**ことを前提に置く。cluster layout / zone / capacity / partition / block resync / worker は Garage のアーキテクチャ固有の概念であり、他の S3 互換実装に対応物が無い。

したがって取る方針は以下に限る。

1. **境界を物理的に分ける**
   - `:server` を `s3/`（ポータブル）と `garage/`（固有）に分離する
   - `:shared` を `model/s3/` と `model/garage/` に分離する
2. **抽象化 interface は作らない**。2 実装目が存在しない段階の interface は Garage の概念が漏れた中途半端なものになる。境界さえ分かれていれば、後から抽出するのは機械的な作業である
3. **S3 側は AWS SDK の標準的な使い方に留める**。エンドポイント・リージョン・path-style を設定で外出しし、Garage 固有の挙動に依存しない

乗り換え時に残るのは「UI 基盤・認証・ルーティング・共通コンポーネント・S3 ブラウザ・バケット/キー管理」、捨てるのは「Garage 管理画面（Layout / Nodes / Workers / Blocks）と Garage API クライアント」となる。

`:web` の画面はバックエンド依存性ではなく機能単位で分ける（D11）。

## 10. テスト

| 対象 | 方針 |
|---|---|
| `:shared` | モデルの直列化テスト。OpenAPI spec の example と実機から採取した JSON を fixture にする |
| `:server` | `ktor-server-test-host` + `ktor-client-mock` で Garage をモックする。ルーティング、エラー正規化、**overview の部分縮退（403 混在）**、**S3 キー選択規則**、**Authorization のログスクラブ**を重点的に検証する |
| `:web` | Compose の UI テストは書かない（費用対効果が低いため）。描画は e2e で担保する。ただし **UI に依存しないロジック（Router のパス解析、アイドルタイマー）には単体テストを書く** |
| `e2e` | Playwright + docker compose の Garage v2.3.0 実機 |

### e2e パリティチェックリスト

旧 e2e spec を「以前できていたこと」の記録として使う。新 UI で同等の経路が通ることを確認する。

- `navigation.spec.ts` — 全画面への遷移（新 UI では URL ルーティングも併せて検証する）
- `dashboard.spec.ts` — 概況の表示
- `cluster.spec.ts` — クラスタ状態の表示（新 UI では `/nodes` に統合）
- `layout.spec.ts` — レイアウトの参照と stage
- `buckets.spec.ts` — バケットの作成・参照・削除
- `keys.spec.ts` — キーの作成・参照・削除

新規に追加するもの: ログイン（トークン入力 → 入場 → 401 で戻る）、明示的なログアウト、scope 縮退、オブジェクトブラウザ、`ApplyClusterLayout` の preview 確認。

アイドルタイムアウトは 30 分の実時間待ちになるため e2e では扱わず、タイマーのロジックを web 側の単体テストで検証する。

CI は既存の GitHub Actions（on-pull-request / on-merge / security）を踏襲する。

## 11. パッケージングと設定

- `:web` の wasm dist を `:server` の resources に入れて fat jar 1 個にする（旧実装の仕組みを踏襲）
- Dockerfile を踏襲する
- 環境変数
  - `GARAGE_ADMIN_ENDPOINT`
  - `GARAGE_S3_ENDPOINT`
  - `GARAGE_S3_REGION`
  - `GARAGE_S3_PATH_STYLE`
  - **`GARAGE_ADMIN_TOKEN` は廃止する**（トークンは利用者が入力するため、サーバーが持つ必要が無くなった）
- ローカル開発の `compose.yaml` は踏襲し、`mise.toml` から `GARAGE_ADMIN_TOKEN` を削除する
- **`docker/init-garage.sh` は修正が必要**。現状の init は `garage.toml` の master token（`dev-admin-token`。仕様上 deprecated 扱い）を使っているだけで、admin token を発行していない。master token に対して `GetCurrentAdminTokenInfo` が何を返すかは未確認であり、エラーになると `/api/session` を経由するログインが dev 環境で最初から通らない
  - 対処: init が master token で `CreateAdminToken` を呼び、**dev 用の named token を発行してログに表示する**形に変える。開発時も本番と同じ経路（named token でログイン）を踏めるようにする
  - あわせて、master token でログインした場合の `GetCurrentAdminTokenInfo` の挙動を実装初日に検証し、通らない場合はログイン画面にその旨のエラーを出す
- init が出力している「`mise.toml` に `GARAGE_S3_ACCESS_KEY_ID` を書け」という案内は、S3 資格情報の自動導出により不要になるため削除する
- dev で web を別ポートの dev server から配信する場合は CORS 設定か proxy が必要になる。実装時に決める

## 12. 実装順序

1. 旧ソースの削除（`:shared` / `:server` / `:web` の src、`mise.toml` の該当 env）
2. `:shared` — モデルと API 契約 DTO
3. `:server` — `garage/` 型付きクライアント → `api/` エンドポイント → StatusPages によるエラー正規化 → `/api/session` と `/api/overview`
4. `:server` — `s3/` 資格情報の導出とオブジェクト操作
5. `:web` — Router / セッション / AppScaffold / 共通コンポーネント
6. `:web` — ログイン → 概況 → ストレージ系 → クラスタ系 → メンテナンス系 → 設定
7. e2e の書き直しとパリティ確認
