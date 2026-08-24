# garage-admin-console 再構築 Phase 2（バケット・キー・オブジェクト）実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** バケットとアクセスキーを画面から管理でき、admin token から自動導出した S3 資格情報でオブジェクトを一覧・アップロード・ダウンロード・削除できるところまでを、Phase 1 の基盤の上に積む。

**Architecture:** `:shared` にバケット / キー / S3 オブジェクトのモデルを足し、`:server` の `garage/` が Admin API を型付きで叩き、`s3/`（ポータブル）が `GetBucketInfo` → キー選択 → `GetKeyInfo?showSecretKey=true` で資格情報を導出して AWS SDK for Kotlin から Garage の S3 API を叩く。導出結果は `(SHA-256(admin token), bucketId)` をキーに TTL 5 分でメモリキャッシュする。`:web` は機能単位の画面（buckets / keys / objects）を足す。

**Tech Stack:** Kotlin Multiplatform / Ktor 3.5.2 / Compose Multiplatform 1.11.1 (wasmJs) / aws-sdk-kotlin 1.8.31 / kotlinx.serialization / Playwright

**Spec:** `docs/superpowers/specs/2026-08-23-garage-admin-console-rebuild-design.md`

**前提計画:** `docs/superpowers/plans/2026-08-23-rebuild-phase1-foundation.md`（完了済み）

## Global Constraints

- 対象 Garage: **v2.3.0**（Admin API v2）。仕様の参照元は `https://garagehq.deuxfleurs.fr/api/garage-admin-v2.json`
- バージョンは `gradle/libs.versions.toml` の既存値を変更しない: Kotlin `2.4.10` / Ktor `3.5.2` / Compose Multiplatform `1.11.1` / kotlinx-serialization `1.11.0` / kotlinx-coroutines `1.11.0` / kotlinx-datetime `0.8.0-0.6.x-compat` / aws-sdk-kotlin `1.8.31` / logback `1.6.3`
- **外部のライブラリを新たに追加しない。** ルーティングは手書き、状態管理ライブラリは使わない。**`aws.sdk.kotlin:s3` は例外ではなく既定路線である** — spec §5 がデータ経路として名指しし、`libs.versions.toml` に `1.8.31` で既にピン留めされている。Task 8 で `server/build.gradle.kts` に `implementation(libs.aws.sdk.s3)` を足すのは本制約に反しない
- **クライアントとサーバー間のエラーは RFC 9457（Problem Details for HTTP APIs）に準拠する。** `application/problem+json` を用い、ラッパーを被せず、独自のエラーコード enum を定義しない（spec §7.1）
- **コンソール固有の問題型には `type` に URN を入れる。** spec §7.1 は「コンソール固有の問題型を定義する必要が生じた時点で `type` に URI を入れる」と定めている。Phase 2 はその時点にあたる（S3 の縮退 2 種を web が区別する必要がある。Task 9）。`type` を持つ problem では `title` は問題型の短い要約になる（RFC 9457 が `about:blank` 以外に求める形）
- **縦切りで作る。** モデルは画面が描画する分だけに限る（spec D6）。46 API 分のモデルを先に書かない
- **サーバー側では scope を判定しない**（spec §6.3）。可否の実体は常に Garage 側にあり、返ってきた 403 を正規化して渡すだけにする
- パッケージルート: `net.brightroom.garage.shared` / `net.brightroom.garage.server` / `net.brightroom.garage.web`
- `jvmToolchain(21)`（`:server` と `:shared`）
- サーバーは admin token を保持しない。ログにも出さない。**S3 の secret access key も同様にブラウザへ返さず、ログにも problem details の `detail` にも出さない**（spec §6.4）
- テーマはダーク固定
- コミットは各タスク末尾で 1 つ

---

## フェーズのロードマップ

| Phase | スコープ | 状態 |
|---|---|---|
| 1 | shared 基盤 / Garage クライアント / エラー正規化 / `/api/session` / `/api/overview` / Router / セッション / ログイン / 概況 | **完了**（PR #68–#72） |
| **2（本計画）** | Buckets / Keys / Objects（S3 資格情報の導出、`SecretCache` の配線、`InspectObject`） | 本計画 |
| 3 | Nodes（Cluster 統合）/ Layout（preview 確認）/ Workers / Blocks / Admin Tokens | 未着手 |
| 4 | 最終パリティ確認と CI 調整 | 未着手 |

### ブランチと PR の分割

Phase 1 と同じく、タスクをまとめて 1 PR にする（`phase1/1-cleanup` … `phase1/5-e2e` の前例）。

| ブランチ | タスク | PR の粒度 |
|---|---|---|
| `phase2/1-shared` | Task 1–4 | `:shared` のモデルとルート |
| `phase2/2-server-garage` | Task 5–7 | `/api/buckets` と `/api/keys` |
| `phase2/3-server-s3` | Task 8–11 | S3 資格情報の導出とオブジェクト操作 |
| `phase2/4-web-storage` | Task 12–16 | 共通コンポーネントとバケット / キー画面 |
| `phase2/5-web-objects` | Task 17–18 | オブジェクトブラウザと導線 |
| `phase2/6-e2e` | Task 19 | e2e |

**次のブランチは、前の PR が main にマージされてから切る。** 各タスクの冒頭にある
`git switch main && git pull` はそれを前提にしている。`phase2/2-server-garage` を
`phase2/1-shared` のマージ前に始めると、`:shared` のモデルが無くてビルドが通らない。

---

## Phase 2 で先に確認した事実

計画の前に Garage v2.3.0 の実機（`compose.yaml` の dev 環境）と `aws-sdk-kotlin 1.8.31` の jar を直接検証した。以下は推測ではなく採取結果である。**これらは Task の中でテストの fixture として使う。**

### `UpdateBucket` の部分更新の意味論

`POST /v2/UpdateBucket?id=<id>` に対して:

| 送ったもの | 結果 |
|---|---|
| フィールドを**省略** | そのフィールドは**変更されない**（`quotas` だけ送っても `corsRules` / `lifecycleRules` / `websiteConfig` は保持される） |
| `"corsRules": []` | CORS ルールが**削除される**（以降のレスポンスでは `"corsRules": null`） |
| `"quotas": {"maxObjects": null, "maxSize": null}` | quota が**解除される**（両方セットで指定する必要がある。API の説明にも明記） |
| `"websiteAccess": {...}` に `routingRules` を省略 | **既存の routingRules は保持される** |

この意味論は `Json { explicitNulls = false }`（`GarageJson`。Phase 1 で設定済み）と噛み合っている。`null` のフィールドは JSON に出ないため「省略 = 変更しない」に、`emptyList()` は `[]` として出るため「削除」になる。**この設定を変えると設定フォームが静かに壊れる。** Task 6 に往復のテストを置く。

### `corsRules` / `lifecycleRules` の実際の JSON

OpenAPI では `ID` / `Status` / `Prefix` / `Date` の型が空スキーマ（`{}`）で潰れているが、実機の応答は S3 の XML 由来の PascalCase で、値は素の文字列と整数である。

```json
{
  "corsRules": [
    {
      "ID": "allow-web",
      "MaxAgeSeconds": 3600,
      "AllowedOrigin": ["https://example.com"],
      "AllowedMethod": ["GET", "PUT"],
      "AllowedHeader": ["*"],
      "ExposeHeader": ["ETag"]
    }
  ],
  "lifecycleRules": [
    {
      "ID": "expire-tmp",
      "Status": "Enabled",
      "Filter": {"Prefix": "tmp/"},
      "Expiration": {"Days": 30},
      "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}
    },
    {
      "ID": "by-date",
      "Status": "Enabled",
      "Filter": {"And": {"Prefix": "logs/", "ObjectSizeGreaterThan": 1024}},
      "Expiration": {"Date": "2027-01-01T00:00:00Z"}
    }
  ]
}
```

`"AllowedHeader": []` も受け付けられ、そのまま返る。

### `aws-sdk-kotlin 1.8.31` で使える API（jar を javap で確認済み）

- `S3Client { region; credentialsProvider; endpointUrl: Url; forcePathStyle: Boolean? }`
- `InputStream.asByteStream(contentLength: Long?): ByteStream.SourceStream`（`aws.smithy.kotlin.runtime.content`）— **アップロードをメモリに載せずに流せる**
- `ByteStream.writeToOutputStream(OutputStream): Long` — **ダウンロードをメモリに載せずに流せる**
- `ListObjectsV2Response`: `contents: List<Object>?` / `commonPrefixes: List<CommonPrefix>?` / `nextContinuationToken: String?` / `keyCount: Int?`
- `Object`: `key` / `size: Long?` / `lastModified: aws.smithy.kotlin.runtime.time.Instant?` / `eTag: String?`
- `NoSuchKey`（`S3Exception` のサブクラス）でオブジェクト不在を判別できる

Ktor 3.5.2 側は `call.receiveStream(): InputStream` と `call.respondOutputStream(contentType, status) { }` が存在する（`ktor-server-core-jvm` を javap で確認済み）。この 2 つと上の 2 つを繋ぐのが Task 10 の中身である。

---

## Phase 2 が固定する設計判断

spec に書かれていない、実装中に迷いうる点を先に決める。**各タスクはこの決定に従うこと。**

| # | 判断 | 理由 |
|---|---|---|
| P2-1 | オブジェクトの取得・投入は**必ずサーバー経由**にする。署名付き URL でブラウザから直接 S3 を叩かせない | spec §5 のデータ経路がサーバー経由と定めている。トークンを URL に載せる方式は §6.2 のログ衛生に反する |
| P2-2 | アップロード / ダウンロードは**ブラウザの `fetch` を JS 側で完結させる**（`@JsFun`）。wasm とバイナリをやり取りしない | wasmJs と JS の間で `ByteArray` ↔ `Blob` を変換するより単純で、ファイル本体が wasm のメモリを通らない。`js("document.hidden")` の前例があり、外部ライブラリも増えない |
| P2-3 | S3 のマルチパートアップロードは実装しない | spec に記述が無い。単一 `PutObject` で足りる（YAGNI） |
| P2-4 | バケット設定は quotas / websiteAccess / CORS / lifecycle の 4 つを編集できる。**`routingRules` は対象外**（表示もしない） | ユーザー決定。routingRules は `websiteAccess` を省略なしで送っても保持されることを実機で確認済みのため、編集対象から外しても壊れない |
| P2-5 | `LifecycleFilter.And` は **1 段のみ**扱う。入れ子の `And` は読み取り時に無視せず保持するが、UI では編集させない | S3 の lifecycle filter は再帰的だが、実運用で 2 段以上は使わない。D6 のサブセット方針に沿う |
| P2-6 | バケットの **global alias は追加・削除できる。local alias は表示のみ** | local alias の追加には対象キーの accessKeyId が要り、UI が煩雑になる割に使途が薄い |
| P2-7 | キー詳細では `?showSecret=true` で secret access key を取得する「表示」ボタンを置く。既定では取得しない | Garage の `GetKeyInfo` が明示的なフラグを要求している以上、UI も明示的な操作にする。押すまでサーバーは secret を受け取らない |
| P2-8 | サイドバーの「Objects」は bucketId を持たないため `/buckets` に飛ばす | オブジェクトはバケットに属する。バケットを選ばせてから入る |
| P2-9 | `CleanupIncompleteUploads` は**バケット詳細に置く**。`olderThanSecs` の既定は 86400（24 時間） | spec §7 が endpoint を要求している以上、UI の消費者が要る（D6）。24 時間は「進行中のアップロードを誤って消さない」ための保守的な既定 |
| P2-10 | scope 縮退の e2e（制限トークンで S3 ブラウザが縮退する）は **Phase 2 に含める** | S3 の縮退は Phase 2 の主要な仕様（spec §6.4）であり、Phase 4 に先送りすると Phase 2 が自身の縮退を検証しないまま終わる |
| P2-11 | バケットのキー権限は「付与（`PUT`）」と「全剥奪（`DELETE`）」の 2 操作だけにする。部分的に減らす UI は作らない | Garage の `AllowBucketKey` / `DenyBucketKey` と 1:1 に保てる。減らしたいときは外してから必要な権限で付け直せば足り、spec §8.6 が求める確認ダイアログとも噛み合う |
| P2-12 | `DELETE` には本文を載せない。対象は常にパスかクエリで指定する | alias 削除やオブジェクト削除で本文つき DELETE を使うと、経路の途中で落とす実装に当たりうる。クエリなら曖昧さが無い |
| P2-13 | spec §8.7 の共通コンポーネントのうち Phase 2 で作るのは DataTable・確認ダイアログ・コピーボタン・空状態（ローディングとエラー表示は Phase 1 で作成済み）。**ステータスチップは作らない** | Phase 2 で状態を出すのはキーの失効と権限の一覧だけで、1 行のテキストで足りる。チップが要るのはノードやワーカーの状態を並べる Phase 3 であり、そこで実際の要件を見てから作るほうが形が決まる |

---

## File Structure（Phase 2 で触れるファイル）

**維持**（変更しない）
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `Dockerfile`, `compose.yaml`, `compose.ci.yaml`, `docker/garage.toml`, `.github/**`
- Phase 1 で作った `:shared` の `api/ProblemDetails.kt` / `api/Section.kt` / `api/Session.kt` / `api/Overview.kt`、`:server` の `garage/GarageAdminClient.kt` / `garage/TokenValidation.kt` / `plugins/CallLogging.kt` / `plugins/StaticFiles.kt`

**`:shared`**

| ファイル | 責務 |
|---|---|
| `model/garage/Bucket.kt` | バケットの一覧 / 詳細と、CORS・lifecycle・website のルール |
| `model/garage/AccessKey.kt` | アクセスキーの一覧 / 詳細 |
| `model/garage/ObjectInspection.kt` | `InspectObject` のレスポンス |
| `model/s3/StoredObject.kt` | S3 のオブジェクト一覧（フォルダとオブジェクト） |
| `api/StorageRequests.kt` | ブラウザ → サーバーのリクエスト DTO |
| `api/ProblemTypes.kt` | コンソール固有の problem type（URN） |
| `navigation/PercentEncoding.kt` | クエリ値の UTF-8 パーセントエンコード / デコード（手書き） |
| `navigation/Route.kt` | **変更**: buckets / keys / objects のルートとクエリ対応 |

**`:server`**

| ファイル | 責務 |
|---|---|
| `garage/BucketOperations.kt` | バケット系 operation への型付きアクセス |
| `garage/KeyOperations.kt` | キー系 operation への型付きアクセス |
| `api/BucketRoutes.kt` | `/api/buckets/**`（オブジェクト系を除く） |
| `api/KeyRoutes.kt` | `/api/keys/**` |
| `api/ObjectRoutes.kt` | `/api/buckets/{id}/objects/**` |
| `s3/S3Credentials.kt` | 導出した資格情報とバケット名 |
| `s3/SecretCache.kt` | `(SHA-256(token), bucketId)` → 資格情報の TTL 付きキャッシュ |
| `s3/S3CredentialResolver.kt` | キー選択規則とバケット名解決 |
| `s3/S3ObjectStore.kt` | AWS SDK 越しのオブジェクト操作 |
| `s3/S3Problems.kt` | S3 由来の失敗をコンソールの問題型に写す |
| `config/AppConfig.kt` | **変更**: `s3` セクション |
| `plugins/Di.kt` | **変更**: S3 まわりの登録 |
| `plugins/Routing.kt` | **変更**: 新しいルートの登録 |
| `plugins/StatusPages.kt` | **変更**: S3 例外の正規化 |
| `api/SessionRoutes.kt` | **変更**: ログアウトでキャッシュを破棄 |
| `src/main/resources/application.yaml` | **変更**: `garage.s3` の設定 |
| `build.gradle.kts` | **変更**: `aws-sdk-s3` の追加 |

**`:web`**

| ファイル | 責務 |
|---|---|
| `components/DataTable.kt` | ソートと絞り込みを持つ表 |
| `components/ConfirmDialog.kt` | 確認ダイアログ（名前のタイプ入力にも対応） |
| `components/CopyButton.kt` | クリップボードへのコピー |
| `components/StateViews.kt` | **変更**: 空状態と 403 表示を追加 |
| `api/ApiClient.kt` | **変更**: ステータス付きの失敗、JSON 本文の送信 |
| `screens/buckets/BucketsScreen.kt` | バケット一覧と作成 |
| `screens/buckets/BucketDetailScreen.kt` | バケット詳細（alias / キー権限 / 後始末 / 削除） |
| `screens/buckets/BucketSettingsForm.kt` | quotas / website / CORS / lifecycle の編集 |
| `screens/keys/KeysScreen.kt` | キー一覧・作成・インポート |
| `screens/keys/KeyDetailScreen.kt` | キー詳細（権限 / バケット権限 / secret 表示 / 削除） |
| `screens/objects/ObjectBrowserScreen.kt` | オブジェクトの一覧・転送・削除・InspectObject |
| `screens/objects/ObjectTransfer.kt` | ブラウザの `fetch` を使う転送（`@JsFun`） |
| `navigation/NavItem.kt` | **変更**: ストレージのグループを追加 |
| `App.kt` | **変更**: 新しい画面の接続 |
| `screens/overview/OverviewScreen.kt` | **変更**: ストレージ数値からのドリルダウン |

**e2e / dev 環境**

| ファイル | 責務 |
|---|---|
| `e2e/tests/buckets.spec.ts` | バケットの作成・参照・設定・削除 |
| `e2e/tests/keys.spec.ts` | キーの作成・参照・削除 |
| `e2e/tests/objects.spec.ts` | オブジェクトのアップロード・一覧・ダウンロード・削除、scope 縮退 |
| `e2e/tests/helpers.ts` | **変更**: 画面遷移と制限トークンのヘルパ |
| `docker/init-garage.sh` | **変更**: S3 資格情報の案内を削除し、scope 制限トークンを発行 |

---

## Task 1: `:shared` のバケットモデル

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/Bucket.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/BucketTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `BucketSummary` / `BucketLocalAlias` — `ListBuckets` の要素
  - `BucketInfo` / `BucketKey` / `BucketKeyPermissions` / `BucketQuotas` / `BucketWebsiteConfig` — `GetBucketInfo` のレスポンス
  - `CorsRule` / `LifecycleRule` / `LifecycleStatus` / `LifecycleFilter` / `LifecycleExpiration` / `AbortIncompleteMultipartUpload`

**ブランチ:** `phase2/1-shared`（Task 1–4 を 1 PR にする）

```bash
git switch -c phase2/1-shared
```

CORS と lifecycle のフィールド名は S3 の XML 由来で PascalCase である。Garage の他のフィールドは camelCase なので、ここだけ `@SerialName` を付ける。**「Phase 2 で先に確認した事実」の JSON をそのままテストの fixture にすること。**

- [ ] **Step 1: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/BucketTest.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun decodesBucketList() {
        val buckets = json.decodeFromString(
            ListSerializer(BucketSummary.serializer()),
            """
            [{"id":"b1","created":"2026-08-22T16:43:38.636Z","globalAliases":["dev-bucket"],
              "localAliases":[{"accessKeyId":"GK01","alias":"mine"}]}]
            """.trimIndent(),
        )

        assertEquals(1, buckets.size)
        assertEquals("dev-bucket", buckets.first().globalAliases.first())
        assertEquals("GK01", buckets.first().localAliases.first().accessKeyId)
        assertEquals("dev-bucket", buckets.first().displayName)
    }

    @Test
    fun decodesBucketInfo() {
        val info = json.decodeFromString<BucketInfo>(
            """
            {"id":"b1","created":"2026-08-22T16:43:38.636Z","globalAliases":["dev-bucket"],
             "websiteAccess":false,"keys":[
               {"accessKeyId":"GK01","name":"dev-key","bucketLocalAliases":[],
                "permissions":{"owner":true,"read":true,"write":true}}],
             "objects":3,"bytes":1024,"unfinishedUploads":1,"unfinishedMultipartUploads":1,
             "unfinishedMultipartUploadParts":2,"unfinishedMultipartUploadBytes":512,
             "quotas":{"maxObjects":null,"maxSize":null}}
            """.trimIndent(),
        )

        assertEquals("b1", info.id)
        assertEquals(3, info.objects)
        assertTrue(info.keys.first().permissions.owner)
        assertNull(info.quotas.maxObjects)
        assertNull(info.corsRules)
        assertNull(info.websiteConfig)
    }

    @Test
    fun decodesCorsAndLifecycleRules() {
        // 実機（Garage v2.3.0）から採取した表現。OpenAPI では ID / Status / Prefix の
        // 型が潰れているため、こちらを正とする。
        val info = json.decodeFromString<BucketInfo>(
            """
            {"id":"b1","globalAliases":[],"websiteAccess":true,
             "websiteConfig":{"indexDocument":"index.html","errorDocument":"error.html"},
             "keys":[],"objects":0,"bytes":0,"unfinishedUploads":0,
             "unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "corsRules":[{"ID":"allow-web","MaxAgeSeconds":3600,
               "AllowedOrigin":["https://example.com"],"AllowedMethod":["GET","PUT"],
               "AllowedHeader":["*"],"ExposeHeader":["ETag"]}],
             "lifecycleRules":[
               {"ID":"expire-tmp","Status":"Enabled","Filter":{"Prefix":"tmp/"},
                "Expiration":{"Days":30},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}},
               {"ID":"by-date","Status":"Enabled",
                "Filter":{"And":{"Prefix":"logs/","ObjectSizeGreaterThan":1024}},
                "Expiration":{"Date":"2027-01-01T00:00:00Z"}}]}
            """.trimIndent(),
        )

        val cors = info.corsRules?.single()
        assertEquals("allow-web", cors?.id)
        assertEquals(3600, cors?.maxAgeSeconds)
        assertEquals(listOf("GET", "PUT"), cors?.allowedMethods)
        assertEquals("index.html", info.websiteConfig?.indexDocument)

        val rules = info.lifecycleRules.orEmpty()
        assertEquals(LifecycleStatus.ENABLED, rules[0].status)
        assertEquals("tmp/", rules[0].filter?.prefix)
        assertEquals(30, rules[0].expiration?.days)
        assertEquals(7, rules[0].abortIncompleteMultipartUpload?.daysAfterInitiation)
        assertEquals(1024, rules[1].filter?.and?.objectSizeGreaterThan)
        assertEquals("2027-01-01T00:00:00Z", rules[1].expiration?.date)
    }

    @Test
    fun keepsPascalCaseWhenEncoding() {
        // Garage に送り返すときも S3 由来の名前でなければ受け付けられない
        val encoded = json.encodeToString(
            CorsRule(allowedOrigins = listOf("*"), allowedMethods = listOf("GET")),
        )

        assertTrue(encoded.contains("\"AllowedOrigin\""))
        assertTrue(encoded.contains("\"AllowedMethod\""))
        // explicitNulls = false のため、指定しなかった ID は出ない
        assertFalse(encoded.contains("\"ID\""))
    }

    @Test
    fun permissionRankOrdersOwnerFirst() {
        assertEquals(3, BucketKeyPermissions(owner = true, read = true, write = true).rank)
        assertEquals(2, BucketKeyPermissions(read = true, write = true).rank)
        assertEquals(1, BucketKeyPermissions(read = true).rank)
        assertEquals(0, BucketKeyPermissions(write = true).rank)
        assertEquals(0, BucketKeyPermissions().rank)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :shared:jvmTest --tests '*BucketTest*'`
Expected: FAIL（`BucketSummary` などが未定義でコンパイルできない）

- [ ] **Step 3: モデルを書く**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/Bucket.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `ListBuckets` の要素。 */
@Serializable
data class BucketSummary(
    val id: String,
    val globalAliases: List<String> = emptyList(),
    val localAliases: List<BucketLocalAlias> = emptyList(),
    val created: Instant? = null,
) {
    /** 画面に出す名前。alias が無いバケットは ID の先頭で代用する。 */
    val displayName: String
        get() = globalAliases.firstOrNull()
            ?: localAliases.firstOrNull()?.alias
            ?: id.take(12)
}

@Serializable
data class BucketLocalAlias(
    val accessKeyId: String,
    val alias: String,
)

/** `GetBucketInfo` のレスポンス。`CreateBucket` と `UpdateBucket` も同じ形を返す。 */
@Serializable
data class BucketInfo(
    val id: String,
    val globalAliases: List<String> = emptyList(),
    val websiteAccess: Boolean = false,
    val keys: List<BucketKey> = emptyList(),
    val objects: Long = 0,
    val bytes: Long = 0,
    val unfinishedUploads: Long = 0,
    val unfinishedMultipartUploads: Long = 0,
    val unfinishedMultipartUploadParts: Long = 0,
    val unfinishedMultipartUploadBytes: Long = 0,
    val quotas: BucketQuotas = BucketQuotas(),
    val created: Instant? = null,
    val websiteConfig: BucketWebsiteConfig? = null,
    /** 未設定なら null。空配列を送ると削除される（実機で確認済み）。 */
    val corsRules: List<CorsRule>? = null,
    val lifecycleRules: List<LifecycleRule>? = null,
) {
    val displayName: String get() = globalAliases.firstOrNull() ?: id.take(12)
}

/** そのバケットに権限を持つアクセスキー。 */
@Serializable
data class BucketKey(
    val accessKeyId: String,
    val name: String,
    val permissions: BucketKeyPermissions,
    val bucketLocalAliases: List<String> = emptyList(),
)

@Serializable
data class BucketKeyPermissions(
    val owner: Boolean = false,
    val read: Boolean = false,
    val write: Boolean = false,
) {
    /**
     * S3 資格情報を導出するときの優先度（spec §6.4 の owner > read+write > read）。
     *
     * 0 はオブジェクト操作に使えないことを表す。read が無いと一覧すらできないため、
     * write だけのキーも 0 とする。
     */
    val rank: Int
        get() = when {
            owner -> 3
            read && write -> 2
            read -> 1
            else -> 0
        }
}

@Serializable
data class BucketQuotas(
    val maxObjects: Long? = null,
    val maxSize: Long? = null,
)

/**
 * website 公開の設定。
 *
 * `routingRules` はコンソールの編集対象外のため持たない（P2-4）。
 * `UpdateBucket` に `routingRules` を含めなくても Garage 側は保持する
 * （実機で確認済み）ので、持たないことで既存設定を壊すことはない。
 */
@Serializable
data class BucketWebsiteConfig(
    val indexDocument: String,
    val errorDocument: String? = null,
)

/**
 * CORS ルール。
 *
 * フィールド名は S3 の XML 由来で PascalCase である。Garage は camelCase を
 * 受け付けないため `@SerialName` を外してはならない。
 */
@Serializable
data class CorsRule(
    @SerialName("AllowedOrigin") val allowedOrigins: List<String> = emptyList(),
    @SerialName("AllowedMethod") val allowedMethods: List<String> = emptyList(),
    @SerialName("AllowedHeader") val allowedHeaders: List<String> = emptyList(),
    @SerialName("ExposeHeader") val exposeHeaders: List<String> = emptyList(),
    @SerialName("MaxAgeSeconds") val maxAgeSeconds: Long? = null,
    @SerialName("ID") val id: String? = null,
)

@Serializable
enum class LifecycleStatus {
    @SerialName("Enabled")
    ENABLED,

    @SerialName("Disabled")
    DISABLED,
}

/** オブジェクトのライフサイクルルール。CORS と同じく PascalCase。 */
@Serializable
data class LifecycleRule(
    @SerialName("Status") val status: LifecycleStatus,
    @SerialName("ID") val id: String? = null,
    @SerialName("Filter") val filter: LifecycleFilter? = null,
    @SerialName("Expiration") val expiration: LifecycleExpiration? = null,
    @SerialName("AbortIncompleteMultipartUpload")
    val abortIncompleteMultipartUpload: AbortIncompleteMultipartUpload? = null,
)

/**
 * ルールの適用対象。
 *
 * [and] は S3 では再帰的だが、コンソールが編集するのは 1 段までとする（P2-5）。
 * 読み取りでは落とさずそのまま保持し、書き戻しでも壊さない。
 */
@Serializable
data class LifecycleFilter(
    @SerialName("Prefix") val prefix: String? = null,
    @SerialName("ObjectSizeGreaterThan") val objectSizeGreaterThan: Long? = null,
    @SerialName("ObjectSizeLessThan") val objectSizeLessThan: Long? = null,
    @SerialName("And") val and: LifecycleFilter? = null,
)

/** [days] と [date] は排他。Garage はどちらの形も返す（実機で確認済み）。 */
@Serializable
data class LifecycleExpiration(
    @SerialName("Days") val days: Long? = null,
    @SerialName("Date") val date: String? = null,
)

@Serializable
data class AbortIncompleteMultipartUpload(
    @SerialName("DaysAfterInitiation") val daysAfterInitiation: Long,
)
```

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :shared:jvmTest --tests '*BucketTest*'`
Expected: PASS

- [ ] **Step 5: wasmJs でも同じモデルが直列化できることを確認**

Run: `CHROME_BIN=$(which chromium || which google-chrome) ./gradlew :shared:wasmJsBrowserTest`
Expected: PASS

`CHROME_BIN` が無い環境では headless Chrome が見つからず失敗する。その場合はこの Step を飛ばし、CI（`on-pull-request`）の結果で確認する。

- [ ] **Step 6: コミット**

```bash
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/Bucket.kt shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/BucketTest.kt
git commit -m "feat(shared): バケットのモデルを追加"
```

---

## Task 2: `:shared` のアクセスキーモデル

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/AccessKey.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/AccessKeyTest.kt`

**Interfaces:**
- Consumes: `BucketKeyPermissions`（Task 1）
- Produces: `KeySummary` / `KeyInfo` / `KeyPermissions` / `KeyBucket`

`GetKeyInfo` のレスポンスは `secretAccessKey` を持つが、**これは `showSecretKey=true` を付けたときと、作成直後のレスポンスにだけ入る**。サーバーは既定でこのフラグを付けず、キー詳細の「表示」操作でのみ付ける（P2-7）。

- [ ] **Step 1: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/AccessKeyTest.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessKeyTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun decodesKeyList() {
        val keys = json.decodeFromString(
            ListSerializer(KeySummary.serializer()),
            """
            [{"id":"GK01","name":"dev-key","expired":false,
              "created":"2026-08-22T16:43:38.636Z","expiration":null}]
            """.trimIndent(),
        )

        assertEquals("GK01", keys.single().id)
        assertEquals("dev-key", keys.single().name)
        assertNull(keys.single().expiration)
    }

    @Test
    fun decodesKeyInfoWithoutSecret() {
        val key = json.decodeFromString<KeyInfo>(
            """
            {"accessKeyId":"GK01","name":"dev-key","expired":false,
             "permissions":{"createBucket":true},
             "buckets":[{"id":"b1","globalAliases":["dev-bucket"],"localAliases":[],
                         "permissions":{"owner":true,"read":true,"write":true}}]}
            """.trimIndent(),
        )

        assertEquals("GK01", key.accessKeyId)
        assertTrue(key.permissions.createBucket)
        assertEquals("dev-bucket", key.buckets.single().displayName)
        assertTrue(key.buckets.single().permissions.owner)
        assertNull(key.secretAccessKey)
    }

    @Test
    fun decodesKeyInfoWithSecret() {
        val key = json.decodeFromString<KeyInfo>(
            """
            {"accessKeyId":"GK01","name":"dev-key","expired":false,
             "secretAccessKey":"s3cr3t","permissions":{},"buckets":[]}
            """.trimIndent(),
        )

        assertEquals("s3cr3t", key.secretAccessKey)
        assertEquals(false, key.permissions.createBucket)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :shared:jvmTest --tests '*AccessKeyTest*'`
Expected: FAIL（`KeySummary` が未定義）

- [ ] **Step 3: モデルを書く**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/AccessKey.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** `ListKeys` の要素。 */
@Serializable
data class KeySummary(
    val id: String,
    val name: String,
    val expired: Boolean,
    val created: Instant? = null,
    val expiration: Instant? = null,
)

/**
 * `GetKeyInfo` のレスポンス。`CreateKey` / `ImportKey` / `UpdateKey` も同じ形を返す。
 *
 * [secretAccessKey] は `showSecretKey=true` を付けたとき、および作成直後の
 * レスポンスにだけ入る。サーバーはこの値をキャッシュにもログにも残さない。
 */
@Serializable
data class KeyInfo(
    val accessKeyId: String,
    val name: String,
    val expired: Boolean,
    val permissions: KeyPermissions = KeyPermissions(),
    val buckets: List<KeyBucket> = emptyList(),
    val created: Instant? = null,
    val expiration: Instant? = null,
    val secretAccessKey: String? = null,
)

@Serializable
data class KeyPermissions(
    val createBucket: Boolean = false,
)

/** そのキーが権限を持つバケット。 */
@Serializable
data class KeyBucket(
    val id: String,
    val globalAliases: List<String> = emptyList(),
    val localAliases: List<String> = emptyList(),
    val permissions: BucketKeyPermissions,
) {
    val displayName: String
        get() = globalAliases.firstOrNull() ?: localAliases.firstOrNull() ?: id.take(12)
}
```

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :shared:jvmTest --tests '*AccessKeyTest*'`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/AccessKey.kt shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/AccessKeyTest.kt
git commit -m "feat(shared): アクセスキーのモデルを追加"
```

---

## Task 3: `:shared` のオブジェクトモデルとリクエスト DTO

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/s3/StoredObject.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ObjectInspection.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/StorageRequests.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/ProblemTypes.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/s3/StoredObjectTest.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/StorageRequestsTest.kt`

**Interfaces:**
- Consumes: `BucketKeyPermissions` / `BucketQuotas` / `CorsRule` / `LifecycleRule`（Task 1）
- Produces:
  - `ObjectListing` / `StoredObject` / `parentPrefix()`（`model/s3/`）
  - `ObjectInspection` / `ObjectVersion` / `ObjectBlock`（`model/garage/`）
  - `CreateBucketRequest` / `UpdateBucketRequest` / `WebsiteAccessRequest` / `BucketAliasRequest` / `BucketKeyPermissionRequest` / `CleanupUploadsRequest` / `CleanupUploadsResult` / `DEFAULT_CLEANUP_AGE_SECS` / `CreateKeyRequest` / `ImportKeyRequest` / `UpdateKeyRequest`
  - `ProblemTypes.BUCKET_NOT_ADDRESSABLE` / `ProblemTypes.NO_USABLE_KEY`

`model/s3/` は spec §9 のポータブルな側であり、Garage 固有の型を参照しない。`ObjectInspection` は Garage の `InspectObject` 専用なので `model/garage/` に置く。この線引きは乗り換え時に捨てる範囲を明確にするためのもので、混ぜてはならない。

- [ ] **Step 1: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/s3/StoredObjectTest.kt`

```kotlin
package net.brightroom.garage.shared.model.s3

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredObjectTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun roundTripsListing() {
        val listing = ObjectListing(
            prefix = "logs/",
            folders = listOf("logs/2026/"),
            objects = listOf(StoredObject(key = "logs/app.log", size = 1024, etag = "\"abc\"")),
            nextToken = "tok",
            keyName = "dev-key",
        )

        val decoded = json.decodeFromString<ObjectListing>(json.encodeToString(listing))

        assertEquals(listing, decoded)
    }

    @Test
    fun exposesNameRelativeToPrefix() {
        val listing = ObjectListing(
            prefix = "logs/2026/",
            folders = listOf("logs/2026/08/"),
            objects = listOf(StoredObject(key = "logs/2026/app.log", size = 1)),
        )

        assertEquals(listOf("08/"), listing.folderNames)
        assertEquals("app.log", listing.objects.single().nameIn("logs/2026/"))
    }

    @Test
    fun emptyListingIsReportedAsEmpty() {
        assertTrue(ObjectListing(prefix = "").isEmpty)
    }

    @Test
    fun parentPrefixWalksUpOneLevel() {
        assertEquals("logs/", parentPrefix("logs/2026/"))
        assertEquals("", parentPrefix("logs/"))
        assertNull(parentPrefix(""))
    }
}
```

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/StorageRequestsTest.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.model.garage.BucketQuotas
import net.brightroom.garage.shared.model.garage.CorsRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageRequestsTest {
    // サーバーと同じ設定。省略と空配列の差が UpdateBucket の意味論そのものになる。
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun omitsUntouchedFields() {
        val encoded = json.encodeToString(
            UpdateBucketRequest(quotas = BucketQuotas(maxObjects = 10, maxSize = 20)),
        )

        assertTrue(encoded.contains("\"quotas\""))
        assertFalse(encoded.contains("corsRules"))
        assertFalse(encoded.contains("lifecycleRules"))
        assertFalse(encoded.contains("websiteAccess"))
    }

    @Test
    fun emptyListMeansDeleteRules() {
        val encoded = json.encodeToString(UpdateBucketRequest(corsRules = emptyList()))

        assertEquals("""{"corsRules":[]}""", encoded)
    }

    @Test
    fun explicitNullQuotasClearsThem() {
        // Garage は maxSize と maxObjects の両方を null にしたときだけ quota を解除する。
        // explicitNulls = false でも quotas 自体は出る（値が非 null のオブジェクトのため）。
        val encoded = json.encodeToString(UpdateBucketRequest(quotas = BucketQuotas()))

        assertEquals("""{"quotas":{}}""", encoded)
    }

    @Test
    fun encodesCorsRulesWithS3Names() {
        val encoded = json.encodeToString(
            UpdateBucketRequest(
                corsRules = listOf(CorsRule(allowedOrigins = listOf("*"), allowedMethods = listOf("GET"))),
            ),
        )

        assertTrue(encoded.contains("\"AllowedOrigin\":[\"*\"]"))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :shared:jvmTest --tests '*StoredObjectTest*' --tests '*StorageRequestsTest*'`
Expected: FAIL（型が未定義）

- [ ] **Step 3: S3 のオブジェクトモデルを書く**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/s3/StoredObject.kt`

```kotlin
package net.brightroom.garage.shared.model.s3

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * `ListObjectsV2` の 1 ページ。
 *
 * S3 に階層は無いが、`delimiter = "/"` を渡すと共通接頭辞がフォルダのように返る。
 * コンソールはこれをフォルダとして描画する。
 *
 * @param prefix この一覧が対象にしている接頭辞。ルートは空文字。
 * @param folders 共通接頭辞。[prefix] を含んだフルパスで、末尾は `/`。
 * @param nextToken 続きがある場合の継続トークン。無ければ null。
 */
@Serializable
data class ObjectListing(
    val prefix: String,
    val folders: List<String> = emptyList(),
    val objects: List<StoredObject> = emptyList(),
    val nextToken: String? = null,
    /**
     * この一覧を取得したアクセスキーの名前。
     *
     * 「どのキーで見ているか」を画面に出すために運ぶ（spec §6.4 の 6）。
     * accessKeyId と secret は載せない。
     */
    val keyName: String? = null,
) {
    /** [prefix] を取り除いたフォルダ名。画面に出すのはこちら。 */
    val folderNames: List<String> get() = folders.map { it.removePrefix(prefix) }

    val isEmpty: Boolean get() = folders.isEmpty() && objects.isEmpty()
}

@Serializable
data class StoredObject(
    val key: String,
    val size: Long,
    val lastModified: Instant? = null,
    val etag: String? = null,
) {
    /** [prefix] 配下での表示名。 */
    fun nameIn(prefix: String): String = key.removePrefix(prefix)
}

/** 1 階層上の接頭辞。ルート（空文字）には親が無いため null を返す。 */
fun parentPrefix(prefix: String): String? {
    if (prefix.isEmpty()) return null

    val trimmed = prefix.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')

    return if (cut < 0) "" else trimmed.substring(0, cut + 1)
}
```

- [ ] **Step 4: `InspectObject` のモデルを書く**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ObjectInspection.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * `InspectObject` のレスポンス。
 *
 * S3 では見えない内部表現（バージョン、ブロック、インライン格納か）を出す。
 * オブジェクトブラウザの「詳細」から開く。
 */
@Serializable
data class ObjectInspection(
    val bucketId: String,
    val key: String,
    val versions: List<ObjectVersion> = emptyList(),
)

@Serializable
data class ObjectVersion(
    val uuid: String,
    val timestamp: Instant,
    val encrypted: Boolean,
    val uploading: Boolean,
    val aborted: Boolean,
    val deleteMarker: Boolean,
    val inline: Boolean,
    val size: Long? = null,
    val etag: String? = null,
    /** HTTP ヘッダの組。Garage は `[["content-type","text/plain"], ...]` の形で返す。 */
    val headers: List<List<String>> = emptyList(),
    val blocks: List<ObjectBlock> = emptyList(),
)

@Serializable
data class ObjectBlock(
    val partNumber: Long,
    val offset: Long,
    val hash: String,
    val size: Long,
)
```

- [ ] **Step 5: リクエスト DTO と problem type を書く**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/StorageRequests.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions
import net.brightroom.garage.shared.model.garage.BucketQuotas
import net.brightroom.garage.shared.model.garage.CorsRule
import net.brightroom.garage.shared.model.garage.LifecycleRule

/**
 * ブラウザ → サーバーのリクエスト。
 *
 * サーバーはこれを型で受け、Garage の operation ごとの形に写して転送する。
 * 生の JSON を素通しさせないための層である（spec D5）。
 */
@Serializable
data class CreateBucketRequest(
    /** null なら alias 無しで作る。 */
    val globalAlias: String? = null,
)

/**
 * バケットの設定変更。
 *
 * **null と空リストの違いに意味がある**（実機で確認済み）。
 * - null（＝ JSON に出ない）: そのフィールドは変更しない
 * - `emptyList()`（＝ `[]`）: そのルールを削除する
 * - `BucketQuotas(null, null)`: quota を解除する
 *
 * サーバーとブラウザの双方で `Json { explicitNulls = false }` を使うことが前提。
 */
@Serializable
data class UpdateBucketRequest(
    val quotas: BucketQuotas? = null,
    val websiteAccess: WebsiteAccessRequest? = null,
    val corsRules: List<CorsRule>? = null,
    val lifecycleRules: List<LifecycleRule>? = null,
)

/**
 * website 公開の設定。
 *
 * Garage は `enabled = true` のとき [indexDocument] を必須とし、
 * `enabled = false` のときはどちらのドキュメントも指定してはならない。
 */
@Serializable
data class WebsiteAccessRequest(
    val enabled: Boolean,
    val indexDocument: String? = null,
    val errorDocument: String? = null,
)

@Serializable
data class BucketAliasRequest(
    val alias: String,
)

@Serializable
data class BucketKeyPermissionRequest(
    val permissions: BucketKeyPermissions,
)

@Serializable
data class CleanupUploadsRequest(
    val olderThanSecs: Long = DEFAULT_CLEANUP_AGE_SECS,
)

/** 24 時間。進行中のアップロードを巻き込まないための既定値（P2-9）。 */
const val DEFAULT_CLEANUP_AGE_SECS: Long = 86_400

/** 後始末の結果。画面に「N 件を削除しました」と出すためだけに持つ。 */
@Serializable
data class CleanupUploadsResult(
    val uploadsDeleted: Long,
)

@Serializable
data class CreateKeyRequest(
    val name: String,
    val allowCreateBucket: Boolean = false,
    val expiration: Instant? = null,
)

@Serializable
data class ImportKeyRequest(
    val name: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)

/**
 * キーの更新。
 *
 * [allowCreateBucket] が null なら権限は変更しない。
 * [neverExpires] が true なら期限を解除し、[expiration] は無視される。
 */
@Serializable
data class UpdateKeyRequest(
    val name: String? = null,
    val allowCreateBucket: Boolean? = null,
    val expiration: Instant? = null,
    val neverExpires: Boolean = false,
)
```

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/ProblemTypes.kt`

```kotlin
package net.brightroom.garage.shared.api

/**
 * コンソール固有の問題型（RFC 9457 の `type`）。
 *
 * spec §7.1 は「コンソール固有の問題型を定義する必要が生じた時点で `type` に
 * URI を入れる」と定めている。S3 ブラウザの 2 つの縮退は、どちらも HTTP
 * ステータスだけでは区別できず、画面に出す案内も導線も異なるため、ここで型を持つ。
 *
 * 解決可能な URL を用意する予定は無いため URN を使う。RFC 9457 は type が
 * 解決できることを求めていない。
 *
 * `type` を持つ problem では `title` は「その status の推奨理由句」ではなく
 * 問題型の要約になる（`about:blank` 以外に RFC 9457 が求める形）。
 */
object ProblemTypes {

    /** global alias も local alias も無く、S3 API でアドレスできないバケット（spec §6.5）。 */
    const val BUCKET_NOT_ADDRESSABLE: String = "urn:garage-admin-console:problem:bucket-not-addressable"

    /** そのバケットに read 以上の権限を持つアクセスキーが 1 つも無い（spec §6.4）。 */
    const val NO_USABLE_KEY: String = "urn:garage-admin-console:problem:no-usable-key"
}
```

- [ ] **Step 6: テストが通ることを確認**

Run: `./gradlew :shared:jvmTest --tests '*StoredObjectTest*' --tests '*StorageRequestsTest*'`
Expected: PASS

- [ ] **Step 7: コミット**

```bash
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/s3/ shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ObjectInspection.kt shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/StorageRequests.kt shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/ProblemTypes.kt shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/s3/ shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/StorageRequestsTest.kt
git commit -m "feat(shared): オブジェクトのモデルとストレージ操作の DTO を追加"
```

---

## Task 4: ルートの拡張とクエリのパーセントエンコード

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/PercentEncoding.kt`
- Modify: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/Route.kt`
- Create: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/PercentEncodingTest.kt`
- Modify: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/RouteTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `percentEncode(value: String): String` / `percentDecode(value: String): String`
  - `Route.Buckets` / `Route.BucketDetail(id)` / `Route.Keys` / `Route.KeyDetail(id)` / `Route.Objects(bucketId, prefix)`

Phase 1 の `Route.parse` はクエリを捨てていた（`normalize` が `?` 以降を切る）。`/objects/{bucketId}?prefix=` はクエリに意味があるため、パースを書き直す。

**`:shared` に ktor-http は無い**（`:server` と `:web` だけが持つ）。プレフィックスにはスペース・スラッシュ・日本語が入るため、UTF-8 のパーセントエンコードを手書きする。`+` はスペースとして扱わない（それは form-urlencoded の規則であり、History API の URL には当てはまらない）。スペースは `%20` にする。

既存テスト `unknownPathBecomesNotFound` は `/buckets` が `NotFound` になることを期待している。このタスクで `/buckets` は実在のルートになるため、**そのケースは別の未知パスに差し替える**。

- [ ] **Step 1: パーセントエンコードの失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/PercentEncodingTest.kt`

```kotlin
package net.brightroom.garage.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class PercentEncodingTest {

    @Test
    fun leavesUnreservedCharactersAlone() {
        assertEquals("abcXYZ-09_.~", percentEncode("abcXYZ-09_.~"))
    }

    @Test
    fun encodesReservedCharacters() {
        assertEquals("logs%2F2026%2F", percentEncode("logs/2026/"))
        assertEquals("a%20b", percentEncode("a b"))
        assertEquals("a%2Bb%26c%3Dd", percentEncode("a+b&c=d"))
        assertEquals("100%25", percentEncode("100%"))
    }

    @Test
    fun encodesMultibyteAsUtf8() {
        // 「あ」は U+3042 → UTF-8 で E3 81 82
        assertEquals("%E3%81%82", percentEncode("あ"))
    }

    @Test
    fun roundTripsEveryKindOfValue() {
        listOf(
            "",
            "logs/2026/",
            "日本語 フォルダ/",
            "a+b&c=d",
            "100%",
            "emoji-🗄️/",
        ).forEach { value ->
            assertEquals(value, percentDecode(percentEncode(value)))
        }
    }

    @Test
    fun decodesLowercaseHex() {
        assertEquals("あ", percentDecode("%e3%81%82"))
    }

    @Test
    fun keepsPlusAsIs() {
        // form-urlencoded ではないので + はスペースにしない
        assertEquals("a+b", percentDecode("a+b"))
    }

    @Test
    fun leavesBrokenEscapesLiteral() {
        // URL は外部入力である。壊れていても例外を投げず、読めるものだけ読む
        assertEquals("%zz", percentDecode("%zz"))
        assertEquals("ab%", percentDecode("ab%"))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :shared:jvmTest --tests '*PercentEncodingTest*'`
Expected: FAIL（`percentEncode` が未定義）

- [ ] **Step 3: パーセントエンコードを書く**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/PercentEncoding.kt`

```kotlin
package net.brightroom.garage.shared.navigation

/**
 * URL のクエリ値のパーセントエンコード（RFC 3986）。
 *
 * `:shared` は ktor-http を持たないため手書きする。オブジェクトの接頭辞には
 * スラッシュ・スペース・日本語が入るため、UTF-8 バイト列に対して行う。
 *
 * `+` はスペースとして扱わない。それは form-urlencoded の規則であり、
 * History API が扱う URL には当てはまらない。スペースは `%20` になる。
 */

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
private const val HEX = "0123456789ABCDEF"

fun percentEncode(value: String): String {
    val builder = StringBuilder(value.length)

    value.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xFF
        val char = code.toChar()

        if (code < 0x80 && char in UNRESERVED) {
            builder.append(char)
        } else {
            builder.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }

    return builder.toString()
}

fun percentDecode(value: String): String {
    if ('%' !in value) return value

    val bytes = ArrayList<Byte>(value.length)
    var index = 0

    while (index < value.length) {
        val char = value[index]
        val high = if (char == '%') hexDigit(value.getOrNull(index + 1)) else null
        val low = if (high != null) hexDigit(value.getOrNull(index + 2)) else null

        if (high != null && low != null) {
            bytes.add(((high shl 4) or low).toByte())
            index += 3
        } else {
            // 壊れたエスケープはリテラルとして残す
            char.toString().encodeToByteArray().forEach(bytes::add)
            index++
        }
    }

    return bytes.toByteArray().decodeToString()
}

private fun hexDigit(char: Char?): Int? = when (char) {
    null -> null
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> null
}
```

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :shared:jvmTest --tests '*PercentEncodingTest*'`
Expected: PASS

- [ ] **Step 5: ルートの失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/RouteTest.kt` の以下を差し替え・追加する。既存の他のテストはそのまま残す。

```kotlin
    @Test
    fun unknownPathBecomesNotFound() {
        assertEquals(Route.NotFound("/nope"), Route.parse("/nope"))
        assertEquals(Route.NotFound("/nope/deep"), Route.parse("/nope/deep"))
        // 実在するルートでもセグメントが多すぎれば未知として扱う
        assertEquals(Route.NotFound("/buckets/b1/extra"), Route.parse("/buckets/b1/extra"))
    }

    @Test
    fun parsesStorageRoutes() {
        assertEquals(Route.Buckets, Route.parse("/buckets"))
        assertEquals(Route.BucketDetail("b1"), Route.parse("/buckets/b1"))
        assertEquals(Route.Keys, Route.parse("/keys"))
        assertEquals(Route.KeyDetail("GK01"), Route.parse("/keys/GK01"))
        assertEquals(Route.Objects("b1"), Route.parse("/objects/b1"))
    }

    @Test
    fun parsesObjectPrefixFromQuery() {
        assertEquals(Route.Objects("b1", "logs/"), Route.parse("/objects/b1?prefix=logs%2F"))
        assertEquals(
            Route.Objects("b1", "日本語 フォルダ/"),
            Route.parse("/objects/b1?prefix=%E6%97%A5%E6%9C%AC%E8%AA%9E%20%E3%83%95%E3%82%A9%E3%83%AB%E3%83%80%2F"),
        )
        // 他のクエリが混ざっていても prefix を取り出せる
        assertEquals(Route.Objects("b1", "a/"), Route.parse("/objects/b1?x=1&prefix=a%2F&y=2"))
        // prefix が無ければルート直下
        assertEquals(Route.Objects("b1", ""), Route.parse("/objects/b1?other=1"))
    }

    @Test
    fun buildsObjectPathWithEncodedPrefix() {
        assertEquals("/objects/b1", Route.Objects("b1").path)
        assertEquals("/objects/b1?prefix=logs%2F", Route.Objects("b1", "logs/").path)
    }

    @Test
    fun parsingCanonicalPathIsStableForEveryRoute() {
        listOf(
            Route.Overview,
            Route.Login,
            Route.Buckets,
            Route.BucketDetail("b1"),
            Route.Keys,
            Route.KeyDetail("GK01"),
            Route.Objects("b1"),
            Route.Objects("b1", "logs/2026/"),
            Route.Objects("b1", "日本語 フォルダ/"),
        ).forEach { route ->
            assertEquals(route, Route.parse(route.path))
        }
    }
```

既存の `ignoresQueryAndFragment` は名前が実態と合わなくなるため、次のように書き換える。

```kotlin
    @Test
    fun ignoresFragmentAndIrrelevantQuery() {
        assertEquals(Route.Login, Route.parse("/login?next=%2F"))
        assertEquals(Route.Login, Route.parse("/login#section"))
        assertEquals(Route.Overview, Route.parse("/?refresh=1"))
    }
```

- [ ] **Step 6: テストが失敗することを確認**

Run: `./gradlew :shared:jvmTest --tests '*RouteTest*'`
Expected: FAIL（`Route.Buckets` が未定義）

- [ ] **Step 7: ルートを書き換える**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/Route.kt` を次の内容で置き換える。

```kotlin
package net.brightroom.garage.shared.navigation

/**
 * URL とスクリーンの対応。
 *
 * ブラウザの History API と組み合わせて使うが、この型自体は純粋な変換であり
 * ブラウザ API に依存しない。
 */
sealed interface Route {

    /** この画面を指す正規の URL パス。クエリを持つ画面はそれも含む。 */
    val path: String

    data object Overview : Route {
        override val path: String = "/"
    }

    data object Login : Route {
        override val path: String = "/login"
    }

    data object Buckets : Route {
        override val path: String = "/buckets"
    }

    data class BucketDetail(val id: String) : Route {
        override val path: String get() = "/buckets/$id"
    }

    data object Keys : Route {
        override val path: String = "/keys"
    }

    data class KeyDetail(val id: String) : Route {
        override val path: String get() = "/keys/$id"
    }

    /**
     * オブジェクトブラウザ。
     *
     * @param prefix 表示中のフォルダ。ルート直下は空文字。URL のクエリに載る。
     */
    data class Objects(val bucketId: String, val prefix: String = "") : Route {
        override val path: String
            get() = if (prefix.isEmpty()) {
                "/objects/$bucketId"
            } else {
                "/objects/$bucketId?prefix=${percentEncode(prefix)}"
            }
    }

    data class NotFound(val requested: String) : Route {
        override val path: String get() = requested
    }

    companion object {

        fun parse(rawPath: String): Route {
            val withoutFragment = rawPath.substringBefore('#')
            val path = normalize(withoutFragment.substringBefore('?'))
            val query = withoutFragment.substringAfter('?', "")
            val segments = path.split('/').filter { it.isNotEmpty() }

            return when {
                segments.isEmpty() -> Overview
                segments.size == 1 && segments[0] == "login" -> Login
                segments.size == 1 && segments[0] == "buckets" -> Buckets
                segments.size == 2 && segments[0] == "buckets" -> BucketDetail(segments[1])
                segments.size == 1 && segments[0] == "keys" -> Keys
                segments.size == 2 && segments[0] == "keys" -> KeyDetail(segments[1])
                segments.size == 2 && segments[0] == "objects" ->
                    Objects(segments[1], queryValue(query, "prefix").orEmpty())

                else -> NotFound(path)
            }
        }

        /** 末尾スラッシュを取り除く。ルートは空文字にそろえる。 */
        private fun normalize(rawPath: String): String {
            val trimmed = rawPath.trimEnd('/')

            return if (trimmed == "/") "" else trimmed
        }

        private fun queryValue(query: String, name: String): String? = query
            .split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let(::percentDecode)
    }
}
```

- [ ] **Step 8: テストが通ることを確認**

Run: `./gradlew :shared:jvmTest`
Expected: PASS（既存のテストも含めてすべて）

- [ ] **Step 9: `:web` がまだコンパイルできることを確認**

`App.kt` は `Route.Overview` / `Route.Login` / `Route.NotFound` だけを分岐しており、`when` は `else` を持たないため、ルートの追加でコンパイルエラーになる。**このタスクでは画面を作らないので、追加したルートは Phase 1 の概況にフォールバックさせておく**（Task 13 以降で本来の画面に差し替える）。

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt` の `AuthenticatedApp` を次のようにする。

```kotlin
@Composable
private fun AuthenticatedApp(router: RouterState) {
    AppScaffold(router) {
        when (val route = router.current) {
            Route.Overview -> OverviewScreen()
            Route.Login -> OverviewScreen()         // ログイン済みで /login に来たら概況を出す
            is Route.NotFound -> ErrorView("画面が見つかりません: ${route.path}")

            // Task 13 以降で各画面に差し替える
            Route.Buckets, is Route.BucketDetail,
            Route.Keys, is Route.KeyDetail,
            is Route.Objects,
            -> OverviewScreen()
        }
    }
}
```

Run: `./gradlew :web:compileKotlinWasmJs`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: コミット**

```bash
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/ shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/ web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt
git commit -m "feat(shared): ストレージ画面のルートとクエリの解析を追加"
```

- [ ] **Step 11: PR を出す**

```bash
git push -u origin phase2/1-shared
gh pr create --title "feat(shared): バケット・キー・オブジェクトのモデルとルートを追加" --body "Phase 2 の :shared。バケット / キー / S3 オブジェクトのモデル、ストレージ操作のリクエスト DTO、コンソール固有の problem type、クエリ対応のルートを追加する。

CORS と lifecycle の JSON 表現は OpenAPI では型が潰れているため、Garage v2.3.0 の実機から採取した応答を fixture にしている。

計画: docs/superpowers/plans/2026-08-23-rebuild-phase2-storage.md の Task 1-4"
```

---

## Task 5: Garage クライアントのバケット operation

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/BucketOperations.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/garage/BucketOperationsTest.kt`

**Interfaces:**
- Consumes: `GarageAdminClient` / `garageBody` / `garageBodyWith` / `requireSuccess`（Phase 1）、Task 1・3 のモデルと DTO
- Produces:
  - `GarageAdminClient.listBuckets(token): List<BucketSummary>`
  - `GarageAdminClient.getBucketInfo(token, id): BucketInfo`
  - `GarageAdminClient.createBucket(token, request): BucketInfo`
  - `GarageAdminClient.updateBucket(token, id, request): BucketInfo`
  - `GarageAdminClient.deleteBucket(token, id)`
  - `GarageAdminClient.addBucketAlias(token, bucketId, alias): BucketInfo`
  - `GarageAdminClient.removeBucketAlias(token, bucketId, alias): BucketInfo`
  - `GarageAdminClient.allowBucketKey(token, bucketId, keyId, permissions): BucketInfo`
  - `GarageAdminClient.denyBucketKey(token, bucketId, keyId, permissions): BucketInfo`
  - `GarageAdminClient.cleanupIncompleteUploads(token, bucketId, olderThanSecs): Long`
  - `GarageAdminClient.inspectObject(token, bucketId, key): ObjectInspection`

**ブランチ:** `phase2/2-server-garage`（Task 5–7 を 1 PR にする）

```bash
git switch main
git pull
git switch -c phase2/2-server-garage
```

Garage 側の呼び出し規約（OpenAPI から確認済み）:

| operation | メソッド | パラメータ | 本文 |
|---|---|---|---|
| `ListBuckets` | GET | — | — |
| `GetBucketInfo` | GET | `id` | — |
| `CreateBucket` | POST | — | `{"globalAlias": ...}` |
| `UpdateBucket` | POST | `id` | `UpdateBucketRequest` と同じ形 |
| `DeleteBucket` | POST | `id` | — |
| `AddBucketAlias` / `RemoveBucketAlias` | POST | — | `{"bucketId","globalAlias"}` |
| `AllowBucketKey` / `DenyBucketKey` | POST | — | `{"bucketId","accessKeyId","permissions"}` |
| `CleanupIncompleteUploads` | POST | — | `{"bucketId","olderThanSecs"}` |
| `InspectObject` | GET | `bucketId`, `key` | — |

`UpdateBucketRequest`（`:shared`）は Garage の `UpdateBucketRequestBody` と同じ形なので、そのまま `encodeToJsonElement` して送れる。**変換関数を書かないこと。**

- [ ] **Step 1: 失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/garage/BucketOperationsTest.kt`

```kotlin
package net.brightroom.garage.server.garage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.brightroom.garage.shared.api.CreateBucketRequest
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions
import net.brightroom.garage.shared.model.garage.BucketQuotas
import net.brightroom.garage.shared.model.garage.CorsRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketOperationsTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private val bucketInfoBody = """
        {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"keys":[],
         "objects":0,"bytes":0,"unfinishedUploads":0,"unfinishedMultipartUploads":0,
         "unfinishedMultipartUploadParts":0,"unfinishedMultipartUploadBytes":0,"quotas":{}}
    """.trimIndent()

    /** 送られたリクエストを記録しつつ、固定の応答を返すクライアント。 */
    private class Recorder {
        val requests = mutableListOf<HttpRequestData>()
        var body: String = ""

        fun client(response: String, status: HttpStatusCode = HttpStatusCode.OK): GarageAdminClient {
            val engine = MockEngine { request ->
                requests += request
                body = (request.body as? TextContent)?.text.orEmpty()
                respond(
                    response,
                    status,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            return GarageAdminClient("http://garage.test:3903", engine)
        }
    }

    @Test
    fun listsBuckets() = runTest {
        val recorder = Recorder()
        val client = recorder.client(
            """[{"id":"b1","globalAliases":["dev-bucket"],"localAliases":[]}]""",
        )

        val buckets = client.listBuckets("tok")

        assertEquals("dev-bucket", buckets.single().displayName)
        assertTrue(recorder.requests.single().url.encodedPath.endsWith("/v2/ListBuckets"))
    }

    @Test
    fun getsBucketInfoById() = runTest {
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        val info = client.getBucketInfo("tok", "b1")

        assertEquals("b1", info.id)
        assertEquals("b1", recorder.requests.single().url.parameters["id"])
    }

    @Test
    fun createsBucketWithGlobalAlias() = runTest {
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        client.createBucket("tok", CreateBucketRequest(globalAlias = "new-bucket"))

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertEquals("new-bucket", sent["globalAlias"]?.toString()?.trim('"'))
    }

    @Test
    fun createsBucketWithoutAlias() = runTest {
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        client.createBucket("tok", CreateBucketRequest(globalAlias = null))

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertNull(sent["globalAlias"])
    }

    @Test
    fun updateBucketOmitsUntouchedFields() = runTest {
        // 省略したフィールドは Garage 側で変更されない。空配列は削除を意味する。
        // この差が消えると設定フォームが他の設定を巻き込んで壊す。
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        client.updateBucket(
            "tok",
            "b1",
            UpdateBucketRequest(
                quotas = BucketQuotas(maxObjects = 10, maxSize = 20),
                corsRules = emptyList(),
            ),
        )

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertTrue(sent.containsKey("quotas"))
        assertEquals("[]", sent["corsRules"]?.toString())
        assertNull(sent["lifecycleRules"])
        assertNull(sent["websiteAccess"])
        assertEquals("b1", recorder.requests.single().url.parameters["id"])
    }

    @Test
    fun updateBucketKeepsS3RuleNames() = runTest {
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        client.updateBucket(
            "tok",
            "b1",
            UpdateBucketRequest(
                corsRules = listOf(CorsRule(allowedOrigins = listOf("*"), allowedMethods = listOf("GET"))),
            ),
        )

        assertTrue(recorder.body.contains("\"AllowedOrigin\""))
    }

    @Test
    fun addsAndRemovesGlobalAlias() = runTest {
        val add = Recorder()
        add.client(bucketInfoBody).addBucketAlias("tok", "b1", "alt")

        val added = json.decodeFromString<JsonObject>(add.body)
        assertEquals("b1", added["bucketId"]?.toString()?.trim('"'))
        assertEquals("alt", added["globalAlias"]?.toString()?.trim('"'))
        assertTrue(add.requests.single().url.encodedPath.endsWith("/v2/AddBucketAlias"))

        val remove = Recorder()
        remove.client(bucketInfoBody).removeBucketAlias("tok", "b1", "alt")

        assertTrue(remove.requests.single().url.encodedPath.endsWith("/v2/RemoveBucketAlias"))
    }

    @Test
    fun allowsAndDeniesBucketKey() = runTest {
        val allow = Recorder()
        allow.client(bucketInfoBody).allowBucketKey(
            "tok",
            "b1",
            "GK01",
            BucketKeyPermissions(read = true, write = true),
        )

        val sent = json.decodeFromString<JsonObject>(allow.body)
        assertEquals("GK01", sent["accessKeyId"]?.toString()?.trim('"'))
        assertTrue(sent["permissions"].toString().contains("\"read\":true"))
        assertTrue(allow.requests.single().url.encodedPath.endsWith("/v2/AllowBucketKey"))

        val deny = Recorder()
        deny.client(bucketInfoBody).denyBucketKey(
            "tok",
            "b1",
            "GK01",
            BucketKeyPermissions(owner = true, read = true, write = true),
        )

        assertTrue(deny.requests.single().url.encodedPath.endsWith("/v2/DenyBucketKey"))
    }

    @Test
    fun cleansUpIncompleteUploads() = runTest {
        val recorder = Recorder()
        val client = recorder.client("""{"uploadsDeleted":3}""")

        val deleted = client.cleanupIncompleteUploads("tok", "b1", olderThanSecs = 86_400)

        assertEquals(3, deleted)
        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertEquals("86400", sent["olderThanSecs"]?.toString())
    }

    @Test
    fun inspectsObject() = runTest {
        val recorder = Recorder()
        val client = recorder.client(
            """
            {"bucketId":"b1","key":"a.txt","versions":[
              {"uuid":"v1","timestamp":"2026-08-22T16:43:38.636Z","encrypted":false,
               "uploading":false,"aborted":false,"deleteMarker":false,"inline":true,
               "size":12,"etag":"abc","headers":[["content-type","text/plain"]],"blocks":[]}]}
            """.trimIndent(),
        )

        val inspection = client.inspectObject("tok", "b1", "a.txt")

        assertEquals("a.txt", inspection.key)
        assertEquals(listOf("content-type", "text/plain"), inspection.versions.single().headers.single())
        assertEquals("a.txt", recorder.requests.single().url.parameters["key"])
    }

    @Test
    fun propagatesGarageFailure() = runTest {
        val recorder = Recorder()
        val client = recorder.client("insufficient scope", HttpStatusCode.Forbidden)

        val failure = assertFailsWith<GarageException> { client.listBuckets("tok") }

        assertEquals(HttpStatusCode.Forbidden, failure.status)
        assertEquals("ListBuckets", failure.operation)
    }

    @Test
    fun deleteBucketReportsGarageRefusal() = runTest {
        // 空でないバケットは Garage が拒否する。理由をそのまま運ぶ（spec §8.6）
        val recorder = Recorder()
        val client = recorder.client("bucket is not empty", HttpStatusCode.BadRequest)

        val failure = assertFailsWith<GarageException> { client.deleteBucket("tok", "b1") }

        assertEquals(HttpStatusCode.BadRequest, failure.status)
        assertEquals("bucket is not empty", failure.message)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :server:test --tests '*BucketOperationsTest*'`
Expected: FAIL（`listBuckets` が未定義）

- [ ] **Step 3: 実装を書く**

`server/src/main/kotlin/net/brightroom/garage/server/garage/BucketOperations.kt`

```kotlin
package net.brightroom.garage.server.garage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.CreateBucketRequest
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions
import net.brightroom.garage.shared.model.garage.BucketSummary
import net.brightroom.garage.shared.model.garage.ObjectInspection

/**
 * バケット系 operation への型付きアクセス。
 *
 * Garage の operation 名はこのファイルの外に出さない。web が見るのは
 * リソース指向の `/api/buckets/**` だけである（spec §7）。
 */

private const val LIST_BUCKETS = "ListBuckets"
private const val GET_BUCKET_INFO = "GetBucketInfo"
private const val CREATE_BUCKET = "CreateBucket"
private const val UPDATE_BUCKET = "UpdateBucket"
private const val DELETE_BUCKET = "DeleteBucket"
private const val ADD_BUCKET_ALIAS = "AddBucketAlias"
private const val REMOVE_BUCKET_ALIAS = "RemoveBucketAlias"
private const val ALLOW_BUCKET_KEY = "AllowBucketKey"
private const val DENY_BUCKET_KEY = "DenyBucketKey"
private const val CLEANUP_INCOMPLETE_UPLOADS = "CleanupIncompleteUploads"
private const val INSPECT_OBJECT = "InspectObject"

suspend fun GarageAdminClient.listBuckets(token: String): List<BucketSummary> =
    get(token, LIST_BUCKETS)
        .garageBodyWith(LIST_BUCKETS, ListSerializer(BucketSummary.serializer()))

suspend fun GarageAdminClient.getBucketInfo(token: String, id: String): BucketInfo =
    get(token, GET_BUCKET_INFO, mapOf("id" to id)).garageBody(GET_BUCKET_INFO)

suspend fun GarageAdminClient.createBucket(
    token: String,
    request: CreateBucketRequest,
): BucketInfo {
    // local alias はコンソールでは作らない（P2-6）。alias 無しのバケットも作れる。
    val body = buildJsonObject {
        request.globalAlias?.let { put("globalAlias", it) }
    }

    return post(token, CREATE_BUCKET, body).garageBody(CREATE_BUCKET)
}

/**
 * バケットの設定を変更する。
 *
 * [request] は Garage の `UpdateBucketRequestBody` と同じ形なのでそのまま送る。
 * 省略したフィールドは変更されず、空配列はそのルールの削除を意味する。
 * `GarageJson` の `explicitNulls = false` がこの意味論を成立させている。
 */
suspend fun GarageAdminClient.updateBucket(
    token: String,
    id: String,
    request: UpdateBucketRequest,
): BucketInfo = post(
    token,
    UPDATE_BUCKET,
    GarageJson.encodeToJsonElement(UpdateBucketRequest.serializer(), request),
    mapOf("id" to id),
).garageBody(UPDATE_BUCKET)

suspend fun GarageAdminClient.deleteBucket(token: String, id: String) {
    post(token, DELETE_BUCKET, params = mapOf("id" to id)).requireSuccess(DELETE_BUCKET)
}

suspend fun GarageAdminClient.addBucketAlias(
    token: String,
    bucketId: String,
    alias: String,
): BucketInfo = post(token, ADD_BUCKET_ALIAS, aliasBody(bucketId, alias))
    .garageBody(ADD_BUCKET_ALIAS)

suspend fun GarageAdminClient.removeBucketAlias(
    token: String,
    bucketId: String,
    alias: String,
): BucketInfo = post(token, REMOVE_BUCKET_ALIAS, aliasBody(bucketId, alias))
    .garageBody(REMOVE_BUCKET_ALIAS)

suspend fun GarageAdminClient.allowBucketKey(
    token: String,
    bucketId: String,
    accessKeyId: String,
    permissions: BucketKeyPermissions,
): BucketInfo = post(token, ALLOW_BUCKET_KEY, permissionBody(bucketId, accessKeyId, permissions))
    .garageBody(ALLOW_BUCKET_KEY)

suspend fun GarageAdminClient.denyBucketKey(
    token: String,
    bucketId: String,
    accessKeyId: String,
    permissions: BucketKeyPermissions,
): BucketInfo = post(token, DENY_BUCKET_KEY, permissionBody(bucketId, accessKeyId, permissions))
    .garageBody(DENY_BUCKET_KEY)

/** @return 削除された未完了アップロードの数。 */
suspend fun GarageAdminClient.cleanupIncompleteUploads(
    token: String,
    bucketId: String,
    olderThanSecs: Long,
): Long {
    val body = buildJsonObject {
        put("bucketId", bucketId)
        put("olderThanSecs", olderThanSecs)
    }

    return post(token, CLEANUP_INCOMPLETE_UPLOADS, body)
        .garageBody<CleanupResponse>(CLEANUP_INCOMPLETE_UPLOADS)
        .uploadsDeleted
}

suspend fun GarageAdminClient.inspectObject(
    token: String,
    bucketId: String,
    key: String,
): ObjectInspection = get(
    token,
    INSPECT_OBJECT,
    mapOf("bucketId" to bucketId, "key" to key),
).garageBody(INSPECT_OBJECT)

private fun aliasBody(bucketId: String, alias: String) = buildJsonObject {
    put("bucketId", bucketId)
    put("globalAlias", alias)
}

private fun permissionBody(
    bucketId: String,
    accessKeyId: String,
    permissions: BucketKeyPermissions,
) = buildJsonObject {
    put("bucketId", bucketId)
    put("accessKeyId", accessKeyId)
    put(
        "permissions",
        GarageJson.encodeToJsonElement(BucketKeyPermissions.serializer(), permissions),
    )
}

/** `CleanupIncompleteUploads` のレスポンス。件数だけを web に返す。 */
@Serializable
private data class CleanupResponse(val uploadsDeleted: Long)
```

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :server:test --tests '*BucketOperationsTest*'`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add server/src/main/kotlin/net/brightroom/garage/server/garage/BucketOperations.kt server/src/test/kotlin/net/brightroom/garage/server/garage/BucketOperationsTest.kt
git commit -m "feat(server): バケット operation の型付きクライアントを追加"
```

---

## Task 6: `/api/buckets`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/BucketRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/api/AuthContext.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/StatusPages.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/BucketRoutesTest.kt`

**Interfaces:**
- Consumes: Task 5 の operation、`adminToken()`（Phase 1）
- Produces:
  - `fun Route.bucketRoutes(client: GarageAdminClient)`
  - `fun ApplicationCall.pathParam(name: String): String`
  - `fun ApplicationCall.queryParam(name: String): String`
  - `class InvalidRequestException(message: String)`

対応表（spec §7）:

| ルート | Garage |
|---|---|
| `GET /api/buckets` | `ListBuckets` |
| `POST /api/buckets` | `CreateBucket` |
| `GET /api/buckets/{id}` | `GetBucketInfo` |
| `PATCH /api/buckets/{id}` | `UpdateBucket` |
| `DELETE /api/buckets/{id}` | `DeleteBucket` |
| `POST /api/buckets/{id}/aliases` | `AddBucketAlias` |
| `DELETE /api/buckets/{id}/aliases?alias=` | `RemoveBucketAlias` |
| `PUT /api/buckets/{id}/keys/{keyId}` | `AllowBucketKey` |
| `DELETE /api/buckets/{id}/keys/{keyId}` | `DenyBucketKey`（全権限を剥奪） |
| `POST /api/buckets/{id}/cleanup-uploads` | `CleanupIncompleteUploads` |

**DELETE には本文を載せない**（P2-12）。alias はクエリで渡し、キーの剥奪は常に owner / read / write の全部を落とす（P2-11）。権限を部分的に減らしたいときは、外してから必要な権限で付け直す。

- [ ] **Step 1: 失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/api/BucketRoutesTest.kt`

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BucketRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private val bucketInfoBody = """
        {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"keys":[],
         "objects":0,"bytes":0,"unfinishedUploads":0,"unfinishedMultipartUploads":0,
         "unfinishedMultipartUploadParts":0,"unfinishedMultipartUploadBytes":0,"quotas":{}}
    """.trimIndent()

    @Test
    fun listsBuckets() = testApplication {
        garageApp(
            MockEngine {
                respond("""[{"id":"b1","globalAliases":["dev-bucket"],"localAliases":[]}]""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/buckets") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val buckets = GarageJson.decodeFromString<List<BucketSummary>>(response.bodyAsText())
        assertEquals("dev-bucket", buckets.single().displayName)
    }

    @Test
    fun requiresTokenForEveryRoute() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/buckets").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/buckets/b1").status)
    }

    @Test
    fun createsBucket() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/buckets") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"globalAlias":"new-bucket"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(sentBody.contains("new-bucket"))
        val info = GarageJson.decodeFromString<BucketInfo>(response.bodyAsText())
        assertEquals("b1", info.id)
    }

    @Test
    fun updatesBucketSettings() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.patch("/api/buckets/b1") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"corsRules":[]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // 触っていない設定を巻き込まないこと
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("[]", sent["corsRules"]?.toString())
        assertEquals(null, sent["quotas"])
        assertEquals(null, sent["websiteAccess"])
    }

    @Test
    fun deletesBucket() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        val response = client.delete("/api/buckets/b1") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun reportsGarageRefusalToDeleteNonEmptyBucket() = testApplication {
        garageApp(MockEngine { respond("bucket is not empty", HttpStatusCode.BadRequest) })

        val response = client.delete("/api/buckets/b1") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("bucket is not empty", problem.detail)
        assertEquals("DeleteBucket", problem.operation)
    }

    @Test
    fun addsAliasFromBodyAndRemovesFromQuery() = testApplication {
        val operations = mutableListOf<String>()
        garageApp(
            MockEngine { request ->
                operations += request.url.encodedPath.substringAfterLast('/')
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.post("/api/buckets/b1/aliases") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"alias":"alt"}""")
        }
        client.delete("/api/buckets/b1/aliases?alias=alt") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(listOf("AddBucketAlias", "RemoveBucketAlias"), operations)
    }

    @Test
    fun rejectsAliasRemovalWithoutAliasParameter() = testApplication {
        garageApp(MockEngine { respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders) })

        val response = client.delete("/api/buckets/b1/aliases") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest.value, problem.status)
    }

    @Test
    fun grantsPermissionsFromBody() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.put("/api/buckets/b1/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"permissions":{"read":true,"write":true,"owner":false}}""")
        }

        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("GK01", sent["accessKeyId"]?.toString()?.trim('"'))
        assertTrue(sent["permissions"].toString().contains("\"write\":true"))
    }

    @Test
    fun revokesEveryPermission() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.delete("/api/buckets/b1/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        // 部分的な剥奪はしない（P2-11）
        val sent = json.decodeFromString<JsonObject>(sentBody)
        val permissions = sent["permissions"].toString()
        assertTrue(permissions.contains("\"owner\":true"))
        assertTrue(permissions.contains("\"read\":true"))
        assertTrue(permissions.contains("\"write\":true"))
    }

    @Test
    fun cleansUpUploadsWithDefaultAge() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond("""{"uploadsDeleted":2}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/buckets/b1/cleanup-uploads") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(sentBody.contains("86400"))
        assertTrue(response.bodyAsText().contains("2"))
    }

    @Test
    fun forwardsForbiddenAsProblemDetails() = testApplication {
        garageApp(MockEngine { respond("insufficient scope", HttpStatusCode.Forbidden) })

        val response = client.get("/api/buckets") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.contains("application/problem+json"))
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("ListBuckets", problem.operation)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :server:test --tests '*BucketRoutesTest*'`
Expected: FAIL（`bucketRoutes` が未定義）

- [ ] **Step 3: リクエスト取り出しのヘルパを足す**

`server/src/main/kotlin/net/brightroom/garage/server/api/AuthContext.kt` の末尾に追加する。

```kotlin
/** パスやクエリの必須パラメータが無い、または本文が解釈できない。 */
class InvalidRequestException(override val message: String) : RuntimeException(message)

/** パスパラメータを取り出す。ルートの定義と食い違っていれば 400 になる。 */
fun ApplicationCall.pathParam(name: String): String =
    parameters[name]?.takeIf { it.isNotBlank() }
        ?: throw InvalidRequestException("パスに $name が必要です")

/** クエリパラメータを取り出す。 */
fun ApplicationCall.queryParam(name: String): String =
    request.queryParameters[name]?.takeIf { it.isNotBlank() }
        ?: throw InvalidRequestException("クエリに $name が必要です")
```

- [ ] **Step 4: StatusPages に 400 の扱いを足す**

`server/src/main/kotlin/net/brightroom/garage/server/plugins/StatusPages.kt` の `exception<NotFoundException>` の隣に追加する。

```kotlin
        exception<InvalidRequestException> { call, cause ->
            call.respondProblem(status = HttpStatusCode.BadRequest, detail = cause.message)
        }

        // 本文のデシリアライズ失敗など、Ktor が投げる 400。内部のメッセージは外に出さない
        exception<BadRequestException> { call, _ ->
            call.respondProblem(
                status = HttpStatusCode.BadRequest,
                detail = "リクエストの内容を解釈できませんでした",
            )
        }
```

import を追加する。

```kotlin
import io.ktor.server.plugins.BadRequestException
import net.brightroom.garage.server.api.InvalidRequestException
```

**`exception<Throwable>` より前に置くこと。** StatusPages は登録順ではなく型の近さで選ぶが、可読性のために既存の具体的なハンドラの並びに入れる。

- [ ] **Step 5: ルートを書く**

`server/src/main/kotlin/net/brightroom/garage/server/api/BucketRoutes.kt`

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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.addBucketAlias
import net.brightroom.garage.server.garage.allowBucketKey
import net.brightroom.garage.server.garage.cleanupIncompleteUploads
import net.brightroom.garage.server.garage.createBucket
import net.brightroom.garage.server.garage.deleteBucket
import net.brightroom.garage.server.garage.denyBucketKey
import net.brightroom.garage.server.garage.getBucketInfo
import net.brightroom.garage.server.garage.listBuckets
import net.brightroom.garage.server.garage.removeBucketAlias
import net.brightroom.garage.server.garage.updateBucket
import net.brightroom.garage.shared.api.BucketAliasRequest
import net.brightroom.garage.shared.api.BucketKeyPermissionRequest
import net.brightroom.garage.shared.api.CleanupUploadsRequest
import net.brightroom.garage.shared.api.CleanupUploadsResult
import net.brightroom.garage.shared.api.CreateBucketRequest
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions

/**
 * バケットのルート。Garage の operation 名は外に出さない（spec §7）。
 *
 * scope の判定はしない。可否は Garage が返す 403 で決まる（spec §6.3）。
 */
fun Route.bucketRoutes(client: GarageAdminClient) {
    route("/buckets") {

        get {
            call.respond(client.listBuckets(call.adminToken()))
        }

        post {
            val request = call.receive<CreateBucketRequest>()
            call.respond(client.createBucket(call.adminToken(), request))
        }

        route("/{id}") {

            get {
                call.respond(client.getBucketInfo(call.adminToken(), call.pathParam("id")))
            }

            patch {
                val request = call.receive<UpdateBucketRequest>()
                call.respond(client.updateBucket(call.adminToken(), call.pathParam("id"), request))
            }

            delete {
                client.deleteBucket(call.adminToken(), call.pathParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }

            post("/aliases") {
                val request = call.receive<BucketAliasRequest>()
                call.respond(
                    client.addBucketAlias(call.adminToken(), call.pathParam("id"), request.alias),
                )
            }

            delete("/aliases") {
                // DELETE に本文は載せない（P2-12）
                call.respond(
                    client.removeBucketAlias(
                        call.adminToken(),
                        call.pathParam("id"),
                        call.queryParam("alias"),
                    ),
                )
            }

            put("/keys/{keyId}") {
                val request = call.receive<BucketKeyPermissionRequest>()
                call.respond(
                    client.allowBucketKey(
                        call.adminToken(),
                        call.pathParam("id"),
                        call.pathParam("keyId"),
                        request.permissions,
                    ),
                )
            }

            delete("/keys/{keyId}") {
                // 部分的な剥奪はしない。減らしたいときは外してから付け直す（P2-11）
                call.respond(
                    client.denyBucketKey(
                        call.adminToken(),
                        call.pathParam("id"),
                        call.pathParam("keyId"),
                        BucketKeyPermissions(owner = true, read = true, write = true),
                    ),
                )
            }

            post("/cleanup-uploads") {
                val request = call.receive<CleanupUploadsRequest>()
                val deleted = client.cleanupIncompleteUploads(
                    call.adminToken(),
                    call.pathParam("id"),
                    request.olderThanSecs,
                )
                call.respond(CleanupUploadsResult(deleted))
            }
        }
    }
}
```

- [ ] **Step 6: ルートを登録し、テスト用アプリにも足す**

`server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt` の `routing` ブロックに 1 行足す。

```kotlin
        route("/api") {
            sessionRoutes(client)
            overviewRoutes(overviewService)
            bucketRoutes(client)
        }
```

`server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt` の `routing` ブロックにも同じ行を足す（import も追加する）。

```kotlin
        routing {
            route("/api") {
                sessionRoutes(client)
                bucketRoutes(client)
            }
        }
```

- [ ] **Step 7: テストが通ることを確認**

Run: `./gradlew :server:test`
Expected: PASS（既存のテストも含めて）

- [ ] **Step 8: コミット**

```bash
git add server/src/main/kotlin/net/brightroom/garage/server/api/ server/src/main/kotlin/net/brightroom/garage/server/plugins/ server/src/test/kotlin/net/brightroom/garage/server/
git commit -m "feat(server): /api/buckets を追加"
```

---

## Task 7: `/api/keys`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/KeyOperations.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/KeyRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/KeyRoutesTest.kt`

**Interfaces:**
- Consumes: `GarageAdminClient`、Task 2・3 の型
- Produces:
  - `GarageAdminClient.listKeys(token): List<KeySummary>`
  - `GarageAdminClient.getKeyInfo(token, id, showSecret: Boolean): KeyInfo`
  - `GarageAdminClient.createKey(token, request): KeyInfo`
  - `GarageAdminClient.importKey(token, request): KeyInfo`
  - `GarageAdminClient.updateKey(token, id, request): KeyInfo`
  - `GarageAdminClient.deleteKey(token, id)`
  - `fun Route.keyRoutes(client: GarageAdminClient)`

Garage の `CreateKey` と `UpdateKey` は同じ本文（`UpdateKeyRequestBody`）を取る。`createBucket` 権限は `allow` / `deny` の 2 つのオブジェクトで表す。**コンソールの DTO は真偽値 1 つなので、ここで振り分ける。**

| コンソールの値 | Garage に送る本文 |
|---|---|
| `allowCreateBucket = true` | `{"allow":{"createBucket":true}}` |
| `allowCreateBucket = false` | `{"deny":{"createBucket":true}}` |
| `allowCreateBucket = null`（更新時） | どちらも送らない（変更しない） |
| `neverExpires = true` | `{"neverExpires":true}` |
| `expiration = <Instant>` | `{"expiration":"<RFC 3339>"}` |

- [ ] **Step 1: 失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/api/KeyRoutesTest.kt`

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
import io.ktor.http.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.model.garage.KeyInfo
import net.brightroom.garage.shared.model.garage.KeySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private val keyInfoBody = """
        {"accessKeyId":"GK01","name":"dev-key","expired":false,
         "permissions":{"createBucket":false},"buckets":[]}
    """.trimIndent()

    @Test
    fun listsKeys() = testApplication {
        garageApp(
            MockEngine {
                respond("""[{"id":"GK01","name":"dev-key","expired":false}]""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/keys") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        val keys = GarageJson.decodeFromString<List<KeySummary>>(response.bodyAsText())
        assertEquals("dev-key", keys.single().name)
    }

    @Test
    fun getsKeyWithoutSecretByDefault() = testApplication {
        var showSecret: String? = "unset"
        garageApp(
            MockEngine { request ->
                showSecret = request.url.parameters["showSecretKey"]
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(showSecret)
    }

    @Test
    fun getsKeyWithSecretWhenAsked() = testApplication {
        var showSecret: String? = null
        garageApp(
            MockEngine { request ->
                showSecret = request.url.parameters["showSecretKey"]
                respond(
                    """{"accessKeyId":"GK01","name":"dev-key","expired":false,
                        "secretAccessKey":"s3cr3t","permissions":{},"buckets":[]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/keys/GK01?showSecret=true") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals("true", showSecret)
        val info = GarageJson.decodeFromString<KeyInfo>(response.bodyAsText())
        assertEquals("s3cr3t", info.secretAccessKey)
    }

    @Test
    fun createsKeyWithCreateBucketPermission() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.post("/api/keys") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ci","allowCreateBucket":true}""")
        }

        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("ci", sent["name"]?.toString()?.trim('"'))
        assertTrue(sent["allow"].toString().contains("\"createBucket\":true"))
        // 期限を指定しなければ無期限
        assertEquals("true", sent["neverExpires"]?.toString())
    }

    @Test
    fun importsKey() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/keys/import") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"restored","accessKeyId":"GK99","secretAccessKey":"old-secret"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("GK99", sent["accessKeyId"]?.toString()?.trim('"'))
        assertEquals("old-secret", sent["secretAccessKey"]?.toString()?.trim('"'))
    }

    @Test
    fun deniesCreateBucketWhenTurnedOff() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.patch("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"allowCreateBucket":false}""")
        }

        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertTrue(sent["deny"].toString().contains("\"createBucket\":true"))
        assertNull(sent["allow"])
    }

    @Test
    fun leavesPermissionsAloneWhenNotSpecified() = testApplication {
        var sentBody = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.patch("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"renamed"}""")
        }

        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("renamed", sent["name"]?.toString()?.trim('"'))
        assertNull(sent["allow"])
        assertNull(sent["deny"])
    }

    @Test
    fun deletesKey() = testApplication {
        var deletedId: String? = null
        garageApp(
            MockEngine { request ->
                deletedId = request.url.parameters["id"]
                respond("", HttpStatusCode.OK)
            },
        )

        val response = client.delete("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("GK01", deletedId)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :server:test --tests '*KeyRoutesTest*'`
Expected: FAIL（`keyRoutes` が未定義）

- [ ] **Step 3: operation を書く**

`server/src/main/kotlin/net/brightroom/garage/server/garage/KeyOperations.kt`

```kotlin
package net.brightroom.garage.server.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.brightroom.garage.shared.api.CreateKeyRequest
import net.brightroom.garage.shared.api.ImportKeyRequest
import net.brightroom.garage.shared.api.UpdateKeyRequest
import net.brightroom.garage.shared.model.garage.KeyInfo
import net.brightroom.garage.shared.model.garage.KeySummary

/**
 * アクセスキー系 operation への型付きアクセス。
 *
 * Garage は `createBucket` 権限を allow / deny の 2 つのオブジェクトで表すが、
 * コンソールの DTO は真偽値 1 つである。その振り分けはここで閉じる。
 */

private const val LIST_KEYS = "ListKeys"
private const val GET_KEY_INFO = "GetKeyInfo"
private const val CREATE_KEY = "CreateKey"
private const val IMPORT_KEY = "ImportKey"
private const val UPDATE_KEY = "UpdateKey"
private const val DELETE_KEY = "DeleteKey"

suspend fun GarageAdminClient.listKeys(token: String): List<KeySummary> =
    get(token, LIST_KEYS).garageBodyWith(LIST_KEYS, ListSerializer(KeySummary.serializer()))

/**
 * @param showSecret true のときだけ secret access key を取得する。
 *   既定で付けないのは、必要のない機密をサーバーに流さないため（spec §6.4）。
 */
suspend fun GarageAdminClient.getKeyInfo(
    token: String,
    id: String,
    showSecret: Boolean = false,
): KeyInfo {
    val params = buildMap {
        put("id", id)
        if (showSecret) put("showSecretKey", "true")
    }

    return get(token, GET_KEY_INFO, params).garageBody(GET_KEY_INFO)
}

suspend fun GarageAdminClient.createKey(token: String, request: CreateKeyRequest): KeyInfo {
    val body = buildJsonObject {
        put("name", request.name)
        putPermission(request.allowCreateBucket)
        putExpiration(request.expiration?.toString(), neverExpires = request.expiration == null)
    }

    return post(token, CREATE_KEY, body).garageBody(CREATE_KEY)
}

suspend fun GarageAdminClient.importKey(token: String, request: ImportKeyRequest): KeyInfo {
    val body = buildJsonObject {
        put("name", request.name)
        put("accessKeyId", request.accessKeyId)
        put("secretAccessKey", request.secretAccessKey)
    }

    return post(token, IMPORT_KEY, body).garageBody(IMPORT_KEY)
}

suspend fun GarageAdminClient.updateKey(
    token: String,
    id: String,
    request: UpdateKeyRequest,
): KeyInfo {
    val body = buildJsonObject {
        request.name?.let { put("name", it) }
        request.allowCreateBucket?.let { putPermission(it) }
        putExpiration(request.expiration?.toString(), neverExpires = request.neverExpires)
    }

    return post(token, UPDATE_KEY, body, mapOf("id" to id)).garageBody(UPDATE_KEY)
}

suspend fun GarageAdminClient.deleteKey(token: String, id: String) {
    post(token, DELETE_KEY, params = mapOf("id" to id)).requireSuccess(DELETE_KEY)
}

/** `createBucket` の可否を Garage の allow / deny に振り分ける。 */
private fun kotlinx.serialization.json.JsonObjectBuilder.putPermission(allowed: Boolean) {
    val field = if (allowed) "allow" else "deny"

    putJsonObject(field) { put("createBucket", true) }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putExpiration(
    expiration: String?,
    neverExpires: Boolean,
) {
    when {
        neverExpires -> put("neverExpires", true)
        expiration != null -> put("expiration", expiration)
    }
}
```

- [ ] **Step 4: ルートを書く**

`server/src/main/kotlin/net/brightroom/garage/server/api/KeyRoutes.kt`

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
import net.brightroom.garage.server.garage.createKey
import net.brightroom.garage.server.garage.deleteKey
import net.brightroom.garage.server.garage.getKeyInfo
import net.brightroom.garage.server.garage.importKey
import net.brightroom.garage.server.garage.listKeys
import net.brightroom.garage.server.garage.updateKey
import net.brightroom.garage.shared.api.CreateKeyRequest
import net.brightroom.garage.shared.api.ImportKeyRequest
import net.brightroom.garage.shared.api.UpdateKeyRequest

/**
 * アクセスキーのルート。
 *
 * secret access key は `?showSecret=true` を明示したときだけ取得する（P2-7）。
 * サーバーはその値をキャッシュにもログにも残さず、応答としてのみ返す。
 */
fun Route.keyRoutes(client: GarageAdminClient) {
    route("/keys") {

        get {
            call.respond(client.listKeys(call.adminToken()))
        }

        post {
            val request = call.receive<CreateKeyRequest>()
            call.respond(client.createKey(call.adminToken(), request))
        }

        post("/import") {
            val request = call.receive<ImportKeyRequest>()
            call.respond(client.importKey(call.adminToken(), request))
        }

        get("/{id}") {
            val showSecret = call.request.queryParameters["showSecret"] == "true"
            call.respond(client.getKeyInfo(call.adminToken(), call.pathParam("id"), showSecret))
        }

        patch("/{id}") {
            val request = call.receive<UpdateKeyRequest>()
            call.respond(client.updateKey(call.adminToken(), call.pathParam("id"), request))
        }

        delete("/{id}") {
            client.deleteKey(call.adminToken(), call.pathParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

**`/keys/import` は `/keys/{id}` より先に登録する。** Ktor は静的なセグメントを優先するため実際には順序に依存しないが、読み手のために並びを守る。

- [ ] **Step 5: ルートを登録する**

`plugins/Routing.kt` と `TestApplication.kt` の両方に `keyRoutes(client)` を足す。

- [ ] **Step 6: テストが通ることを確認**

Run: `./gradlew :server:test`
Expected: PASS

- [ ] **Step 7: 実機で通しの確認**

```bash
docker compose up -d
docker compose logs garage-init | grep "Console login token"
./gradlew :server:run
```

別の端末から、表示されたトークンで:

```bash
TOKEN=<Console login token>
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/buckets | jq
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/keys | jq
```

Expected: `dev-bucket` と `dev-key` が返る。

- [ ] **Step 8: コミット**

```bash
git add server/src/main/kotlin/net/brightroom/garage/server/garage/KeyOperations.kt server/src/main/kotlin/net/brightroom/garage/server/api/KeyRoutes.kt server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt server/src/test/kotlin/net/brightroom/garage/server/
git commit -m "feat(server): /api/keys を追加"
```

- [ ] **Step 9: PR を出す**

```bash
git push -u origin phase2/2-server-garage
gh pr create --title "feat(server): /api/buckets と /api/keys を追加" --body "Phase 2 のサーバー（Garage 側）。バケットとアクセスキーの operation を型付きで叩き、リソース指向のルートで公開する。

UpdateBucket の「省略 = 変更しない / 空配列 = 削除」の意味論をテストで固定している。

計画: docs/superpowers/plans/2026-08-23-rebuild-phase2-storage.md の Task 5-7"
```

---

## Task 8: S3 の設定と接続

**Files:**
- Modify: `server/build.gradle.kts`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/config/AppConfig.kt`
- Modify: `server/src/main/resources/application.yaml`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/s3/S3Credentials.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/s3/S3ObjectStore.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/config/AppConfigTest.kt`

**Interfaces:**
- Consumes: `AppConfig`（Phase 1）
- Produces:
  - `AppConfig.S3`（`endpoint` / `region` / `pathStyle`）
  - `data class S3Credentials(accessKeyId, secretAccessKey, keyName, bucketName)`
  - `class S3ObjectStore(config: AppConfig.S3)` — このタスクでは接続の生成だけを持ち、操作は Task 10 で足す

**ブランチ:** `phase2/3-server-s3`（Task 8–11 を 1 PR にする）

```bash
git switch main
git pull
git switch -c phase2/3-server-s3
```

**Phase 1 の「実装初日の検証」に相当する確認を最初に行う。** aws-sdk-kotlin が Garage の S3 API と path-style で本当に噛み合うかは、ここで確かめておかないと Task 10 以降の形が決まらない。

Ktor の設定デコーダは Boolean を `decodeString()` → `Boolean.parseBoolean` で読む（`AbstractMapConfigDecoder` を確認済み）。したがって `${GARAGE_S3_PATH_STYLE:true}` のような文字列展開でも `Boolean` のフィールドで受けられる。

- [ ] **Step 1: 依存を足す**

`server/build.gradle.kts` の `dependencies` に 1 行足す。

```kotlin
    implementation(libs.aws.sdk.s3)
```

`libs.versions.toml` は変更しない（`aws-sdk-s3 = { module = "aws.sdk.kotlin:s3", version.ref = "aws-sdk-kotlin" }` が既にある）。

- [ ] **Step 2: 設定の失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/config/AppConfigTest.kt` に追加する（既存のテストは残す）。

```kotlin
    @Test
    fun readsS3Settings() {
        val config = MapApplicationConfig(
            "garage.admin.endpoint" to "http://garage.test:3903",
            "garage.s3.endpoint" to "http://garage.test:3900",
            "garage.s3.region" to "garage",
            "garage.s3.pathStyle" to "true",
        ).property<AppConfig>("garage")

        assertEquals("http://garage.test:3900", config.s3.endpoint)
        assertEquals("garage", config.s3.region)
        assertEquals(true, config.s3.pathStyle)
    }

    @Test
    fun readsPathStyleAsFalse() {
        val config = MapApplicationConfig(
            "garage.admin.endpoint" to "http://garage.test:3903",
            "garage.s3.endpoint" to "http://garage.test:3900",
            "garage.s3.region" to "garage",
            "garage.s3.pathStyle" to "false",
        ).property<AppConfig>("garage")

        assertEquals(false, config.s3.pathStyle)
    }
```

- [ ] **Step 3: テストが失敗することを確認**

Run: `./gradlew :server:test --tests '*AppConfigTest*'`
Expected: FAIL（`AppConfig.s3` が無い）

- [ ] **Step 4: 設定を足す**

`server/src/main/kotlin/net/brightroom/garage/server/config/AppConfig.kt`

```kotlin
package net.brightroom.garage.server.config

import kotlinx.serialization.Serializable

/**
 * サーバーの設定。`application.yaml` の `garage` セクションをそのまま受ける。
 *
 * admin token は含まない。トークンは利用者がブラウザで入力し、
 * リクエストごとに転送されるため、サーバーは保持しない。
 *
 * S3 の資格情報も含まない。admin token から導出する（spec §6.4）。
 * ここにあるのは接続先の情報だけである。
 */
@Serializable
data class AppConfig(
    val admin: Admin,
    val s3: S3,
) {
    @Serializable
    data class Admin(
        val endpoint: String,
    )

    /**
     * S3 API の接続先。
     *
     * spec §9 のポータブルな側であり、Garage 固有の値を前提にしない。
     * [pathStyle] は仮想ホスト形式（`bucket.example.com`）を使わないことを指す。
     */
    @Serializable
    data class S3(
        val endpoint: String,
        val region: String,
        val pathStyle: Boolean,
    )
}
```

`server/src/main/resources/application.yaml` の `garage` セクションを次のようにする。

```yaml
garage:
  admin:
    endpoint: ${GARAGE_ADMIN_ENDPOINT:http://localhost:3903}
  s3:
    endpoint: ${GARAGE_S3_ENDPOINT:http://localhost:3900}
    region: ${GARAGE_S3_REGION:garage}
    pathStyle: ${GARAGE_S3_PATH_STYLE:true}
```

- [ ] **Step 5: テストが通ることを確認**

Run: `./gradlew :server:test --tests '*AppConfigTest*'`
Expected: PASS

- [ ] **Step 6: 資格情報の型と S3 クライアントの生成を書く**

`server/src/main/kotlin/net/brightroom/garage/server/s3/S3Credentials.kt`

```kotlin
package net.brightroom.garage.server.s3

/**
 * admin token から導出した S3 の資格情報（spec §6.4）。
 *
 * **この型はブラウザに返してはならない。** [keyName] だけは
 * 「どのキーで見ているか」を画面に出すために使う（spec §6.4 の 6）。
 *
 * @param bucketName S3 API に渡すバケット名。global alias か local alias（spec §6.5）。
 */
data class S3Credentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val keyName: String,
    val bucketName: String,
) {
    /** ログや例外に混ぜても secret が出ないようにする。 */
    override fun toString(): String = "S3Credentials(keyName=$keyName, bucketName=$bucketName)"
}
```

`server/src/main/kotlin/net/brightroom/garage/server/s3/S3ObjectStore.kt`

```kotlin
package net.brightroom.garage.server.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.net.url.Url
import net.brightroom.garage.server.config.AppConfig

/**
 * S3 API 越しのオブジェクト操作。
 *
 * spec §9 のポータブルな側であり、Garage 固有の概念を持ち込まない。
 * エンドポイント・リージョン・path-style は設定から受ける。
 *
 * クライアントは呼び出しごとに作って閉じる。資格情報がバケットごとに異なるため
 * 使い回せず、管理コンソールの操作頻度なら接続の作り直しは問題にならない。
 */
class S3ObjectStore(private val config: AppConfig.S3) {

    internal suspend fun <T> withClient(
        credentials: S3Credentials,
        block: suspend (S3Client, String) -> T,
    ): T = S3Client {
        region = config.region
        endpointUrl = Url.parse(config.endpoint)
        forcePathStyle = config.pathStyle
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = credentials.accessKeyId
            secretAccessKey = credentials.secretAccessKey
        }
    }.use { client -> block(client, credentials.bucketName) }
}
```

- [ ] **Step 7: コンパイルを確認**

Run: `./gradlew :server:build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 実機で往復できることを確認（Phase 2 の「初日の検証」）**

`server/src/test/kotlin/net/brightroom/garage/server/s3/S3RoundTripManualTest.kt` を一時的に作り、dev の Garage に対して実行する。**確認できたらこのファイルは削除する**（CI では Garage が居ないため常設できない）。

```kotlin
package net.brightroom.garage.server.s3

import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.server.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class S3RoundTripManualTest {

    @Test
    fun putsAndListsAgainstLocalGarage() = runTest {
        val store = S3ObjectStore(
            AppConfig.S3(endpoint = "http://localhost:3900", region = "garage", pathStyle = true),
        )
        // docker compose logs garage-init で表示される dev-key の値を入れる
        val credentials = S3Credentials(
            accessKeyId = System.getenv("DEV_ACCESS_KEY_ID"),
            secretAccessKey = System.getenv("DEV_SECRET_ACCESS_KEY"),
            keyName = "dev-key",
            bucketName = "dev-bucket",
        )

        store.withClient(credentials) { client, bucket ->
            client.putObject(
                PutObjectRequest {
                    this.bucket = bucket
                    key = "probe.txt"
                    body = ByteStream.fromString("hello")
                },
            )

            val listing = client.listObjectsV2(
                ListObjectsV2Request {
                    this.bucket = bucket
                    delimiter = "/"
                },
            )

            assertTrue(listing.contents.orEmpty().any { it.key == "probe.txt" })
        }
    }
}
```

`dev-key` の資格情報は Garage から直接引ける。

```bash
docker compose up -d
TOKEN=$(docker compose logs garage-init | sed -n 's/.*Console login token: //p' | tr -d '\r')
KEY_ID=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:3903/v2/ListKeys | jq -r '.[0].id')
SECRET=$(curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:3903/v2/GetKeyInfo?id=$KEY_ID&showSecretKey=true" | jq -r '.secretAccessKey')
DEV_ACCESS_KEY_ID=$KEY_ID DEV_SECRET_ACCESS_KEY=$SECRET ./gradlew :server:test --tests '*S3RoundTripManualTest*'
```

Expected: PASS

**通らない場合はここで止めて原因を突き止める。** path-style が効いていない（`dev-bucket.localhost` に接続しにいく）、署名の region が合わない、といった問題は Task 10 以降では診断しにくくなる。

確認できたら片付ける。

```bash
rm server/src/test/kotlin/net/brightroom/garage/server/s3/S3RoundTripManualTest.kt
```

- [ ] **Step 9: コミット**

```bash
git add server/build.gradle.kts server/src/main/kotlin/net/brightroom/garage/server/config/AppConfig.kt server/src/main/resources/application.yaml server/src/main/kotlin/net/brightroom/garage/server/s3/ server/src/test/kotlin/net/brightroom/garage/server/config/AppConfigTest.kt
git commit -m "feat(server): S3 の接続設定を追加"
```

---

## Task 9: S3 資格情報の導出とキャッシュ

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/s3/SecretCache.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/s3/S3CredentialResolver.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/s3/S3Problems.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/s3/SecretCacheTest.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/s3/S3CredentialResolverTest.kt`

**Interfaces:**
- Consumes: `GarageAdminClient.getBucketInfo` / `getKeyInfo`（Task 5・7）、`S3Credentials`（Task 8）
- Produces:
  - `fun hashToken(token: String): String` — SHA-256 の hex
  - `class SecretCache(ttl: Duration, now: () -> Instant)` — `get` / `put` / `purge` / `size`
  - `class S3CredentialResolver(client, cache)` — `suspend fun resolve(token, bucketId): S3Credentials`
  - `class NoUsableKeyException(bucketId)` / `class BucketNotAddressableException(bucketId)`

spec §6.4 の規則をそのまま実装する。

1. `GetBucketInfo` でそのバケットに権限を持つキーを取る
2. **owner > read+write > read** の優先度で選ぶ。同順位は accessKeyId の昇順で決定的に選ぶ
3. 選んだキーの secret を `GetKeyInfo?showSecretKey=true` で取る
4. `(SHA-256(admin token), bucketId)` をキーに TTL 5 分でキャッシュする
5. secret はブラウザに返さず、ログにも出さない

**キャッシュのキーに生のトークンを使ってはならない。** サーバーはトークンを保持しないという原則（spec §6.2）は、Map のキーであっても同じである。

- [ ] **Step 1: キャッシュの失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/s3/SecretCacheTest.kt`

```kotlin
package net.brightroom.garage.server.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SecretCacheTest {

    private val credentials = S3Credentials(
        accessKeyId = "GK01",
        secretAccessKey = "s3cr3t",
        keyName = "dev-key",
        bucketName = "dev-bucket",
    )

    private class FakeClock(var now: Instant = Instant.fromEpochSeconds(0)) {
        fun advance(minutes: Int) {
            now = now.plus(minutes.minutes)
        }
    }

    @Test
    fun returnsWhatWasStored() {
        val cache = SecretCache()

        cache.put("hash", "b1", credentials)

        assertEquals(credentials, cache.get("hash", "b1"))
    }

    @Test
    fun separatesTokensAndBuckets() {
        val cache = SecretCache()

        cache.put("hash-a", "b1", credentials)

        assertNull(cache.get("hash-b", "b1"))
        assertNull(cache.get("hash-a", "b2"))
    }

    @Test
    fun expiresAfterTtl() {
        val clock = FakeClock()
        val cache = SecretCache(ttl = 5.minutes, now = { clock.now })

        cache.put("hash", "b1", credentials)
        clock.advance(4)
        assertEquals(credentials, cache.get("hash", "b1"))

        clock.advance(2)
        assertNull(cache.get("hash", "b1"))
    }

    @Test
    fun purgeDropsEveryBucketOfThatToken() {
        val cache = SecretCache()
        cache.put("hash-a", "b1", credentials)
        cache.put("hash-a", "b2", credentials)
        cache.put("hash-b", "b1", credentials)

        cache.purge("hash-a")

        assertNull(cache.get("hash-a", "b1"))
        assertNull(cache.get("hash-a", "b2"))
        assertEquals(credentials, cache.get("hash-b", "b1"))
    }

    @Test
    fun hashesTokenWithSha256() {
        // 生のトークンをキーにしないこと。ハッシュは決定的で、値そのものを含まない
        val hash = hashToken("dev-console-token")

        assertEquals(64, hash.length)
        assertEquals(hash, hashToken("dev-console-token"))
        assertNotEquals(hash, hashToken("other-token"))
        assertEquals(false, hash.contains("dev-console-token"))
    }

    @Test
    fun credentialsDoNotLeakSecretInToString() {
        assertEquals(false, credentials.toString().contains("s3cr3t"))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :server:test --tests '*SecretCacheTest*'`
Expected: FAIL（`SecretCache` が未定義）

- [ ] **Step 3: キャッシュを書く**

`server/src/main/kotlin/net/brightroom/garage/server/s3/SecretCache.kt`

```kotlin
package net.brightroom.garage.server.s3

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 導出した S3 資格情報の短命なキャッシュ（spec §6.4 の 4）。
 *
 * オブジェクト操作のたびに `GetBucketInfo` と `GetKeyInfo` を呼ばないためにある。
 *
 * キーは **admin token のハッシュ**とバケット ID の組である。生のトークンは
 * 保持しない（spec §6.2）。ハッシュを引けるのは同じトークンを提示できる者だけなので、
 * 別の利用者のキャッシュには到達できない。
 */
class SecretCache(
    private val ttl: Duration = 5.minutes,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private data class Entry(val credentials: S3Credentials, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(tokenHash: String, bucketId: String): S3Credentials? {
        val key = cacheKey(tokenHash, bucketId)
        val entry = entries[key] ?: return null

        if (entry.expiresAt <= now()) {
            entries.remove(key)
            return null
        }

        return entry.credentials
    }

    fun put(tokenHash: String, bucketId: String, credentials: S3Credentials) {
        entries[cacheKey(tokenHash, bucketId)] = Entry(credentials, now().plus(ttl))
    }

    /** ログアウト時にそのトークン配下をすべて捨てる（spec §6.6）。 */
    fun purge(tokenHash: String) {
        entries.keys.removeAll { it.startsWith("$tokenHash:") }
    }

    /** テスト用。 */
    val size: Int get() = entries.size

    private fun cacheKey(tokenHash: String, bucketId: String) = "$tokenHash:$bucketId"
}

/**
 * admin token の SHA-256（hex）。
 *
 * キャッシュのキーに使う。元のトークンは復元できない。
 */
fun hashToken(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.encodeToByteArray())
    .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
```

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :server:test --tests '*SecretCacheTest*'`
Expected: PASS

- [ ] **Step 5: 導出の失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/s3/S3CredentialResolverTest.kt`

```kotlin
package net.brightroom.garage.server.s3

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.GarageException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class S3CredentialResolverTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    /** owner / read+write / read / write-only の 4 つを持つバケット。 */
    private val bucketWithManyKeys = """
        {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"objects":0,"bytes":0,
         "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
         "unfinishedMultipartUploadBytes":0,"quotas":{},
         "keys":[
           {"accessKeyId":"GK-read","name":"reader","bucketLocalAliases":[],
            "permissions":{"owner":false,"read":true,"write":false}},
           {"accessKeyId":"GK-owner","name":"owner-key","bucketLocalAliases":[],
            "permissions":{"owner":true,"read":true,"write":true}},
           {"accessKeyId":"GK-rw","name":"rw-key","bucketLocalAliases":[],
            "permissions":{"owner":false,"read":true,"write":true}},
           {"accessKeyId":"GK-write","name":"writer","bucketLocalAliases":[],
            "permissions":{"owner":false,"read":false,"write":true}}]}
    """.trimIndent()

    private fun engineOf(responses: Map<String, Pair<String, HttpStatusCode>>) = MockEngine { request ->
        val operation = request.url.encodedPath.substringAfterLast('/')
        val (body, status) = responses[operation] ?: error("unexpected operation: $operation")
        respond(body, status, jsonHeaders)
    }

    private fun secretBody(accessKeyId: String, name: String) = """
        {"accessKeyId":"$accessKeyId","name":"$name","expired":false,
         "secretAccessKey":"secret-of-$accessKeyId","permissions":{},"buckets":[]}
    """.trimIndent()

    @Test
    fun prefersOwnerKey() = runTest {
        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (bucketWithManyKeys to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK-owner", "owner-key") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        val credentials = resolver.resolve("tok", "b1")

        assertEquals("GK-owner", credentials.accessKeyId)
        assertEquals("owner-key", credentials.keyName)
        assertEquals("secret-of-GK-owner", credentials.secretAccessKey)
        assertEquals("dev-bucket", credentials.bucketName)
    }

    @Test
    fun fallsBackToReadWriteThenRead() = runTest {
        val withoutOwner = bucketWithManyKeys.replace("\"owner\":true", "\"owner\":false")
        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (withoutOwner to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK-rw", "rw-key") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        // owner が居なければ read+write。read だけの GK-read や write だけの GK-write は選ばない
        assertEquals("GK-rw", resolver.resolve("tok", "b1").accessKeyId)
    }

    @Test
    fun breaksTiesByAccessKeyIdAscending() = runTest {
        val twoOwners = """
            {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[
               {"accessKeyId":"GK-zzz","name":"z","bucketLocalAliases":[],
                "permissions":{"owner":true,"read":true,"write":true}},
               {"accessKeyId":"GK-aaa","name":"a","bucketLocalAliases":[],
                "permissions":{"owner":true,"read":true,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (twoOwners to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK-aaa", "a") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        // 同順位は決定的に選ぶ。実行のたびに使うキーが変わってはならない
        assertEquals("GK-aaa", resolver.resolve("tok", "b1").accessKeyId)
    }

    @Test
    fun usesLocalAliasWhenNoGlobalAlias() = runTest {
        val localOnly = """
            {"id":"b1","globalAliases":[],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[{"accessKeyId":"GK01","name":"k","bucketLocalAliases":["mine"],
                      "permissions":{"owner":true,"read":true,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (localOnly to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK01", "k") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        assertEquals("mine", resolver.resolve("tok", "b1").bucketName)
    }

    @Test
    fun failsWhenBucketHasNoAddressableName() = runTest {
        val noAlias = """
            {"id":"b1","globalAliases":[],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[{"accessKeyId":"GK01","name":"k","bucketLocalAliases":[],
                      "permissions":{"owner":true,"read":true,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (noAlias to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK01", "k") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        assertFailsWith<BucketNotAddressableException> { resolver.resolve("tok", "b1") }
    }

    @Test
    fun failsWhenNoKeyCanRead() = runTest {
        val writeOnly = """
            {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[{"accessKeyId":"GK01","name":"k","bucketLocalAliases":[],
                      "permissions":{"owner":false,"read":false,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(mapOf("GetBucketInfo" to (writeOnly to HttpStatusCode.OK))),
            ),
            SecretCache(),
        )

        assertFailsWith<NoUsableKeyException> { resolver.resolve("tok", "b1") }
    }

    @Test
    fun propagatesForbiddenFromGetKeyInfo() = runTest {
        // scope が GetKeyInfo を持たないトークン。S3 ブラウザだけが縮退する（spec §6.4）
        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (bucketWithManyKeys to HttpStatusCode.OK),
                        "GetKeyInfo" to ("insufficient scope" to HttpStatusCode.Forbidden),
                    ),
                ),
            ),
            SecretCache(),
        )

        val failure = assertFailsWith<GarageException> { resolver.resolve("tok", "b1") }

        assertEquals(HttpStatusCode.Forbidden, failure.status)
        assertEquals("GetKeyInfo", failure.operation)
    }

    @Test
    fun usesCacheOnSecondCall() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            val operation = request.url.encodedPath.substringAfterLast('/')
            val body = if (operation == "GetBucketInfo") {
                bucketWithManyKeys
            } else {
                secretBody("GK-owner", "owner-key")
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val resolver = S3CredentialResolver(
            GarageAdminClient("http://garage.test:3903", engine),
            SecretCache(),
        )

        resolver.resolve("tok", "b1")
        val callsAfterFirst = calls
        resolver.resolve("tok", "b1")

        assertEquals(2, callsAfterFirst)   // GetBucketInfo と GetKeyInfo
        assertEquals(callsAfterFirst, calls)
    }
}
```

- [ ] **Step 6: テストが失敗することを確認**

Run: `./gradlew :server:test --tests '*S3CredentialResolverTest*'`
Expected: FAIL（`S3CredentialResolver` が未定義）

- [ ] **Step 7: 問題型と導出を書く**

`server/src/main/kotlin/net/brightroom/garage/server/s3/S3Problems.kt`

```kotlin
package net.brightroom.garage.server.s3

/**
 * S3 ブラウザだけが縮退する 2 つの理由。
 *
 * どちらも HTTP ステータスだけでは区別できず、画面に出す案内も導線も異なるため、
 * RFC 9457 の `type` を持つ（`ProblemTypes`）。
 */

/** そのバケットに read 以上の権限を持つキーが 1 つも無い（spec §6.4 の縮退動作）。 */
class NoUsableKeyException(val bucketId: String) :
    RuntimeException("バケット $bucketId にアクセスできるキーがありません")

/** global alias も local alias も無く、S3 API でアドレスできない（spec §6.5）。 */
class BucketNotAddressableException(val bucketId: String) :
    RuntimeException("バケット $bucketId は S3 API でアドレスできません")
```

`server/src/main/kotlin/net/brightroom/garage/server/s3/S3CredentialResolver.kt`

```kotlin
package net.brightroom.garage.server.s3

import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.getBucketInfo
import net.brightroom.garage.server.garage.getKeyInfo
import net.brightroom.garage.shared.model.garage.BucketKey

/**
 * admin token から S3 の資格情報を導出する（spec §6.4）。
 *
 * scope で事前に判定はしない。`GetBucketInfo` や `GetKeyInfo` が 403 を返したら
 * そのまま伝播させ、S3 ブラウザだけが縮退する（spec §6.3）。
 */
class S3CredentialResolver(
    private val client: GarageAdminClient,
    private val cache: SecretCache,
) {

    suspend fun resolve(token: String, bucketId: String): S3Credentials {
        val tokenHash = hashToken(token)

        cache.get(tokenHash, bucketId)?.let { return it }

        val bucket = client.getBucketInfo(token, bucketId)
        val key = bucket.keys.selectForObjectAccess() ?: throw NoUsableKeyException(bucketId)

        // S3 API はバケット名を要求する。global alias が無ければ、選んだキーから見た
        // local alias を使う（spec §6.5）
        val bucketName = bucket.globalAliases.firstOrNull()
            ?: key.bucketLocalAliases.firstOrNull()
            ?: throw BucketNotAddressableException(bucketId)

        // showSecretKey=true を付けたときだけ secret が入る。Garage が返さないのは想定外
        val secret = client.getKeyInfo(token, key.accessKeyId, showSecret = true).secretAccessKey
            ?: error("GetKeyInfo が secretAccessKey を返しませんでした")

        val credentials = S3Credentials(
            accessKeyId = key.accessKeyId,
            secretAccessKey = secret,
            keyName = key.name,
            bucketName = bucketName,
        )

        cache.put(tokenHash, bucketId, credentials)

        return credentials
    }
}

/**
 * オブジェクト操作に使うキーを選ぶ。
 *
 * owner > read+write > read の優先度。同順位のときは accessKeyId の昇順で
 * 決定的に選ぶ（実行のたびに使うキーが変わると、画面の「どのキーで見ているか」
 * の表示も揺れるため）。read が無いキーは一覧すらできないので対象外。
 */
private fun List<BucketKey>.selectForObjectAccess(): BucketKey? = this
    .filter { it.permissions.rank > 0 }
    .minWithOrNull(
        compareByDescending<BucketKey> { it.permissions.rank }.thenBy { it.accessKeyId },
    )
```

- [ ] **Step 8: テストが通ることを確認**

Run: `./gradlew :server:test --tests '*S3CredentialResolverTest*'`
Expected: PASS

- [ ] **Step 9: コミット**

```bash
git add server/src/main/kotlin/net/brightroom/garage/server/s3/ server/src/test/kotlin/net/brightroom/garage/server/s3/
git commit -m "feat(server): S3 資格情報の導出とキャッシュを追加"
```

---

## Task 10: `/api/buckets/{id}/objects`

**Files:**
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/s3/S3ObjectStore.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/ObjectRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/api/AuthContext.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/api/SessionRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/StatusPages.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Di.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/s3/S3ObjectStoreTest.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/ObjectRoutesTest.kt`

**Interfaces:**
- Consumes: `S3CredentialResolver`（Task 9）、`S3ObjectStore.withClient`（Task 8）、`inspectObject`（Task 5）
- Produces:
  - `S3ObjectStore.list(credentials, prefix, continuationToken): ObjectListing`
  - `S3ObjectStore.put(credentials, key, contentType, contentLength, stream)`
  - `S3ObjectStore.delete(credentials, key)`
  - `S3ObjectStore.download(credentials, key, block)`
  - `fun Route.objectRoutes(client, resolver, store)`
  - `class MissingContentLengthException`
  - `ApplicationCall.respondProblem(..., type, title)` の拡張

| ルート | 中身 |
|---|---|
| `GET /api/buckets/{id}/objects?prefix=&token=` | `ListObjectsV2`（`delimiter = "/"`） |
| `PUT /api/buckets/{id}/objects?key=` | `PutObject`（本文をそのまま流す） |
| `DELETE /api/buckets/{id}/objects?key=` | `DeleteObject` |
| `GET /api/buckets/{id}/objects/content?key=` | `GetObject`（本文をそのまま流す） |
| `GET /api/buckets/{id}/objects/inspect?key=` | Garage の `InspectObject` |

**本文はメモリに載せない。** アップロードは `call.receiveStream().asByteStream(contentLength)`、ダウンロードは `body.writeToOutputStream(...)` で流す。`Content-Length` の無いアップロードは 411 で拒否する（S3 の `PutObject` は長さを要求し、`aws-chunked` に頼らないため）。

**`Content-Disposition` は付けない。** ダウンロードのファイル名はブラウザ側で `a.download` に指定する（P2-2）。サーバーが付けると日本語ファイル名のエンコードを別途扱うことになり、使われないヘッダのために複雑さが増す。

S3 の失敗の写し方:

| 例外 | HTTP | detail |
|---|---|---|
| `NoUsableKeyException` | 409 | 型 `ProblemTypes.NO_USABLE_KEY` を付ける |
| `BucketNotAddressableException` | 409 | 型 `ProblemTypes.BUCKET_NOT_ADDRESSABLE` を付ける |
| `NoSuchKey` | 404 | 「オブジェクトが見つかりません」 |
| その他の `S3Exception` | 502 | 「ストレージへのアクセスに失敗しました」（**SDK のメッセージを載せない**） |

- [ ] **Step 1: 変換の失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/s3/S3ObjectStoreTest.kt`

```kotlin
package net.brightroom.garage.server.s3

import aws.sdk.kotlin.services.s3.model.CommonPrefix
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import aws.sdk.kotlin.services.s3.model.Object as S3Object

class S3ObjectStoreTest {

    @Test
    fun mapsFoldersAndObjects() {
        val response = ListObjectsV2Response {
            commonPrefixes = listOf(CommonPrefix { prefix = "logs/2026/" })
            contents = listOf(
                S3Object {
                    key = "logs/app.log"
                    size = 1024
                    eTag = "\"abc\""
                    lastModified = SmithyInstant.fromEpochSeconds(1_700_000_000, 0)
                },
            )
            nextContinuationToken = "next"
        }

        val listing = response.toListing(prefix = "logs/", keyName = "dev-key")

        assertEquals("logs/", listing.prefix)
        assertEquals(listOf("logs/2026/"), listing.folders)
        assertEquals(listOf("2026/"), listing.folderNames)
        assertEquals("logs/app.log", listing.objects.single().key)
        assertEquals(1024, listing.objects.single().size)
        assertEquals(1_700_000_000, listing.objects.single().lastModified?.epochSeconds)
        assertEquals("next", listing.nextToken)
        assertEquals("dev-key", listing.keyName)
    }

    @Test
    fun dropsTheFolderMarkerItself() {
        // 「logs/」という 0 バイトのキーは、そのフォルダを開いたときに自分自身として現れる
        val response = ListObjectsV2Response {
            contents = listOf(S3Object { key = "logs/"; size = 0 })
        }

        assertEquals(emptyList(), response.toListing(prefix = "logs/", keyName = "k").objects)
    }

    @Test
    fun emptyResponseBecomesEmptyListing() {
        val listing = ListObjectsV2Response { }.toListing(prefix = "", keyName = "k")

        assertEquals(true, listing.isEmpty)
        assertNull(listing.nextToken)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :server:test --tests '*S3ObjectStoreTest*'`
Expected: FAIL（`toListing` が未定義）

- [ ] **Step 3: オブジェクト操作を書く**

`server/src/main/kotlin/net/brightroom/garage/server/s3/S3ObjectStore.kt` を次の内容で置き換える。

```kotlin
package net.brightroom.garage.server.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import java.io.InputStream
import kotlin.time.Instant
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.shared.model.s3.ObjectListing
import net.brightroom.garage.shared.model.s3.StoredObject

/** 1 ページの上限。画面は「続きを読み込む」で継続する。 */
private const val PAGE_SIZE = 1_000

/** フォルダの区切り。S3 に階層は無く、これは一覧のときの見せ方の問題である。 */
private const val DELIMITER = "/"

/**
 * S3 API 越しのオブジェクト操作。
 *
 * spec §9 のポータブルな側であり、Garage 固有の概念を持ち込まない。
 * エンドポイント・リージョン・path-style は設定から受ける。
 *
 * クライアントは呼び出しごとに作って閉じる。資格情報がバケットごとに異なるため
 * 使い回せず、管理コンソールの操作頻度なら接続の作り直しは問題にならない。
 */
class S3ObjectStore(private val config: AppConfig.S3) {

    suspend fun list(
        credentials: S3Credentials,
        prefix: String,
        continuationToken: String?,
    ): ObjectListing = withClient(credentials) { client, bucket ->
        val response = client.listObjectsV2(
            ListObjectsV2Request {
                this.bucket = bucket
                this.prefix = prefix.ifEmpty { null }
                this.continuationToken = continuationToken
                delimiter = DELIMITER
                maxKeys = PAGE_SIZE
            },
        )

        response.toListing(prefix, credentials.keyName)
    }

    /**
     * オブジェクトを置く。
     *
     * [stream] は読み切らずにそのまま S3 へ流す。サーバーのメモリにファイル全体を
     * 載せないため、大きなファイルでも詰まらない。
     */
    suspend fun put(
        credentials: S3Credentials,
        key: String,
        contentType: String,
        contentLength: Long,
        stream: InputStream,
    ) {
        withClient(credentials) { client, bucket ->
            client.putObject(
                PutObjectRequest {
                    this.bucket = bucket
                    this.key = key
                    this.contentType = contentType
                    this.contentLength = contentLength
                    body = stream.asByteStream(contentLength)
                },
            )
        }
    }

    suspend fun delete(credentials: S3Credentials, key: String) {
        withClient(credentials) { client, bucket ->
            client.deleteObject(
                DeleteObjectRequest {
                    this.bucket = bucket
                    this.key = key
                },
            )
        }
    }

    /**
     * オブジェクトを取り出す。
     *
     * 本文は [block] の中でだけ有効なので、レスポンスへの書き出しもその中で行う。
     * 呼び出し側は受け取った [ByteStream] をそのまま出力に流すこと。
     */
    suspend fun <T> download(
        credentials: S3Credentials,
        key: String,
        block: suspend (contentType: String, body: ByteStream) -> T,
    ): T = withClient(credentials) { client, bucket ->
        client.getObject(
            GetObjectRequest {
                this.bucket = bucket
                this.key = key
            },
        ) { response ->
            block(
                response.contentType ?: "application/octet-stream",
                response.body ?: ByteStream.fromBytes(ByteArray(0)),
            )
        }
    }

    private suspend fun <T> withClient(
        credentials: S3Credentials,
        block: suspend (S3Client, String) -> T,
    ): T = S3Client {
        region = config.region
        endpointUrl = Url.parse(config.endpoint)
        forcePathStyle = config.pathStyle
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = credentials.accessKeyId
            secretAccessKey = credentials.secretAccessKey
        }
    }.use { client -> block(client, credentials.bucketName) }
}

/**
 * SDK の応答をコンソールの一覧に写す。
 *
 * 接頭辞そのもののキー（0 バイトのフォルダマーカー）は、そのフォルダを開いたときに
 * 自分自身として現れるため落とす。
 */
internal fun ListObjectsV2Response.toListing(prefix: String, keyName: String): ObjectListing =
    ObjectListing(
        prefix = prefix,
        folders = commonPrefixes.orEmpty().mapNotNull { it.prefix },
        objects = contents.orEmpty()
            .mapNotNull { entry ->
                val key = entry.key ?: return@mapNotNull null
                if (key == prefix) return@mapNotNull null

                StoredObject(
                    key = key,
                    size = entry.size ?: 0,
                    lastModified = entry.lastModified?.let {
                        Instant.fromEpochSeconds(it.epochSeconds, it.nanosecondsOfSecond)
                    },
                    etag = entry.eTag,
                )
            },
        nextToken = nextContinuationToken,
        keyName = keyName,
    )
```

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :server:test --tests '*S3ObjectStoreTest*'`
Expected: PASS

- [ ] **Step 5: problem の type を渡せるようにする**

`server/src/main/kotlin/net/brightroom/garage/server/api/AuthContext.kt` の `respondProblem` を次のようにする。

```kotlin
/**
 * RFC 9457 の定める `application/problem+json` で返す。
 *
 * [type] を省略した場合は `about:blank` とみなされ、[title] にはその status の
 * 推奨理由句を使う。コンソール固有の問題型（`ProblemTypes`）を返すときだけ
 * [type] と [title] を明示する。
 */
suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    detail: String? = null,
    operation: String? = null,
    type: String? = null,
    title: String = status.description,
) {
    val problem = ProblemDetails(
        title = title,
        status = status.value,
        detail = detail,
        type = type,
        instance = request.path(),
        operation = operation,
    )

    respondText(
        text = GarageJson.encodeToString(problem),
        contentType = ContentType("application", "problem+json"),
        status = status,
    )
}
```

同じファイルの末尾に例外を 1 つ足す。

```kotlin
/** アップロードに `Content-Length` が無い。S3 の PutObject は長さを要求する。 */
class MissingContentLengthException : RuntimeException("Content-Length ヘッダが必要です")
```

- [ ] **Step 6: StatusPages に S3 の失敗を足す**

`server/src/main/kotlin/net/brightroom/garage/server/plugins/StatusPages.kt` の `exception<Throwable>` より前に足す。

```kotlin
        // S3 ブラウザだけが縮退する 2 つ。web が案内を出し分けられるよう型を付ける
        exception<NoUsableKeyException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.Conflict,
                detail = cause.message,
                type = ProblemTypes.NO_USABLE_KEY,
                title = "利用できるアクセスキーがありません",
            )
        }

        exception<BucketNotAddressableException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.Conflict,
                detail = cause.message,
                type = ProblemTypes.BUCKET_NOT_ADDRESSABLE,
                title = "S3 でアドレスできないバケットです",
            )
        }

        exception<MissingContentLengthException> { call, cause ->
            call.respondProblem(status = HttpStatusCode.LengthRequired, detail = cause.message)
        }

        exception<NoSuchKey> { call, _ ->
            call.respondProblem(
                status = HttpStatusCode.NotFound,
                detail = "オブジェクトが見つかりません",
            )
        }

        // S3 側の失敗。SDK のメッセージは外に出さない（資格情報や内部の詳細を含みうる）
        exception<S3Exception> { call, cause ->
            call.application.log.error("S3 request failed at ${call.request.path()}", cause)
            call.respondProblem(
                status = HttpStatusCode.BadGateway,
                detail = "ストレージへのアクセスに失敗しました",
            )
        }
```

import を足す。

```kotlin
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.S3Exception
import net.brightroom.garage.server.api.MissingContentLengthException
import net.brightroom.garage.server.s3.BucketNotAddressableException
import net.brightroom.garage.server.s3.NoUsableKeyException
import net.brightroom.garage.shared.api.ProblemTypes
```

**`NoSuchKey` は `S3Exception` のサブクラスなので、より具体的な方を先に書く。** StatusPages は登録順ではなく型の近さで選ぶが、読み手のために順序も合わせる。

- [ ] **Step 7: ルートの失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/api/ObjectRoutesTest.kt`

S3 に到達する前に決まる経路だけをここで検証する。実際の転送は e2e（Task 19）で担保する。

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
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.ProblemTypes
import net.brightroom.garage.shared.model.garage.ObjectInspection
import kotlin.test.Test
import kotlin.test.assertEquals

class ObjectRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun bucketBody(keys: String, aliases: String = """["dev-bucket"]""") = """
        {"id":"b1","globalAliases":$aliases,"websiteAccess":false,"objects":0,"bytes":0,
         "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
         "unfinishedMultipartUploadBytes":0,"quotas":{},"keys":$keys}
    """.trimIndent()

    private val ownerKey = """
        [{"accessKeyId":"GK01","name":"dev-key","bucketLocalAliases":[],
          "permissions":{"owner":true,"read":true,"write":true}}]
    """.trimIndent()

    private fun engineOf(responses: Map<String, Pair<String, HttpStatusCode>>) = MockEngine { request ->
        val operation = request.url.encodedPath.substringAfterLast('/')
        val (body, status) = responses[operation] ?: error("unexpected operation: $operation")
        respond(body, status, jsonHeaders)
    }

    @Test
    fun requiresKeyParameterForDownload() = testApplication {
        garageApp(engineOf(mapOf("GetBucketInfo" to (bucketBody(ownerKey) to HttpStatusCode.OK))))

        val response = client.get("/api/buckets/b1/objects/content") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun rejectsUploadWithoutContentLength() = testApplication {
        garageApp(engineOf(mapOf("GetBucketInfo" to (bucketBody(ownerKey) to HttpStatusCode.OK))))

        // Transfer-Encoding は Ktor client が拒む（unsafe header）ため手では付けられない。
        // 長さを持たない本文を送ると Content-Length が付かず、同じ経路を通る
        val response = client.put("/api/buckets/b1/objects?key=a.txt") {
            header(HttpHeaders.Authorization, "Bearer tok")
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeStringUtf8("hello")
                    }
                },
            )
        }

        assertEquals(HttpStatusCode.LengthRequired, response.status)
    }

    @Test
    fun reportsForbiddenFromKeyLookup() = testApplication {
        // scope に GetKeyInfo が無いトークン。S3 ブラウザだけが縮退する（spec §6.4）
        garageApp(
            engineOf(
                mapOf(
                    "GetBucketInfo" to (bucketBody(ownerKey) to HttpStatusCode.OK),
                    "GetKeyInfo" to ("insufficient scope" to HttpStatusCode.Forbidden),
                ),
            ),
        )

        val response = client.get("/api/buckets/b1/objects") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("GetKeyInfo", problem.operation)
    }

    @Test
    fun reportsMissingUsableKeyWithItsOwnType() = testApplication {
        val writeOnly = """
            [{"accessKeyId":"GK01","name":"writer","bucketLocalAliases":[],
              "permissions":{"owner":false,"read":false,"write":true}}]
        """.trimIndent()
        garageApp(engineOf(mapOf("GetBucketInfo" to (bucketBody(writeOnly) to HttpStatusCode.OK))))

        val response = client.get("/api/buckets/b1/objects") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals(ProblemTypes.NO_USABLE_KEY, problem.type)
    }

    @Test
    fun reportsUnaddressableBucketWithItsOwnType() = testApplication {
        garageApp(
            engineOf(
                mapOf("GetBucketInfo" to (bucketBody(ownerKey, aliases = "[]") to HttpStatusCode.OK)),
            ),
        )

        val response = client.get("/api/buckets/b1/objects") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals(ProblemTypes.BUCKET_NOT_ADDRESSABLE, problem.type)
    }

    @Test
    fun inspectsObjectWithoutTouchingS3() = testApplication {
        garageApp(
            engineOf(
                mapOf(
                    "InspectObject" to (
                        """
                        {"bucketId":"b1","key":"a.txt","versions":[
                          {"uuid":"v1","timestamp":"2026-08-22T16:43:38.636Z","encrypted":false,
                           "uploading":false,"aborted":false,"deleteMarker":false,"inline":true,
                           "size":5,"blocks":[]}]}
                        """.trimIndent() to HttpStatusCode.OK
                        ),
                ),
            ),
        )

        val response = client.get("/api/buckets/b1/objects/inspect?key=a.txt") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val inspection = GarageJson.decodeFromString<ObjectInspection>(response.bodyAsText())
        assertEquals("a.txt", inspection.key)
    }

    @Test
    fun requiresToken() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/buckets/b1/objects").status)
    }
}
```

- [ ] **Step 8: ルートを書く**

`server/src/main/kotlin/net/brightroom/garage/server/api/ObjectRoutes.kt`

```kotlin
package net.brightroom.garage.server.api

import aws.smithy.kotlin.runtime.content.writeToOutputStream
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.inspectObject
import net.brightroom.garage.server.s3.S3CredentialResolver
import net.brightroom.garage.server.s3.S3ObjectStore

/**
 * オブジェクトのルート。
 *
 * 一覧・転送・削除は S3 API を、詳細は Garage の `InspectObject` を使う。
 * どちらもブラウザからは同じ `/api/buckets/{id}/objects/**` に見える。
 *
 * 本文はメモリに載せずに流す。アップロードは `Content-Length` を要求する
 * （S3 の PutObject が長さを求めるため）。
 */
fun Route.objectRoutes(
    client: GarageAdminClient,
    resolver: S3CredentialResolver,
    store: S3ObjectStore,
) {
    route("/buckets/{id}/objects") {

        get {
            val token = call.adminToken()
            val credentials = resolver.resolve(token, call.pathParam("id"))

            call.respond(
                store.list(
                    credentials = credentials,
                    prefix = call.request.queryParameters["prefix"].orEmpty(),
                    continuationToken = call.request.queryParameters["token"],
                ),
            )
        }

        put {
            val token = call.adminToken()
            val credentials = resolver.resolve(token, call.pathParam("id"))
            val contentLength = call.request.contentLength() ?: throw MissingContentLengthException()

            store.put(
                credentials = credentials,
                key = call.queryParam("key"),
                contentType = call.request.contentType().toString(),
                contentLength = contentLength,
                stream = call.receiveStream(),
            )

            call.respond(HttpStatusCode.NoContent)
        }

        delete {
            val token = call.adminToken()
            val credentials = resolver.resolve(token, call.pathParam("id"))

            store.delete(credentials, call.queryParam("key"))

            call.respond(HttpStatusCode.NoContent)
        }

        get("/content") {
            val token = call.adminToken()
            val key = call.queryParam("key")
            val credentials = resolver.resolve(token, call.pathParam("id"))

            // Content-Disposition は付けない。ファイル名はブラウザ側が決める（P2-2）
            store.download(credentials, key) { contentType, body ->
                call.respondOutputStream(contentType = ContentType.parse(contentType)) {
                    body.writeToOutputStream(this)
                }
            }
        }

        get("/inspect") {
            call.respond(
                client.inspectObject(call.adminToken(), call.pathParam("id"), call.queryParam("key")),
            )
        }
    }
}
```

- [ ] **Step 9: ログアウトでキャッシュを捨てる**

`server/src/main/kotlin/net/brightroom/garage/server/api/SessionRoutes.kt` を次のようにする。Phase 1 で「Phase 2 で配線する」と残したコメントを実装で置き換える。

```kotlin
fun Route.sessionRoutes(client: GarageAdminClient, cache: SecretCache) {
    route("/session") {

        get {
            val token = client.requireValidToken(call.adminToken())

            call.respond(token.toSession())
        }

        post("/logout") {
            // トークンの検証は行わない。ログアウトは失敗しないほうが利用者に親切である。
            //
            // サーバーが持つ唯一の状態は S3 secret のキャッシュなので、それを捨てる。
            // 引けるのは同じトークンを提示できる者だけであり、これは機密性の担保
            // というより後始末である（spec §6.6）。
            cache.purge(hashToken(call.adminToken()))

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

import を足す。

```kotlin
import net.brightroom.garage.server.s3.SecretCache
import net.brightroom.garage.server.s3.hashToken
```

- [ ] **Step 10: DI とルーティングを更新する**

`server/src/main/kotlin/net/brightroom/garage/server/plugins/Di.kt`

```kotlin
    dependencies {
        provide<AppConfig> { appConfig }
        provide<GarageAdminClient> { GarageAdminClient(appConfig.admin.endpoint) }
        provide<OverviewService> { OverviewService(resolve<GarageAdminClient>()) }
        provide<SecretCache> { SecretCache() }
        provide<S3ObjectStore> { S3ObjectStore(appConfig.s3) }
        provide<S3CredentialResolver> {
            S3CredentialResolver(resolve<GarageAdminClient>(), resolve<SecretCache>())
        }
    }
```

`server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`

```kotlin
fun Application.configureRouting() {
    val client: GarageAdminClient by dependencies
    val overviewService: OverviewService by dependencies
    val cache: SecretCache by dependencies
    val resolver: S3CredentialResolver by dependencies
    val objectStore: S3ObjectStore by dependencies

    routing {
        route("/api") {
            sessionRoutes(client, cache)
            overviewRoutes(overviewService)
            bucketRoutes(client)
            keyRoutes(client)
            objectRoutes(client, resolver, objectStore)
        }
    }
}
```

`server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt` も同じ構成にする。テストでは S3 の接続先はどこでもよい（S3 に到達する経路はテストしない）。

```kotlin
fun ApplicationTestBuilder.garageApp(engine: MockEngine) {
    environment {
        config = MapApplicationConfig(
            "garage.admin.endpoint" to "http://garage.test:3903",
            "garage.s3.endpoint" to "http://garage.test:3900",
            "garage.s3.region" to "garage",
            "garage.s3.pathStyle" to "true",
        )
    }
    application {
        val client = GarageAdminClient("http://garage.test:3903", engine)
        val cache = SecretCache()
        val s3Config = AppConfig.S3(
            endpoint = "http://garage.test:3900",
            region = "garage",
            pathStyle = true,
        )
        configureSerialization()
        configureStatusPages()
        routing {
            route("/api") {
                sessionRoutes(client, cache)
                bucketRoutes(client)
                keyRoutes(client)
                objectRoutes(client, S3CredentialResolver(client, cache), S3ObjectStore(s3Config))
            }
        }
    }
}
```

- [ ] **Step 11: テストが通ることを確認**

Run: `./gradlew :server:test`
Expected: PASS

- [ ] **Step 12: 実機で往復を確認**

```bash
docker compose up -d
./gradlew :server:run
```

別の端末で:

```bash
TOKEN=$(docker compose logs garage-init | sed -n 's/.*Console login token: //p' | tr -d '\r')
BUCKET=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/buckets | jq -r '.[0].id')

echo "hello garage" > /tmp/probe.txt
curl -s -X PUT --data-binary @/tmp/probe.txt \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" \
  "http://localhost:8080/api/buckets/$BUCKET/objects?key=probe.txt" -w "%{http_code}\n"

curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/buckets/$BUCKET/objects" | jq
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/buckets/$BUCKET/objects/content?key=probe.txt"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/buckets/$BUCKET/objects/inspect?key=probe.txt" | jq
curl -s -X DELETE -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/buckets/$BUCKET/objects?key=probe.txt" -w "%{http_code}\n"
```

Expected: 204 → 一覧に `probe.txt` → 本文 `hello garage` → バージョン情報 → 204

- [ ] **Step 13: コミット**

```bash
git add server/src/main/kotlin/net/brightroom/garage/server/ server/src/test/kotlin/net/brightroom/garage/server/
git commit -m "feat(server): オブジェクトの一覧・転送・削除を追加"
```

---

## Task 11: dev 環境の整理

**Files:**
- Modify: `docker/init-garage.sh`

**Interfaces:**
- Consumes: なし
- Produces: `docker compose logs garage-init` が `Console login token:` と `Limited-scope token:` を表示する

spec §11 が求める後始末を行う。

1. 「`mise.toml` に `GARAGE_S3_ACCESS_KEY_ID` を書け」という案内を削除する。S3 資格情報は admin token から導出するため不要になった
2. **scope を絞った admin token を追加で発行する。** Task 19 の scope 縮退 e2e が要求する（P2-10）

`dev-limited` の scope は `GetKeyInfo` を含まない。これにより「バケットとキーの一覧は見えるが、S3 ブラウザだけが縮退する」という spec §6.4 の状態を dev と CI で再現できる。

- [ ] **Step 1: S3 資格情報の案内を消す**

`docker/init-garage.sh` のアクセスキー作成部分から、`mise.toml` への案内を落とす。キー自体は `dev-key` として作り続ける（オブジェクト操作の権限付与に要る）。

```sh
if [ "${KEY_COUNT}" = "0" ]; then
  echo "Creating access key..."
  KEY_RESPONSE=$(curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name": "dev-key"}' \
    "${GARAGE_ADMIN}/v2/CreateKey")

  ACCESS_KEY_ID=$(echo "${KEY_RESPONSE}" | jq -r '.accessKeyId')

  # secret はコンソールが admin token から導出するため、ここでは表示しない
  echo "Access key 'dev-key' created (${ACCESS_KEY_ID})."
else
  echo "Access key already exists, skipping."
  ACCESS_KEY_ID=$(echo "${EXISTING_KEYS}" | jq -r '.[0].id')
fi
```

- [ ] **Step 2: scope を絞ったトークンを発行する**

`dev-console` トークンを作る部分の後ろに足す。

```sh
# Create a scope-limited token for testing degraded views
LIMITED_TOKEN_COUNT=$(echo "${EXISTING_TOKENS}" | jq '[.[] | select(.name == "dev-limited")] | length')

if [ "${LIMITED_TOKEN_COUNT}" = "0" ]; then
  echo "Creating admin token 'dev-limited'..."
  # GetKeyInfo を含まない。S3 資格情報の導出だけが 403 になり、
  # オブジェクトブラウザの縮退を再現できる
  LIMITED_RESPONSE=$(curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name": "dev-limited", "neverExpires": true, "scope": [
          "GetCurrentAdminTokenInfo", "GetClusterHealth", "GetClusterStatus",
          "GetClusterLayout", "ListBuckets", "GetBucketInfo", "ListKeys",
          "ListBlockErrors"]}' \
    "${GARAGE_ADMIN}/v2/CreateAdminToken")

  LIMITED_TOKEN=$(echo "${LIMITED_RESPONSE}" | jq -r '.secretToken')

  echo "============================================"
  echo "Limited-scope token: ${LIMITED_TOKEN}"
  echo "============================================"
else
  echo "Admin token 'dev-limited' already exists."
fi
```

- [ ] **Step 3: dev 環境を作り直して確認**

```bash
docker compose down -v
docker compose up -d
docker compose logs garage-init
```

Expected: `Console login token:` と `Limited-scope token:` が表示され、`mise.toml` への案内が消えている

- [ ] **Step 4: 縮退が再現することを確認**

```bash
./gradlew :server:run
```

別の端末で:

```bash
LIMITED=$(docker compose logs garage-init | sed -n 's/.*Limited-scope token: //p' | tr -d '\r')
BUCKET=$(curl -s -H "Authorization: Bearer $LIMITED" http://localhost:8080/api/buckets | jq -r '.[0].id')
curl -s -H "Authorization: Bearer $LIMITED" "http://localhost:8080/api/buckets/$BUCKET/objects" | jq
```

Expected: バケット一覧は 200 で返り、オブジェクト一覧は 403 の problem details（`operation: "GetKeyInfo"`）になる

- [ ] **Step 5: コミット**

```bash
git add docker/init-garage.sh
git commit -m "chore(dev): S3 資格情報の案内を削除し scope 制限トークンを発行する"
```

- [ ] **Step 6: PR を出す**

```bash
git push -u origin phase2/3-server-s3
gh pr create --title "feat(server): S3 資格情報の導出とオブジェクト操作を追加" --body "Phase 2 のサーバー（S3 側）。admin token から S3 資格情報を導出し、TTL 5 分でキャッシュして、オブジェクトの一覧・転送・削除を行う。

- キー選択は owner > read+write > read、同順位は accessKeyId の昇順
- キャッシュのキーは SHA-256(admin token) とバケット ID。ログアウトで破棄する
- S3 ブラウザだけが縮退する 2 つの理由に RFC 9457 の type を付ける
- 本文はメモリに載せず、そのまま流す

計画: docs/superpowers/plans/2026-08-23-rebuild-phase2-storage.md の Task 8-11"
```

---

## Task 12: `:web` の共通コンポーネントと API クライアントの拡張

**Files:**
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/api/ApiClient.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/session/SessionState.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/overview/OverviewScreen.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/StateViews.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/DataTable.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/ConfirmDialog.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/CopyButton.kt`

**Interfaces:**
- Consumes: `ProblemDetails` / `ProblemTypes`（`:shared`）
- Produces:
  - `ApiResult.Failure(status: HttpStatusCode, problem: ProblemDetails)`
  - `ApiClient.sendText(method, path, json)` と拡張 `getJson` / `sendJson` / `sendEmpty`
  - `DataTable(items, columns, ...)` / `Column<T>`
  - `ConfirmDialog(title, message, confirmLabel, requiredInput, onConfirm, onDismiss)`
  - `CopyButton(value, label)`
  - `EmptyState(message, actionLabel, onAction)` / `ProblemView(problem, onRetry)`

**ブランチ:** `phase2/4-web-storage`（Task 12–16 を 1 PR にする）

```bash
git switch main
git pull
git switch -c phase2/4-web-storage
```

Phase 1 の `ApiResult.Failure` は problem details しか持たない。Phase 2 は **403（scope 不足）と 409（S3 の縮退）で画面の出し分けが変わる**ため、HTTP のステータスを運ぶ必要がある。spec §7.1 が禁じているのは「DTO の `status` フィールドで分岐すること」であり、HTTP レスポンスのステータスで分岐するのは本来の姿である。

`:web` は `ktor-http` を既に持っているので、`HttpStatusCode` をそのまま使える。

- [ ] **Step 1: API クライアントを拡張する**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/api/ApiClient.kt` を次の内容で置き換える。

```kotlin
package net.brightroom.garage.web.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.api.ProblemDetails

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * `/api` の呼び出し結果。
 *
 * 401 は「トークンが無効になった」を意味し、画面をログインへ戻す必要があるため
 * 通常の失敗と区別する。それ以外の分岐（403 の scope 不足、409 の S3 縮退）は
 * [Failure.status] と [ProblemDetails.type] で判断する。
 *
 * 分岐に使うのは HTTP レスポンスのステータスであって、problem details の
 * `status` フィールドではない（spec §7.1）。
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val status: HttpStatusCode, val problem: ProblemDetails) : ApiResult<Nothing>
    data object Unauthorized : ApiResult<Nothing>
}

/** 利用者に見せる文言。RFC 9457 の `detail` は省略されうるので `title` に落とす。 */
val ProblemDetails.displayMessage: String get() = detail ?: title

/**
 * @param tokenProvider 現在のセッションのトークンを返す。未ログインなら null。
 */
class ApiClient(private val tokenProvider: () -> String?) {

    private val http = HttpClient(Js) {
        expectSuccess = false
    }

    suspend fun getText(path: String): ApiResult<String> = sendText(HttpMethod.Get, path)

    /**
     * @param json 送る本文。null なら本文なしで送る。
     */
    suspend fun sendText(
        method: HttpMethod,
        path: String,
        json: String? = null,
    ): ApiResult<String> = runCatching {
        http.request(path) {
            this.method = method
            authorize()
            if (json != null) {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
        }
    }.fold(
        onSuccess = { it.toResult { body -> body } },
        onFailure = { ApiResult.Failure(HttpStatusCode.ServiceUnavailable, networkProblem(it)) },
    )

    private fun HttpRequestBuilder.authorize() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun <T> HttpResponse.toResult(transform: (String) -> T): ApiResult<T> {
        val body = bodyAsText()

        return when {
            status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
            status.isSuccess() -> ApiResult.Success(transform(body))
            else -> ApiResult.Failure(status, parseProblem(body, status))
        }
    }

    /** サーバーは RFC 9457 の problem details を返す。壊れていた場合だけ自前で組み立てる。 */
    private fun parseProblem(body: String, status: HttpStatusCode): ProblemDetails =
        runCatching { AppJson.decodeFromString<ProblemDetails>(body) }
            .getOrElse { problemOf(status, "サーバーからの応答を解釈できませんでした") }

    private fun networkProblem(cause: Throwable): ProblemDetails =
        problemOf(
            status = HttpStatusCode.ServiceUnavailable,
            detail = "サーバーに接続できませんでした: ${cause.message ?: "原因不明"}",
        )
}

/**
 * サーバーからの応答が使えないときに、クライアント側で組み立てる problem details。
 *
 * `type` は省略するため、`title` にはその status の推奨理由句を使う。
 */
private fun problemOf(status: HttpStatusCode, detail: String): ProblemDetails =
    ProblemDetails(title = status.description, status = status.value, detail = detail)

/** 本文を [deserializer] でデコードして返す。 */
suspend fun <T> ApiClient.getJson(
    path: String,
    deserializer: DeserializationStrategy<T>,
): ApiResult<T> = decode(getText(path), deserializer)

/** JSON を送り、返ってきた JSON をデコードする。 */
suspend fun <T> ApiClient.sendJson(
    method: HttpMethod,
    path: String,
    body: String?,
    deserializer: DeserializationStrategy<T>,
): ApiResult<T> = decode(sendText(method, path, body), deserializer)

/** 応答の本文を読まない呼び出し（204 を返すもの）。 */
suspend fun ApiClient.sendEmpty(
    method: HttpMethod,
    path: String,
    body: String? = null,
): ApiResult<Unit> = when (val result = sendText(method, path, body)) {
    is ApiResult.Success -> ApiResult.Success(Unit)
    is ApiResult.Failure -> result
    ApiResult.Unauthorized -> ApiResult.Unauthorized
}

private fun <T> decode(
    raw: ApiResult<String>,
    deserializer: DeserializationStrategy<T>,
): ApiResult<T> = when (raw) {
    is ApiResult.Success ->
        runCatching { ApiResult.Success(AppJson.decodeFromString(deserializer, raw.value)) }
            .getOrElse {
                ApiResult.Failure(
                    HttpStatusCode.InternalServerError,
                    problemOf(
                        status = HttpStatusCode.InternalServerError,
                        detail = "サーバーからの応答を解釈できませんでした",
                    ),
                )
            }

    is ApiResult.Failure -> raw
    ApiResult.Unauthorized -> ApiResult.Unauthorized
}
```

- [ ] **Step 2: 既存の呼び出し元を直す**

`SessionState.kt` の `signOut` は `postEmpty` を使っている。

```kotlin
        api.sendEmpty(HttpMethod.Post, "/api/session/logout")
```

`import io.ktor.http.HttpMethod` と `net.brightroom.garage.web.api.sendEmpty` を足し、`postEmpty` の import を消す。

`OverviewScreen.kt` の `is ApiResult.Failure -> error = result.problem.displayMessage` はそのままで動く（`Failure` の分解にステータスを使っていないため）。

Run: `./gradlew :web:compileKotlinWasmJs`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 状態表示を足す**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/StateViews.kt` に追加する（既存の `LoadingView` / `ErrorView` / `DeniedView` はそのまま）。

```kotlin
/**
 * 何も無いことを伝える。次にできることが分かっているなら導線を置く。
 */
@Composable
fun EmptyState(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * 失敗をそのまま画面に出す。
 *
 * 403 は scope 不足であり、コンソールの不具合ではないことが伝わる文言にする
 * （spec §6.3）。それ以外は problem details の文言をそのまま見せる。
 */
@Composable
fun ProblemView(
    problem: ProblemDetails,
    status: HttpStatusCode,
    onRetry: (() -> Unit)? = null,
) {
    if (status == HttpStatusCode.Forbidden) {
        DeniedView(problem.operation ?: "不明な operation")
        return
    }

    ErrorView(problem.displayMessage, onRetry)
}
```

import を足す。

```kotlin
import androidx.compose.foundation.layout.Column
import io.ktor.http.HttpStatusCode
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.web.api.displayMessage
```

- [ ] **Step 4: 表を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/DataTable.kt`

```kotlin
package net.brightroom.garage.web.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 表の 1 列。
 *
 * @param value 絞り込みと既定の並べ替えに使う文字列表現。
 * @param comparator 並べ替えの規則。null なら [value] の辞書順。
 *   数値や日時の列は明示的に渡す（文字列の辞書順では順序が狂うため）。
 * @param content セルの描画。null なら [value] をそのまま出す。
 */
data class Column<T>(
    val title: String,
    val value: (T) -> String,
    val weight: Float = 1f,
    val comparator: Comparator<T>? = null,
    val content: (@Composable (T) -> Unit)? = null,
)

/**
 * 絞り込みと並べ替えを持つ表（spec §8.7）。
 *
 * 状態は表の中に閉じる。画面が持つのは元のデータだけでよい。
 */
@Composable
fun <T> DataTable(
    items: List<T>,
    columns: List<Column<T>>,
    modifier: Modifier = Modifier,
    searchPlaceholder: String = "絞り込み",
    emptyMessage: String = "項目がありません",
    onRowClick: ((T) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var sortIndex by remember { mutableStateOf(0) }
    var ascending by remember { mutableStateOf(true) }

    val filtered = items.filter { item ->
        query.isBlank() || columns.any { it.value(item).contains(query, ignoreCase = true) }
    }

    val column = columns.getOrNull(sortIndex)
    val comparator = column?.comparator ?: column?.let { current ->
        compareBy<T> { current.value(it) }
    }
    val sorted = when {
        comparator == null -> filtered
        ascending -> filtered.sortedWith(comparator)
        else -> filtered.sortedWith(comparator.reversed())
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(searchPlaceholder) },
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            columns.forEachIndexed { index, current ->
                val marker = when {
                    index != sortIndex -> ""
                    ascending -> " ▲"
                    else -> " ▼"
                }

                Text(
                    current.title + marker,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(current.weight)
                        .clickable {
                            if (index == sortIndex) ascending = !ascending else sortIndex = index
                        },
                )
            }
        }

        HorizontalDivider()

        if (sorted.isEmpty()) {
            EmptyState(if (items.isEmpty()) emptyMessage else "条件に合う項目がありません")
            return@Column
        }

        sorted.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base -> onRowClick?.let { base.clickable { it(item) } } ?: base }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                columns.forEach { current ->
                    Column(modifier = Modifier.weight(current.weight)) {
                        current.content?.invoke(item)
                            ?: Text(current.value(item), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            HorizontalDivider()
        }
    }
}
```

- [ ] **Step 5: 確認ダイアログを書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/ConfirmDialog.kt`

```kotlin
package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * 破壊的な操作の確認（spec §8.6）。
 *
 * **確認ボタンの文言は既定の「実行」のままにする。** 代わりに、それを開く画面側の
 * ボタンへ「バケットを削除」「権限を外す」のような固有の名前を付ける。同じ画面に
 * 「削除」が複数あると、押した対象を取り違えるし、e2e からも区別できない。
 *
 * @param requiredInput 指定すると、その文字列を打ち込むまで実行できない。
 *   バケットの削除のように、取り返しがつかず対象を取り違えやすい操作に使う。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "実行",
    requiredInput: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val enabled = requiredInput == null || typed == requiredInput

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)

                if (requiredInput != null) {
                    Text(
                        "確認のため「$requiredInput」と入力してください",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
```

- [ ] **Step 6: コピーボタンを書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/CopyButton.kt`

```kotlin
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * クリップボードへ書き込む。
 *
 * `kotlinx-browser` の wasmJs 向け Navigator に clipboard が無いため、
 * `js()` で直接呼ぶ（`OverviewScreen` の `document.hidden` と同じやり方）。
 */
private fun writeToClipboard(value: String): Unit =
    js("navigator.clipboard.writeText(value)")

@Composable
fun CopyButton(value: String, label: String = "コピー") {
    var copied by remember { mutableStateOf(false) }

    TextButton(
        onClick = {
            writeToClipboard(value)
            copied = true
        },
    ) {
        Text(if (copied) "コピーしました" else label)
    }
}
```

- [ ] **Step 7: interop が動くことを確認する**

`js()` は関数本体としてのみ使え、その関数の引数を参照できる。この形が wasmJs で通ることを、画面を作る前に確かめておく。

`js()` はトップレベル関数の**式本体**としてしか書けない（ブロック本体の中に置くとコンパイルが通らない）。上の形はその制約に合わせてある。

Run: `./gradlew :web:compileKotlinWasmJs`
Expected: BUILD SUCCESSFUL

それでも通らない場合は、`@JsFun` を使う形に切り替える（Task 17 で使う形と同じ）。

```kotlin
@JsFun("(value) => navigator.clipboard.writeText(value)")
private external fun writeToClipboard(value: String)
```

- [ ] **Step 8: コミット**

```bash
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/api/ApiClient.kt web/src/wasmJsMain/kotlin/net/brightroom/garage/web/session/SessionState.kt web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/
git commit -m "feat(web): 表・確認ダイアログ・コピーの共通部品を追加"
```

---

## Task 13: バケット一覧

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketsScreen.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: `DataTable` / `Column` / `EmptyState` / `ProblemView`（Task 12）、`BucketSummary`（Task 1）、`CreateBucketRequest`（Task 3）
- Produces: `@Composable fun BucketsScreen(onOpen: (String) -> Unit)`

spec §8.5 のとおり、バケット一覧は**手動更新のみ**でポーリングしない。

- [ ] **Step 1: 画面を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketsScreen.kt`

```kotlin
package net.brightroom.garage.web.screens.buckets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.shared.api.CreateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketSummary
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.Column as TableColumn
import net.brightroom.garage.web.components.DataTable
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.session.LocalSession

@Composable
fun BucketsScreen(onOpen: (String) -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var buckets by remember { mutableStateOf<List<BucketSummary>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var creating by remember { mutableStateOf(false) }

    suspend fun load() {
        when (
            val result = session.api.getJson(
                "/api/buckets",
                ListSerializer(BucketSummary.serializer()),
            )
        ) {
            is ApiResult.Success -> {
                buckets = result.value
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
            Text(
                "バケット",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            Button(onClick = { creating = true }) { Text("バケットを作成") }
        }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = buckets) {
            null -> if (failure == null) LoadingView()
            else -> BucketTable(current, onOpen)
        }
    }

    if (creating) {
        CreateBucketDialog(
            onDismiss = { creating = false },
            onCreated = {
                creating = false
                scope.launch { load() }
            },
        )
    }
}

@Composable
private fun BucketTable(buckets: List<BucketSummary>, onOpen: (String) -> Unit) {
    DataTable(
        items = buckets,
        onRowClick = { onOpen(it.id) },
        emptyMessage = "バケットがありません",
        searchPlaceholder = "名前や ID で絞り込み",
        columns = listOf(
            TableColumn(
                title = "名前",
                weight = 2f,
                value = { it.displayName },
            ),
            TableColumn(
                title = "ID",
                weight = 2f,
                value = { it.id },
                content = { bucket ->
                    Text(
                        bucket.id.take(16),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ),
            TableColumn(
                title = "別名",
                value = { bucket ->
                    (bucket.globalAliases + bucket.localAliases.map { it.alias }).joinToString(", ")
                },
            ),
            TableColumn(
                title = "作成",
                value = { it.created?.toString().orEmpty() },
                comparator = compareBy { it.created },
                content = { bucket ->
                    Text(
                        bucket.created?.toString()?.substringBefore('T') ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            ),
        ),
    )
}

/**
 * バケットの作成。
 *
 * global alias は省略できる（Garage は alias 無しのバケットを許す）。ただし
 * alias が無いと S3 でアドレスできないため、その旨を添える（spec §6.5）。
 */
@Composable
private fun CreateBucketDialog(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("バケットを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("グローバル別名") },
                    singleLine = true,
                )
                Text(
                    "別名を付けないバケットは S3 API から参照できません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val body = AppJson.encodeToString(
                            CreateBucketRequest.serializer(),
                            CreateBucketRequest(globalAlias = name.ifBlank { null }),
                        )

                        when (
                            val result = session.api.sendJson(
                                HttpMethod.Post,
                                "/api/buckets",
                                body,
                                BucketInfo.serializer(),
                            )
                        ) {
                            is ApiResult.Success -> onCreated()
                            is ApiResult.Failure -> {
                                error = result.problem.displayMessage
                                sending = false
                            }

                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                },
            ) {
                Text("作成")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
```

`displayMessage` の import を足す（`net.brightroom.garage.web.api.displayMessage`）。

- [ ] **Step 2: App に接続する**

`App.kt` の `AuthenticatedApp` を更新する。

```kotlin
            Route.Buckets -> BucketsScreen(onOpen = { router.navigate(Route.BucketDetail(it)) })
```

Task 4 で置いた仮のフォールバックから `Route.Buckets` を外す。

- [ ] **Step 3: 動作を確認**

```bash
docker compose up -d
./gradlew :server:run
```

ブラウザで `http://localhost:8080/buckets` を開き、dev のトークンでログインして一覧と作成を確認する。作成したバケットは次のタスクでも使うので消さなくてよい。

Expected: `dev-bucket` が一覧に出る。作成ダイアログから新しいバケットを作れる。

- [ ] **Step 4: コミット**

```bash
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketsScreen.kt web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt
git commit -m "feat(web): バケット一覧と作成を追加"
```

---

## Task 14: バケット詳細（別名・キー権限・後始末・削除）

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketDetailScreen.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: `BucketInfo`（Task 1）、`BucketAliasRequest` / `BucketKeyPermissionRequest` / `CleanupUploadsRequest` / `CleanupUploadsResult`（Task 3）、Task 12 の部品
- Produces:
  - `@Composable fun BucketDetailScreen(bucketId: String, onOpenObjects: (String) -> Unit, onOpenKey: (String) -> Unit, onDeleted: () -> Unit)`
  - `@Composable internal fun BucketSection(title: String, content: @Composable () -> Unit)` — Task 15 でも使う

この画面が持つのは次の 5 つ。設定フォーム（quotas / website / CORS / lifecycle）は Task 15 で足す。

1. 概要（ID・オブジェクト数・使用量・未完了アップロード）
2. グローバル別名の追加と削除（ローカル別名は表示のみ。P2-6）
3. アクセスキーの権限（付与と全剥奪。P2-11）
4. 未完了アップロードの後始末（P2-9）
5. バケットの削除（名前のタイプ入力を要求。spec §8.6）

- [ ] **Step 1: 画面を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketDetailScreen.kt`

```kotlin
package net.brightroom.garage.web.screens.buckets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import net.brightroom.garage.shared.api.BucketAliasRequest
import net.brightroom.garage.shared.api.BucketKeyPermissionRequest
import net.brightroom.garage.shared.api.CleanupUploadsRequest
import net.brightroom.garage.shared.api.CleanupUploadsResult
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketKey
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendEmpty
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.session.LocalSession

@Composable
fun BucketDetailScreen(
    bucketId: String,
    onOpenObjects: (String) -> Unit,
    onOpenKey: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var bucket by remember(bucketId) { mutableStateOf<BucketInfo?>(null) }
    var failure by remember(bucketId) { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember(bucketId) { mutableStateOf<String?>(null) }
    var deleting by remember(bucketId) { mutableStateOf(false) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/buckets/$bucketId", BucketInfo.serializer())) {
            is ApiResult.Success -> {
                bucket = result.value
                failure = null
            }

            is ApiResult.Failure -> failure = result
            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    /** 変更系の共通後処理。成功したら読み直し、失敗したら理由を出す。 */
    suspend fun apply(result: ApiResult<*>, success: String? = null) {
        when (result) {
            is ApiResult.Success -> {
                notice = success
                load()
            }

            is ApiResult.Failure -> notice = result.problem.displayMessage
            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    LaunchedEffect(bucketId) { load() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val current = bucket

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                current?.displayName ?: "バケット",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            if (current != null) {
                Button(onClick = { onOpenObjects(current.id) }) { Text("オブジェクトを見る") }
            }
        }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        if (current == null) {
            if (failure == null) LoadingView()
            return@Column
        }

        Overview(current)

        AliasSection(
            bucket = current,
            onAdd = { alias ->
                scope.launch {
                    apply(
                        session.api.sendJson(
                            HttpMethod.Post,
                            "/api/buckets/$bucketId/aliases",
                            AppJson.encodeToString(BucketAliasRequest.serializer(), BucketAliasRequest(alias)),
                            BucketInfo.serializer(),
                        ),
                        success = "別名 $alias を追加しました",
                    )
                }
            },
            onRemove = { alias ->
                scope.launch {
                    apply(
                        session.api.sendEmpty(
                            HttpMethod.Delete,
                            "/api/buckets/$bucketId/aliases?alias=$alias",
                        ),
                        success = "別名 $alias を削除しました",
                    )
                }
            },
        )

        KeySection(
            bucket = current,
            onOpenKey = onOpenKey,
            onGrant = { key, permissions ->
                scope.launch {
                    apply(
                        session.api.sendJson(
                            HttpMethod.Put,
                            "/api/buckets/$bucketId/keys/${key.accessKeyId}",
                            AppJson.encodeToString(
                                BucketKeyPermissionRequest.serializer(),
                                BucketKeyPermissionRequest(permissions),
                            ),
                            BucketInfo.serializer(),
                        ),
                        success = "${key.name} の権限を更新しました",
                    )
                }
            },
            onRevoke = { key ->
                scope.launch {
                    apply(
                        session.api.sendEmpty(
                            HttpMethod.Delete,
                            "/api/buckets/$bucketId/keys/${key.accessKeyId}",
                        ),
                        success = "${key.name} の権限をすべて外しました",
                    )
                }
            },
        )

        // 設定フォーム（quotas / website / CORS / lifecycle）は Task 15 でここに入る

        MaintenanceSection(
            bucket = current,
            onCleanup = {
                scope.launch {
                    val result = session.api.sendJson(
                        HttpMethod.Post,
                        "/api/buckets/$bucketId/cleanup-uploads",
                        AppJson.encodeToString(
                            CleanupUploadsRequest.serializer(),
                            CleanupUploadsRequest(),
                        ),
                        CleanupUploadsResult.serializer(),
                    )

                    val message = (result as? ApiResult.Success)?.value
                        ?.let { "${it.uploadsDeleted} 件の未完了アップロードを削除しました" }
                    apply(result, success = message)
                }
            },
            onDelete = { deleting = true },
        )
    }

    if (deleting && bucket != null) {
        val target = bucket!!
        ConfirmDialog(
            title = "バケットを削除",
            message = "${target.displayName} を削除します。中身が残っている場合、Garage は削除を拒否します。",
            requiredInput = target.displayName,
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                scope.launch {
                    when (val result = session.api.sendEmpty(HttpMethod.Delete, "/api/buckets/$bucketId")) {
                        is ApiResult.Success -> onDeleted()
                        is ApiResult.Failure -> notice = result.problem.displayMessage
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
        )
    }
}

@Composable
internal fun BucketSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Overview(bucket: BucketInfo) {
    BucketSection("概要") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ID", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(bucket.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(4f))
            CopyButton(bucket.id)
        }

        Text("オブジェクト ${bucket.objects} 件 ・ ${formatBytes(bucket.bytes)}")

        if (bucket.unfinishedUploads > 0) {
            Text(
                "未完了のアップロード ${bucket.unfinishedUploads} 件" +
                    "（マルチパート ${bucket.unfinishedMultipartUploads} 件 / " +
                    "${formatBytes(bucket.unfinishedMultipartUploadBytes)}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AliasSection(
    bucket: BucketInfo,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var newAlias by remember { mutableStateOf("") }

    BucketSection("別名") {
        if (bucket.globalAliases.isEmpty()) {
            Text(
                "グローバル別名がありません。S3 API から参照するには別名が要ります",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        bucket.globalAliases.forEach { alias ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(alias, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(alias) }) { Text("別名を削除") }
            }
        }

        // ローカル別名は表示のみ（P2-6）。追加には対象キーの指定が要り、UI が煩雑になる
        bucket.keys.flatMap { key -> key.bucketLocalAliases.map { key.name to it } }
            .forEach { (keyName, alias) ->
                Text(
                    "$alias（$keyName のローカル別名）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newAlias,
                onValueChange = { newAlias = it },
                label = { Text("追加する別名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = newAlias.isNotBlank(),
                onClick = {
                    onAdd(newAlias)
                    newAlias = ""
                },
            ) {
                Text("追加")
            }
        }
    }
}

/**
 * アクセスキーの権限。
 *
 * 付与は望む権限を送り、剥奪は全部を外す（P2-11）。減らしたいときは
 * 外してから必要な権限で付け直す。
 */
@Composable
private fun KeySection(
    bucket: BucketInfo,
    onOpenKey: (String) -> Unit,
    onGrant: (BucketKey, BucketKeyPermissions) -> Unit,
    onRevoke: (BucketKey) -> Unit,
) {
    var revoking by remember { mutableStateOf<BucketKey?>(null) }

    BucketSection("アクセスキー") {
        if (bucket.keys.isEmpty()) {
            Text(
                "このバケットに権限を持つキーがありません。オブジェクトの操作にはキーが要ります",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        bucket.keys.forEach { key ->
            var read by remember(key.accessKeyId) { mutableStateOf(key.permissions.read) }
            var write by remember(key.accessKeyId) { mutableStateOf(key.permissions.write) }
            var owner by remember(key.accessKeyId) { mutableStateOf(key.permissions.owner) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { onOpenKey(key.accessKeyId) }, modifier = Modifier.weight(2f)) {
                    Text(key.name)
                }

                Checkbox(checked = read, onCheckedChange = { read = it })
                Text("read", style = MaterialTheme.typography.labelSmall)
                Checkbox(checked = write, onCheckedChange = { write = it })
                Text("write", style = MaterialTheme.typography.labelSmall)
                Checkbox(checked = owner, onCheckedChange = { owner = it })
                Text("owner", style = MaterialTheme.typography.labelSmall)

                Button(
                    enabled = read != key.permissions.read ||
                        write != key.permissions.write ||
                        owner != key.permissions.owner,
                    onClick = {
                        onGrant(key, BucketKeyPermissions(owner = owner, read = read, write = write))
                    },
                ) {
                    Text("付与")
                }

                OutlinedButton(onClick = { revoking = key }) { Text("権限を外す") }
            }
        }
    }

    revoking?.let { key ->
        ConfirmDialog(
            title = "権限を外す",
            message = "${key.name}（${key.accessKeyId}）から ${bucket.displayName} の権限をすべて外します。",
            onDismiss = { revoking = null },
            onConfirm = {
                revoking = null
                onRevoke(key)
            },
        )
    }
}

@Composable
private fun MaintenanceSection(
    bucket: BucketInfo,
    onCleanup: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingCleanup by remember { mutableStateOf(false) }

    BucketSection("メンテナンス") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "24 時間より古い未完了のアップロードを削除します",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { confirmingCleanup = true }) { Text("未完了アップロードを削除") }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "バケットを削除します。中身が残っていると Garage は拒否します",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onDelete) {
                Text("バケットを削除", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmingCleanup) {
        ConfirmDialog(
            title = "未完了アップロードの後始末",
            message = "${bucket.displayName} で 24 時間より古い未完了のアップロードを削除します。" +
                "進行中のアップロードには影響しません。",
            onDismiss = { confirmingCleanup = false },
            onConfirm = {
                confirmingCleanup = false
                onCleanup()
            },
        )
    }
}
```

- [ ] **Step 2: App に接続する**

```kotlin
            is Route.BucketDetail -> BucketDetailScreen(
                bucketId = route.id,
                onOpenObjects = { router.navigate(Route.Objects(it)) },
                onOpenKey = { router.navigate(Route.KeyDetail(it)) },
                onDeleted = { router.navigate(Route.Buckets) },
            )
```

`when` の分岐は `is Route.BucketDetail ->` で受けるため、`val route = router.current` の形を保つこと。

- [ ] **Step 3: 動作を確認**

`http://localhost:8080/buckets` からバケットを開き、別名の追加と削除、キー権限の付与と剥奪、後始末、削除の確認ダイアログ（名前を打たないと押せないこと）を確認する。

- [ ] **Step 4: コミット**

```bash
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketDetailScreen.kt web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt
git commit -m "feat(web): バケット詳細を追加"
```

---

## Task 15: バケットの設定（quota・公開・CORS・ライフサイクル）

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/LifecycleFilters.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/LifecycleFiltersTest.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketSettingsForm.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketDetailScreen.kt`

**Interfaces:**
- Consumes: `BucketInfo` / `CorsRule` / `LifecycleRule`（Task 1）、`UpdateBucketRequest` / `WebsiteAccessRequest`（Task 3）、`BucketSection`（Task 14）
- Produces:
  - `FilterConditions`（`:shared`）と `LifecycleFilter.toConditions()` / `FilterConditions.toFilter()`
  - `@Composable internal fun BucketSettingsForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit)`

**4 つの設定はそれぞれ独立して保存する。** `UpdateBucket` は省略したフィールドを変更しないため、quota を保存しても CORS には触れない（実機で確認済み）。1 つの「保存」ボタンで全部を送ると、触っていない設定まで送り直すことになり、事故の余地が増える。

S3 の lifecycle filter は、条件が 1 つならフィルタ直下に、2 つ以上なら `And` の中に置く決まりがある。**この差は画面に出さない。** 変換は `:shared` の純粋関数に閉じ、`jvmTest` で検証する（`And` の入れ子は 1 段まで。P2-5）。

- [ ] **Step 1: フィルタ変換の失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/LifecycleFiltersTest.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LifecycleFiltersTest {

    @Test
    fun readsSingleConditionFromFilterItself() {
        val conditions = LifecycleFilter(prefix = "logs/").toConditions()

        assertEquals("logs/", conditions.prefix)
        assertNull(conditions.sizeGreaterThan)
    }

    @Test
    fun readsMultipleConditionsFromAnd() {
        val filter = LifecycleFilter(
            and = LifecycleFilter(prefix = "logs/", objectSizeGreaterThan = 1024),
        )

        val conditions = filter.toConditions()

        assertEquals("logs/", conditions.prefix)
        assertEquals(1024, conditions.sizeGreaterThan)
    }

    @Test
    fun nullFilterMeansNoConditions() {
        val conditions = null.toConditions()

        assertEquals(FilterConditions(), conditions)
        assertEquals(true, conditions.isEmpty)
    }

    @Test
    fun writesSingleConditionWithoutAnd() {
        val filter = FilterConditions(prefix = "logs/").toFilter()

        assertEquals(LifecycleFilter(prefix = "logs/"), filter)
    }

    @Test
    fun writesMultipleConditionsInsideAnd() {
        val filter = FilterConditions(prefix = "logs/", sizeGreaterThan = 1024).toFilter()

        assertEquals(
            LifecycleFilter(and = LifecycleFilter(prefix = "logs/", objectSizeGreaterThan = 1024)),
            filter,
        )
    }

    @Test
    fun emptyConditionsBecomeNoFilter() {
        assertNull(FilterConditions().toFilter())
    }

    @Test
    fun roundTripsEveryShape() {
        listOf(
            FilterConditions(),
            FilterConditions(prefix = "logs/"),
            FilterConditions(sizeGreaterThan = 1024),
            FilterConditions(prefix = "logs/", sizeGreaterThan = 1024, sizeLessThan = 4096),
        ).forEach { conditions ->
            assertEquals(conditions, conditions.toFilter().toConditions())
        }
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :shared:jvmTest --tests '*LifecycleFiltersTest*'`
Expected: FAIL（`FilterConditions` が未定義）

- [ ] **Step 3: 変換を書く**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/LifecycleFilters.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

/**
 * ライフサイクルルールの適用条件を、画面が扱いやすい平らな形にしたもの。
 *
 * S3 は「条件が 1 つならフィルタ直下、2 つ以上なら `And` の中」という決まりを持つ。
 * その差は画面に出さず、ここで吸収する。`And` の入れ子は 1 段までしか扱わない（P2-5）。
 */
data class FilterConditions(
    val prefix: String? = null,
    val sizeGreaterThan: Long? = null,
    val sizeLessThan: Long? = null,
) {
    val isEmpty: Boolean
        get() = prefix == null && sizeGreaterThan == null && sizeLessThan == null

    private val count: Int
        get() = listOfNotNull(prefix, sizeGreaterThan, sizeLessThan).size

    /** S3 が受け付ける形に戻す。条件が 2 つ以上なら `And` でくくる。 */
    fun toFilter(): LifecycleFilter? {
        if (isEmpty) return null

        val flat = LifecycleFilter(
            prefix = prefix,
            objectSizeGreaterThan = sizeGreaterThan,
            objectSizeLessThan = sizeLessThan,
        )

        return if (count == 1) flat else LifecycleFilter(and = flat)
    }
}

/** `And` の有無にかかわらず、条件を平らに取り出す。 */
fun LifecycleFilter?.toConditions(): FilterConditions {
    if (this == null) return FilterConditions()

    val source = and ?: this

    return FilterConditions(
        prefix = source.prefix,
        sizeGreaterThan = source.objectSizeGreaterThan,
        sizeLessThan = source.objectSizeLessThan,
    )
}
```

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :shared:jvmTest --tests '*LifecycleFiltersTest*'`
Expected: PASS

- [ ] **Step 5: 設定フォームを書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/BucketSettingsForm.kt`

```kotlin
package net.brightroom.garage.web.screens.buckets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.api.WebsiteAccessRequest
import net.brightroom.garage.shared.model.garage.AbortIncompleteMultipartUpload
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketQuotas
import net.brightroom.garage.shared.model.garage.CorsRule
import net.brightroom.garage.shared.model.garage.FilterConditions
import net.brightroom.garage.shared.model.garage.LifecycleExpiration
import net.brightroom.garage.shared.model.garage.LifecycleRule
import net.brightroom.garage.shared.model.garage.LifecycleStatus
import net.brightroom.garage.shared.model.garage.toConditions

/** CORS で選べるメソッド。S3 が受け付けるものに限る。 */
private val CORS_METHODS = listOf("GET", "PUT", "POST", "DELETE", "HEAD")

/**
 * バケットの設定。
 *
 * **4 つの設定はそれぞれ独立して保存する。** `UpdateBucket` は省略したフィールドを
 * 変更しないため、quota を保存しても CORS やライフサイクルには触れない。
 * まとめて送ると、触っていない設定まで書き戻すことになる。
 */
@Composable
internal fun BucketSettingsForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    QuotaForm(bucket, onSave)
    WebsiteForm(bucket, onSave)
    CorsForm(bucket, onSave)
    LifecycleForm(bucket, onSave)
}

@Composable
private fun QuotaForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    var maxObjects by remember(bucket) { mutableStateOf(bucket.quotas.maxObjects?.toString().orEmpty()) }
    var maxSize by remember(bucket) { mutableStateOf(bucket.quotas.maxSize?.toString().orEmpty()) }

    BucketSection("上限") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = maxObjects,
                onValueChange = { maxObjects = it.filter(Char::isDigit) },
                label = { Text("最大オブジェクト数") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxSize,
                onValueChange = { maxSize = it.filter(Char::isDigit) },
                label = { Text("最大サイズ（バイト）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "空欄にして保存すると上限を解除します。Garage は 2 つをまとめて扱うため、片方だけの解除はできません",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(
                        UpdateBucketRequest(
                            quotas = BucketQuotas(
                                maxObjects = maxObjects.toLongOrNull(),
                                maxSize = maxSize.toLongOrNull(),
                            ),
                        ),
                    )
                },
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun WebsiteForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    var enabled by remember(bucket) { mutableStateOf(bucket.websiteAccess) }
    var index by remember(bucket) {
        mutableStateOf(bucket.websiteConfig?.indexDocument ?: "index.html")
    }
    var error by remember(bucket) { mutableStateOf(bucket.websiteConfig?.errorDocument.orEmpty()) }

    BucketSection("公開") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Text("website として公開する")
        }

        if (enabled) {
            OutlinedTextField(
                value = index,
                onValueChange = { index = it },
                label = { Text("インデックスドキュメント") },
                singleLine = true,
            )
            OutlinedTextField(
                value = error,
                onValueChange = { error = it },
                label = { Text("エラードキュメント（任意）") },
                singleLine = true,
            )
        }

        Button(
            enabled = !enabled || index.isNotBlank(),
            onClick = {
                // 無効にするときはドキュメントを送ってはならない（Garage が拒否する）
                val request = if (enabled) {
                    WebsiteAccessRequest(
                        enabled = true,
                        indexDocument = index,
                        errorDocument = error.ifBlank { null },
                    )
                } else {
                    WebsiteAccessRequest(enabled = false)
                }

                onSave(UpdateBucketRequest(websiteAccess = request))
            },
        ) {
            Text("保存")
        }
    }
}

/** 編集中の CORS ルール。文字列のまま持ち、保存のときに [CorsRule] へ写す。 */
private class CorsDraft(rule: CorsRule) {
    var id by mutableStateOf(rule.id.orEmpty())
    var origins by mutableStateOf(rule.allowedOrigins.joinToString(", "))
    var headers by mutableStateOf(rule.allowedHeaders.joinToString(", "))
    var exposed by mutableStateOf(rule.exposeHeaders.joinToString(", "))
    var maxAge by mutableStateOf(rule.maxAgeSeconds?.toString().orEmpty())
    val methods = mutableStateListOf<String>().apply { addAll(rule.allowedMethods) }

    fun toRule() = CorsRule(
        allowedOrigins = origins.splitList(),
        allowedMethods = methods.toList(),
        allowedHeaders = headers.splitList(),
        exposeHeaders = exposed.splitList(),
        maxAgeSeconds = maxAge.toLongOrNull(),
        id = id.ifBlank { null },
    )
}

private fun String.splitList(): List<String> =
    split(',').map(String::trim).filter(String::isNotEmpty)

@Composable
private fun CorsForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    val drafts = remember(bucket) {
        mutableStateListOf<CorsDraft>().apply {
            addAll(bucket.corsRules.orEmpty().map(::CorsDraft))
        }
    }

    BucketSection("CORS") {
        if (drafts.isEmpty()) {
            Text(
                "ルールがありません。保存すると設定を削除します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        drafts.forEachIndexed { index, draft ->
            if (index > 0) HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.id,
                        onValueChange = { draft.id = it },
                        label = { Text("ID（任意）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.maxAge,
                        onValueChange = { draft.maxAge = it.filter(Char::isDigit) },
                        label = { Text("MaxAgeSeconds") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = draft.origins,
                    onValueChange = { draft.origins = it },
                    label = { Text("許可するオリジン（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    CORS_METHODS.forEach { method ->
                        Checkbox(
                            checked = draft.methods.contains(method),
                            onCheckedChange = { checked ->
                                if (checked) draft.methods.add(method) else draft.methods.remove(method)
                            },
                        )
                        Text(method, style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedTextField(
                    value = draft.headers,
                    onValueChange = { draft.headers = it },
                    label = { Text("許可するヘッダ（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.exposed,
                    onValueChange = { draft.exposed = it },
                    label = { Text("公開するヘッダ（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                TextButton(onClick = { drafts.removeAt(index) }) { Text("このルールを削除") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    drafts.add(
                        CorsDraft(CorsRule(allowedOrigins = listOf("*"), allowedMethods = listOf("GET"))),
                    )
                },
            ) {
                Text("ルールを追加")
            }
            Button(onClick = { onSave(UpdateBucketRequest(corsRules = drafts.map { it.toRule() })) }) {
                Text("保存")
            }
        }
    }
}

/** 編集中のライフサイクルルール。 */
private class LifecycleDraft(rule: LifecycleRule) {
    var id by mutableStateOf(rule.id.orEmpty())
    var enabled by mutableStateOf(rule.status == LifecycleStatus.ENABLED)
    var prefix by mutableStateOf(rule.filter.toConditions().prefix.orEmpty())
    var sizeGreaterThan by mutableStateOf(
        rule.filter.toConditions().sizeGreaterThan?.toString().orEmpty(),
    )
    var sizeLessThan by mutableStateOf(
        rule.filter.toConditions().sizeLessThan?.toString().orEmpty(),
    )
    var expireByDays by mutableStateOf(rule.expiration?.date == null)
    var days by mutableStateOf(rule.expiration?.days?.toString().orEmpty())
    var date by mutableStateOf(rule.expiration?.date.orEmpty())
    var abortDays by mutableStateOf(
        rule.abortIncompleteMultipartUpload?.daysAfterInitiation?.toString().orEmpty(),
    )

    fun toRule() = LifecycleRule(
        status = if (enabled) LifecycleStatus.ENABLED else LifecycleStatus.DISABLED,
        id = id.ifBlank { null },
        filter = FilterConditions(
            prefix = prefix.ifBlank { null },
            sizeGreaterThan = sizeGreaterThan.toLongOrNull(),
            sizeLessThan = sizeLessThan.toLongOrNull(),
        ).toFilter(),
        expiration = expiration(),
        abortIncompleteMultipartUpload = abortDays.toLongOrNull()
            ?.let(::AbortIncompleteMultipartUpload),
    )

    /** 日数と日付は排他。どちらも空なら期限を設けない。 */
    private fun expiration(): LifecycleExpiration? = when {
        expireByDays -> days.toLongOrNull()?.let { LifecycleExpiration(days = it) }
        date.isNotBlank() -> LifecycleExpiration(date = date)
        else -> null
    }
}

@Composable
private fun LifecycleForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    val drafts = remember(bucket) {
        mutableStateListOf<LifecycleDraft>().apply {
            addAll(bucket.lifecycleRules.orEmpty().map(::LifecycleDraft))
        }
    }

    BucketSection("ライフサイクル") {
        if (drafts.isEmpty()) {
            Text(
                "ルールがありません。保存すると設定を削除します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        drafts.forEachIndexed { index, draft ->
            if (index > 0) HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = draft.id,
                        onValueChange = { draft.id = it },
                        label = { Text("ID（任意）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = draft.enabled, onCheckedChange = { draft.enabled = it })
                    Text(if (draft.enabled) "有効" else "無効")
                }

                OutlinedTextField(
                    value = draft.prefix,
                    onValueChange = { draft.prefix = it },
                    label = { Text("対象の接頭辞（任意）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.sizeGreaterThan,
                        onValueChange = { draft.sizeGreaterThan = it.filter(Char::isDigit) },
                        label = { Text("これより大きい（バイト）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.sizeLessThan,
                        onValueChange = { draft.sizeLessThan = it.filter(Char::isDigit) },
                        label = { Text("これより小さい（バイト）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.expireByDays, onClick = { draft.expireByDays = true })
                    Text("日数で削除")
                    RadioButton(selected = !draft.expireByDays, onClick = { draft.expireByDays = false })
                    Text("日付で削除")
                }

                if (draft.expireByDays) {
                    OutlinedTextField(
                        value = draft.days,
                        onValueChange = { draft.days = it.filter(Char::isDigit) },
                        label = { Text("作成から何日後に削除するか") },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = draft.date,
                        onValueChange = { draft.date = it },
                        label = { Text("削除する日時（例 2027-01-01T00:00:00Z）") },
                        singleLine = true,
                    )
                }

                OutlinedTextField(
                    value = draft.abortDays,
                    onValueChange = { draft.abortDays = it.filter(Char::isDigit) },
                    label = { Text("未完了アップロードを打ち切るまでの日数（任意）") },
                    singleLine = true,
                )

                TextButton(onClick = { drafts.removeAt(index) }) { Text("このルールを削除") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { drafts.add(LifecycleDraft(LifecycleRule(status = LifecycleStatus.ENABLED))) },
            ) {
                Text("ルールを追加")
            }
            Button(
                onClick = { onSave(UpdateBucketRequest(lifecycleRules = drafts.map { it.toRule() })) },
            ) {
                Text("保存")
            }
        }
    }
}
```

`CorsDraft` と `LifecycleDraft` で `by mutableStateOf` を使うため、`androidx.compose.runtime.getValue` と `setValue` の import が要る。

- [ ] **Step 6: 詳細画面に差し込む**

`BucketDetailScreen.kt` の

```kotlin
        // 設定フォーム（quotas / website / CORS / lifecycle）は Task 15 でここに入る
```

を次で置き換える。

```kotlin
        BucketSettingsForm(current) { request ->
            scope.launch {
                apply(
                    session.api.sendJson(
                        HttpMethod.Patch,
                        "/api/buckets/$bucketId",
                        AppJson.encodeToString(UpdateBucketRequest.serializer(), request),
                        BucketInfo.serializer(),
                    ),
                    success = "設定を保存しました",
                )
            }
        }
```

`import net.brightroom.garage.shared.api.UpdateBucketRequest` を足す。

- [ ] **Step 7: 実機で「触っていない設定を巻き込まない」ことを確認**

```bash
./gradlew :server:run
```

1. バケット詳細で CORS ルールを 1 つ作って保存する
2. 上限（quota）に値を入れて保存する
3. 画面を更新し、**CORS ルールが残っていること**を確認する
4. ライフサイクルルールを 1 つ作って保存し、再度 quota を変えても消えないことを確認する

Expected: それぞれの保存が他の設定に影響しない

- [ ] **Step 8: コミット**

```bash
git add shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/LifecycleFilters.kt shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/LifecycleFiltersTest.kt web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/buckets/
git commit -m "feat(web): バケットの設定フォームを追加"
```

---

## Task 16: アクセスキーの一覧と詳細

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/keys/KeysScreen.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/keys/KeyDetailScreen.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: `KeySummary` / `KeyInfo`（Task 2）、`CreateKeyRequest` / `ImportKeyRequest` / `UpdateKeyRequest`（Task 3）、Task 12 の部品
- Produces:
  - `@Composable fun KeysScreen(onOpen: (String) -> Unit)`
  - `@Composable fun KeyDetailScreen(keyId: String, onOpenBucket: (String) -> Unit, onDeleted: () -> Unit)`

**作成直後の secret は一度しか出ない。** Garage は `CreateKey` の応答でだけ平文を返す（以後は `showSecretKey=true` が要る）。作成ダイアログはその値をコピーできる形で見せ、閉じたら二度と出さない。

キー詳細の secret は「表示」ボタンで取りにいく（P2-7）。押すまでサーバーは secret を受け取らない。

- [ ] **Step 1: 一覧を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/keys/KeysScreen.kt`

```kotlin
package net.brightroom.garage.web.screens.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import net.brightroom.garage.shared.api.CreateKeyRequest
import net.brightroom.garage.shared.api.ImportKeyRequest
import net.brightroom.garage.shared.model.garage.KeyInfo
import net.brightroom.garage.shared.model.garage.KeySummary
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.Column as TableColumn
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.DataTable
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.session.LocalSession

@Composable
fun KeysScreen(onOpen: (String) -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var keys by remember { mutableStateOf<List<KeySummary>?>(null) }
    var failure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<KeyInfo?>(null) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/keys", ListSerializer(KeySummary.serializer()))) {
            is ApiResult.Success -> {
                keys = result.value
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
            Text(
                "アクセスキー",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            TextButton(onClick = { importing = true }) { Text("インポート") }
            Button(onClick = { creating = true }) { Text("キーを作成") }
        }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }

        when (val current = keys) {
            null -> if (failure == null) LoadingView()
            else -> DataTable(
                items = current,
                onRowClick = { onOpen(it.id) },
                emptyMessage = "アクセスキーがありません",
                searchPlaceholder = "名前や ID で絞り込み",
                columns = listOf(
                    TableColumn(title = "名前", weight = 2f, value = { it.name }),
                    TableColumn(title = "ID", weight = 2f, value = { it.id }),
                    TableColumn(
                        title = "期限",
                        value = { it.expiration?.toString() ?: "無期限" },
                        comparator = compareBy { it.expiration },
                        content = { key ->
                            Text(
                                when {
                                    key.expired -> "失効"
                                    key.expiration == null -> "無期限"
                                    else -> key.expiration.toString().substringBefore('T')
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (key.expired) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                    ),
                ),
            )
        }
    }

    if (creating) {
        CreateKeyDialog(
            onDismiss = { creating = false },
            onCreated = { key ->
                creating = false
                created = key
                scope.launch { load() }
            },
        )
    }

    if (importing) {
        ImportKeyDialog(
            onDismiss = { importing = false },
            onImported = {
                importing = false
                scope.launch { load() }
            },
        )
    }

    created?.let { key ->
        SecretOnceDialog(key) { created = null }
    }
}

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreated: (KeyInfo) -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var allowCreateBucket by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アクセスキーを作成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowCreateBucket, onCheckedChange = { allowCreateBucket = it })
                    Text("バケットの作成を許可する")
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    scope.launch {
                        val body = AppJson.encodeToString(
                            CreateKeyRequest.serializer(),
                            CreateKeyRequest(name = name, allowCreateBucket = allowCreateBucket),
                        )

                        when (
                            val result = session.api.sendJson(
                                HttpMethod.Post,
                                "/api/keys",
                                body,
                                KeyInfo.serializer(),
                            )
                        ) {
                            is ApiResult.Success -> onCreated(result.value)
                            is ApiResult.Failure -> error = result.problem.displayMessage
                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                },
            ) {
                Text("作成")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onImported: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var accessKeyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アクセスキーをインポート") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "他のクラスタから持ち込んだキーを登録します",
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
                    value = accessKeyId,
                    onValueChange = { accessKeyId = it },
                    label = { Text("アクセスキー ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("シークレットアクセスキー") },
                    singleLine = true,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && accessKeyId.isNotBlank() && secret.isNotBlank(),
                onClick = {
                    scope.launch {
                        val body = AppJson.encodeToString(
                            ImportKeyRequest.serializer(),
                            ImportKeyRequest(name = name, accessKeyId = accessKeyId, secretAccessKey = secret),
                        )

                        when (
                            val result = session.api.sendJson(
                                HttpMethod.Post,
                                "/api/keys/import",
                                body,
                                KeyInfo.serializer(),
                            )
                        ) {
                            is ApiResult.Success -> onImported()
                            is ApiResult.Failure -> error = result.problem.displayMessage
                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                },
            ) {
                Text("インポート")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * 作成直後の secret を一度だけ見せる。
 *
 * Garage は作成時の応答でしか平文を返さない。閉じたら取り直しには
 * `showSecretKey=true` が要る。
 */
@Composable
private fun SecretOnceDialog(key: KeyInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${key.name} を作成しました") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("アクセスキー ID", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(key.accessKeyId, modifier = Modifier.weight(1f))
                    CopyButton(key.accessKeyId)
                }

                Text("シークレットアクセスキー", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(key.secretAccessKey.orEmpty(), modifier = Modifier.weight(1f))
                    CopyButton(key.secretAccessKey.orEmpty())
                }

                Text(
                    "この画面を閉じると、シークレットは詳細画面から取り直すことになります",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
```

- [ ] **Step 2: 詳細を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/keys/KeyDetailScreen.kt`

```kotlin
package net.brightroom.garage.web.screens.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import net.brightroom.garage.shared.api.UpdateKeyRequest
import net.brightroom.garage.shared.model.garage.KeyInfo
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendEmpty
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.session.LocalSession

@Composable
fun KeyDetailScreen(
    keyId: String,
    onOpenBucket: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var key by remember(keyId) { mutableStateOf<KeyInfo?>(null) }
    var failure by remember(keyId) { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember(keyId) { mutableStateOf<String?>(null) }
    var secret by remember(keyId) { mutableStateOf<String?>(null) }
    var deleting by remember(keyId) { mutableStateOf(false) }

    suspend fun load(showSecret: Boolean = false) {
        val path = if (showSecret) "/api/keys/$keyId?showSecret=true" else "/api/keys/$keyId"

        when (val result = session.api.getJson(path, KeyInfo.serializer())) {
            is ApiResult.Success -> {
                key = result.value
                if (showSecret) secret = result.value.secretAccessKey
                failure = null
            }

            is ApiResult.Failure -> failure = result
            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    LaunchedEffect(keyId) { load() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val current = key

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                current?.name ?: "アクセスキー",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
        }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        if (current == null) {
            if (failure == null) LoadingView()
            return@Column
        }

        KeySection("資格情報") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ID", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(current.accessKeyId, modifier = Modifier.weight(4f))
                CopyButton(current.accessKeyId)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("シークレット", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))

                val shown = secret
                if (shown == null) {
                    // 押すまでサーバーは secret を受け取らない（P2-7）
                    OutlinedButton(onClick = { scope.launch { load(showSecret = true) } }) {
                        Text("表示")
                    }
                } else {
                    Text(shown, modifier = Modifier.weight(4f))
                    CopyButton(shown)
                }
            }

            Text(
                if (current.expired) {
                    "このキーは失効しています"
                } else {
                    current.expiration?.let { "期限 $it" } ?: "無期限"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (current.expired) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        SettingsSection(
            key = current,
            onSave = { request ->
                scope.launch {
                    val body = AppJson.encodeToString(UpdateKeyRequest.serializer(), request)

                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Patch,
                            "/api/keys/$keyId",
                            body,
                            KeyInfo.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            notice = "設定を保存しました"
                            load()
                        }

                        is ApiResult.Failure -> notice = result.problem.displayMessage
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
        )

        KeySection("権限を持つバケット") {
            if (current.buckets.isEmpty()) {
                Text(
                    "このキーがアクセスできるバケットはありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            current.buckets.forEach { bucket ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onOpenBucket(bucket.id) }, modifier = Modifier.weight(2f)) {
                        Text(bucket.displayName)
                    }
                    Text(
                        listOfNotNull(
                            "owner".takeIf { bucket.permissions.owner },
                            "read".takeIf { bucket.permissions.read },
                            "write".takeIf { bucket.permissions.write },
                        ).joinToString(" / ").ifEmpty { "権限なし" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        KeySection("削除") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "このキーを削除します。キーを使っている利用者はアクセスできなくなります",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { deleting = true }) {
                    Text("キーを削除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (deleting && key != null) {
        val target = key!!
        ConfirmDialog(
            title = "アクセスキーを削除",
            message = "${target.name}（${target.accessKeyId}）を削除します。",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                scope.launch {
                    when (val result = session.api.sendEmpty(HttpMethod.Delete, "/api/keys/$keyId")) {
                        is ApiResult.Success -> onDeleted()
                        is ApiResult.Failure -> notice = result.problem.displayMessage
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(key: KeyInfo, onSave: (UpdateKeyRequest) -> Unit) {
    var name by remember(key) { mutableStateOf(key.name) }
    var allowCreateBucket by remember(key) { mutableStateOf(key.permissions.createBucket) }

    KeySection("設定") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名前") },
            singleLine = true,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allowCreateBucket, onCheckedChange = { allowCreateBucket = it })
            Text("バケットの作成を許可する")
        }

        Button(
            enabled = name != key.name || allowCreateBucket != key.permissions.createBucket,
            onClick = {
                onSave(
                    UpdateKeyRequest(
                        name = name.takeIf { it != key.name },
                        allowCreateBucket = allowCreateBucket
                            .takeIf { it != key.permissions.createBucket },
                    ),
                )
            },
        ) {
            Text("保存")
        }
    }
}

@Composable
private fun KeySection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
```

- [ ] **Step 3: App に接続する**

```kotlin
            Route.Keys -> KeysScreen(onOpen = { router.navigate(Route.KeyDetail(it)) })

            is Route.KeyDetail -> KeyDetailScreen(
                keyId = route.id,
                onOpenBucket = { router.navigate(Route.BucketDetail(it)) },
                onDeleted = { router.navigate(Route.Keys) },
            )
```

- [ ] **Step 4: 動作を確認**

キーを作成し、secret が一度だけ出ること、詳細の「表示」で取り直せること、権限を持つバケットからバケット詳細へ飛べること、削除の確認ダイアログが出ることを確認する。

- [ ] **Step 5: コミット**

```bash
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/keys/ web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt
git commit -m "feat(web): アクセスキーの一覧と詳細を追加"
```

- [ ] **Step 6: PR を出す**

```bash
git push -u origin phase2/4-web-storage
gh pr create --title "feat(web): バケットとアクセスキーの画面を追加" --body "Phase 2 の web（ストレージ管理）。共通の表・確認ダイアログ・コピーボタンと、バケット一覧 / 詳細 / 設定、アクセスキー一覧 / 詳細を追加する。

- バケットの 4 つの設定（上限・公開・CORS・ライフサイクル）はそれぞれ独立して保存する
- キーの secret は作成直後と「表示」を押したときだけ扱う
- 削除はバケット名のタイプ入力を要求する

計画: docs/superpowers/plans/2026-08-23-rebuild-phase2-storage.md の Task 12-16"
```

---

## Task 17: オブジェクトブラウザ

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/objects/ObjectTransfer.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/objects/ObjectBrowserScreen.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: `ObjectListing` / `StoredObject` / `parentPrefix`（Task 3）、`ObjectInspection`（Task 3）、`percentEncode`（Task 4）、`ProblemTypes`（Task 3）、Task 12 の部品
- Produces:
  - `sealed interface TransferOutcome` — `Done` / `Cancelled` / `Failed`
  - `suspend fun uploadObject(url: String, token: String): TransferOutcome`
  - `suspend fun downloadObject(url: String, token: String, fileName: String): TransferOutcome`
  - `@Composable fun ObjectBrowserScreen(bucketId: String, prefix: String, onNavigatePrefix: (String) -> Unit, onOpenBucket: (String) -> Unit)`

**ブランチ:** `phase2/5-web-objects`（Task 17–18 を 1 PR にする）

```bash
git switch main
git pull
git switch -c phase2/5-web-objects
```

ファイルの転送は JS 側の `fetch` で完結させる（P2-2）。wasm とバイナリをやり取りせずに済み、ファイル本体が wasm のメモリを通らない。

**ただし `ApiClient` を通らないので、401 の扱いが素通しになる。** これを避けるため、JS からは常に `"<status> <body>"` の形で結果を返し、Kotlin 側で `ApiClient` と同じ判断（401 ならセッション破棄、403 なら scope 不足の表示、それ以外は problem details の文言）を行う。

- [ ] **Step 1: interop が動くことを先に確かめる**

`@JsFun` と `Promise.await()` が wasmJs で使えることを、画面を書く前に確認する。Phase 1 の `js("document.hidden")` は引数なしの単純な形だったので、ここは別途の確認が要る。

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/objects/ObjectTransfer.kt` を次の内容で作る。

```kotlin
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.screens.objects

import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * ファイルの転送結果。
 *
 * 転送は JS 側の `fetch` で完結するため（P2-2）、`ApiClient` の判断を通らない。
 * 401 / 403 の扱いを取りこぼさないよう、ステータスをそのまま持ち帰る。
 */
sealed interface TransferOutcome {
    data class Done(val fileName: String) : TransferOutcome
    data object Cancelled : TransferOutcome
    data class Failed(val status: Int, val body: String) : TransferOutcome
}

/**
 * ファイルを選ばせて `PUT` する。
 *
 * `[url]` の末尾に、選ばれたファイル名をエンコードして繋ぐ。ファイル本体は
 * `fetch` の body に渡すため、ブラウザが `Content-Length` を付けてそのまま流す。
 *
 * ダイアログを閉じただけのときは `cancel` イベントで区別する（`change` は発火しない）。
 *
 * 結果は `"<status> <body>"`。キャンセルは `"cancelled "` を返す。
 */
@JsFun(
    """
    (url, token) => new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';

        input.addEventListener('cancel', () => resolve('cancelled '));

        input.addEventListener('change', () => {
            const file = input.files && input.files[0];
            if (!file) {
                resolve('cancelled ');
                return;
            }

            fetch(url + encodeURIComponent(file.name), {
                method: 'PUT',
                headers: {
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': file.type || 'application/octet-stream',
                },
                body: file,
            }).then((response) => {
                if (response.ok) {
                    resolve('204 ' + file.name);
                    return;
                }
                response.text().then((body) => resolve(response.status + ' ' + body));
            }).catch((error) => resolve('0 ' + error));
        });

        input.click();
    })
    """,
)
private external fun pickAndUpload(url: String, token: String): Promise<JsString>

/**
 * `GET` した本文をファイルとして保存させる。
 *
 * ブラウザに保存させるのは Blob からの object URL である。サーバーは
 * `Content-Disposition` を付けないため、ファイル名はここで決める。
 */
@JsFun(
    """
    (url, token, fileName) => new Promise((resolve) => {
        fetch(url, { headers: { 'Authorization': 'Bearer ' + token } })
            .then((response) => {
                if (!response.ok) {
                    return response.text().then((body) => resolve(response.status + ' ' + body));
                }
                return response.blob().then((blob) => {
                    const objectUrl = URL.createObjectURL(blob);
                    const anchor = document.createElement('a');
                    anchor.href = objectUrl;
                    anchor.download = fileName;
                    anchor.click();
                    URL.revokeObjectURL(objectUrl);
                    resolve('200 ' + fileName);
                });
            })
            .catch((error) => resolve('0 ' + error));
    })
    """,
)
private external fun fetchAndSave(url: String, token: String, fileName: String): Promise<JsString>

suspend fun uploadObject(url: String, token: String): TransferOutcome =
    pickAndUpload(url, token).await().toString().toOutcome()

suspend fun downloadObject(url: String, token: String, fileName: String): TransferOutcome =
    fetchAndSave(url, token, fileName).await().toString().toOutcome()

/** `"<status> <body>"` を読み解く。`0` はネットワーク側の失敗。 */
private fun String.toOutcome(): TransferOutcome {
    if (startsWith("cancelled")) return TransferOutcome.Cancelled

    val status = substringBefore(' ').toIntOrNull() ?: 0
    val body = substringAfter(' ', "")

    return if (status in 200..299) TransferOutcome.Done(body) else TransferOutcome.Failed(status, body)
}
```

Run: `./gradlew :web:compileKotlinWasmJs`
Expected: BUILD SUCCESSFUL

**通らない場合はここで方針を決め直す。** 想定される失敗は 2 つ。

- `kotlinx.coroutines.await` が wasmJs に無い → `suspendCoroutine` と `Promise.then` で自前に包む
- `JsString` の受け渡しが合わない → 戻り値を `Promise<JsString>` から `Promise<JsAny?>` にして `toString()` で受ける

どちらの場合も、**JS 側で `fetch` を完結させる方針自体は変えない**。wasm にバイナリを渡す形に戻すと、ファイル全体が wasm のメモリに載る。

- [ ] **Step 2: 画面を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/objects/ObjectBrowserScreen.kt`

```kotlin
package net.brightroom.garage.web.screens.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.ProblemTypes
import net.brightroom.garage.shared.model.garage.ObjectInspection
import net.brightroom.garage.shared.model.s3.ObjectListing
import net.brightroom.garage.shared.model.s3.StoredObject
import net.brightroom.garage.shared.model.s3.parentPrefix
import net.brightroom.garage.shared.navigation.percentEncode
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendEmpty
import net.brightroom.garage.web.components.Column as TableColumn
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.DataTable
import net.brightroom.garage.web.components.EmptyState
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.session.LocalSession

/**
 * オブジェクトブラウザ。
 *
 * 一覧は手動更新のみ（spec §8.5）。表示中のフォルダは URL のクエリに載るため、
 * リロードとブックマークで同じ場所に戻れる。
 */
@Composable
fun ObjectBrowserScreen(
    bucketId: String,
    prefix: String,
    onNavigatePrefix: (String) -> Unit,
    onOpenBucket: (String) -> Unit,
) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var listing by remember(bucketId, prefix) { mutableStateOf<ObjectListing?>(null) }
    var failure by remember(bucketId, prefix) { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember(bucketId, prefix) { mutableStateOf<String?>(null) }
    var deleting by remember(bucketId, prefix) { mutableStateOf<StoredObject?>(null) }
    var inspecting by remember(bucketId, prefix) { mutableStateOf<ObjectInspection?>(null) }
    var busy by remember(bucketId, prefix) { mutableStateOf(false) }

    val listPath = "/api/buckets/$bucketId/objects?prefix=${percentEncode(prefix)}"

    suspend fun load(continuation: String? = null) {
        val path = continuation?.let { "$listPath&token=${percentEncode(it)}" } ?: listPath

        when (val result = session.api.getJson(path, ObjectListing.serializer())) {
            is ApiResult.Success -> {
                // 続きを読んだときは前のページに足す
                listing = listing
                    ?.takeIf { continuation != null }
                    ?.let { previous ->
                        result.value.copy(
                            folders = previous.folders + result.value.folders,
                            objects = previous.objects + result.value.objects,
                        )
                    }
                    ?: result.value
                failure = null
            }

            is ApiResult.Failure -> failure = result
            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    /** 転送の結果を `ApiClient` と同じ基準で扱う。 */
    fun handle(outcome: TransferOutcome, onDone: (String) -> String) {
        when (outcome) {
            is TransferOutcome.Done -> {
                notice = onDone(outcome.fileName)
                scope.launch { load() }
            }

            TransferOutcome.Cancelled -> notice = null

            is TransferOutcome.Failed -> when (outcome.status) {
                HttpStatusCode.Unauthorized.value -> session.invalidate()
                else -> notice = problemMessage(outcome)
            }
        }
    }

    LaunchedEffect(bucketId, prefix) { load() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "オブジェクト",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onOpenBucket(bucketId) }) { Text("バケットの設定") }
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val url = "/api/buckets/$bucketId/objects?key=${percentEncode(prefix)}"
                        val outcome = uploadObject(url, session.token.orEmpty())
                        busy = false
                        handle(outcome) { name -> "$name をアップロードしました" }
                    }
                },
            ) {
                Text("アップロード")
            }
        }

        Breadcrumbs(prefix, onNavigatePrefix)

        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        failure?.let { current ->
            DegradedView(current, onOpenBucket = { onOpenBucket(bucketId) }, onRetry = { scope.launch { load() } })
        }

        val current = listing

        when {
            current == null -> if (failure == null) LoadingView()

            current.isEmpty -> EmptyState(
                if (prefix.isEmpty()) {
                    "このバケットにオブジェクトはありません"
                } else {
                    "このフォルダにオブジェクトはありません"
                },
            )

            else -> ObjectTable(
                listing = current,
                onOpenFolder = onNavigatePrefix,
                onDownload = { obj ->
                    busy = true
                    scope.launch {
                        val url = "/api/buckets/$bucketId/objects/content?key=${percentEncode(obj.key)}"
                        val outcome = downloadObject(url, session.token.orEmpty(), obj.nameIn(prefix))
                        busy = false
                        handle(outcome) { "" }
                    }
                },
                onInspect = { obj ->
                    scope.launch {
                        val path = "/api/buckets/$bucketId/objects/inspect?key=${percentEncode(obj.key)}"

                        when (val result = session.api.getJson(path, ObjectInspection.serializer())) {
                            is ApiResult.Success -> inspecting = result.value
                            is ApiResult.Failure -> notice = result.problem.displayMessage
                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                },
                onDelete = { deleting = it },
            )
        }

        current?.nextToken?.let { token ->
            TextButton(onClick = { scope.launch { load(token) } }) { Text("続きを読み込む") }
        }

        current?.keyName?.let { key ->
            Text(
                "$key として閲覧中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = "オブジェクトを削除",
            message = "${target.key} を削除します。取り消せません。",
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    val path = "/api/buckets/$bucketId/objects?key=${percentEncode(target.key)}"

                    when (val result = session.api.sendEmpty(HttpMethod.Delete, path)) {
                        is ApiResult.Success -> {
                            notice = "${target.key} を削除しました"
                            load()
                        }

                        is ApiResult.Failure -> notice = result.problem.displayMessage
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
        )
    }

    inspecting?.let { inspection ->
        InspectionDialog(inspection) { inspecting = null }
    }
}

/** 現在地と、上の階層への導線。 */
@Composable
private fun Breadcrumbs(prefix: String, onNavigate: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { onNavigate("") }) { Text("ルート") }

        var walked = ""
        prefix.trimEnd('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            walked += "$segment/"
            val destination = walked

            Text("/", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onNavigate(destination) }) { Text(segment) }
        }

        parentPrefix(prefix)?.let { parent ->
            TextButton(onClick = { onNavigate(parent) }) { Text("上へ") }
        }
    }
}

/**
 * S3 ブラウザだけが使えない状態を、理由に応じて出し分ける（spec §6.4）。
 *
 * 判断に使うのは HTTP のステータスと problem details の `type` である。
 */
@Composable
private fun DegradedView(
    failure: ApiResult.Failure,
    onOpenBucket: () -> Unit,
    onRetry: () -> Unit,
) {
    when (failure.problem.type) {
        ProblemTypes.NO_USABLE_KEY -> EmptyState(
            message = "このバケットにアクセスできるキーがありません。" +
                "バケットの設定でキーに read 以上の権限を与えてください",
            actionLabel = "バケットの設定を開く",
            onAction = onOpenBucket,
        )

        ProblemTypes.BUCKET_NOT_ADDRESSABLE -> EmptyState(
            message = "このバケットには別名が無いため、S3 API から参照できません。" +
                "バケットの設定でグローバル別名を追加してください",
            actionLabel = "バケットの設定を開く",
            onAction = onOpenBucket,
        )

        else -> ProblemView(failure.problem, failure.status, onRetry = onRetry)
    }
}

@Composable
private fun ObjectTable(
    listing: ObjectListing,
    onOpenFolder: (String) -> Unit,
    onDownload: (StoredObject) -> Unit,
    onInspect: (StoredObject) -> Unit,
    onDelete: (StoredObject) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listing.folders.forEach { folder ->
            TextButton(onClick = { onOpenFolder(folder) }) {
                Text("📁 ${folder.removePrefix(listing.prefix)}")
            }
        }

        DataTable(
            items = listing.objects,
            emptyMessage = "オブジェクトがありません",
            searchPlaceholder = "名前で絞り込み",
            columns = listOf(
                TableColumn(
                    title = "名前",
                    weight = 3f,
                    value = { it.nameIn(listing.prefix) },
                ),
                TableColumn(
                    title = "サイズ",
                    value = { formatBytes(it.size) },
                    comparator = compareBy { it.size },
                ),
                TableColumn(
                    title = "更新",
                    value = { it.lastModified?.toString().orEmpty() },
                    comparator = compareBy { it.lastModified },
                    content = { obj ->
                        Text(
                            obj.lastModified?.toString()?.substringBefore('.') ?: "-",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                ),
                TableColumn(
                    title = "操作",
                    weight = 2f,
                    value = { "" },
                    content = { obj ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onDownload(obj) }) { Text("取得") }
                            TextButton(onClick = { onInspect(obj) }) { Text("詳細") }
                            TextButton(onClick = { onDelete(obj) }) {
                                Text("削除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                ),
            ),
        )
    }
}

/** `InspectObject` の結果。S3 では見えない内部表現を出す。 */
@Composable
private fun InspectionDialog(inspection: ObjectInspection, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(inspection.key) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (inspection.versions.isEmpty()) {
                    Text("バージョンがありません")
                }

                inspection.versions.forEach { version ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            version.timestamp.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            listOfNotNull(
                                version.size?.let { formatBytes(it) },
                                "インライン格納".takeIf { version.inline },
                                "アップロード中".takeIf { version.uploading },
                                "中断".takeIf { version.aborted },
                                "削除マーカー".takeIf { version.deleteMarker },
                                "SSE-C 暗号化".takeIf { version.encrypted },
                                "${version.blocks.size} ブロック".takeIf { version.blocks.isNotEmpty() },
                            ).joinToString(" ・ "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        version.headers.forEach { header ->
                            Text(
                                header.joinToString(": "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/**
 * 転送の失敗を利用者に見せる文言にする。
 *
 * 本文はサーバーが返した problem details である。壊れていたらステータスで代用する。
 */
private fun problemMessage(failure: TransferOutcome.Failed): String {
    if (failure.status == 0) return "サーバーに接続できませんでした"

    return runCatching {
        AppJson.decodeFromString(ProblemDetails.serializer(), failure.body).displayMessage
    }.getOrElse { "転送に失敗しました（HTTP ${failure.status}）" }
}
```

- [ ] **Step 3: App に接続する**

```kotlin
            is Route.Objects -> ObjectBrowserScreen(
                bucketId = route.bucketId,
                prefix = route.prefix,
                onNavigatePrefix = { router.navigate(Route.Objects(route.bucketId, it)) },
                onOpenBucket = { router.navigate(Route.BucketDetail(it)) },
            )
```

Task 4 で置いた仮のフォールバックはこれで空になる。`when` から消すこと。

- [ ] **Step 4: 実機で転送を確認**

```bash
docker compose up -d
./gradlew :server:run
```

1. `dev-bucket` のオブジェクト画面を開く
2. アップロードでファイルを選び、一覧に出ることを確認する
3. ファイル選択をキャンセルしても画面が固まらないことを確認する
4. 「取得」でダウンロードできることを確認する
5. 「詳細」で `InspectObject` の内容が出ることを確認する
6. 削除の確認ダイアログから削除できることを確認する
7. `a/b/c.txt` のような名前でアップロードし、フォルダとして辿れること、URL に `?prefix=a%2F` が乗ること、リロードで同じ場所に戻ることを確認する
8. 日本語のファイル名でも 2〜6 が通ることを確認する

- [ ] **Step 5: 縮退を確認**

`Limited-scope token`（Task 11 で発行）でログインし、オブジェクト画面を開く。

Expected: バケット一覧は見えるが、オブジェクト画面は「このトークンでは参照できません（必要な scope: GetKeyInfo）」になる

- [ ] **Step 6: コミット**

```bash
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/objects/ web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt
git commit -m "feat(web): オブジェクトブラウザを追加"
```

---

## Task 18: サイドバーと概況からの導線

**Files:**
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/NavItem.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/Sidebar.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/overview/OverviewScreen.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: Task 4 のルート
- Produces:
  - `NavItem` に `matches: (Route) -> Boolean` を追加
  - `OverviewScreen(onNavigate: (Route) -> Unit)`

spec §8.2 のストレージのグループを作る。「オブジェクト」はバケットに属するため、単体では開けない。押したときはバケット一覧へ送り、選ばせる（P2-8）。ただし `/objects/...` を開いている間はこの項目を選択状態にしたいので、**選択判定を等値からルートの照合に変える。**

概況のストレージ数値からバケットとキーの一覧へ飛べるようにする（spec §8.3 の「各項目は該当画面へドリルダウンできる」）。

- [ ] **Step 1: サイドバーの構成を書き換える**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/NavItem.kt`

```kotlin
package net.brightroom.garage.web.navigation

import net.brightroom.garage.shared.navigation.Route

/**
 * @param route この項目を押したときの行き先。
 * @param requiredOperation この画面が最低限必要とする Garage の operation。
 *   scope に含まれない場合はサイドバーで無効表示にする。
 *   これは UI ヒントであり、可否の実体は常に Garage が返す 403 で決まる（spec §6.3）。
 * @param matches この項目を選択状態にするルートの条件。行き先と現在地が
 *   一致しない項目（オブジェクトなど）があるため、等値ではなく述語で持つ。
 */
data class NavItem(
    val route: Route,
    val label: String,
    val requiredOperation: String? = null,
    val matches: (Route) -> Boolean = { it == route },
)

data class NavGroup(
    val title: String?,
    val items: List<NavItem>,
)

/**
 * サイドバーの構成。役割でグループ化する（spec §8.2）。
 *
 * クラスタ・メンテナンス・設定の各グループは、対応する画面を実装する Phase 3 で追加する。
 */
val navGroups: List<NavGroup> = listOf(
    NavGroup(
        title = null,
        items = listOf(
            NavItem(Route.Overview, "概況", requiredOperation = "GetClusterHealth"),
        ),
    ),
    NavGroup(
        title = "ストレージ",
        items = listOf(
            NavItem(
                route = Route.Buckets,
                label = "バケット",
                requiredOperation = "ListBuckets",
                matches = { it == Route.Buckets || it is Route.BucketDetail },
            ),
            NavItem(
                route = Route.Keys,
                label = "アクセスキー",
                requiredOperation = "ListKeys",
                matches = { it == Route.Keys || it is Route.KeyDetail },
            ),
            NavItem(
                // オブジェクトはバケットに属するため、単体では開けない。
                // 押したらバケットを選ばせる（P2-8）
                route = Route.Buckets,
                label = "オブジェクト",
                requiredOperation = "ListBuckets",
                matches = { it is Route.Objects },
            ),
        ),
    ),
)
```

- [ ] **Step 2: サイドバーの選択判定を直す**

`Sidebar.kt` の `NavigationDrawerItem` の `selected` を書き換える。

```kotlin
                NavigationDrawerItem(
                    label = {
                        Text(
                            if (enabled) item.label else "${item.label}（権限なし）",
                        )
                    },
                    selected = item.matches(current),
                    onClick = { if (enabled) onNavigate(item.route) },
                    modifier = Modifier.fillMaxWidth(),
                )
```

- [ ] **Step 3: 概況にドリルダウンを足す**

`OverviewScreen.kt` を次のように変える。

シグネチャに遷移を受け取る。

```kotlin
@Composable
fun OverviewScreen(onNavigate: (Route) -> Unit) {
```

`OverviewContent` と `KeyFigures` にも渡す。

```kotlin
@Composable
private fun OverviewContent(overview: Overview, onNavigate: (Route) -> Unit) {
    AlertBand(overview)
    KeyFigures(overview, onNavigate)
    NodeList(overview.nodes)
}
```

`FigureCard` に押せる状態を足す。

```kotlin
@Composable
private fun FigureCard(
    title: String,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(220.dp)
            .let { base -> onClick?.let { base.clickable(onClick = it) } ?: base },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
```

ストレージのカードを 2 枚に分け、それぞれの一覧へ送る。`KeyFigures` の「ストレージ」カードを次で置き換える。

```kotlin
        FigureCard("バケット", onClick = { onNavigate(Route.Buckets) }) {
            when (val storage = overview.storage) {
                is Section.Loaded -> Text(
                    "${storage.data.buckets}",
                    style = MaterialTheme.typography.headlineMedium,
                )

                is Section.Denied -> DeniedView(storage.operation)
                is Section.Failed -> Text(storage.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        FigureCard("アクセスキー", onClick = { onNavigate(Route.Keys) }) {
            when (val storage = overview.storage) {
                is Section.Loaded -> Text(
                    "${storage.data.keys}",
                    style = MaterialTheme.typography.headlineMedium,
                )

                is Section.Denied -> DeniedView(storage.operation)
                is Section.Failed -> Text(storage.message, style = MaterialTheme.typography.bodySmall)
            }
        }
```

`import androidx.compose.foundation.clickable` と `import net.brightroom.garage.shared.navigation.Route` を足す。

- [ ] **Step 4: App の呼び出しを直す**

```kotlin
            Route.Overview -> OverviewScreen(onNavigate = router::navigate)
            Route.Login -> OverviewScreen(onNavigate = router::navigate)
```

- [ ] **Step 5: 動作を確認**

- サイドバーからバケット・キーに移動できる
- `/objects/...` を開いている間、サイドバーの「オブジェクト」が選択状態になる
- サイドバーの「オブジェクト」を押すとバケット一覧に行く
- 概況の「バケット」「アクセスキー」のカードから一覧へ飛べる
- 制限トークンでログインすると、scope に無い項目が「（権限なし）」になる

- [ ] **Step 6: コミット**

```bash
git add web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/ web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/overview/OverviewScreen.kt web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt
git commit -m "feat(web): ストレージのナビゲーションと概況からの導線を追加"
```

- [ ] **Step 7: PR を出す**

```bash
git push -u origin phase2/5-web-objects
gh pr create --title "feat(web): オブジェクトブラウザとストレージの導線を追加" --body "Phase 2 の web（オブジェクト）。オブジェクトの一覧・アップロード・ダウンロード・削除・InspectObject と、サイドバーのストレージグループ、概況からのドリルダウンを追加する。

ファイルの転送はブラウザの fetch で完結させ、本文が wasm のメモリを通らないようにしている。401 / 403 の扱いは ApiClient と揃えている。

計画: docs/superpowers/plans/2026-08-23-rebuild-phase2-storage.md の Task 17-18"
```

---

## Task 19: e2e（バケット・キー・オブジェクト・scope 縮退）

**Files:**
- Modify: `e2e/tests/helpers.ts`
- Create: `e2e/tests/buckets.spec.ts`
- Create: `e2e/tests/keys.spec.ts`
- Create: `e2e/tests/objects.spec.ts`
- Modify: `.github/workflows/on-pull-request.yaml`

**Interfaces:**
- Consumes: Task 11 の `Limited-scope token`、Task 13–18 の画面
- Produces: `limitedToken()` / `openScreen(page, path, token)` / `uniqueName(prefix)`

**ブランチ:** `phase2/6-e2e`

```bash
git switch main
git pull
git switch -c phase2/6-e2e
```

spec §10 のパリティチェックリストのうち、Phase 2 が担うのは `buckets.spec.ts` と `keys.spec.ts`、および新規のオブジェクトブラウザと scope 縮退である。

Compose はキャンバスに描画するため、DOM のセレクタでは要素を掴めない。Phase 1 と同じくアクセシビリティツリー（`getByRole` / `getByText`）で操作する。**ファイル選択はページの `filechooser` イベントで受ける**（`<input>` は JS が動的に作ってすぐ捨てるため、セレクタでは掴めない）。

テストは並行実行しない（`playwright.config.ts` の `fullyParallel: false`）。ただし**同じ Garage を共有するため、作るものの名前は毎回変える**。

- [ ] **Step 1: ヘルパを足す**

`e2e/tests/helpers.ts` に追加する（既存の `adminToken` / `waitForLoginScreen` / `signIn` はそのまま）。

```ts
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
 */
export function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}`;
}
```

- [ ] **Step 2: バケットの e2e を書く**

`e2e/tests/buckets.spec.ts`

```ts
import { test, expect } from "@playwright/test";
import { adminToken, openScreen, uniqueName } from "./helpers";

const token = adminToken();

test.describe("Buckets", () => {
  test("creates, configures and deletes a bucket", async ({ page }) => {
    const name = uniqueName("e2e-bucket");

    await openScreen(page, "/buckets", token);
    await expect(page.getByText("dev-bucket").first()).toBeVisible({ timeout: 30_000 });

    // 作成。「作成」は「バケットを作成」にも部分一致するため exact で絞る
    await page.getByRole("button", { name: "バケットを作成" }).click({ force: true });
    await page.getByRole("textbox").last().fill(name, { force: true });
    await page.getByRole("button", { name: "作成", exact: true }).click({ force: true });

    await expect(page.getByText(name).first()).toBeVisible({ timeout: 15_000 });

    // 詳細へ
    await page.getByText(name).first().click({ force: true });
    await expect(page.getByText("アクセスキー", { exact: true })).toBeVisible();

    // 設定フォームが揃っていること。保存が他の設定を巻き込まないことは
    // server 側のテスト（UpdateBucket の部分更新）で担保している
    await expect(page.getByText("上限", { exact: true })).toBeVisible();
    await expect(page.getByText("CORS", { exact: true })).toBeVisible();
    await expect(page.getByText("ライフサイクル", { exact: true })).toBeVisible();

    // 削除は名前のタイプ入力を要求する（spec §8.6）
    await page.getByRole("button", { name: "バケットを削除" }).click({ force: true });
    await expect(page.getByText(/確認のため/)).toBeVisible();

    const confirm = page.getByRole("button", { name: "実行", exact: true });
    await confirm.click({ force: true });
    // 名前を打つまでは消えない
    await expect(page.getByText(/確認のため/)).toBeVisible();

    await page.getByRole("textbox").last().fill(name, { force: true });
    await confirm.click({ force: true });

    await expect(page).toHaveURL(/\/buckets$/, { timeout: 15_000 });
    await expect(page.getByText(name)).toHaveCount(0);
  });

  test("keeps the bucket route on reload", async ({ page }) => {
    await openScreen(page, "/buckets", token);
    await page.getByText("dev-bucket").first().click({ force: true });

    await expect(page).toHaveURL(/\/buckets\/[0-9a-f]+$/, { timeout: 15_000 });

    await page.reload();
    await expect(page.getByText("概要", { exact: true })).toBeVisible({ timeout: 30_000 });
  });
});
```

- [ ] **Step 3: キーの e2e を書く**

`e2e/tests/keys.spec.ts`

```ts
import { test, expect } from "@playwright/test";
import { adminToken, openScreen, uniqueName } from "./helpers";

const token = adminToken();

test.describe("Access keys", () => {
  test("creates a key, shows its secret once, then deletes it", async ({ page }) => {
    const name = uniqueName("e2e-key");

    await openScreen(page, "/keys", token);
    await expect(page.getByText("dev-key").first()).toBeVisible({ timeout: 30_000 });

    await page.getByRole("button", { name: "キーを作成" }).click({ force: true });
    await page.getByRole("textbox").last().fill(name, { force: true });
    await page.getByRole("button", { name: "作成", exact: true }).click({ force: true });

    // 作成直後だけ平文のシークレットが出る
    await expect(page.getByText(/を作成しました/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("シークレットアクセスキー")).toBeVisible();
    await page.getByRole("button", { name: "閉じる" }).click({ force: true });

    // 詳細では隠れており、「表示」で取り直す
    await page.getByText(name).first().click({ force: true });
    await expect(page.getByRole("button", { name: "表示" })).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "表示" }).click({ force: true });
    await expect(page.getByRole("button", { name: "表示" })).toHaveCount(0);

    // 削除
    await page.getByRole("button", { name: "キーを削除" }).click({ force: true });
    await page.getByRole("button", { name: "実行", exact: true }).click({ force: true });

    await expect(page).toHaveURL(/\/keys$/, { timeout: 15_000 });
    await expect(page.getByText(name)).toHaveCount(0);
  });
});
```

- [ ] **Step 4: オブジェクトの e2e を書く**

`e2e/tests/objects.spec.ts`

```ts
import { test, expect } from "@playwright/test";
import { adminToken, limitedToken, openScreen, signIn, uniqueName } from "./helpers";

const token = adminToken();

/** dev-bucket の ID を API から引く。UI から辿るより速く、壊れにくい。 */
async function devBucketId(request: import("@playwright/test").APIRequestContext): Promise<string> {
  const response = await request.get("/api/buckets", {
    headers: { Authorization: `Bearer ${token}` },
  });
  const buckets = await response.json();
  const bucket = buckets.find((it: { globalAliases: string[] }) =>
    it.globalAliases.includes("dev-bucket"),
  );

  if (!bucket) throw new Error("dev-bucket が見つかりません");

  return bucket.id;
}

test.describe("Objects", () => {
  test("uploads, lists, downloads and deletes an object", async ({ page, request }) => {
    const bucketId = await devBucketId(request);
    const fileName = `${uniqueName("e2e-object")}.txt`;

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

    await expect(page.getByText(`${fileName} をアップロードしました`)).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText(fileName).first()).toBeVisible();

    // 表の絞り込みで対象を 1 行にする。前のテストの残骸があっても
    // 「取得」「詳細」「削除」が別のオブジェクトを指さない
    await page.getByRole("textbox").first().fill(fileName, { force: true });

    // どのキーで見ているかを出す（spec §6.4）
    await expect(page.getByText(/として閲覧中/)).toBeVisible();

    // ダウンロード
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "取得" }).first().click({ force: true });
    expect((await download).suggestedFilename()).toBe(fileName);

    // InspectObject
    await page.getByRole("button", { name: "詳細" }).first().click({ force: true });
    await expect(page.getByText(/インライン格納|ブロック/)).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "閉じる" }).click({ force: true });

    // 削除
    await page.getByRole("button", { name: "削除", exact: true }).click({ force: true });
    await page.getByRole("button", { name: "実行", exact: true }).click({ force: true });

    await expect(page.getByText(/を削除しました/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(fileName)).toHaveCount(0);
  });

  test("keeps the folder in the url", async ({ page, request }) => {
    const bucketId = await devBucketId(request);
    const folder = uniqueName("e2e-folder");

    await openScreen(page, `/objects/${bucketId}`, token);

    const chooser = page.waitForEvent("filechooser");
    await page.getByRole("button", { name: "アップロード" }).click({ force: true });
    (await chooser).setFiles({
      name: "nested.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("nested"),
    });
    await expect(page.getByText(/をアップロードしました/)).toBeVisible({ timeout: 30_000 });

    // ルート直下に置いたものが見えていること
    await expect(page.getByText("nested.txt").first()).toBeVisible();

    // プレフィックスつきの URL を直接開いてもログイン済みのまま描画される
    await page.goto(`/objects/${bucketId}?prefix=${encodeURIComponent(folder + "/")}`);
    await expect(page.getByText(/オブジェクトはありません/)).toBeVisible({ timeout: 30_000 });
    await expect(page).toHaveURL(new RegExp(`prefix=${encodeURIComponent(folder + "/")}`));

    // 後始末
    await page.goto(`/objects/${bucketId}`);
    await page.getByRole("textbox").first().fill("nested.txt", { force: true });
    await page.getByRole("button", { name: "削除", exact: true }).click({ force: true });
    await page.getByRole("button", { name: "実行", exact: true }).click({ force: true });
    await expect(page.getByText(/を削除しました/)).toBeVisible({ timeout: 15_000 });
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
```

- [ ] **Step 5: CI に制限トークンを渡す**

`.github/workflows/on-pull-request.yaml` の "Resolve console admin token" ステップの後ろに追加する。

```yaml
      - name: Resolve limited-scope token
        run: |
          TOKEN=$(docker compose -f compose.ci.yaml logs garage-init \
            | grep 'Limited-scope token:' \
            | sed 's/.*Limited-scope token: //' \
            | tr -d '\r')
          if [ -z "$TOKEN" ]; then
            echo "failed to resolve limited-scope token from garage-init logs" >&2
            docker compose -f compose.ci.yaml logs garage-init >&2
            exit 1
          fi
          echo "::add-mask::$TOKEN"
          echo "E2E_LIMITED_TOKEN=$TOKEN" >> "$GITHUB_ENV"
```

"Start admin console" ステップの `env` に S3 の接続先を足す。既定値と同じだが、CI が何に繋いでいるかを明示する。

```yaml
        env:
          # サーバーは admin token も S3 資格情報も保持しない。
          # トークンは利用者がブラウザで入力し、リクエストごとに転送される。
          # S3 の資格情報は admin token から導出する（spec §6.4）。
          GARAGE_ADMIN_ENDPOINT: http://localhost:3903
          GARAGE_S3_ENDPOINT: http://localhost:3900
          GARAGE_S3_REGION: garage
          GARAGE_S3_PATH_STYLE: "true"
```

- [ ] **Step 6: ローカルで e2e を通す**

```bash
docker compose down -v
docker compose up -d
./gradlew :server:build
java -jar server/build/libs/garage-admin-console-all.jar &
```

```bash
export E2E_ADMIN_TOKEN=$(docker compose logs garage-init | sed -n 's/.*Console login token: //p' | tr -d '\r')
export E2E_LIMITED_TOKEN=$(docker compose logs garage-init | sed -n 's/.*Limited-scope token: //p' | tr -d '\r')
cd e2e && npm ci && npx playwright install --with-deps chromium && npm test
```

Expected: 既存の login / navigation / overview に加えて、buckets / keys / objects がすべて PASS

**落ちたテストは飛ばさない。** Compose のキャンバス描画は要素の出現が遅れるため、タイムアウト不足で落ちることがある。その場合は待ち方を直す（Phase 1 の `signIn` が入力の反映を待っているのと同じ考え方）。**待ちの問題なのか実装の問題なのかを見極めてから直すこと。**

- [ ] **Step 7: コミット**

```bash
git add e2e/tests/ .github/workflows/on-pull-request.yaml
git commit -m "test(e2e): バケット・キー・オブジェクトのテストを追加"
```

- [ ] **Step 8: PR を出す**

```bash
git push -u origin phase2/6-e2e
gh pr create --title "test(e2e): Phase 2 のパリティを確認する" --body "Phase 2 の e2e。バケットの作成・設定・削除、キーの作成・シークレット表示・削除、オブジェクトのアップロード・一覧・ダウンロード・詳細・削除、および scope 縮退（GetKeyInfo を持たないトークンで S3 ブラウザだけが縮退すること）を確認する。

ファイル選択は filechooser イベントで受ける（動的に作られる input はセレクタで掴めないため）。

計画: docs/superpowers/plans/2026-08-23-rebuild-phase2-storage.md の Task 19"
```

---

## Phase 2 の完了条件

すべて満たしたら Phase 3（Nodes / Layout / Workers / Blocks / Admin Tokens）に進む。

**機能**

- [ ] バケットの一覧・作成・詳細・削除ができる。削除はバケット名のタイプ入力を要求する
- [ ] バケットのグローバル別名を追加・削除できる
- [ ] バケットのキー権限を付与・全剥奪できる
- [ ] バケットの上限・公開・CORS・ライフサイクルを編集でき、**それぞれの保存が他の設定を巻き込まない**
- [ ] 未完了アップロードの後始末ができる
- [ ] アクセスキーの一覧・作成・インポート・更新・削除ができる
- [ ] キーのシークレットは作成直後と「表示」の操作でだけ現れる
- [ ] オブジェクトの一覧・アップロード・ダウンロード・削除ができ、フォルダを辿れる
- [ ] オブジェクトの詳細（`InspectObject`）を見られる
- [ ] 表示中のフォルダが URL に載り、リロードとブックマークで同じ場所に戻る
- [ ] サイドバーにストレージのグループがあり、概況からバケットとキーの一覧へ飛べる

**縮退（spec §6.3–6.5）**

- [ ] `GetKeyInfo` を持たないトークンでは、S3 ブラウザだけが「このトークンでは参照できません」になり、他の画面は通常どおり描ける
- [ ] read 以上の権限を持つキーが無いバケットでは、その旨とバケット設定への導線が出る
- [ ] 別名の無いバケットでは、S3 でアドレスできない旨とバケット設定への導線が出る
- [ ] scope に無い項目はサイドバーで無効表示になる

**秘密の扱い（spec §6.2, §6.4）**

- [ ] サーバーは admin token を保持しない。キャッシュのキーも SHA-256 のハッシュである
- [ ] S3 の secret access key はブラウザに返らない（キー詳細の「表示」で明示的に取得する場合を除く）
- [ ] ログに admin token も secret も出ない
- [ ] ログアウトでサーバーのキャッシュが破棄される

**テスト**

- [ ] `./gradlew build` が通る（`:shared` の wasmJs テストを含む）
- [ ] `:server` のテストが、キー選択規則・バケット名解決・キャッシュの TTL と破棄・`UpdateBucket` の部分更新・S3 縮退の 3 経路を覆っている
- [ ] e2e（login / navigation / overview / buckets / keys / objects）がローカルと CI の双方で通る

**後始末**

- [ ] `docker/init-garage.sh` から S3 資格情報の案内が消え、`Limited-scope token` を発行している
- [ ] Task 8 の手動確認用テスト（`S3RoundTripManualTest`）がリポジトリに残っていない
- [ ] 6 つの PR がすべて main にマージされている

**Phase 3 への申し送り**

Phase 3 の計画を書くときに引き継ぐ事実を、この計画の末尾に追記すること。少なくとも次を残す。

- `DataTable` / `ConfirmDialog` / `CopyButton` は Phase 3 の画面でもそのまま使える
- `MultiResponse`（`:shared`、Phase 1）は Node / Worker / Block 系で本格的に使う。Phase 2 では概況の block error 数でしか使っていない
- `ApiResult.Failure` がステータスを持つようになったため、Phase 3 の画面も 403 を `ProblemView` で扱える

## Phase 3 への申し送り

Phase 3 の計画を書くときに引き継ぐ事実を残す。

**Phase 2 で作ったものの再利用**

- `DataTable` / `ConfirmDialog` / `CopyButton` は Phase 3 の画面でもそのまま使える
- `MultiResponse`（`:shared`、Phase 1）は Node / Worker / Block 系で本格的に使う。Phase 2 では概況の block error 数でしか使っていない
- `ApiResult.Failure` がステータスを持つようになったため、Phase 3 の画面も 403 を `ProblemView` で扱える

**e2e を書くときに踏む事実**

- Compose のアクセシビリティツリーは `AlertDialog` を閉じると空になり、リロードするまで復活しない。`helpers.ts` の `afterDialog` がその回避策
- 表の行は 1 つの `button` に畳まれ、ラベルは全セルの連結になる。隣接テキストも連結される。`{ exact: true }` はほぼ使えない
- spec ファイルは並行実行されるため、`uniqueName` の prefix は spec ごとに固有にする
- 「サイドバーの scope 無効表示」は現在の fixture では原理的に検証できない。`docker/init-garage.sh` の `dev-limited` の scope は `GetClusterHealth` / `ListBuckets` / `ListKeys` を含み、`NavItem` の `requiredOperation` をすべて満たすため 1 項目も無効にならない。検証するには `ListBuckets` を持たない 3 本目の fixture トークンが要る
- ログインの往復（`openScreen` が毎テスト wasm を 2 回読む）は `page.addInitScript` で `sessionStorage` にトークンを入れれば 1 回に減らせる。テスト数が増える Phase 3 で効く

**e2e が覆っていない Phase 2 の機能**

- バケットの別名の追加・削除
- キー権限の付与・全剥奪
- 未完了アップロードの後始末
- S3 縮退の 2 経路（`no-usable-key` / `bucket-not-addressable`。ただし実機で手動確認済み）
- キーのインポートと更新
