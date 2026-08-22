# garage-admin-console 再構築 Phase 1（基盤・ログイン・概況）実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin token でログインし、クラスタの概況が異常ファーストで見えるところまでを、スクラッチで動く状態にする。

**Architecture:** `:shared` に型を置き、`:server` が Garage Admin API を型付きで叩いて正規化したエラーとともに返し、`:web`（Compose Multiplatform / wasmJs）が描画する。サーバーは admin token を保持せず、リクエストごとに転送する。概況は `/api/overview` 1 本に集約し、セクション単位で成否を持たせる。

**Tech Stack:** Kotlin Multiplatform / Ktor 3.5.2 / Compose Multiplatform 1.11.1 (wasmJs) / kotlinx.serialization / Playwright

**Spec:** `docs/superpowers/specs/2026-08-23-garage-admin-console-rebuild-design.md`

## Global Constraints

- 対象 Garage: **v2.3.0**（Admin API v2）。仕様の参照元は `https://garagehq.deuxfleurs.fr/api/garage-admin-v2.json`
- バージョンは `gradle/libs.versions.toml` の既存値を変更しない: Kotlin `2.4.10` / Ktor `3.5.2` / Compose Multiplatform `1.11.1` / kotlinx-serialization `1.11.0` / kotlinx-coroutines `1.11.0` / kotlinx-datetime `0.8.0-0.6.x-compat` / aws-sdk-kotlin `1.8.31` / logback `1.6.3`
- **新しいライブラリを追加しない。** ルーティングは手書き、状態管理ライブラリは使わない
- **縦切りで作る。** spec §12 の実装順序は目安であり、Phase 1 で作るモデルは `/api/session` と `/api/overview` が必要とする分だけに限る。46 API 分のモデルを先に書かない
- パッケージルート: `net.brightroom.garage.shared` / `net.brightroom.garage.server` / `net.brightroom.garage.web`
- `jvmToolchain(21)`（server）
- サーバーは admin token を保持しない。ログにも出さない
- テーマはダーク固定
- コミットは各タスク末尾で 1 つ

---

## フェーズのロードマップ

この計画は Phase 1 のみを扱う。各フェーズはそれ単体で動作し、e2e まで含めて完結する。

| Phase | スコープ | 依存 | e2e パリティ対応 |
|---|---|---|---|
| **1（本計画）** | shared 基盤 / Garage クライアント / エラー正規化 / `/api/session` / `/api/overview` / Router / セッション / ログイン / 概況 | — | `navigation` `dashboard` + 新規: ログイン・ログアウト |
| 2 | Buckets / Keys / Objects（S3 資格情報の導出、`SecretCache` の配線、`InspectObject`） | P1 | `buckets` `keys` + 新規: オブジェクトブラウザ |
| 3 | Nodes（Cluster 統合）/ Layout（preview 確認）/ Workers / Blocks / Admin Tokens | P1 | `cluster` `layout` |
| 4 | 最終パリティ確認と CI 調整 | P1–3 | 全 spec の通し確認 |

**P2 以降は各フェーズの着手時に、本計画と同じ粒度で書く。** Phase 1 には spec が「実装初日に検証する」と定めた項目（generic sealed interface の serializer 生成、master token での `GetCurrentAdminTokenInfo`）が含まれ、その結果が後続の型の形を左右するためである。今すぐ P2–P4 も同じ粒度で欲しい場合は書くので指示してほしい。

---

## File Structure（Phase 1 で触れるファイル）

**削除**（Task 0）
- `shared/src/**`, `server/src/**`, `web/src/**` の全ソース

**維持**（変更しない）
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `Dockerfile`, `compose.yaml`, `compose.ci.yaml`, `docker/garage.toml`, `.github/**`

**`:shared`**

| ファイル | 責務 |
|---|---|
| `shared/build.gradle.kts` | 依存を `api` で公開する形に変更 |
| `api/ApiError.kt` | エラーの正規化形（`ErrorCode` / `ApiError` / `ApiErrorResponse`） |
| `api/Section.kt` | `/api/overview` のセクション単位の成否 |
| `api/SessionInfo.kt` | ログイン中のトークン情報と scope 判定 |
| `api/Overview.kt` | 概況の集約 DTO と要約型 |
| `model/garage/ClusterHealth.kt` | `GetClusterHealth` のレスポンス |
| `model/garage/ClusterStatus.kt` | `GetClusterStatus` のレスポンスとノード |
| `model/garage/ClusterLayout.kt` | `GetClusterLayout` のうち Phase 1 が使う範囲 |
| `model/garage/MultiResponse.kt` | ノード別 success/error マップ |
| `model/garage/BlockError.kt` | `ListBlockErrors` の要素 |
| `model/garage/AdminTokenInfo.kt` | `GetCurrentAdminTokenInfo` のレスポンス |
| `navigation/Route.kt` | URL とスクリーンの相互変換（純粋関数） |
| `session/IdleTracker.kt` | アイドル判定（純粋ロジック） |

`Route` と `IdleTracker` は UI に依存しない純粋ロジックであり、`:shared` に置いて `jvmTest` で検証する。`:web` に置くと wasmJs のテスト実行に headless Chrome が必要になり、検証が重くなるため。spec §10 の「UI に依存しないロジックには単体テストを書く」を成立させるための配置である。

**`:server`**

| ファイル | 責務 |
|---|---|
| `Application.kt` | エントリポイントとモジュール構成 |
| `config/AppConfig.kt` | 環境変数の読み取り |
| `garage/GarageException.kt` | Garage 由来のエラーを表す例外 |
| `garage/GarageAdminClient.kt` | Admin API への型付きアクセス（トークンは引数で受ける） |
| `plugins/Serialization.kt` | JSON 設定 |
| `plugins/Di.kt` | 依存の登録 |
| `plugins/StatusPages.kt` | エラーの正規化 |
| `plugins/CallLogging.kt` | Authorization をログに出さない設定 |
| `plugins/StaticFiles.kt` | wasm 配信と SPA フォールバック |
| `plugins/Routing.kt` | `/api` 配下の集約 |
| `api/AuthContext.kt` | リクエストからトークンを取り出す |
| `api/SessionRoutes.kt` | `/api/session`, `/api/session/logout` |
| `api/OverviewService.kt` | 概況の並列取得と部分縮退 |
| `api/OverviewRoutes.kt` | `/api/overview` |

**`:web`**

| ファイル | 責務 |
|---|---|
| `web/build.gradle.kts` | 依存の定義 |
| `Main.kt` / `App.kt` | エントリポイントと画面の切り替え |
| `router/Router.kt` | History API と `Route`（`:shared`）の接続 |
| `session/SessionState.kt` | トークン保持・アイドル監視・CompositionLocal |
| `api/ApiClient.kt` | `/api` へのアクセスと 401 の扱い |
| `screens/login/LoginScreen.kt` | トークン入力 |
| `screens/overview/OverviewScreen.kt` | 概況（異常ファースト） |
| `navigation/NavItem.kt` / `Sidebar.kt` / `AppScaffold.kt` | 役割グループのサイドバー |
| `components/*.kt` | 共通コンポーネント |
| `theme/Color.kt` / `theme/Theme.kt` | ダークテーマ |

**`e2e`**
- `tests/login.spec.ts`, `tests/navigation.spec.ts`, `tests/overview.spec.ts`

---

## Task 0: 旧実装の削除と dev 環境の整備

**Files:**
- Delete: `shared/src/`, `server/src/`, `web/src/` 配下の全ソース
- Delete: `e2e/tests/*.spec.ts`（旧 UI 前提のため。パリティ確認用に内容は git 履歴から参照する）
- Modify: `mise.toml`
- Modify: `docker/init-garage.sh`

**Interfaces:**
- Consumes: なし
- Produces: `docker/init-garage.sh` が起動時に **dev 用の named admin token** を標準出力に表示する。以降のタスクとローカル確認はこのトークンを使う

- [ ] **Step 1: 旧ソースを削除する**

```bash
git rm -r --quiet shared/src server/src web/src e2e/tests
```

`build.gradle.kts` / `settings.gradle.kts` / `gradle/` / `compose.yaml` / `docker/garage.toml` / `.github/` は残すこと。スタックを踏襲するため、ビルド定義と CI は再利用する。

- [ ] **Step 2: `mise.toml` から不要になった環境変数を削除する**

`GARAGE_ADMIN_TOKEN` はサーバーが持たなくなった。S3 キーの案内も自動導出により不要になる。ファイル全体を次の内容にする。

```toml
[tools]
java = "openjdk-25"

[env]
GARAGE_ADMIN_ENDPOINT = "http://localhost:3903"
GARAGE_S3_ENDPOINT = "http://localhost:3900"
GARAGE_S3_REGION = "garage"
GARAGE_S3_PATH_STYLE = "true"

# ログイン用の admin token は `docker compose up` の初期化ログに表示される:
#   docker logs garage-admin-console-garage-init-1
```

- [ ] **Step 3: `docker/init-garage.sh` を named token 発行に変更する**

現行スクリプトは `garage.toml` の master token（`dev-admin-token`）を使うだけで、admin token を発行していない。ログイン画面が `GetCurrentAdminTokenInfo` を叩く以上、本番と同じ経路を dev でも踏めるようにする。

既存のアクセスキー作成ブロックの後（`echo "Garage initialization complete!"` の直前）に、次を挿入する。

```sh
# Create a named admin token for console login
EXISTING_TOKENS=$(curl -sf -H "Authorization: Bearer ${ADMIN_TOKEN}" "${GARAGE_ADMIN}/v2/ListAdminTokens")
CONSOLE_TOKEN_COUNT=$(echo "${EXISTING_TOKENS}" | jq '[.[] | select(.name == "dev-console")] | length')

if [ "${CONSOLE_TOKEN_COUNT}" = "0" ]; then
  echo "Creating admin token 'dev-console'..."
  TOKEN_RESPONSE=$(curl -sf -X POST \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name": "dev-console", "neverExpires": true, "scope": ["*"]}' \
    "${GARAGE_ADMIN}/v2/CreateAdminToken")

  CONSOLE_TOKEN=$(echo "${TOKEN_RESPONSE}" | jq -r '.secretToken')

  echo "============================================"
  echo "Console login token: ${CONSOLE_TOKEN}"
  echo "============================================"
else
  echo "Admin token 'dev-console' already exists."
  echo "Delete it and re-run to obtain a new one (the secret is shown only once)."
fi
```

- [ ] **Step 4: init が期待どおり動くことを確認する**

```bash
docker compose down -v
docker compose up -d
docker compose logs garage-init
```

期待: `Console login token: ` に続けてトークン文字列が表示される。`CreateAdminToken` のレスポンスのどのフィールドにトークンが入るかは実物で確認し、`secretToken` でなければ jq のパスをそのフィールド名に直す。

- [ ] **Step 5: master token の挙動を検証する（spec §11 の検証項目）**

```bash
curl -s -H "Authorization: Bearer dev-admin-token" http://localhost:3903/v2/GetCurrentAdminTokenInfo | jq .
curl -s -H "Authorization: Bearer <Step 4 で表示された token>" http://localhost:3903/v2/GetCurrentAdminTokenInfo | jq .
```

期待: named token の方は `name` `scope` `expired` を含む JSON が返る。master token の方は成功するとは限らない（`name` を持たない認証方式のため）。**結果を次のとおり扱う。**

- master token でも JSON が返る場合 → ログイン画面は master token も受け付ける。追加対応は不要
- master token でエラーが返る場合 → 仕様どおり。Task 10 のログイン失敗メッセージに「master token ではログインできない。`garage admin-token create` で発行したトークンを使うこと」を含める

どちらの結果だったかを Step 6 のコミットメッセージ本文に記録すること。後続タスクの実装者がこの検証をやり直さずに済む。

- [ ] **Step 6: コミット**

```bash
git add -A
git commit -m "chore: 旧実装を削除し dev 用 admin token の発行を追加

$(echo 'GetCurrentAdminTokenInfo の master token に対する挙動: <Step 5 の結果を記載>')"
```

---

## Task 1: `:shared` のビルド設定とエラーの正規化形

**Files:**
- Create: `shared/build.gradle.kts`（既存を上書き）
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/ApiError.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/ApiErrorTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `enum class ErrorCode { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, BAD_REQUEST, GARAGE_ERROR, INTERNAL }`
  - `data class ApiError(val code: ErrorCode, val message: String, val operation: String? = null)`
  - `data class ApiErrorResponse(val error: ApiError)`

- [ ] **Step 1: `shared/build.gradle.kts` を書く**

依存を `api` で公開する。`:server` と `:web` が `Instant` と `JsonElement` を直接扱うため。

```kotlin
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
```

- [ ] **Step 2: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/ApiErrorTest.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiErrorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun encodesErrorResponseWithOperation() {
        val response = ApiErrorResponse(
            ApiError(ErrorCode.FORBIDDEN, "insufficient scope", "GetKeyInfo"),
        )

        assertEquals(
            """{"error":{"code":"FORBIDDEN","message":"insufficient scope","operation":"GetKeyInfo"}}""",
            json.encodeToString(response),
        )
    }

    @Test
    fun omitsOperationWhenAbsent() {
        val response = ApiErrorResponse(ApiError(ErrorCode.INTERNAL, "boom"))

        val decoded = json.decodeFromString<ApiErrorResponse>(json.encodeToString(response))

        assertEquals(ErrorCode.INTERNAL, decoded.error.code)
        assertEquals("boom", decoded.error.message)
        assertEquals(null, decoded.error.operation)
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: コンパイルエラー（`ApiError` などが未定義）で FAIL。

- [ ] **Step 4: 実装する**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/ApiError.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable

/** サーバーが返すエラーの分類。HTTP ステータスとは独立に、UI が扱いを決めるために使う。 */
@Serializable
enum class ErrorCode {
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    BAD_REQUEST,
    GARAGE_ERROR,
    INTERNAL,
}

/**
 * @param operation エラーの原因となった Garage の operation 名。サーバー内部で起きたエラーでは null。
 */
@Serializable
data class ApiError(
    val code: ErrorCode,
    val message: String,
    val operation: String? = null,
)

@Serializable
data class ApiErrorResponse(val error: ApiError)
```

- [ ] **Step 5: テストが通ることを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: PASS。

- [ ] **Step 6: コミット**

```bash
git add shared/
git commit -m "feat(shared): エラーの正規化形を追加"
```

---

## Task 2: `Section<T>`（generic sealed interface の serializer 検証を兼ねる）

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/Section.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/SectionTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `sealed interface Section<out T>` と `Section.Loaded<T>(data: T)` / `Section.Denied(operation: String)` / `Section.Failed(message: String)`
  - JSON 表現は `{"type":"loaded","data":…}` / `{"type":"denied","operation":…}` / `{"type":"failed","message":…}`

**このタスクは spec が「実装の最初に検証する」と定めた項目を含む。** generic な sealed interface の serializer が生成できない場合の分岐は Step 5 に記載する。

- [ ] **Step 1: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/SectionTest.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun loadedRoundTrips() {
        val section: Section<Int> = Section.Loaded(42)
        val serializer = Section.serializer(Int.serializer())

        val encoded = json.encodeToString(serializer, section)

        assertEquals("""{"type":"loaded","data":42}""", encoded)
        assertEquals(section, json.decodeFromString(serializer, encoded))
    }

    @Test
    fun deniedRoundTrips() {
        val section: Section<Int> = Section.Denied("ListBuckets")
        val serializer = Section.serializer(Int.serializer())

        val encoded = json.encodeToString(serializer, section)

        assertEquals("""{"type":"denied","operation":"ListBuckets"}""", encoded)
        assertEquals(section, json.decodeFromString(serializer, encoded))
    }

    @Test
    fun failedRoundTrips() {
        val section: Section<Int> = Section.Failed("connection refused")
        val serializer = Section.serializer(Int.serializer())

        val encoded = json.encodeToString(serializer, section)

        assertEquals("""{"type":"failed","message":"connection refused"}""", encoded)
        assertEquals(section, json.decodeFromString(serializer, encoded))
    }

    @Test
    fun dataOrNullReturnsPayloadOnlyWhenLoaded() {
        assertEquals(42, Section.Loaded(42).dataOrNull())
        assertEquals(null, Section.Denied("X").dataOrNull())
        assertEquals(null, Section.Failed("X").dataOrNull())
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: コンパイルエラー（`Section` が未定義）で FAIL。

- [ ] **Step 3: 実装する**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/Section.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 概況のように複数の operation を集約するレスポンスで、セクション単位の成否を運ぶ。
 *
 * admin token は scope を限定できるため 403 は正常系であり、
 * 1 つの operation が拒否されても他のセクションは通常どおり描画する。
 */
@Serializable
sealed interface Section<out T> {

    @Serializable
    @SerialName("loaded")
    data class Loaded<out T>(val data: T) : Section<T>

    /** scope 不足で拒否された（HTTP 403）。 */
    @Serializable
    @SerialName("denied")
    data class Denied(val operation: String) : Section<Nothing>

    /** 403 以外の理由で取得できなかった。 */
    @Serializable
    @SerialName("failed")
    data class Failed(val message: String) : Section<Nothing>

    fun dataOrNull(): T? = (this as? Loaded)?.data
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: 4 テストすべて PASS。

- [ ] **Step 5: wasmJs 側でコンパイルが通ることを確認する**

serializer はコンパイル時プラグインが生成するため、コンパイルが通れば生成に成功している。

```bash
./gradlew :shared:compileKotlinWasmJs
```

期待: BUILD SUCCESSFUL。

**失敗した場合の分岐（spec §7.2 に記載の代替案）:** generic sealed interface の serializer が生成できない場合は、ジェネリクスをやめてセクションごとの具体型に展開する。`Section<ClusterHealth>` を `HealthSection`（`HealthSection.Loaded(data: ClusterHealth)` / `.Denied` / `.Failed`）のように、Task 5 で必要になる 5 種類分だけ定義する。JSON の形（`type` による判別と `data` / `operation` / `message`）は変えないこと。web 側の描画とテストがそのまま使える。

- [ ] **Step 6: コミット**

```bash
git add shared/
git commit -m "feat(shared): セクション単位の成否を表す Section を追加"
```

---

## Task 3: `:shared` の Garage モデル（Phase 1 で使う範囲）

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ClusterHealth.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ClusterStatus.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/ClusterLayout.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/MultiResponse.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/BlockError.kt`
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/model/garage/AdminTokenInfo.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/GarageModelTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `ClusterHealth(status, knownNodes, connectedNodes, storageNodes, storageNodesUp, partitions, partitionsQuorum, partitionsAllOk)`
  - `ClusterStatus(layoutVersion: Long, nodes: List<NodeResp>)`
  - `NodeResp(id, isUp, draining, hostname?, addr?, garageVersion?, lastSeenSecsAgo?, role?, dataPartition?, metadataPartition?)`
  - `NodeAssignedRole(zone: String, tags: List<String>, capacity: Long?)`
  - `FreeSpace(available: Long, total: Long)`
  - `ClusterLayout(version: Long, stagedRoleChanges: List<JsonElement>)`
  - `MultiResponse<T>(success: Map<String, T>, error: Map<String, String>)`
  - `BlockError(blockHash, refcount, errorCount, lastTrySecsAgo, nextTryInSecs)`
  - `AdminTokenInfo(name, scope, expired, id?, created?, expiration?)`

**縦切りの範囲:** `ClusterLayout` は Phase 1 では `stagedRoleChanges` の件数しか使わないため、要素を `JsonElement` のまま受ける。Phase 3 の Layout 画面で型を付ける。`ListBuckets` / `ListKeys` は件数のみ必要なので専用の型を作らず、サーバー側で `JsonArray` として受けて `size` を取る（Task 8）。

- [ ] **Step 1: 失敗するテストを書く**

Garage の実レスポンスに近い JSON を fixture にする。`ignoreUnknownKeys` を効かせるため、モデルに無いフィールドをあえて含める。

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/model/garage/GarageModelTest.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GarageModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesClusterHealth() {
        val health = json.decodeFromString<ClusterHealth>(
            """
            {"status":"healthy","knownNodes":3,"connectedNodes":3,"storageNodes":3,
             "storageNodesUp":3,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}
            """.trimIndent(),
        )

        assertEquals("healthy", health.status)
        assertEquals(256, health.partitionsAllOk)
        assertTrue(health.isHealthy)
    }

    @Test
    fun degradedHealthIsNotHealthy() {
        val health = json.decodeFromString<ClusterHealth>(
            """
            {"status":"degraded","knownNodes":3,"connectedNodes":2,"storageNodes":3,
             "storageNodesUp":2,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":200}
            """.trimIndent(),
        )

        assertEquals(false, health.isHealthy)
    }

    @Test
    fun decodesClusterStatusWithOptionalNodeFields() {
        val status = json.decodeFromString<ClusterStatus>(
            """
            {"layoutVersion":7,"nodes":[
              {"id":"abc","isUp":true,"draining":false,"hostname":"node-a","addr":"10.0.0.1:3901",
               "garageVersion":"v2.3.0","role":{"zone":"dc1","tags":["ssd"],"capacity":1073741824},
               "dataPartition":{"available":500,"total":1000},
               "metadataPartition":{"available":900,"total":1000},"unknownField":1},
              {"id":"def","isUp":false,"draining":true,"lastSeenSecsAgo":120}
            ]}
            """.trimIndent(),
        )

        assertEquals(7L, status.layoutVersion)
        assertEquals(2, status.nodes.size)
        assertEquals("dc1", status.nodes[0].role?.zone)
        assertEquals(1073741824L, status.nodes[0].role?.capacity)
        assertEquals(500L, status.nodes[0].dataPartition?.available)
        assertNull(status.nodes[1].role)
        assertEquals(120L, status.nodes[1].lastSeenSecsAgo)
    }

    @Test
    fun decodesClusterLayoutAndCountsStagedChanges() {
        val layout = json.decodeFromString<ClusterLayout>(
            """
            {"version":7,"roles":[],"parameters":{"zoneRedundancy":"maximum"},"partitionSize":1024,
             "stagedRoleChanges":[{"id":"abc","zone":"dc2","capacity":1,"tags":[]}]}
            """.trimIndent(),
        )

        assertEquals(7L, layout.version)
        assertEquals(1, layout.stagedRoleChanges.size)
    }

    @Test
    fun decodesMultiResponseOfBlockErrors() {
        val serializer = MultiResponse.serializer(ListSerializer(BlockError.serializer()))
        val response = json.decodeFromString(
            serializer,
            """
            {"success":{"node-a":[
               {"blockHash":"ff00","refcount":2,"errorCount":3,"lastTrySecsAgo":10,"nextTryInSecs":60}
             ],"node-b":[]},
             "error":{"node-c":"node unavailable"}}
            """.trimIndent(),
        )

        assertEquals(1, response.success["node-a"]?.size)
        assertEquals(0, response.success["node-b"]?.size)
        assertEquals("ff00", response.success["node-a"]?.first()?.blockHash)
        assertEquals("node unavailable", response.error["node-c"])
    }

    @Test
    fun decodesAdminTokenInfo() {
        val info = json.decodeFromString<AdminTokenInfo>(
            """
            {"id":"tok1","name":"alice","scope":["ListBuckets","GetBucketInfo"],
             "expired":false,"created":"2026-01-01T00:00:00Z","expiration":"2026-12-31T23:59:59Z"}
            """.trimIndent(),
        )

        assertEquals("alice", info.name)
        assertEquals(listOf("ListBuckets", "GetBucketInfo"), info.scope)
        assertEquals(false, info.expired)
    }

    @Test
    fun decodesAdminTokenInfoWithoutOptionalFields() {
        val info = json.decodeFromString<AdminTokenInfo>(
            """{"name":"bob","scope":["*"],"expired":false}""",
        )

        assertNull(info.expiration)
        assertNull(info.created)
        assertNull(info.id)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 3: モデルを実装する**

`ClusterHealth.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetClusterHealth` のレスポンス。 */
@Serializable
data class ClusterHealth(
    /** `healthy` / `degraded` / `unavailable` のいずれか。 */
    val status: String,
    val knownNodes: Int,
    val connectedNodes: Int,
    val storageNodes: Int,
    val storageNodesUp: Int,
    val partitions: Int,
    val partitionsQuorum: Int,
    val partitionsAllOk: Int,
) {
    val isHealthy: Boolean get() = status == "healthy"
}
```

`ClusterStatus.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `GetClusterStatus` のレスポンス。 */
@Serializable
data class ClusterStatus(
    val layoutVersion: Long,
    val nodes: List<NodeResp>,
)

@Serializable
data class NodeResp(
    val id: String,
    val isUp: Boolean,
    /** 旧レイアウトに属し、データを退避中であることを示す。 */
    val draining: Boolean,
    val hostname: String? = null,
    val addr: String? = null,
    val garageVersion: String? = null,
    val lastSeenSecsAgo: Long? = null,
    val role: NodeAssignedRole? = null,
    val dataPartition: FreeSpace? = null,
    val metadataPartition: FreeSpace? = null,
)

@Serializable
data class NodeAssignedRole(
    val zone: String,
    val tags: List<String>,
    /** gateway ノードでは null。 */
    val capacity: Long? = null,
)

@Serializable
data class FreeSpace(
    val available: Long,
    val total: Long,
)
```

`ClusterLayout.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `GetClusterLayout` のレスポンスのうち Phase 1 が使う範囲。
 *
 * Phase 1 は staged 変更の「件数」しか必要としないため、要素は [JsonElement] のまま受ける。
 * Layout 画面を作る Phase 3 で型を付ける。
 */
@Serializable
data class ClusterLayout(
    val version: Long,
    val stagedRoleChanges: List<JsonElement> = emptyList(),
)
```

`MultiResponse.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/**
 * 複数ノードに問い合わせる operation のレスポンス。
 * 一部のノードだけが失敗しうるため、成功と失敗を潰さずに保持する。
 */
@Serializable
data class MultiResponse<T>(
    val success: Map<String, T> = emptyMap(),
    val error: Map<String, String> = emptyMap(),
)
```

`BlockError.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/** `ListBlockErrors` の要素。再同期に失敗しているブロックを表す。 */
@Serializable
data class BlockError(
    val blockHash: String,
    val refcount: Long,
    val errorCount: Long,
    val lastTrySecsAgo: Long,
    val nextTryInSecs: Long,
)
```

`AdminTokenInfo.kt`

```kotlin
package net.brightroom.garage.shared.model.garage

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** `GetCurrentAdminTokenInfo` および `GetAdminTokenInfo` のレスポンス。 */
@Serializable
data class AdminTokenInfo(
    val name: String,
    /** 許可された operation 名の一覧。`*` はすべてを許可する。 */
    val scope: List<String>,
    val expired: Boolean,
    val id: String? = null,
    val created: Instant? = null,
    val expiration: Instant? = null,
)
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: 7 テストすべて PASS。

`Instant` のデシリアライズで失敗する場合は、`kotlinx.datetime.Instant` ではなく `kotlin.time.Instant` を import する（kotlinx-datetime `0.8.0-0.6.x-compat` はどちらかを typealias で提供する）。両方失敗する場合のみ、`created` / `expiration` の型を `String?` に変え、Task 16 の残り期限表示を「期限の文字列表示」に落とす。

- [ ] **Step 5: wasmJs でコンパイルが通ることを確認する**

```bash
./gradlew :shared:compileKotlinWasmJs
```

期待: BUILD SUCCESSFUL。

- [ ] **Step 6: コミット**

```bash
git add shared/
git commit -m "feat(shared): Phase 1 で使う Garage のモデルを追加"
```

---

## Task 4: `SessionInfo` と scope 判定

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/SessionInfo.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/SessionInfoTest.kt`

**Interfaces:**
- Consumes: `AdminTokenInfo`（Task 3）
- Produces:
  - `data class SessionInfo(name: String, scope: List<String>, expired: Boolean, expiration: Instant?)`
  - `fun SessionInfo.allows(operation: String): Boolean`
  - `fun AdminTokenInfo.toSessionInfo(): SessionInfo`

**scope 判定の用途を限定すること。** これはサイドバーの無効表示に使う UI ヒントであり、権限判定の実体ではない（spec §6.3）。サーバーはこの判定を行わず、Garage が返す 403 をそのまま正規化する。

- [ ] **Step 1: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/SessionInfoTest.kt`

```kotlin
package net.brightroom.garage.shared.api

import net.brightroom.garage.shared.model.garage.AdminTokenInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionInfoTest {

    @Test
    fun wildcardScopeAllowsEveryOperation() {
        val session = SessionInfo(name = "root", scope = listOf("*"), expired = false)

        assertTrue(session.allows("ListBuckets"))
        assertTrue(session.allows("PurgeBlocks"))
    }

    @Test
    fun explicitScopeAllowsOnlyListedOperations() {
        val session = SessionInfo(
            name = "readonly",
            scope = listOf("ListBuckets", "GetBucketInfo"),
            expired = false,
        )

        assertTrue(session.allows("ListBuckets"))
        assertFalse(session.allows("DeleteBucket"))
    }

    @Test
    fun convertsAdminTokenInfo() {
        val info = AdminTokenInfo(
            name = "alice",
            scope = listOf("ListKeys"),
            expired = false,
            id = "tok1",
        )

        val session = info.toSessionInfo()

        assertEquals("alice", session.name)
        assertEquals(listOf("ListKeys"), session.scope)
        assertFalse(session.expired)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 3: 実装する**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/SessionInfo.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.AdminTokenInfo

/** ログイン中の admin token の情報。`/api/session` が返す。 */
@Serializable
data class SessionInfo(
    val name: String,
    val scope: List<String>,
    val expired: Boolean,
    val expiration: Instant? = null,
)

/**
 * この操作が scope に含まれるかを返す。
 *
 * サイドバーの無効表示に使う UI ヒントであり、権限判定の実体ではない。
 * 実際の可否は常に Garage が返す 403 で決まる。
 */
fun SessionInfo.allows(operation: String): Boolean =
    scope.contains("*") || scope.contains(operation)

fun AdminTokenInfo.toSessionInfo(): SessionInfo =
    SessionInfo(
        name = name,
        scope = scope,
        expired = expired,
        expiration = expiration,
    )
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: PASS。

- [ ] **Step 5: コミット**

```bash
git add shared/
git commit -m "feat(shared): SessionInfo と scope 判定を追加"
```

---

## Task 5: `Overview` の集約 DTO

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/Overview.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/OverviewTest.kt`

**Interfaces:**
- Consumes: `Section`（Task 2）, `ClusterHealth` / `NodeResp`（Task 3）
- Produces:
  - `data class Overview(health, nodes, layout, storage, blockErrors)` — 各フィールドは `Section<…>`
  - `data class NodeSummary(id, hostname?, zone?, isUp, draining, capacity?, dataAvailable?, dataTotal?)`
  - `data class LayoutSummary(version: Long, stagedChanges: Int)`
  - `data class StorageSummary(buckets: Int, keys: Int)`
  - `fun NodeResp.toSummary(): NodeSummary`
  - `fun Overview.alerts(): List<OverviewAlert>` と `data class OverviewAlert(severity, message)` / `enum class AlertSeverity { WARNING, ERROR }`

**`alerts()` が概況画面の異常帯の実体である。** spec §8.3 のとおり、判定に使えるのは `Overview` が運ぶ情報だけに限る。

- [ ] **Step 1: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/api/OverviewTest.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.FreeSpace
import net.brightroom.garage.shared.model.garage.NodeAssignedRole
import net.brightroom.garage.shared.model.garage.NodeResp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverviewTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun health(status: String, quorum: Int = 256) = ClusterHealth(
        status = status,
        knownNodes = 3,
        connectedNodes = 3,
        storageNodes = 3,
        storageNodesUp = 3,
        partitions = 256,
        partitionsQuorum = quorum,
        partitionsAllOk = 256,
    )

    private fun overview(
        healthSection: Section<ClusterHealth> = Section.Loaded(health("healthy")),
        nodes: List<NodeSummary> = emptyList(),
        layout: LayoutSummary = LayoutSummary(version = 7, stagedChanges = 0),
        blockErrors: Int = 0,
    ) = Overview(
        health = healthSection,
        nodes = Section.Loaded(nodes),
        layout = Section.Loaded(layout),
        storage = Section.Loaded(StorageSummary(buckets = 12, keys = 8)),
        blockErrors = Section.Loaded(blockErrors),
    )

    @Test
    fun summarisesNode() {
        val node = NodeResp(
            id = "abc",
            isUp = true,
            draining = false,
            hostname = "node-a",
            role = NodeAssignedRole(zone = "dc1", tags = listOf("ssd"), capacity = 1000),
            dataPartition = FreeSpace(available = 400, total = 1000),
        )

        val summary = node.toSummary()

        assertEquals("abc", summary.id)
        assertEquals("node-a", summary.hostname)
        assertEquals("dc1", summary.zone)
        assertEquals(1000L, summary.capacity)
        assertEquals(400L, summary.dataAvailable)
        assertEquals(1000L, summary.dataTotal)
    }

    @Test
    fun healthyClusterHasNoAlerts() {
        assertEquals(emptyList(), overview().alerts())
    }

    @Test
    fun stagedLayoutChangesRaiseWarning() {
        val alerts = overview(layout = LayoutSummary(version = 7, stagedChanges = 2)).alerts()

        assertEquals(1, alerts.size)
        assertEquals(AlertSeverity.WARNING, alerts[0].severity)
        assertTrue(alerts[0].message.contains("2"))
    }

    @Test
    fun blockErrorsRaiseWarning() {
        val alerts = overview(blockErrors = 3).alerts()

        assertEquals(1, alerts.size)
        assertEquals(AlertSeverity.WARNING, alerts[0].severity)
    }

    @Test
    fun downNodeRaisesError() {
        val down = NodeSummary(id = "def", isUp = false, draining = false)
        val alerts = overview(nodes = listOf(down)).alerts()

        assertEquals(1, alerts.size)
        assertEquals(AlertSeverity.ERROR, alerts[0].severity)
    }

    @Test
    fun unavailableClusterRaisesError() {
        val alerts = overview(healthSection = Section.Loaded(health("unavailable", quorum = 200))).alerts()

        assertTrue(alerts.any { it.severity == AlertSeverity.ERROR })
    }

    @Test
    fun deniedSectionsProduceNoAlerts() {
        val denied = Overview(
            health = Section.Denied("GetClusterHealth"),
            nodes = Section.Denied("GetClusterStatus"),
            layout = Section.Denied("GetClusterLayout"),
            storage = Section.Denied("ListBuckets"),
            blockErrors = Section.Denied("ListBlockErrors"),
        )

        assertEquals(emptyList(), denied.alerts())
    }

    // --- wire format の検証 ---
    // サーバーは Overview.serializer() で書き、web は同じ serializer で読む。
    // generic sealed interface の discriminator がこの経路で往復できることを
    // ここで確定させる（サーバーと web を書く前に失敗させる）。

    @Test
    fun overviewRoundTripsWhenEverySectionIsLoaded() {
        val original = overview(
            nodes = listOf(
                NodeSummary(
                    id = "abc",
                    isUp = true,
                    draining = false,
                    hostname = "node-a",
                    zone = "dc1",
                    capacity = 1000,
                    dataAvailable = 400,
                    dataTotal = 1000,
                ),
            ),
        )

        val encoded = json.encodeToString(Overview.serializer(), original)

        assertEquals(original, json.decodeFromString(Overview.serializer(), encoded))
        assertTrue(encoded.contains(""""type":"loaded""""), "discriminator が出力されるべき: $encoded")
    }

    @Test
    fun overviewRoundTripsWithMixedSections() {
        val original = Overview(
            health = Section.Loaded(health("degraded")),
            nodes = Section.Denied("GetClusterStatus"),
            layout = Section.Failed("connection refused"),
            storage = Section.Loaded(StorageSummary(buckets = 1, keys = 2)),
            blockErrors = Section.Denied("ListBlockErrors"),
        )

        val encoded = json.encodeToString(Overview.serializer(), original)

        assertEquals(original, json.decodeFromString(Overview.serializer(), encoded))
        assertTrue(encoded.contains(""""type":"denied""""), "discriminator が出力されるべき: $encoded")
        assertTrue(encoded.contains(""""type":"failed""""), "discriminator が出力されるべき: $encoded")
    }
}
```

**この 2 つの round-trip テストが、generic sealed interface に関する検証スパイクの実体である。** Task 2 は `Section.serializer(Int.serializer())` を明示的に渡した場合しか確認しておらず、`Overview` のフィールドとして埋め込まれた経路（サーバーと web が実際に使う経路）は検証していない。ここが通って初めて、Task 10 の `call.respond(overview)` と Task 17 の `decodeFromString(Overview.serializer(), …)` の対称性が保証される。

**失敗した場合は Task 2 Step 5 に記載した代替案（セクションごとの具体型への展開）に切り替える。** その判断はこのタスクで行い、サーバーと web を書き始める前に決着させること。

- [ ] **Step 2: テストが失敗することを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 3: 実装する**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/api/Overview.kt`

```kotlin
package net.brightroom.garage.shared.api

import kotlinx.serialization.Serializable
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.NodeResp

/**
 * 概況画面が 1 リクエストで取得する集約。
 *
 * scope 制限により一部が 403 になりうるため、セクション単位で成否を持つ。
 */
@Serializable
data class Overview(
    val health: Section<ClusterHealth>,
    val nodes: Section<List<NodeSummary>>,
    val layout: Section<LayoutSummary>,
    val storage: Section<StorageSummary>,
    val blockErrors: Section<Int>,
)

@Serializable
data class NodeSummary(
    val id: String,
    val isUp: Boolean,
    val draining: Boolean,
    val hostname: String? = null,
    val zone: String? = null,
    val capacity: Long? = null,
    val dataAvailable: Long? = null,
    val dataTotal: Long? = null,
)

@Serializable
data class LayoutSummary(
    val version: Long,
    val stagedChanges: Int,
)

@Serializable
data class StorageSummary(
    val buckets: Int,
    val keys: Int,
)

@Serializable
enum class AlertSeverity { WARNING, ERROR }

@Serializable
data class OverviewAlert(
    val severity: AlertSeverity,
    val message: String,
)

fun NodeResp.toSummary(): NodeSummary =
    NodeSummary(
        id = id,
        isUp = isUp,
        draining = draining,
        hostname = hostname,
        zone = role?.zone,
        capacity = role?.capacity,
        dataAvailable = dataPartition?.available,
        dataTotal = dataPartition?.total,
    )

/**
 * 概況画面の異常帯に出す内容。正常時は空になる。
 *
 * 判定には [Overview] が運ぶ情報しか使わない。取得できなかったセクション
 * （403 や失敗）については何も主張しない。
 */
fun Overview.alerts(): List<OverviewAlert> = buildList {
    health.dataOrNull()?.let { h ->
        when {
            h.status == "unavailable" ->
                add(OverviewAlert(AlertSeverity.ERROR, "一部のパーティションで書き込みquorumが得られていません"))

            h.partitionsQuorum < h.partitions ->
                add(
                    OverviewAlert(
                        AlertSeverity.ERROR,
                        "${h.partitions - h.partitionsQuorum} 個のパーティションでquorumが不足しています",
                    ),
                )

            h.status == "degraded" ->
                add(OverviewAlert(AlertSeverity.WARNING, "一部のストレージノードに接続できていません"))
        }
    }

    nodes.dataOrNull()?.filter { !it.isUp }?.takeIf { it.isNotEmpty() }?.let { down ->
        add(
            OverviewAlert(
                AlertSeverity.ERROR,
                "${down.size} 台のノードがダウンしています: ${down.joinToString { it.hostname ?: it.id }}",
            ),
        )
    }

    layout.dataOrNull()?.takeIf { it.stagedChanges > 0 }?.let { l ->
        add(
            OverviewAlert(
                AlertSeverity.WARNING,
                "レイアウト v${l.version} に ${l.stagedChanges} 件の未適用の変更があります",
            ),
        )
    }

    blockErrors.dataOrNull()?.takeIf { it > 0 }?.let { count ->
        add(OverviewAlert(AlertSeverity.WARNING, "$count 件のブロックで再同期エラーが発生しています"))
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :shared:jvmTest
```

期待: 9 テストすべて PASS。

- [ ] **Step 5: shared 全体のコンパイルを確認する**

```bash
./gradlew :shared:build
```

期待: BUILD SUCCESSFUL。

- [ ] **Step 6: コミット**

```bash
git add shared/
git commit -m "feat(shared): 概況の集約 DTO と異常判定を追加"
```

---

## Task 6: `:server` の骨格と設定

**Files:**
- Create: `server/build.gradle.kts`（既存を上書き）
- Create: `server/src/main/kotlin/net/brightroom/garage/server/Application.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/config/AppConfig.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Serialization.kt`
- Create: `server/src/main/resources/application.yaml`
- Create: `server/src/main/resources/logback.xml`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/config/AppConfigTest.kt`
- Test: `server/src/test/resources/application-test.yaml`

**Interfaces:**
- Consumes: `:shared`
- Produces:
  - `data class AppConfig(garageAdminEndpoint: String)` と `AppConfig.from(environment: ApplicationEnvironment): AppConfig`
  - `fun Application.configureSerialization()`
  - `val GarageJson: Json`（`ignoreUnknownKeys = true`, `explicitNulls = false`）

**Phase 1 では AWS SDK を依存に入れない。** S3 は Phase 2 で扱う。`AppConfig` に S3 の項目を先回りして足さないこと。

- [ ] **Step 1: `server/build.gradle.kts` を書く**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("net.brightroom.garage.server.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("garage-admin-console-all.jar")
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.di)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named("processResources") {
    dependsOn(":web:wasmJsBrowserDistribution")
}

tasks.named<Copy>("processResources") {
    from(project(":web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) {
        into("web")
    }
}
```

CORS プラグインは使わない。web は同じサーバーから配信するため不要である（spec §11 の dev の項）。

- [ ] **Step 2: 失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/config/AppConfigTest.kt`

```kotlin
package net.brightroom.garage.server.config

import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {

    @Test
    fun readsAdminEndpointFromConfig() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "garage.admin.endpoint" to "http://garage.test:3903",
            )
        }
        application {
            val config = AppConfig.from(environment)
            assertEquals("http://garage.test:3903", config.garageAdminEndpoint)
        }
        startApplication()
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

```bash
./gradlew :server:test
```

期待: コンパイルエラー（`AppConfig` が未定義）で FAIL。

- [ ] **Step 4: 実装する**

`server/src/main/kotlin/net/brightroom/garage/server/config/AppConfig.kt`

```kotlin
package net.brightroom.garage.server.config

import io.ktor.server.application.ApplicationEnvironment

/**
 * サーバーの設定。
 *
 * admin token は含まない。トークンは利用者がブラウザで入力し、
 * リクエストごとに転送されるため、サーバーは保持しない。
 */
data class AppConfig(
    val garageAdminEndpoint: String,
) {
    companion object {
        fun from(environment: ApplicationEnvironment): AppConfig =
            AppConfig(
                garageAdminEndpoint = environment.config
                    .property("garage.admin.endpoint")
                    .getString(),
            )
    }
}
```

`server/src/main/kotlin/net/brightroom/garage/server/plugins/Serialization.kt`

```kotlin
package net.brightroom.garage.server.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

/**
 * Garage のレスポンスと自身のレスポンスの双方に使う JSON 設定。
 *
 * Garage は将来のバージョンでフィールドを増やしうるため、未知のキーは無視する。
 */
val GarageJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(GarageJson)
    }
}
```

`server/src/main/kotlin/net/brightroom/garage/server/Application.kt`

```kotlin
package net.brightroom.garage.server

import io.ktor.server.application.Application
import io.ktor.server.cio.EngineMain
import net.brightroom.garage.server.plugins.configureSerialization

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
}
```

以降のタスクで `module()` に設定を追加していく。

`server/src/main/resources/application.yaml`

```yaml
ktor:
  deployment:
    host: 0.0.0.0
    port: ${PORT:8080}
  application:
    modules:
      - net.brightroom.garage.server.ApplicationKt.module

garage:
  admin:
    endpoint: ${GARAGE_ADMIN_ENDPOINT:http://localhost:3903}
```

`server/src/main/resources/logback.xml`

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
    <logger name="io.ktor.routing.Routing" level="INFO"/>
</configuration>
```

`server/src/test/resources/application-test.yaml`

```yaml
ktor:
  application:
    modules:
      - net.brightroom.garage.server.ApplicationKt.module

garage:
  admin:
    endpoint: http://garage.test:3903
```

- [ ] **Step 5: テストが通ることを確認する**

```bash
./gradlew :server:test
```

期待: PASS。`:web` のソースがまだ無いため `processResources` が失敗する場合は、Task 13 まで `-x processResources` を付けて実行してよい。

- [ ] **Step 6: コミット**

```bash
git add server/
git commit -m "feat(server): アプリケーションの骨格と設定を追加"
```

---

## Task 7: Garage Admin API クライアント

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/GarageException.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/garage/GarageAdminClient.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/garage/GarageAdminClientTest.kt`

**Interfaces:**
- Consumes: `AppConfig`（Task 6）, `ErrorCode`（Task 1）
- Produces:
  - `class GarageException(code: ErrorCode, operation: String, status: HttpStatusCode, message: String) : RuntimeException`
  - `class GarageAdminClient(endpoint: String, engine: HttpClientEngine = CIO.create())`
    - `suspend fun get(token: String, operation: String, params: Map<String, String> = emptyMap()): HttpResponse`
    - `suspend fun post(token: String, operation: String, body: JsonElement? = null, params: Map<String, String> = emptyMap()): HttpResponse`
    - `fun close()`
  - `suspend fun HttpResponse.requireSuccess(operation: String): HttpResponse`
  - `suspend inline fun <reified T> HttpResponse.garageBody(operation: String): T`
  - `suspend fun <T> HttpResponse.garageBodyWith(operation: String, deserializer: DeserializationStrategy<T>): T` — `MultiResponse<List<BlockError>>` のようにジェネリック型で `reified` が効かない場合に使う

**トークンは引数で受ける。** クライアントのインスタンスにトークンを持たせないこと。サーバーはトークンを保持しない設計であり、インスタンスに持たせると利用者ごとの分離が壊れる。

- [ ] **Step 1: 失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/garage/GarageAdminClientTest.kt`

```kotlin
package net.brightroom.garage.server.garage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.shared.api.ErrorCode
import net.brightroom.garage.shared.model.garage.ClusterHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GarageAdminClientTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun sendsBearerTokenAndParsesResponse() = runTest {
        var capturedAuth: String? = null
        var capturedUrl: String? = null

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            capturedUrl = request.url.toString()
            respond(
                content = """
                    {"status":"healthy","knownNodes":1,"connectedNodes":1,"storageNodes":1,
                     "storageNodesUp":1,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val health: ClusterHealth = client.get("tok-abc", "GetClusterHealth")
            .garageBody("GetClusterHealth")

        assertEquals("Bearer tok-abc", capturedAuth)
        assertEquals("http://garage.test:3903/v2/GetClusterHealth", capturedUrl)
        assertEquals("healthy", health.status)
    }

    @Test
    fun appendsQueryParameters() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond("""{"success":{},"error":{}}""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        client.get("tok", "ListBlockErrors", mapOf("node" to "*"))

        assertEquals("http://garage.test:3903/v2/ListBlockErrors?node=*", capturedUrl)
    }

    @Test
    fun mapsForbiddenToErrorCode() = runTest {
        val engine = MockEngine {
            respond("insufficient scope", HttpStatusCode.Forbidden)
        }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val failure = assertFailsWith<GarageException> {
            client.get("tok", "GetKeyInfo").requireSuccess("GetKeyInfo")
        }

        assertEquals(ErrorCode.FORBIDDEN, failure.code)
        assertEquals("GetKeyInfo", failure.operation)
    }

    @Test
    fun mapsUnauthorizedToErrorCode() = runTest {
        val engine = MockEngine { respond("bad token", HttpStatusCode.Unauthorized) }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val failure = assertFailsWith<GarageException> {
            client.get("tok", "GetClusterHealth").requireSuccess("GetClusterHealth")
        }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.code)
    }

    @Test
    fun mapsServerErrorToGarageError() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        val failure = assertFailsWith<GarageException> {
            client.get("tok", "GetClusterStatus").requireSuccess("GetClusterStatus")
        }

        assertEquals(ErrorCode.GARAGE_ERROR, failure.code)
        assertEquals("boom", failure.message)
    }

    @Test
    fun postsJsonBody() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as io.ktor.http.content.TextContent).text
            respond("""[]""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = GarageAdminClient("http://garage.test:3903", engine)

        client.post(
            token = "tok",
            operation = "ListWorkers",
            body = kotlinx.serialization.json.buildJsonObject {
                put("node", kotlinx.serialization.json.JsonPrimitive("*"))
            },
        )

        assertEquals("""{"node":"*"}""", capturedBody)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

```bash
./gradlew :server:test --tests '*GarageAdminClientTest*'
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 3: 実装する**

`server/src/main/kotlin/net/brightroom/garage/server/garage/GarageException.kt`

```kotlin
package net.brightroom.garage.server.garage

import io.ktor.http.HttpStatusCode
import net.brightroom.garage.shared.api.ErrorCode

/**
 * Garage が非 2xx を返したことを表す。
 *
 * scope 制限による 403 は正常系であり、呼び出し側は [code] で扱いを分ける。
 */
class GarageException(
    val code: ErrorCode,
    val operation: String,
    val status: HttpStatusCode,
    override val message: String,
) : RuntimeException(message)

internal fun HttpStatusCode.toErrorCode(): ErrorCode = when (value) {
    401 -> ErrorCode.UNAUTHORIZED
    403 -> ErrorCode.FORBIDDEN
    404 -> ErrorCode.NOT_FOUND
    400, 409, 422 -> ErrorCode.BAD_REQUEST
    else -> ErrorCode.GARAGE_ERROR
}
```

`server/src/main/kotlin/net/brightroom/garage/server/garage/GarageAdminClient.kt`

```kotlin
package net.brightroom.garage.server.garage

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.JsonElement
import net.brightroom.garage.server.plugins.GarageJson

/**
 * Garage Admin API v2 へのアクセス。
 *
 * トークンはインスタンスではなく呼び出しごとに受け取る。サーバーは利用者の
 * admin token を保持しないため、インスタンスに持たせてはならない。
 */
class GarageAdminClient(
    private val endpoint: String,
    engine: HttpClientEngine = CIO.create(),
) {
    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(GarageJson)
        }
        expectSuccess = false
    }

    suspend fun get(
        token: String,
        operation: String,
        params: Map<String, String> = emptyMap(),
    ): HttpResponse = client.get("$endpoint/v2/$operation") {
        header(HttpHeaders.Authorization, "Bearer $token")
        params.forEach { (key, value) -> parameter(key, value) }
    }

    suspend fun post(
        token: String,
        operation: String,
        body: JsonElement? = null,
        params: Map<String, String> = emptyMap(),
    ): HttpResponse = client.post("$endpoint/v2/$operation") {
        header(HttpHeaders.Authorization, "Bearer $token")
        params.forEach { (key, value) -> parameter(key, value) }
        contentType(ContentType.Application.Json)
        if (body != null) {
            setBody(body)
        }
    }

    fun close() {
        client.close()
    }
}

/** 非 2xx なら [GarageException] を投げ、成功ならそのまま返す。 */
suspend fun HttpResponse.requireSuccess(operation: String): HttpResponse {
    if (status.isSuccess()) return this

    val detail = runCatching { bodyAsText() }.getOrDefault("")
    throw GarageException(
        code = status.toErrorCode(),
        operation = operation,
        status = status,
        message = detail.ifBlank { status.description },
    )
}

/** 成功を確認したうえで本文を [T] にデシリアライズする。 */
suspend inline fun <reified T> HttpResponse.garageBody(operation: String): T =
    requireSuccess(operation).body()

/**
 * serializer を明示して本文をデシリアライズする。
 *
 * `MultiResponse<List<BlockError>>` のようなジェネリック型は `reified` で解決できないため、
 * そうした場合はこちらを使う。
 */
suspend fun <T> HttpResponse.garageBodyWith(
    operation: String,
    deserializer: DeserializationStrategy<T>,
): T {
    val text = requireSuccess(operation).bodyAsText()
    return GarageJson.decodeFromString(deserializer, text)
}
```

import に `kotlinx.serialization.DeserializationStrategy` を加えること。

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :server:test --tests '*GarageAdminClientTest*'
```

期待: 6 テストすべて PASS。

- [ ] **Step 5: コミット**

```bash
git add server/
git commit -m "feat(server): Garage Admin API の型付きクライアントを追加"
```

---

## Task 8: エラーの正規化とログ衛生

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/AuthContext.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/plugins/StatusPages.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/plugins/CallLogging.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/Application.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/plugins/StatusPagesTest.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/plugins/CallLoggingTest.kt`

**Interfaces:**
- Consumes: `GarageException`（Task 7）, `ApiErrorResponse`（Task 1）
- Produces:
  - `class MissingTokenException : RuntimeException`
  - `fun ApplicationCall.adminToken(): String` — `Authorization: Bearer …` からトークンを取り出す。無い / 形式不正なら `MissingTokenException`
  - `fun Application.configureStatusPages()` — 全エラーを `{"error":{...}}` に正規化する
  - `fun Application.configureCallLogging()` — リクエストヘッダをログに出さない

**ログ衛生は spec §10 の重点検証項目である。** 利用者のトークンが毎回のポーリングで転送されるため、ログに残してはならない。

- [ ] **Step 1: 失敗するテストを書く（エラー正規化）**

`server/src/test/kotlin/net/brightroom/garage/server/plugins/StatusPagesTest.kt`

```kotlin
package net.brightroom.garage.server.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.api.MissingTokenException
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.shared.api.ApiErrorResponse
import net.brightroom.garage.shared.api.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusPagesTest {

    private fun errorOf(body: String): ApiErrorResponse =
        GarageJson.decodeFromString(body)

    @Test
    fun normalisesForbiddenFromGarage() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") {
                    throw GarageException(
                        code = ErrorCode.FORBIDDEN,
                        operation = "GetKeyInfo",
                        status = HttpStatusCode.Forbidden,
                        message = "insufficient scope",
                    )
                }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = errorOf(response.bodyAsText()).error
        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertEquals("GetKeyInfo", error.operation)
        assertEquals("insufficient scope", error.message)
    }

    @Test
    fun missingTokenBecomesUnauthorized() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw MissingTokenException() }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(ErrorCode.UNAUTHORIZED, errorOf(response.bodyAsText()).error.code)
    }

    @Test
    fun unexpectedExceptionBecomesInternalError() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw IllegalStateException("unexpected") }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val error = errorOf(response.bodyAsText()).error
        assertEquals(ErrorCode.INTERNAL, error.code)
        assertEquals(null, error.operation)
    }

    @Test
    fun unknownApiPathReturnsNormalisedNotFound() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/ok") { call.respondText("ok") }
            }
        }

        val response = client.get("/api/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorCode.NOT_FOUND, errorOf(response.bodyAsText()).error.code)
    }
}
```

- [ ] **Step 2: 失敗するテストを書く（ログ衛生）**

`server/src/test/kotlin/net/brightroom/garage/server/plugins/CallLoggingTest.kt`

```kotlin
package net.brightroom.garage.server.plugins

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLoggingTest {

    private val secretToken = "super-secret-admin-token-value"
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var rootLogger: Logger

    @BeforeTest
    fun attachAppender() {
        rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        rootLogger.detachAppender(appender)
    }

    @Test
    fun doesNotLogAuthorizationHeader() = testApplication {
        application {
            configureCallLogging()
            routing {
                get("/api/session") { call.respondText("ok") }
            }
        }

        client.get("/api/session") {
            header(HttpHeaders.Authorization, "Bearer $secretToken")
        }

        val logged = appender.list.joinToString("\n") { it.formattedMessage }

        assertFalse(logged.contains(secretToken), "ログにトークンが含まれてはならない: $logged")
        assertFalse(logged.contains("Authorization", ignoreCase = true))
        assertTrue(logged.contains("/api/session"), "パスは記録されるべき: $logged")
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

```bash
./gradlew :server:test --tests '*StatusPagesTest*' --tests '*CallLoggingTest*'
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 4: 実装する**

`server/src/main/kotlin/net/brightroom/garage/server/api/AuthContext.kt`

```kotlin
package net.brightroom.garage.server.api

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/** `Authorization` ヘッダが無い、または Bearer 形式でない。 */
class MissingTokenException : RuntimeException("Authorization ヘッダに Bearer トークンが必要です")

private const val BEARER_PREFIX = "Bearer "

/**
 * リクエストから利用者の admin token を取り出す。
 *
 * サーバーはこの値を保持せず、Garage への転送にのみ使う。
 */
fun ApplicationCall.adminToken(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: throw MissingTokenException()

    if (!header.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
        throw MissingTokenException()
    }

    val token = header.substring(BEARER_PREFIX.length).trim()
    if (token.isEmpty()) throw MissingTokenException()

    return token
}
```

`server/src/main/kotlin/net/brightroom/garage/server/plugins/StatusPages.kt`

```kotlin
package net.brightroom.garage.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import net.brightroom.garage.server.api.MissingTokenException
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.shared.api.ApiError
import net.brightroom.garage.shared.api.ApiErrorResponse
import net.brightroom.garage.shared.api.ErrorCode

/**
 * すべてのエラーを `{"error":{"code":…,"message":…,"operation":…}}` に正規化する。
 *
 * Garage のエラー形をそのままブラウザへ漏らさないことが目的。
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<GarageException> { call, cause ->
            call.respond(
                cause.status,
                ApiErrorResponse(ApiError(cause.code, cause.message, cause.operation)),
            )
        }

        exception<MissingTokenException> { call, cause ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, cause.message ?: "認証が必要です")),
            )
        }

        exception<Throwable> { call, cause ->
            // 例外そのものは記録するが、リクエストヘッダは触らない
            call.application.log.error("Unhandled exception at ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorResponse(ApiError(ErrorCode.INTERNAL, "サーバー内部でエラーが発生しました")),
            )
        }

        status(HttpStatusCode.NotFound) { call, status ->
            // 静的ファイルのフォールバックは Task 11 で /api 以外を index.html に流すため、
            // ここへ来るのは実質 /api 配下の未定義パスだけになる
            call.respond(
                status,
                ApiErrorResponse(ApiError(ErrorCode.NOT_FOUND, "エンドポイントが見つかりません")),
            )
        }
    }
}
```

`server/src/main/kotlin/net/brightroom/garage/server/plugins/CallLogging.kt`

```kotlin
package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

/**
 * アクセスログの形式を明示する。
 *
 * 利用者の admin token が毎リクエストで転送されるため、
 * ヘッダを一切ログに含めない形式を固定する。
 */
fun Application.configureCallLogging() {
    install(CallLogging) {
        format { call ->
            val status = call.response.status()?.value ?: "-"
            "$status ${call.request.httpMethod.value} ${call.request.path()}"
        }
    }
}
```

`server/src/main/kotlin/net/brightroom/garage/server/Application.kt` を更新する。

```kotlin
package net.brightroom.garage.server

import io.ktor.server.application.Application
import io.ktor.server.cio.EngineMain
import net.brightroom.garage.server.plugins.configureCallLogging
import net.brightroom.garage.server.plugins.configureSerialization
import net.brightroom.garage.server.plugins.configureStatusPages

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureCallLogging()
    configureSerialization()
    configureStatusPages()
}
```

- [ ] **Step 5: テストが通ることを確認する**

```bash
./gradlew :server:test --tests '*StatusPagesTest*' --tests '*CallLoggingTest*'
```

期待: 5 テストすべて PASS。

`CallLogging` のパッケージが `io.ktor.server.plugins.calllogging` で解決できない場合は `io.ktor.server.plugins.callloging`（Ktor 3.x でパッケージ名が修正された経緯がある）を試す。IDE の補完ではなく、実際にコンパイルが通る方を採用すること。

- [ ] **Step 6: コミット**

```bash
git add server/
git commit -m "feat(server): エラーの正規化とログ衛生を追加"
```

---

## Task 9: `/api/session` と `/api/session/logout`

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/SessionRoutes.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Di.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/Application.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/SessionRoutesTest.kt`

**Interfaces:**
- Consumes: `GarageAdminClient`（Task 7）, `adminToken()`（Task 8）, `SessionInfo` / `toSessionInfo()`（Task 4）
- Produces:
  - `fun Route.sessionRoutes(client: GarageAdminClient)`
  - `fun Application.configureDi()` — `AppConfig` と `GarageAdminClient` を登録
  - `fun Application.configureRouting()` — `/api` 配下をまとめる
  - テスト用ヘルパ `fun ApplicationTestBuilder.garageApp(engine: MockEngine, block: ...)`

**`/api/session/logout` は Phase 1 では何もしない（204 を返すだけ）。** S3 secret のキャッシュは Phase 2 で導入するため、Phase 1 の時点で破棄すべき状態が存在しない。空の `SecretCache` を先に作らないこと。Phase 2 でキャッシュを追加する際に、このルートから `purge` を呼ぶ配線を足す。この意図をコード中のコメントに残す。

- [ ] **Step 1: テスト用ヘルパを書く**

`server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`

```kotlin
package net.brightroom.garage.server

import io.ktor.client.engine.mock.MockEngine
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.plugins.configureSerialization
import net.brightroom.garage.server.plugins.configureStatusPages
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import net.brightroom.garage.server.api.sessionRoutes

/**
 * Garage をモックしたアプリケーションを組み立てる。
 *
 * 静的ファイル配信と CallLogging は各ルートのテストに不要なため含めない。
 */
fun ApplicationTestBuilder.garageApp(engine: MockEngine) {
    environment {
        config = MapApplicationConfig("garage.admin.endpoint" to "http://garage.test:3903")
    }
    application {
        val client = GarageAdminClient("http://garage.test:3903", engine)
        configureSerialization()
        configureStatusPages()
        routing {
            route("/api") {
                sessionRoutes(client)
            }
        }
    }
}
```

- [ ] **Step 2: 失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/api/SessionRoutesTest.kt`

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ApiErrorResponse
import net.brightroom.garage.shared.api.ErrorCode
import net.brightroom.garage.shared.api.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun returnsSessionInfoForValidToken() = testApplication {
        var forwardedAuth: String? = null
        garageApp(
            MockEngine { request ->
                forwardedAuth = request.headers[HttpHeaders.Authorization]
                respond(
                    """{"id":"tok1","name":"alice","scope":["ListBuckets"],"expired":false}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/session") {
            header(HttpHeaders.Authorization, "Bearer tok-abc")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val session: SessionInfo = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals("alice", session.name)
        assertEquals(listOf("ListBuckets"), session.scope)
        assertEquals("Bearer tok-abc", forwardedAuth)
    }

    @Test
    fun rejectsRequestWithoutToken() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        val response = client.get("/api/session")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error: ApiErrorResponse = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals(ErrorCode.UNAUTHORIZED, error.error.code)
    }

    @Test
    fun rejectsMalformedAuthorizationHeader() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        val response = client.get("/api/session") {
            header(HttpHeaders.Authorization, "tok-abc")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun propagatesUnauthorizedFromGarage() = testApplication {
        garageApp(MockEngine { respond("invalid token", HttpStatusCode.Unauthorized) })

        val response = client.get("/api/session") {
            header(HttpHeaders.Authorization, "Bearer wrong")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error: ApiErrorResponse = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals(ErrorCode.UNAUTHORIZED, error.error.code)
        assertEquals("GetCurrentAdminTokenInfo", error.error.operation)
    }

    @Test
    fun logoutSucceedsWithoutCallingGarage() = testApplication {
        var garageCalled = false
        garageApp(
            MockEngine {
                garageCalled = true
                respond("", HttpStatusCode.OK)
            },
        )

        val response = client.post("/api/session/logout") {
            header(HttpHeaders.Authorization, "Bearer tok-abc")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(false, garageCalled)
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

```bash
./gradlew :server:test --tests '*SessionRoutesTest*'
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 4: 実装する**

`server/src/main/kotlin/net/brightroom/garage/server/api/SessionRoutes.kt`

```kotlin
package net.brightroom.garage.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.garageBody
import net.brightroom.garage.shared.api.toSessionInfo
import net.brightroom.garage.shared.model.garage.AdminTokenInfo

private const val CURRENT_TOKEN_INFO = "GetCurrentAdminTokenInfo"

fun Route.sessionRoutes(client: GarageAdminClient) {
    route("/session") {

        get {
            val token = call.adminToken()
            val info: AdminTokenInfo = client.get(token, CURRENT_TOKEN_INFO)
                .garageBody(CURRENT_TOKEN_INFO)

            call.respond(info.toSessionInfo())
        }

        post("/logout") {
            // トークンの検証は行わない。ログアウトは失敗しないほうが利用者に親切であり、
            // サーバーはトークンを保持していないため破棄すべき状態も無い。
            //
            // Phase 2 で S3 secret のキャッシュを導入したら、ここでそのトークン
            // ハッシュ配下のエントリを purge する。
            call.adminToken()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

`server/src/main/kotlin/net/brightroom/garage/server/plugins/Di.kt`

```kotlin
package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.server.garage.GarageAdminClient

fun Application.configureDi() {
    val appConfig = AppConfig.from(environment)

    dependencies {
        provide<AppConfig> { appConfig }
        provide<GarageAdminClient> { GarageAdminClient(appConfig.garageAdminEndpoint) }
    }
}
```

`server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`

```kotlin
package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.brightroom.garage.server.api.sessionRoutes
import net.brightroom.garage.server.garage.GarageAdminClient

fun Application.configureRouting() {
    val client: GarageAdminClient by dependencies

    routing {
        route("/api") {
            sessionRoutes(client)
        }
    }
}
```

`Application.kt` の `module()` を更新する。

```kotlin
fun Application.module() {
    configureCallLogging()
    configureSerialization()
    configureDi()
    configureStatusPages()
    configureRouting()
}
```

`configureDi` と `configureRouting` の import を追加すること。

- [ ] **Step 5: テストが通ることを確認する**

```bash
./gradlew :server:test --tests '*SessionRoutesTest*'
```

期待: 5 テストすべて PASS。

- [ ] **Step 6: コミット**

```bash
git add server/
git commit -m "feat(server): /api/session と /api/session/logout を追加"
```

---

## Task 10: `/api/overview`（並列取得と部分縮退）

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/OverviewService.kt`
- Create: `server/src/main/kotlin/net/brightroom/garage/server/api/OverviewRoutes.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/plugins/Routing.kt`
- Modify: `server/src/test/kotlin/net/brightroom/garage/server/TestApplication.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/api/OverviewServiceTest.kt`

**Interfaces:**
- Consumes: `GarageAdminClient`（Task 7）, `Section`（Task 2）, `Overview` 一式（Task 5）, Garage モデル（Task 3）
- Produces:
  - `class OverviewService(client: GarageAdminClient)` と `suspend fun build(token: String): Overview`
  - `fun Route.overviewRoutes(service: OverviewService)`

**振る舞いの規則**
- 5 セクションを並列に取得する
- 403 は `Section.Denied(operation)` に落とし、**全体は 200 を返す**
- 403 以外の失敗は `Section.Failed(message)` に落とす
- **401 だけは全体を失敗させる**（トークンが無効なら概況に意味がないため、再スローして 401 を返し、web を `/login` に戻す）
- `storage` セクションは `ListBuckets` と `ListKeys` の 2 つを必要とする。どちらかが拒否されたら、その operation 名で `Denied` にする
- `ListBlockErrors` は `node` パラメータが必須なので `*`（全ノード）を渡し、`MultiResponse` の success の合計を件数とする

- [ ] **Step 1: 失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/api/OverviewServiceTest.kt`

```kotlin
package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.shared.api.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OverviewServiceTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val healthBody = """
        {"status":"healthy","knownNodes":2,"connectedNodes":2,"storageNodes":2,
         "storageNodesUp":2,"partitions":256,"partitionsQuorum":256,"partitionsAllOk":256}
    """.trimIndent()

    private val statusBody = """
        {"layoutVersion":7,"nodes":[
          {"id":"abc","isUp":true,"draining":false,"hostname":"node-a",
           "role":{"zone":"dc1","tags":[],"capacity":1000},
           "dataPartition":{"available":400,"total":1000}}
        ]}
    """.trimIndent()

    private val layoutBody = """
        {"version":7,"roles":[],"parameters":{"zoneRedundancy":"maximum"},"partitionSize":1024,
         "stagedRoleChanges":[{"id":"abc"},{"id":"def"}]}
    """.trimIndent()

    private val blockErrorsBody = """
        {"success":{"node-a":[
           {"blockHash":"ff","refcount":1,"errorCount":2,"lastTrySecsAgo":5,"nextTryInSecs":60}
         ],"node-b":[]},"error":{}}
    """.trimIndent()

    /** operation 名ごとに応答を差し替えられるモック。 */
    private fun engineOf(responses: Map<String, Pair<String, HttpStatusCode>>) = MockEngine { request ->
        val operation = request.url.encodedPath.substringAfterLast('/')
        val (body, status) = responses[operation]
            ?: error("unexpected operation: $operation")
        respond(body, status, jsonHeaders)
    }

    private fun serviceOf(responses: Map<String, Pair<String, HttpStatusCode>>) =
        OverviewService(GarageAdminClient("http://garage.test:3903", engineOf(responses)))

    private val allOk = mapOf(
        "GetClusterHealth" to (healthBody to HttpStatusCode.OK),
        "GetClusterStatus" to (statusBody to HttpStatusCode.OK),
        "GetClusterLayout" to (layoutBody to HttpStatusCode.OK),
        "ListBuckets" to ("""[{"id":"b1"},{"id":"b2"}]""" to HttpStatusCode.OK),
        "ListKeys" to ("""[{"id":"k1"}]""" to HttpStatusCode.OK),
        "ListBlockErrors" to (blockErrorsBody to HttpStatusCode.OK),
    )

    @Test
    fun loadsEverySectionWhenAllOperationsSucceed() = runTest {
        val overview = serviceOf(allOk).build("tok")

        assertEquals("healthy", overview.health.dataOrNull()?.status)
        assertEquals(1, overview.nodes.dataOrNull()?.size)
        assertEquals("node-a", overview.nodes.dataOrNull()?.first()?.hostname)
        assertEquals("dc1", overview.nodes.dataOrNull()?.first()?.zone)
        assertEquals(7L, overview.layout.dataOrNull()?.version)
        assertEquals(2, overview.layout.dataOrNull()?.stagedChanges)
        assertEquals(2, overview.storage.dataOrNull()?.buckets)
        assertEquals(1, overview.storage.dataOrNull()?.keys)
        assertEquals(1, overview.blockErrors.dataOrNull())
    }

    @Test
    fun deniesOnlyTheForbiddenSection() = runTest {
        val overview = serviceOf(
            allOk + ("ListBlockErrors" to ("forbidden" to HttpStatusCode.Forbidden)),
        ).build("tok")

        val denied = assertIs<Section.Denied>(overview.blockErrors)
        assertEquals("ListBlockErrors", denied.operation)
        assertEquals("healthy", overview.health.dataOrNull()?.status)
        assertEquals(2, overview.storage.dataOrNull()?.buckets)
    }

    @Test
    fun deniesStorageWhenEitherListIsForbidden() = runTest {
        val overview = serviceOf(
            allOk + ("ListKeys" to ("forbidden" to HttpStatusCode.Forbidden)),
        ).build("tok")

        val denied = assertIs<Section.Denied>(overview.storage)
        assertEquals("ListKeys", denied.operation)
    }

    @Test
    fun failsOnlyTheBrokenSection() = runTest {
        val overview = serviceOf(
            allOk + ("GetClusterLayout" to ("boom" to HttpStatusCode.InternalServerError)),
        ).build("tok")

        val failed = assertIs<Section.Failed>(overview.layout)
        assertEquals("boom", failed.message)
        assertEquals("healthy", overview.health.dataOrNull()?.status)
    }

    @Test
    fun unauthorizedFailsTheWholeRequest() = runTest {
        val failure = assertFailsWith<GarageException> {
            serviceOf(
                allOk + ("GetClusterHealth" to ("invalid token" to HttpStatusCode.Unauthorized)),
            ).build("tok")
        }

        assertEquals(net.brightroom.garage.shared.api.ErrorCode.UNAUTHORIZED, failure.code)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

```bash
./gradlew :server:test --tests '*OverviewServiceTest*'
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 3: 実装する**

`server/src/main/kotlin/net/brightroom/garage/server/api/OverviewService.kt`

```kotlin
package net.brightroom.garage.server.api

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.server.garage.garageBody
import net.brightroom.garage.shared.api.ErrorCode
import net.brightroom.garage.shared.api.LayoutSummary
import net.brightroom.garage.shared.api.Overview
import net.brightroom.garage.shared.api.Section
import net.brightroom.garage.shared.api.StorageSummary
import net.brightroom.garage.shared.api.toSummary
import net.brightroom.garage.shared.model.garage.BlockError
import net.brightroom.garage.shared.model.garage.ClusterHealth
import net.brightroom.garage.shared.model.garage.ClusterLayout
import net.brightroom.garage.shared.model.garage.ClusterStatus
import net.brightroom.garage.shared.model.garage.MultiResponse

/**
 * 概況を 1 リクエストで組み立てる。
 *
 * 各セクションを並列に取得し、scope 不足（403）はそのセクションだけを
 * [Section.Denied] に落として全体は成功させる。
 */
class OverviewService(private val client: GarageAdminClient) {

    suspend fun build(token: String): Overview = coroutineScope {
        val health = async {
            section {
                client.get(token, HEALTH).garageBody<ClusterHealth>(HEALTH)
            }
        }

        val nodes = async {
            section {
                client.get(token, STATUS).garageBody<ClusterStatus>(STATUS)
                    .nodes
                    .map { it.toSummary() }
            }
        }

        val layout = async {
            section {
                val response = client.get(token, LAYOUT).garageBody<ClusterLayout>(LAYOUT)
                LayoutSummary(
                    version = response.version,
                    stagedChanges = response.stagedRoleChanges.size,
                )
            }
        }

        val storage = async {
            section {
                val buckets = client.get(token, LIST_BUCKETS).garageBody<JsonArray>(LIST_BUCKETS)
                val keys = client.get(token, LIST_KEYS).garageBody<JsonArray>(LIST_KEYS)
                StorageSummary(buckets = buckets.size, keys = keys.size)
            }
        }

        val blockErrors = async {
            section {
                // MultiResponse はジェネリック型のため serializer を明示する
                val response = client.get(token, LIST_BLOCK_ERRORS, mapOf("node" to "*"))
                    .garageBodyWith(
                        LIST_BLOCK_ERRORS,
                        MultiResponse.serializer(ListSerializer(BlockError.serializer())),
                    )
                response.success.values.sumOf { it.size }
            }
        }

        Overview(
            health = health.await(),
            nodes = nodes.await(),
            layout = layout.await(),
            storage = storage.await(),
            blockErrors = blockErrors.await(),
        )
    }

    /**
     * 403 は正常系として [Section.Denied] に、その他の失敗は [Section.Failed] に落とす。
     * 401 だけは概況全体を無意味にするため再スローする。
     */
    private suspend fun <T> section(block: suspend () -> T): Section<T> =
        try {
            Section.Loaded(block())
        } catch (e: GarageException) {
            when (e.code) {
                ErrorCode.UNAUTHORIZED -> throw e
                ErrorCode.FORBIDDEN -> Section.Denied(e.operation)
                else -> Section.Failed(e.message)
            }
        }

    private companion object {
        const val HEALTH = "GetClusterHealth"
        const val STATUS = "GetClusterStatus"
        const val LAYOUT = "GetClusterLayout"
        const val LIST_BUCKETS = "ListBuckets"
        const val LIST_KEYS = "ListKeys"
        const val LIST_BLOCK_ERRORS = "ListBlockErrors"
    }
}
```

`garageBodyWith` は Task 7 で `GarageAdminClient.kt` に定義済みである。import に加えること。

`server/src/main/kotlin/net/brightroom/garage/server/api/OverviewRoutes.kt`

```kotlin
package net.brightroom.garage.server.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.overviewRoutes(service: OverviewService) {
    get("/overview") {
        call.respond(service.build(call.adminToken()))
    }
}
```

`Di.kt` に `OverviewService` を登録する。

```kotlin
        provide<OverviewService> { OverviewService(resolve<GarageAdminClient>()) }
```

`Routing.kt` に追加する。

```kotlin
fun Application.configureRouting() {
    val client: GarageAdminClient by dependencies
    val overviewService: OverviewService by dependencies

    routing {
        route("/api") {
            sessionRoutes(client)
            overviewRoutes(overviewService)
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :server:test --tests '*OverviewServiceTest*'
```

期待: 5 テストすべて PASS。

- [ ] **Step 5: server 全体のテストを通す**

```bash
./gradlew :server:test
```

期待: すべて PASS。

- [ ] **Step 6: コミット**

```bash
git add server/
git commit -m "feat(server): /api/overview の並列取得と部分縮退を追加"
```

---

## Task 11: 静的ファイル配信と SPA フォールバック

**Files:**
- Create: `server/src/main/kotlin/net/brightroom/garage/server/plugins/StaticFiles.kt`
- Modify: `server/src/main/kotlin/net/brightroom/garage/server/Application.kt`
- Test: `server/src/test/kotlin/net/brightroom/garage/server/plugins/StaticFilesTest.kt`
- Test: `server/src/test/resources/web/index.html`

**Interfaces:**
- Consumes: なし
- Produces: `fun Application.configureStaticFiles()`

**History API ルーティングの前提となる処理である。** `/buckets/xyz` への直接アクセスとリロードで `index.html` が返らないと、Task 12 の Router が成立しない。同時に、`/api` 配下の未定義パスまで `index.html` を返してしまうと API のエラーが HTML になるため、`/api` は必ず JSON の 404 にする。

- [ ] **Step 1: テスト用の index.html を置く**

`server/src/test/resources/web/index.html`

```html
<!DOCTYPE html>
<html lang="ja"><head><title>test</title></head><body><canvas id="ComposeTarget"></canvas></body></html>
```

- [ ] **Step 2: 失敗するテストを書く**

`server/src/test/kotlin/net/brightroom/garage/server/plugins/StaticFilesTest.kt`

```kotlin
package net.brightroom.garage.server.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import net.brightroom.garage.shared.api.ApiErrorResponse
import net.brightroom.garage.shared.api.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticFilesTest {

    private fun io.ktor.server.testing.ApplicationTestBuilder.staticApp() {
        application {
            configureSerialization()
            configureStatusPages()
            configureStaticFiles()
        }
    }

    @Test
    fun servesIndexAtRoot() = testApplication {
        staticApp()

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ComposeTarget"))
    }

    @Test
    fun fallsBackToIndexForClientRoutes() = testApplication {
        staticApp()

        val response = client.get("/buckets/abc-123")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.bodyAsText().contains("ComposeTarget"),
            "クライアント側ルートへの直接アクセスは index.html を返すべき",
        )
    }

    @Test
    fun doesNotFallBackForApiPaths() = testApplication {
        staticApp()

        val response = client.get("/api/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error: ApiErrorResponse = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals(ErrorCode.NOT_FOUND, error.error.code)
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

```bash
./gradlew :server:test --tests '*StaticFilesTest*'
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 4: 実装する**

`server/src/main/kotlin/net/brightroom/garage/server/plugins/StaticFiles.kt`

```kotlin
package net.brightroom.garage.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.brightroom.garage.shared.api.ApiError
import net.brightroom.garage.shared.api.ApiErrorResponse
import net.brightroom.garage.shared.api.ErrorCode

/**
 * wasm の成果物を配信する。
 *
 * クライアント側は History API でルーティングするため、未知のパスは index.html に
 * フォールバックさせる必要がある。ただし `/api` 配下は API のエラーが HTML に
 * なってしまうため、明示的に JSON の 404 を返す。
 */
fun Application.configureStaticFiles() {
    routing {
        route("/api/{...}") {
            get {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiErrorResponse(
                        ApiError(ErrorCode.NOT_FOUND, "エンドポイントが見つかりません: ${call.request.path()}"),
                    ),
                )
            }
        }

        singlePageApplication {
            useResources = true
            filesPath = "web"
            defaultPage = "index.html"
        }
    }
}
```

`Application.kt` の `module()` の末尾に `configureStaticFiles()` を追加する。**`configureRouting()` より後に呼ぶこと。** ルートの評価順序により、`/api` の実ルートが先に一致する必要がある。

```kotlin
fun Application.module() {
    configureCallLogging()
    configureSerialization()
    configureDi()
    configureStatusPages()
    configureRouting()
    configureStaticFiles()
}
```

- [ ] **Step 5: テストが通ることを確認する**

```bash
./gradlew :server:test --tests '*StaticFilesTest*'
```

期待: 3 テストすべて PASS。

`/api` の catch-all が定義済みのルートを覆ってしまう場合（`/api/session` が 404 になる）は、`configureStaticFiles` の `/api/{...}` ブロックを消し、代わりに `configureRouting` の `route("/api")` ブロックの末尾に同じ内容を置く。ルートの一致は登録順ではなく特異性で決まるため、実際の挙動をテストで確認してから採用すること。

- [ ] **Step 6: server 全体のテストとビルドを確認する**

```bash
./gradlew :server:test
```

期待: すべて PASS。

- [ ] **Step 7: コミット**

```bash
git add server/
git commit -m "feat(server): SPA フォールバック付きの静的ファイル配信を追加"
```

---

## Task 12: URL ルートの解析

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/Route.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/RouteTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `sealed interface Route { val path: String }` と `Route.Overview` / `Route.Login` / `Route.NotFound(requested: String)`
  - `fun Route.Companion.parse(rawPath: String): Route`

**縦切りの範囲:** Phase 1 に存在する画面は概況とログインだけなので、ルートもこの 2 つと `NotFound` に限る。`/buckets` などは Phase 2 で分岐を足す（現時点では `NotFound` になるのが正しい）。

- [ ] **Step 1: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/navigation/RouteTest.kt`

```kotlin
package net.brightroom.garage.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {

    @Test
    fun parsesRootAsOverview() {
        assertEquals(Route.Overview, Route.parse("/"))
        assertEquals(Route.Overview, Route.parse(""))
    }

    @Test
    fun parsesLogin() {
        assertEquals(Route.Login, Route.parse("/login"))
    }

    @Test
    fun ignoresTrailingSlash() {
        assertEquals(Route.Login, Route.parse("/login/"))
    }

    @Test
    fun ignoresQueryAndFragment() {
        assertEquals(Route.Login, Route.parse("/login?next=%2F"))
        assertEquals(Route.Login, Route.parse("/login#section"))
        assertEquals(Route.Overview, Route.parse("/?refresh=1"))
    }

    @Test
    fun unknownPathBecomesNotFound() {
        assertEquals(Route.NotFound("/buckets"), Route.parse("/buckets"))
        assertEquals(Route.NotFound("/nope/deep"), Route.parse("/nope/deep"))
    }

    @Test
    fun exposesCanonicalPath() {
        assertEquals("/", Route.Overview.path)
        assertEquals("/login", Route.Login.path)
        assertEquals("/whatever", Route.NotFound("/whatever").path)
    }

    @Test
    fun parsingCanonicalPathIsStable() {
        listOf(Route.Overview, Route.Login).forEach { route ->
            assertEquals(route, Route.parse(route.path))
        }
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

```bash
./gradlew :shared:jvmTest --tests '*RouteTest*'
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 3: 実装する**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/navigation/Route.kt`

```kotlin
package net.brightroom.garage.shared.navigation

/**
 * URL とスクリーンの対応。
 *
 * ブラウザの History API と組み合わせて使うが、この型自体は純粋な変換であり
 * ブラウザ API に依存しない。
 */
sealed interface Route {

    /** この画面を指す正規の URL パス。 */
    val path: String

    data object Overview : Route {
        override val path: String = "/"
    }

    data object Login : Route {
        override val path: String = "/login"
    }

    data class NotFound(val requested: String) : Route {
        override val path: String get() = requested
    }

    companion object {

        fun parse(rawPath: String): Route {
            val normalized = normalize(rawPath)

            return when (normalized) {
                "" -> Overview
                "/login" -> Login
                else -> NotFound(normalized)
            }
        }

        /** クエリと fragment を落とし、末尾スラッシュを取り除く。 */
        private fun normalize(rawPath: String): String {
            val withoutFragment = rawPath.substringBefore('#')
            val withoutQuery = withoutFragment.substringBefore('?')
            val trimmed = withoutQuery.trimEnd('/')

            return if (trimmed == "/") "" else trimmed
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :shared:jvmTest --tests '*RouteTest*'
```

期待: 7 テストすべて PASS。

- [ ] **Step 5: コミット**

```bash
git add shared/
git commit -m "feat(shared): URL ルートの解析を追加"
```

---

## Task 13: アイドル判定

**Files:**
- Create: `shared/src/commonMain/kotlin/net/brightroom/garage/shared/session/IdleTracker.kt`
- Test: `shared/src/commonTest/kotlin/net/brightroom/garage/shared/session/IdleTrackerTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `enum class IdleState { ACTIVE, WARNING, EXPIRED }`
  - `class IdleTracker(startedAtMillis: Long, timeoutMillis: Long = 30 * 60 * 1000, warningMillis: Long = 60 * 1000)`
    - `fun recordActivity(nowMillis: Long)`
    - `fun state(nowMillis: Long): IdleState`
    - `fun remainingMillis(nowMillis: Long): Long`

**判定の基準は利用者の操作である。** ポーリングによる通信は `recordActivity` を呼ばないこと（spec §6.6）。時刻を引数で受け取る形にしてあるのは、テストで時計を進められるようにするためである。

- [ ] **Step 1: 失敗するテストを書く**

`shared/src/commonTest/kotlin/net/brightroom/garage/shared/session/IdleTrackerTest.kt`

```kotlin
package net.brightroom.garage.shared.session

import kotlin.test.Test
import kotlin.test.assertEquals

class IdleTrackerTest {

    private val minute = 60_000L
    private val timeout = 30 * minute
    private val warning = 1 * minute

    private fun tracker(startedAt: Long = 0) =
        IdleTracker(startedAtMillis = startedAt, timeoutMillis = timeout, warningMillis = warning)

    @Test
    fun isActiveImmediatelyAfterStart() {
        assertEquals(IdleState.ACTIVE, tracker().state(0))
    }

    @Test
    fun staysActiveBeforeWarningThreshold() {
        assertEquals(IdleState.ACTIVE, tracker().state(28 * minute))
    }

    @Test
    fun warnsOneMinuteBeforeTimeout() {
        assertEquals(IdleState.WARNING, tracker().state(29 * minute))
        assertEquals(IdleState.WARNING, tracker().state(29 * minute + 30_000))
    }

    @Test
    fun expiresAtTimeout() {
        assertEquals(IdleState.EXPIRED, tracker().state(30 * minute))
        assertEquals(IdleState.EXPIRED, tracker().state(31 * minute))
    }

    @Test
    fun activityResetsTheClock() {
        val tracker = tracker()

        tracker.recordActivity(29 * minute)

        assertEquals(IdleState.ACTIVE, tracker.state(29 * minute))
        assertEquals(IdleState.ACTIVE, tracker.state(50 * minute))
        assertEquals(IdleState.WARNING, tracker.state(58 * minute))
        assertEquals(IdleState.EXPIRED, tracker.state(59 * minute))
    }

    @Test
    fun reportsRemainingTime() {
        val tracker = tracker()

        assertEquals(30 * minute, tracker.remainingMillis(0))
        assertEquals(10 * minute, tracker.remainingMillis(20 * minute))
        assertEquals(0, tracker.remainingMillis(45 * minute))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

```bash
./gradlew :shared:jvmTest --tests '*IdleTrackerTest*'
```

期待: コンパイルエラーで FAIL。

- [ ] **Step 3: 実装する**

`shared/src/commonMain/kotlin/net/brightroom/garage/shared/session/IdleTracker.kt`

```kotlin
package net.brightroom.garage.shared.session

enum class IdleState {
    ACTIVE,

    /** まもなく自動ログアウトする。利用者に警告を出す。 */
    WARNING,

    /** 自動ログアウトすべき状態。 */
    EXPIRED,
}

/**
 * 最終操作からの経過時間で自動ログアウトを判定する。
 *
 * 判定の基準は利用者の操作（クリックやキー入力）であり、
 * ポーリングによる通信で [recordActivity] を呼んではならない。
 * 自動更新が走り続けるため、通信の有無では放置を検出できない。
 *
 * 現在時刻を引数で受け取るのは、テストで時計を進められるようにするため。
 */
class IdleTracker(
    startedAtMillis: Long,
    private val timeoutMillis: Long = 30 * 60 * 1000,
    private val warningMillis: Long = 60 * 1000,
) {
    private var lastActivityAtMillis: Long = startedAtMillis

    fun recordActivity(nowMillis: Long) {
        lastActivityAtMillis = nowMillis
    }

    fun state(nowMillis: Long): IdleState {
        val elapsed = nowMillis - lastActivityAtMillis

        return when {
            elapsed >= timeoutMillis -> IdleState.EXPIRED
            elapsed >= timeoutMillis - warningMillis -> IdleState.WARNING
            else -> IdleState.ACTIVE
        }
    }

    /** 自動ログアウトまでの残り時間。期限を過ぎていれば 0。 */
    fun remainingMillis(nowMillis: Long): Long =
        (lastActivityAtMillis + timeoutMillis - nowMillis).coerceAtLeast(0)
}
```

- [ ] **Step 4: テストが通ることを確認する**

```bash
./gradlew :shared:jvmTest --tests '*IdleTrackerTest*'
```

期待: 6 テストすべて PASS。

- [ ] **Step 5: shared 全体を確認する**

```bash
./gradlew :shared:build
```

期待: BUILD SUCCESSFUL。

- [ ] **Step 6: コミット**

```bash
git add shared/
git commit -m "feat(shared): アイドル判定を追加"
```

---

### `:web` のタスクについて

ここから先の 4 タスクは Compose の UI を扱う。spec §10 のとおり **Compose の UI テストは書かず、描画は e2e（Task 18）で担保する**。各タスクの検証は「ビルドが通ること」と「実際にブラウザで動くこと」で行う。UI に依存しないロジックは Task 12・13 で `:shared` に切り出し済みである。

ローカルでの確認手順は共通で次のとおり。

```bash
docker compose up -d                       # Garage v2.3.0
./gradlew :server:run                      # http://localhost:8080
```

ログイン用のトークンは `docker compose logs garage-init` に表示されている（Task 0）。

---

## Task 14: `:web` の骨格・API クライアント・Router

**Files:**
- Create: `web/build.gradle.kts`（既存を上書き）
- Create: `web/src/wasmJsMain/resources/index.html`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/Main.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/theme/Color.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/theme/Theme.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/api/ApiClient.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/router/Router.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: `Route`（Task 12）, `ApiError` / `ErrorCode`（Task 1）
- Produces:
  - `val AppJson: Json`
  - `sealed interface ApiResult<out T>` — `Success<T>(value: T)` / `Failure(error: ApiError)` / `Unauthorized`
  - `class ApiClient(tokenProvider: () -> String?)` と `suspend fun getText(path: String): ApiResult<String>` / `suspend fun postEmpty(path: String): ApiResult<Unit>`
  - `suspend fun <T> ApiClient.getJson(path: String, deserializer: DeserializationStrategy<T>): ApiResult<T>`
  - `@Composable fun rememberRouter(): RouterState` と `class RouterState { val current: Route; fun navigate(route: Route); fun replace(route: Route) }`
  - `@Composable fun GarageAdminTheme(content: @Composable () -> Unit)`

**`index.html` のスクリプト参照は絶対パスにすること。** SPA フォールバックにより `/buckets/abc` でも同じ HTML が返るため、相対パスだと `/buckets/garage-admin-console.js` を取りに行って壊れる。

- [ ] **Step 1: `web/build.gradle.kts` を書く**

```kotlin
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "garage-admin-console.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
```

- [ ] **Step 2: `index.html` を書く**

`web/src/wasmJsMain/resources/index.html`

```html
<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Garage Admin Console</title>
    <style>
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            overflow: hidden;
            background-color: #101418;
        }
        #ComposeTarget {
            width: 100%;
            height: 100%;
        }
    </style>
</head>
<body>
<canvas id="ComposeTarget"></canvas>
<!-- 絶対パスで参照すること。SPA フォールバックにより /buckets/abc でもこの HTML が返るため。 -->
<script src="/garage-admin-console.js"></script>
</body>
</html>
```

- [ ] **Step 3: テーマを書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/theme/Color.kt`

```kotlin
package net.brightroom.garage.web.theme

import androidx.compose.ui.graphics.Color

internal val Background = Color(0xFF101418)
internal val Surface = Color(0xFF171C22)
internal val SurfaceVariant = Color(0xFF222A32)
internal val Outline = Color(0xFF3A454F)
internal val OnBackground = Color(0xFFE3E8ED)
internal val OnSurfaceVariant = Color(0xFF9AA7B4)
internal val Primary = Color(0xFF6DB6FF)
internal val OnPrimary = Color(0xFF00243F)
internal val Warning = Color(0xFFE0A040)
internal val Error = Color(0xFFE06C75)
internal val OnError = Color(0xFF3A0A0E)
internal val Success = Color(0xFF6FCF97)
```

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/theme/Theme.kt`

```kotlin
package net.brightroom.garage.web.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ConsoleColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = Error,
    onError = OnError,
)

/** ダーク固定。ライトテーマは要件に含まれない（spec §8.8）。 */
@Composable
fun GarageAdminTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ConsoleColors,
        content = content,
    )
}
```

- [ ] **Step 4: API クライアントを書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/api/ApiClient.kt`

```kotlin
package net.brightroom.garage.web.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import net.brightroom.garage.shared.api.ApiError
import net.brightroom.garage.shared.api.ApiErrorResponse
import net.brightroom.garage.shared.api.ErrorCode

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * `/api` の呼び出し結果。
 *
 * 401 は「トークンが無効になった」を意味し、画面をログインへ戻す必要があるため
 * 通常の失敗と区別する。
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
    data object Unauthorized : ApiResult<Nothing>
}

/**
 * @param tokenProvider 現在のセッションのトークンを返す。未ログインなら null。
 */
class ApiClient(private val tokenProvider: () -> String?) {

    private val http = HttpClient(Js) {
        expectSuccess = false
    }

    suspend fun getText(path: String): ApiResult<String> =
        runCatching {
            http.get(path) { authorize() }
        }.fold(
            onSuccess = { it.toResult { body -> body } },
            onFailure = { ApiResult.Failure(networkError(it)) },
        )

    suspend fun postEmpty(path: String): ApiResult<Unit> =
        runCatching {
            http.post(path) { authorize() }
        }.fold(
            onSuccess = { it.toResult { } },
            onFailure = { ApiResult.Failure(networkError(it)) },
        )

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun <T> HttpResponse.toResult(transform: (String) -> T): ApiResult<T> {
        val body = bodyAsText()

        return when {
            status.value == 401 -> ApiResult.Unauthorized
            status.isSuccess() -> ApiResult.Success(transform(body))
            else -> ApiResult.Failure(parseError(body))
        }
    }

    private fun parseError(body: String): ApiError =
        runCatching { AppJson.decodeFromString<ApiErrorResponse>(body).error }
            .getOrElse { ApiError(ErrorCode.INTERNAL, "サーバーからの応答を解釈できませんでした") }

    private fun networkError(cause: Throwable): ApiError =
        ApiError(ErrorCode.INTERNAL, "サーバーに接続できませんでした: ${cause.message ?: "原因不明"}")
}

/** 本文を [deserializer] でデコードして返す。 */
suspend fun <T> ApiClient.getJson(
    path: String,
    deserializer: DeserializationStrategy<T>,
): ApiResult<T> = when (val raw = getText(path)) {
    is ApiResult.Success ->
        runCatching { ApiResult.Success(AppJson.decodeFromString(deserializer, raw.value)) }
            .getOrElse {
                ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "サーバーからの応答を解釈できませんでした"))
            }

    is ApiResult.Failure -> raw
    ApiResult.Unauthorized -> ApiResult.Unauthorized
}
```

- [ ] **Step 5: Router を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/router/Router.kt`

```kotlin
package net.brightroom.garage.web.router

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import net.brightroom.garage.shared.navigation.Route

/**
 * History API と [Route] を橋渡しする。
 *
 * ライブラリは使わない（spec §8.1）。パスの解析は `:shared` の [Route.parse] が担い、
 * ここはブラウザ API との接続だけを持つ。
 */
class RouterState internal constructor(initial: Route) {

    var current: Route by mutableStateOf(initial)
        internal set

    /** 履歴に積んで遷移する。 */
    fun navigate(route: Route) {
        if (route == current) return
        window.history.pushState(null, "", route.path)
        current = route
    }

    /** 履歴を積まずに差し替える（ログインへの強制送還など）。 */
    fun replace(route: Route) {
        window.history.replaceState(null, "", route.path)
        current = route
    }
}

@Composable
fun rememberRouter(): RouterState {
    val state = remember {
        RouterState(Route.parse(window.location.pathname + window.location.search))
    }

    DisposableEffect(Unit) {
        val listener: (org.w3c.dom.events.Event) -> Unit = {
            state.current = Route.parse(window.location.pathname + window.location.search)
        }
        window.addEventListener("popstate", listener)

        onDispose { window.removeEventListener("popstate", listener) }
    }

    return state
}
```

- [ ] **Step 6: エントリポイントと仮の App を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/Main.kt`

```kotlin
package net.brightroom.garage.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val body = document.body ?: return
    ComposeViewport(body) {
        App()
    }
}
```

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`（Task 15 で書き換える）

```kotlin
package net.brightroom.garage.web

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import net.brightroom.garage.web.router.rememberRouter
import net.brightroom.garage.web.theme.GarageAdminTheme

@Composable
fun App() {
    val router = rememberRouter()

    GarageAdminTheme {
        Text("route: ${router.current}")
    }
}
```

- [ ] **Step 7: ビルドが通ることを確認する**

```bash
./gradlew :web:wasmJsBrowserDistribution
```

期待: BUILD SUCCESSFUL。

**yarn.lock の不一致でタスクが失敗した場合**は、`kotlin-js-store/wasm/yarn.lock` が旧ビルドのものであるため再生成する。

```bash
./gradlew kotlinUpgradeYarnLock
```

再生成された `kotlin-js-store/` を Step 9 のコミットに含めること。このリポジトリには過去に lockfile の同期で問題が起きた経緯がある（`fix/e2e-lockfile-sync`）。

- [ ] **Step 8: ブラウザで確認する**

```bash
./gradlew :server:run
```

`http://localhost:8080/` を開き `route: Overview` が表示されること。`http://localhost:8080/login` で `route: Login` に変わること。`http://localhost:8080/nope` で `route: NotFound(requested=/nope)` が表示されること（**SPA フォールバックが効いている証拠になる**）。

- [ ] **Step 9: コミット**

```bash
git add web/
git commit -m "feat(web): 骨格・API クライアント・Router を追加"
```

---

## Task 15: セッション管理とログイン画面

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/session/SessionState.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/login/LoginScreen.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: `ApiClient` / `ApiResult` / `getJson`（Task 14）, `RouterState`（Task 14）, `SessionInfo`（Task 4）, `IdleTracker` / `IdleState`（Task 13）
- Produces:
  - `class SessionState` — `token: String?`, `info: SessionInfo?`, `api: ApiClient`, `suspend fun signIn(candidate: String): ApiError?`, `suspend fun restore(): Boolean`, `suspend fun signOut()`, `fun recordActivity()`, `fun idleState(): IdleState`, `fun idleRemainingSeconds(): Long`
  - `val LocalSession: ProvidableCompositionLocal<SessionState>`
  - `@Composable fun LoginScreen(onSignedIn: () -> Unit)`

**トークンは sessionStorage に置く**（spec §6.2）。localStorage は使わないこと。

- [ ] **Step 1: `SessionState` を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/session/SessionState.kt`

```kotlin
package net.brightroom.garage.web.session

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.datetime.Clock
import net.brightroom.garage.shared.api.ApiError
import net.brightroom.garage.shared.api.ErrorCode
import net.brightroom.garage.shared.api.SessionInfo
import net.brightroom.garage.shared.session.IdleState
import net.brightroom.garage.shared.session.IdleTracker
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.getJson

private const val TOKEN_STORAGE_KEY = "garage-admin-console.token"

/**
 * ログイン中の admin token とその情報を保持する。
 *
 * トークンは sessionStorage に置く（タブを閉じれば消え、リロードでは残る）。
 * localStorage を使わないのは共有端末での残留を避けるため。
 */
class SessionState {

    var token: String? by mutableStateOf(null)
        private set

    var info: SessionInfo? by mutableStateOf(null)
        private set

    val api: ApiClient = ApiClient { token }

    private val idle = IdleTracker(startedAtMillis = nowMillis())

    val isSignedIn: Boolean get() = info != null

    /** sessionStorage に残っているトークンで復帰を試みる。成功したら true。 */
    suspend fun restore(): Boolean {
        val stored = readStoredToken() ?: return false
        return signIn(stored) == null
    }

    /** 成功したら null、失敗したらその理由を返す。 */
    suspend fun signIn(candidate: String): ApiError? {
        token = candidate

        return when (val result = api.getJson("/api/session", SessionInfo.serializer())) {
            is ApiResult.Success -> {
                info = result.value
                storeToken(candidate)
                idle.recordActivity(nowMillis())
                null
            }

            is ApiResult.Failure -> {
                clear()
                result.error
            }

            ApiResult.Unauthorized -> {
                clear()
                ApiError(
                    ErrorCode.UNAUTHORIZED,
                    "トークンが受け付けられませんでした。" +
                        "`garage admin-token create` で発行したトークンを使用してください。",
                )
            }
        }
    }

    suspend fun signOut() {
        // サーバー側の後始末（Phase 2 以降は S3 secret キャッシュの破棄）を依頼する。
        // 失敗してもローカルの破棄は必ず行う。
        api.postEmpty("/api/session/logout")
        clear()
    }

    /** 401 を受け取った画面から呼ばれる。サーバーへの通知は行わない。 */
    fun invalidate() {
        clear()
    }

    fun recordActivity() {
        idle.recordActivity(nowMillis())
    }

    fun idleState(): IdleState = idle.state(nowMillis())

    fun idleRemainingSeconds(): Long = idle.remainingMillis(nowMillis()) / 1000

    private fun clear() {
        token = null
        info = null
        window.sessionStorage.removeItem(TOKEN_STORAGE_KEY)
    }

    private fun storeToken(value: String) {
        window.sessionStorage.setItem(TOKEN_STORAGE_KEY, value)
    }

    private fun readStoredToken(): String? =
        window.sessionStorage.getItem(TOKEN_STORAGE_KEY)?.takeIf { it.isNotBlank() }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

val LocalSession = compositionLocalOf<SessionState> {
    error("SessionState が提供されていません")
}
```

- [ ] **Step 2: ログイン画面を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/login/LoginScreen.kt`

```kotlin
package net.brightroom.garage.web.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.brightroom.garage.web.session.LocalSession

/**
 * admin token を入力してログインする。
 *
 * コンソールはトークンを保持しないため、ここで入力された値がそのまま
 * 利用者の権限になる（spec §6.2）。
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    fun submit() {
        if (input.isBlank() || submitting) return

        submitting = true
        error = null
        scope.launch {
            val failure = session.signIn(input.trim())
            submitting = false

            if (failure == null) {
                onSignedIn()
            } else {
                error = failure.message
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.width(460.dp)) {
            Column(
                modifier = Modifier.padding(32.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Garage Admin Console", style = MaterialTheme.typography.headlineSmall)

                Text(
                    "Admin API トークンを入力してください。" +
                        "できることは、そのトークンに設定された scope の範囲に従います。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("Admin API token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    isError = error != null,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().testTag("login-token-input"),
                )

                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("login-error"),
                    )
                }

                Button(
                    onClick = { submit() },
                    enabled = input.isNotBlank() && !submitting,
                    modifier = Modifier.fillMaxWidth().testTag("login-submit"),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    } else {
                        Text("ログイン")
                    }
                }
            }
        }
    }
}
```

**上記コードから `.testTag(...)` をすべて削除して実装すること。** Compose for Web（wasmJs）はキャンバスに描画する一方でアクセシビリティツリーを DOM に出すため、e2e は **role と表示テキスト**で要素を掴む（`page.getByRole("button", { name: "ログイン" })` など。旧 e2e もこの方式で動作していた）。`testTag` は DOM に露出しないので使わない。

このため **UI に表示する文言が e2e の掴み所になる**。ボタンやラベルの文字列を変えるときは e2e も併せて直すこと。（この注意は Task 18 の e2e 方式と整合させるために残している。）

- [ ] **Step 3: `App.kt` を書き換える**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

```kotlin
package net.brightroom.garage.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.brightroom.garage.shared.navigation.Route
import net.brightroom.garage.web.router.rememberRouter
import net.brightroom.garage.web.screens.login.LoginScreen
import net.brightroom.garage.web.session.LocalSession
import net.brightroom.garage.web.session.SessionState
import net.brightroom.garage.web.theme.GarageAdminTheme

@Composable
fun App() {
    val router = rememberRouter()
    val session = remember { SessionState() }

    // sessionStorage に残ったトークンでの復帰を試みる間は判断を保留する
    var restoring by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        session.restore()
        restoring = false
    }

    GarageAdminTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalSession provides session) {
                when {
                    restoring -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    !session.isSignedIn -> LoginScreen(
                        onSignedIn = {
                            val destination =
                                if (router.current == Route.Login) Route.Overview else router.current
                            router.replace(destination)
                        },
                    )

                    else -> AuthenticatedApp(router)
                }
            }
        }
    }
}
```

`AuthenticatedApp` は Task 16 で作る。このタスクでは仮に次を同じファイルへ置く。

```kotlin
@Composable
private fun AuthenticatedApp(router: net.brightroom.garage.web.router.RouterState) {
    androidx.compose.material3.Text("signed in: ${router.current}")
}
```

- [ ] **Step 4: ビルドが通ることを確認する**

```bash
./gradlew :web:wasmJsBrowserDistribution
```

期待: BUILD SUCCESSFUL。

- [ ] **Step 5: ブラウザで動作を確認する**

```bash
docker compose up -d
./gradlew :server:run
```

`http://localhost:8080/` で次を確認する。

1. ログイン画面が出る
2. でたらめな文字列を入れると「トークンが受け付けられませんでした」が出る
3. `docker compose logs garage-init` のトークンを入れると `signed in: Overview` に変わる
4. **リロードしてもログイン状態が保たれる**（sessionStorage からの復帰）
5. ブラウザの開発者ツールでタブを閉じて開き直すとログイン画面に戻る

- [ ] **Step 6: コミット**

```bash
git add web/
git commit -m "feat(web): セッション管理とログイン画面を追加"
```

---

## Task 16: アプリの外枠（サイドバー・ヘッダ・アイドル監視）

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/NavItem.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/Sidebar.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/AppScaffold.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/StateViews.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: `SessionState` / `LocalSession`（Task 15）, `RouterState`（Task 14）, `Route`（Task 12）, `IdleState`（Task 13）, `allows()`（Task 4）
- Produces:
  - `data class NavItem(route: Route, label: String, requiredOperation: String?)` と `val navGroups: List<NavGroup>` / `data class NavGroup(title: String?, items: List<NavItem>)`
  - `@Composable fun Sidebar(current: Route, onNavigate: (Route) -> Unit)`
  - `@Composable fun AppScaffold(router: RouterState, content: @Composable () -> Unit)`
  - `@Composable fun ErrorView(message: String, onRetry: (() -> Unit)?)`, `@Composable fun LoadingView()`, `@Composable fun DeniedView(operation: String)`

**Phase 1 のサイドバーには「概況」だけを載せる。** Buckets や Layout の項目は、その画面を実装する Phase 2・3 で `navGroups` に追加する。存在しない画面へのリンクを先に置かないこと。`NavItem.requiredOperation` は scope による無効表示のための仕組みで、Phase 2 以降で本領を発揮する。

- [ ] **Step 1: ナビゲーション定義を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/NavItem.kt`

```kotlin
package net.brightroom.garage.web.navigation

import net.brightroom.garage.shared.navigation.Route

/**
 * @param requiredOperation この画面が最低限必要とする Garage の operation。
 *   scope に含まれない場合はサイドバーで無効表示にする。
 *   これは UI ヒントであり、可否の実体は常に Garage が返す 403 で決まる（spec §6.3）。
 */
data class NavItem(
    val route: Route,
    val label: String,
    val requiredOperation: String? = null,
)

data class NavGroup(
    val title: String?,
    val items: List<NavItem>,
)

/**
 * サイドバーの構成。役割でグループ化する（spec §8.2）。
 *
 * Phase 1 では概況のみ。ストレージ・クラスタ・メンテナンス・設定の各グループは
 * 対応する画面を実装する Phase 2・3 で追加する。
 */
val navGroups: List<NavGroup> = listOf(
    NavGroup(
        title = null,
        items = listOf(
            NavItem(Route.Overview, "概況", requiredOperation = "GetClusterHealth"),
        ),
    ),
)
```

- [ ] **Step 2: サイドバーを書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/Sidebar.kt`

```kotlin
package net.brightroom.garage.web.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.allows
import net.brightroom.garage.shared.navigation.Route
import net.brightroom.garage.web.session.LocalSession

@Composable
fun Sidebar(
    current: Route,
    onNavigate: (Route) -> Unit,
) {
    val session = LocalSession.current

    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Garage",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
        )

        navGroups.forEach { group ->
            group.title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                )
            }

            group.items.forEach { item ->
                val enabled = item.requiredOperation == null ||
                    session.info?.allows(item.requiredOperation) == true

                NavigationDrawerItem(
                    label = {
                        Text(
                            if (enabled) item.label else "${item.label}（権限なし）",
                        )
                    },
                    selected = current == item.route,
                    onClick = { if (enabled) onNavigate(item.route) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
```

- [ ] **Step 3: 共通の状態表示を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/StateViews.kt`

```kotlin
package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorView(message: String, onRetry: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            onRetry?.let {
                TextButton(onClick = it) { Text("再試行") }
            }
        }
    }
}

/**
 * scope 不足でこのセクションを参照できないことを伝える。
 *
 * 403 は正常系であり、画面全体を失敗させないための表示である（spec §6.3）。
 */
@Composable
fun DeniedView(operation: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "このトークンでは参照できません",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "必要な scope: $operation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 4: 外枠とアイドル監視を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/navigation/AppScaffold.kt`

```kotlin
package net.brightroom.garage.web.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.session.IdleState
import net.brightroom.garage.web.router.RouterState
import net.brightroom.garage.web.session.LocalSession
import org.w3c.dom.events.Event

@Composable
fun AppScaffold(
    router: RouterState,
    content: @Composable () -> Unit,
) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var idleWarning by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableStateOf(0L) }

    // アイドル判定の基準は利用者の操作。ポーリングの通信では延長しない（spec §6.6）
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { session.recordActivity() }
        window.addEventListener("click", listener)
        window.addEventListener("keydown", listener)

        onDispose {
            window.removeEventListener("click", listener)
            window.removeEventListener("keydown", listener)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            when (session.idleState()) {
                IdleState.ACTIVE -> idleWarning = false
                IdleState.WARNING -> {
                    idleWarning = true
                    remainingSeconds = session.idleRemainingSeconds()
                }

                IdleState.EXPIRED -> {
                    session.signOut()
                    return@LaunchedEffect
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Sidebar(current = router.current, onNavigate = router::navigate)

        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                idleWarning = idleWarning,
                remainingSeconds = remainingSeconds,
                onSignOut = { scope.launch { session.signOut() } },
            )
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun Header(
    idleWarning: Boolean,
    remainingSeconds: Long,
    onSignOut: () -> Unit,
) {
    val session = LocalSession.current
    val info = session.info

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(info?.name ?: "-", style = MaterialTheme.typography.titleSmall)
            Text(
                expirationLabel(info?.expiration?.toString(), info?.scope?.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (idleWarning) {
            Text(
                "操作がないため ${remainingSeconds} 秒後にログアウトします",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TextButton(onClick = onSignOut) { Text("ログアウト") }
    }
}

private fun expirationLabel(expiration: String?, scopeSize: Int?): String {
    val scopeText = when (scopeSize) {
        null -> "scope 不明"
        else -> "$scopeSize 個の operation を許可"
    }

    return if (expiration == null) "$scopeText ・ 無期限" else "$scopeText ・ 期限 $expiration"
}
```

`info.scope` が `["*"]` の場合に「1 個の operation を許可」と出てしまうため、`expirationLabel` の呼び出し前に `info?.scope?.contains("*") == true` なら「すべての operation を許可」と出す分岐を入れること。実装時に `Header` 内で次のように書く。

```kotlin
        val scopeText = when {
            info == null -> "scope 不明"
            info.scope.contains("*") -> "すべての operation を許可"
            else -> "${info.scope.size} 個の operation を許可"
        }
```

その場合 `expirationLabel` は `scopeText` と `expiration` を受け取る形に変える。

- [ ] **Step 5: `App.kt` の `AuthenticatedApp` を差し替える**

```kotlin
@Composable
private fun AuthenticatedApp(router: RouterState) {
    AppScaffold(router) {
        when (router.current) {
            Route.Overview -> OverviewScreen()          // Task 17 で実装。今は仮に Text を置く
            Route.Login -> OverviewScreen()             // ログイン済みで /login に来たら概況を出す
            is Route.NotFound -> ErrorView("画面が見つかりません: ${router.current.path}")
        }
    }
}
```

Task 17 を実装するまでは `OverviewScreen()` の代わりに `Text("概況")` を置く。import は実装時に整える。

- [ ] **Step 6: ビルドとブラウザで確認する**

```bash
./gradlew :web:wasmJsBrowserDistribution
./gradlew :server:run
```

確認内容:
1. ログイン後にサイドバー（「Garage」と「概況」）とヘッダ（トークン名・scope・ログアウト）が出る
2. 「ログアウト」を押すとログイン画面に戻り、リロードしてもログイン画面のままである
3. `http://localhost:8080/nope` に直接アクセスすると「画面が見つかりません」が出る

- [ ] **Step 7: コミット**

```bash
git add web/
git commit -m "feat(web): サイドバー・ヘッダ・アイドル監視を追加"
```

---

## Task 17: 概況画面（異常ファースト・ポーリング）

**Files:**
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/overview/OverviewScreen.kt`
- Create: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/Formatting.kt`
- Modify: `web/src/wasmJsMain/kotlin/net/brightroom/garage/web/App.kt`

**Interfaces:**
- Consumes: `Overview` / `alerts()` / `NodeSummary`（Task 5）, `Section`（Task 2）, `getJson` / `ApiResult`（Task 14）, `LocalSession`（Task 15）, `LoadingView` / `ErrorView` / `DeniedView`（Task 16）
- Produces:
  - `@Composable fun OverviewScreen()`
  - `fun formatBytes(bytes: Long): String`

**画面の構成は spec §8.3 のとおり。**
1. 最上段は異常帯専用。`alerts()` が空なら「異常はありません」の 1 行に収める
2. 主要数値（nodes up / health / bucket 数 / key 数）
3. ノード一覧（ゾーンと容量）

**ポーリングは 10 秒間隔。** `document.visibilityState` が `hidden` の間は止める（放置したタブが Garage を叩き続けないため）。自動更新のトグルと「最終更新 N 秒前」を出す。401 を受けたら `session.invalidate()` を呼びログイン画面へ戻す。

- [ ] **Step 1: 書式ヘルパを書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/components/Formatting.kt`

```kotlin
package net.brightroom.garage.web.components

private const val UNIT = 1024.0

/** バイト数を人が読める単位にする。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    var value = bytes.toDouble()
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
    var index = -1

    while (value >= UNIT && index < units.lastIndex) {
        value /= UNIT
        index++
    }

    val rounded = ((value * 10).toLong()).toDouble() / 10
    return "$rounded ${units[index]}"
}
```

- [ ] **Step 2: 概況画面を書く**

`web/src/wasmJsMain/kotlin/net/brightroom/garage/web/screens/overview/OverviewScreen.kt`

```kotlin
package net.brightroom.garage.web.screens.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.api.AlertSeverity
import net.brightroom.garage.shared.api.NodeSummary
import net.brightroom.garage.shared.api.Overview
import net.brightroom.garage.shared.api.Section
import net.brightroom.garage.shared.api.alerts
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.components.DeniedView
import net.brightroom.garage.web.components.ErrorView
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.session.LocalSession

private const val POLL_INTERVAL_MILLIS = 10_000L

@Composable
fun OverviewScreen() {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var overview by remember { mutableStateOf<Overview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoRefresh by remember { mutableStateOf(true) }
    var secondsSinceUpdate by remember { mutableStateOf(0) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/overview", Overview.serializer())) {
            is ApiResult.Success -> {
                overview = result.value
                error = null
                secondsSinceUpdate = 0
            }

            is ApiResult.Failure -> error = result.error.message
            // トークンが失効した。ログイン画面に戻す
            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    LaunchedEffect(Unit) { load() }

    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            delay(1_000)
            secondsSinceUpdate++

            // 放置されたタブが Garage を叩き続けないようにする。
            // visibilityState は wasmJs では external な列挙型で扱いが不安定なため、
            // Boolean の document.hidden を使う。
            if (secondsSinceUpdate * 1000L >= POLL_INTERVAL_MILLIS && !document.hidden) {
                load()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("概況", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text(
                "最終更新 ${secondsSinceUpdate} 秒前",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("自動更新", style = MaterialTheme.typography.bodySmall)
            Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
        }

        error?.let { ErrorView(it, onRetry = { scope.launch { load() } }) }

        when (val current = overview) {
            null -> if (error == null) LoadingView()
            else -> OverviewContent(current)
        }
    }
}

@Composable
private fun OverviewContent(overview: Overview) {
    AlertBand(overview)
    KeyFigures(overview)
    NodeList(overview.nodes)
}

/** 最上段の異常帯。正常時は 1 行に収める（spec §8.3）。 */
@Composable
private fun AlertBand(overview: Overview) {
    val alerts = overview.alerts()

    if (alerts.isEmpty()) {
        Text(
            "異常はありません",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        alerts.forEach { alert ->
            val container = when (alert.severity) {
                AlertSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
                AlertSeverity.WARNING -> MaterialTheme.colorScheme.surfaceVariant
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = container),
            ) {
                Text(
                    alert.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun KeyFigures(overview: Overview) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FigureCard("ノード") {
            when (val nodes = overview.nodes) {
                is Section.Loaded -> Text(
                    "${nodes.data.count { it.isUp }} / ${nodes.data.size}",
                    style = MaterialTheme.typography.headlineMedium,
                )

                is Section.Denied -> DeniedView(nodes.operation)
                is Section.Failed -> Text(nodes.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        FigureCard("状態") {
            when (val health = overview.health) {
                is Section.Loaded -> Column {
                    Text(health.data.status, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "quorum ${health.data.partitionsQuorum}/${health.data.partitions}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Section.Denied -> DeniedView(health.operation)
                is Section.Failed -> Text(health.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        FigureCard("ストレージ") {
            when (val storage = overview.storage) {
                is Section.Loaded -> Column {
                    Text(
                        "${storage.data.buckets} バケット",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "${storage.data.keys} キー",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Section.Denied -> DeniedView(storage.operation)
                is Section.Failed -> Text(storage.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        FigureCard("レイアウト") {
            when (val layout = overview.layout) {
                is Section.Loaded -> Column {
                    Text("v${layout.data.version}", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (layout.data.stagedChanges == 0) {
                            "未適用の変更なし"
                        } else {
                            "${layout.data.stagedChanges} 件が未適用"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Section.Denied -> DeniedView(layout.operation)
                is Section.Failed -> Text(layout.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FigureCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.width(220.dp)) {
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

@Composable
private fun NodeList(section: Section<List<NodeSummary>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ノード", style = MaterialTheme.typography.titleSmall)

            when (section) {
                is Section.Denied -> DeniedView(section.operation)
                is Section.Failed -> Text(section.message, style = MaterialTheme.typography.bodySmall)
                is Section.Loaded ->
                    if (section.data.isEmpty()) {
                        Text(
                            "ノードがありません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        section.data.forEach { NodeRow(it) }
                    }
            }
        }
    }
}

@Composable
private fun NodeRow(node: NodeSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (node.isUp) "稼働" else "停止",
            style = MaterialTheme.typography.labelSmall,
            color = if (node.isUp) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.width(48.dp),
        )

        Text(
            node.hostname ?: node.id.take(12),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(160.dp),
        )

        Text(
            node.zone ?: "-",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )

        val total = node.dataTotal
        val available = node.dataAvailable

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

        if (node.draining) {
            Text(
                "退避中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 3: `App.kt` の仮実装を差し替える**

`AuthenticatedApp` の `Text("概況")` を `OverviewScreen()` に置き換え、import を追加する。

- [ ] **Step 4: ビルドを確認する**

```bash
./gradlew :web:wasmJsBrowserDistribution
```

期待: BUILD SUCCESSFUL。

- [ ] **Step 5: ブラウザで確認する**

```bash
docker compose up -d
./gradlew :server:run
```

確認内容:
1. ログイン後に「異常はありません」と主要数値、ノード一覧が出る
2. 「最終更新 N 秒前」が増え、10 秒ごとに 0 に戻る
3. 自動更新をオフにすると増え続ける
4. **別タブに移動している間は更新が止まる**（タブに戻ってカウンタが 10 を超えていること）

異常時の表示も確認する。レイアウトに staged 変更を作ると異常帯が出る。

```bash
NODE_ID=$(curl -sf -H "Authorization: Bearer <token>" http://localhost:3903/v2/GetClusterStatus | jq -r '.nodes[0].id')
curl -sf -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d "{\"roles\":[{\"id\":\"${NODE_ID}\",\"zone\":\"dc1\",\"capacity\":2147483648,\"tags\":[\"dev\"]}]}" \
  http://localhost:3903/v2/UpdateClusterLayout
```

画面に「レイアウト v… に 1 件の未適用の変更があります」が出ること。確認後は revert する。

```bash
curl -sf -X POST -H "Authorization: Bearer <token>" http://localhost:3903/v2/RevertClusterLayout
```

- [ ] **Step 6: コミット**

```bash
git add web/
git commit -m "feat(web): 異常ファーストの概況画面を追加"
```

---

## Task 18: e2e（ログイン・ナビゲーション・概況）

**Files:**
- Create: `e2e/tests/login.spec.ts`
- Create: `e2e/tests/navigation.spec.ts`
- Create: `e2e/tests/overview.spec.ts`
- Modify: `.github/workflows/on-pull-request.yaml`（e2e にトークンを渡すステップを追加）

**Interfaces:**
- Consumes: 動作中の server（`http://localhost:8080`）と Garage v2.3.0
- Produces: `E2E_ADMIN_TOKEN` 環境変数を前提とする Playwright テスト群

**要素の掴み方は role と表示テキストで行う。** Compose for Web はキャンバスに描画しつつアクセシビリティツリーを DOM に出す。旧 e2e もこの方式だった。キャンバスが重なるためクリックには `{ force: true }` が必要になる。

**トークンの受け渡し:** `docker compose logs garage-init` に出るトークンを `E2E_ADMIN_TOKEN` として渡す。

```bash
export E2E_ADMIN_TOKEN=$(docker compose logs garage-init | grep 'Console login token:' | sed 's/.*Console login token: //' | tr -d '\r')
```

- [ ] **Step 1: ログインのテストを書く**

`e2e/tests/login.spec.ts`

```typescript
import { test, expect } from "@playwright/test";

const token = process.env.E2E_ADMIN_TOKEN;

test.beforeAll(() => {
  if (!token) {
    throw new Error(
      "E2E_ADMIN_TOKEN が未設定です。docker compose logs garage-init から取得してください",
    );
  }
});

test.describe("Login", () => {
  test("shows the login screen when no token is stored", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });
  });

  test("rejects an invalid token", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("textbox").fill("not-a-real-token");
    await page.getByRole("button", { name: "ログイン" }).click({ force: true });

    await expect(page.getByText(/受け付けられませんでした/)).toBeVisible();
  });

  test("signs in with a valid token and survives a reload", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("textbox").fill(token!);
    await page.getByRole("button", { name: "ログイン" }).click({ force: true });

    await expect(page.getByText("概況")).toBeVisible({ timeout: 15_000 });

    // sessionStorage に保持されるため、リロードでもログイン状態が保たれる
    await page.reload();
    await expect(page.getByText("概況")).toBeVisible({ timeout: 30_000 });
  });

  test("signs out and returns to the login screen", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });
    await page.getByRole("textbox").fill(token!);
    await page.getByRole("button", { name: "ログイン" }).click({ force: true });
    await expect(page.getByText("概況")).toBeVisible({ timeout: 15_000 });

    await page.getByRole("button", { name: "ログアウト" }).click({ force: true });

    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible();

    await page.reload();
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });
  });
});
```

- [ ] **Step 2: ナビゲーションのテストを書く**

`e2e/tests/navigation.spec.ts`

```typescript
import { test, expect } from "@playwright/test";

const token = process.env.E2E_ADMIN_TOKEN;

async function signIn(page: import("@playwright/test").Page) {
  await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
    timeout: 30_000,
  });
  await page.getByRole("textbox").fill(token!);
  await page.getByRole("button", { name: "ログイン" }).click({ force: true });
  await expect(page.getByText("概況")).toBeVisible({ timeout: 15_000 });
}

test.describe("Navigation", () => {
  test("serves the app for a deep link instead of a 404", async ({ page }) => {
    // SPA フォールバックが効いていることの確認。
    // サーバーが index.html を返さなければ、この時点で Playwright は HTML ではなく
    // JSON エラーを受け取り、ログイン画面が出ない。
    const response = await page.goto("/login");

    expect(response?.status()).toBe(200);
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });
  });

  test("shows the sidebar after signing in", async ({ page }) => {
    await page.goto("/");
    await signIn(page);

    await expect(page.getByText("Garage")).toBeVisible();
    await expect(page.getByText("概況")).toBeVisible();
  });

  test("returns a JSON error for unknown api paths", async ({ request }) => {
    const response = await request.get("/api/does-not-exist");

    expect(response.status()).toBe(404);
    const body = await response.json();
    expect(body.error.code).toBe("NOT_FOUND");
  });

  test("rejects api access without a token", async ({ request }) => {
    const response = await request.get("/api/overview");

    expect(response.status()).toBe(401);
    const body = await response.json();
    expect(body.error.code).toBe("UNAUTHORIZED");
  });
});
```

- [ ] **Step 3: 概況のテストを書く**

`e2e/tests/overview.spec.ts`

```typescript
import { test, expect } from "@playwright/test";

const token = process.env.E2E_ADMIN_TOKEN;

test.describe("Overview", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("button", { name: "ログイン" })).toBeVisible({
      timeout: 30_000,
    });
    await page.getByRole("textbox").fill(token!);
    await page.getByRole("button", { name: "ログイン" }).click({ force: true });
    await expect(page.getByText("概況")).toBeVisible({ timeout: 15_000 });
  });

  test("shows cluster figures", async ({ page }) => {
    // 「ノード」は主要数値カードとノード一覧の見出しの両方に出るため、
    // strict mode violation を避けて first() を取る。
    // 実物の DOM を見て、必要なら文言側を一意にする判断をすること。
    await expect(page.getByText("ノード", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("状態", { exact: true })).toBeVisible();
    await expect(page.getByText("ストレージ", { exact: true })).toBeVisible();
    await expect(page.getByText("レイアウト", { exact: true })).toBeVisible();
  });

  test("reports a healthy cluster with no alerts", async ({ page }) => {
    // compose の dev クラスタは単一ノードで healthy な状態にある
    await expect(page.getByText("異常はありません")).toBeVisible();
  });

  test("refreshes on demand", async ({ page }) => {
    await page.getByRole("button", { name: "更新" }).click({ force: true });

    await expect(page.getByText("最終更新 0 秒前")).toBeVisible();
  });

  test("serves the overview payload with every section loaded", async ({ request }) => {
    const response = await request.get("/api/overview", {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(response.status()).toBe(200);
    const body = await response.json();

    // すべてのセクションが取得できていること（scope は "*" のため）
    for (const key of ["health", "nodes", "layout", "storage", "blockErrors"]) {
      expect(body[key].type).toBe("loaded");
    }
  });
});
```

- [ ] **Step 4: ローカルで e2e を実行する**

```bash
docker compose up -d
export E2E_ADMIN_TOKEN=$(docker compose logs garage-init | grep 'Console login token:' | sed 's/.*Console login token: //' | tr -d '\r')
./gradlew :server:run &
cd e2e && npm ci && npx playwright test
```

期待: すべて PASS。

要素が見つからない場合は、まず `npx playwright test --debug` で実際の DOM を確認する。Compose が露出するロール名が想定と違う可能性があるため、**セレクタを実物に合わせて修正する**（アプリ側の文言を e2e のために変えるのは避けること。文言は利用者向けの判断で決める）。

- [ ] **Step 5: CI にトークンの受け渡しを追加する**

`.github/workflows/on-pull-request.yaml` の e2e ジョブで、Garage を起動した後・Playwright を実行する前に次のステップを挿入する。

```yaml
      - name: Resolve console admin token
        run: |
          TOKEN=$(docker compose logs garage-init \
            | grep 'Console login token:' \
            | sed 's/.*Console login token: //' \
            | tr -d '\r')
          if [ -z "$TOKEN" ]; then
            echo "failed to resolve admin token from garage-init logs" >&2
            docker compose logs garage-init >&2
            exit 1
          fi
          echo "::add-mask::$TOKEN"
          echo "E2E_ADMIN_TOKEN=$TOKEN" >> "$GITHUB_ENV"
```

`::add-mask::` を必ず入れること。トークンがログに出るのを防ぐ。既存ワークフローの構成（ジョブ名・ステップの並び）は現物を見て合わせる。

- [ ] **Step 6: 全体のビルドとテストを通す**

```bash
./gradlew build
```

期待: BUILD SUCCESSFUL。

- [ ] **Step 7: コミット**

```bash
git add e2e/ .github/
git commit -m "test(e2e): ログイン・ナビゲーション・概況のテストを追加"
```

---

## Phase 1 の完了条件

すべて満たしたら Phase 2 の計画作成に進む。

- [ ] `./gradlew build` が通る
- [ ] `docker compose up -d` と `./gradlew :server:run` の状態で e2e がすべて通る
- [ ] ログイン → 概況表示 → リロードで維持 → ログアウト → ログイン画面、が手動でも確認できている
- [ ] `/nope` へ直接アクセスしてもアプリが起動する（SPA フォールバック）
- [ ] `/api/overview` にトークン無しでアクセスすると JSON の 401 が返る
- [ ] サーバーのログにトークンが出ていない（`./gradlew :server:run` の出力を目視でも確認する）
- [ ] Task 0 Step 5 で確認した master token の挙動が、コミットメッセージに記録されている

