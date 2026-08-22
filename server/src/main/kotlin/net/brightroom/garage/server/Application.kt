package net.brightroom.garage.server

import io.ktor.server.cio.EngineMain

/**
 * エントリポイント。
 *
 * どのプラグインをどの順で構成するかは `application.yaml` の
 * `ktor.application.modules` が持つ。
 */
fun main(args: Array<String>) {
    EngineMain.main(args)
}
