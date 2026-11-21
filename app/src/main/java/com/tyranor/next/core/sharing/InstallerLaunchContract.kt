package com.tyranor.next.core.sharing

/**
 * 直装包启动契约。
 *
 * 直装包（Tyn Packager 产物）本质是「Tyranor + 内置游戏」，启动时需要一个
 * 能**按路径直接拉起游戏**的入口——而不是要求游戏先进入游戏库。
 *
 * 该入口由 Tyranor 侧提供，直装包通过显式 Intent 启动。
 *
 * ### 与 [com.tyranor.next.ui.game.GameShortcutActivity] 的区别
 * - `GameShortcutActivity` 用游戏 uri 的 SHA-256 反查**库内**记录，要求游戏已入库；
 * - 本入口直接接收路径与启动参数，**不查库、不写库**，适合内置游戏。
 *
 * ### 安全
 * 入口 `exported=true` 但**只读**：不回写游戏库、不改任何设置。
 * 传入的路径必须位于应用可访问的范围内，越界路径直接拒绝。
 */
object InstallerLaunchContract {

    /** 直装包发起的启动 Action。 */
    const val ACTION_LAUNCH_GAME = "com.tyranor.next.action.LAUNCH_GAME_BY_PATH"

    /** 游戏目录的真实路径（必填）。 */
    const val EXTRA_GAME_PATH = "extra_game_path"

    /** 游戏标题（可选，仅用于日志与界面）。 */
    const val EXTRA_TITLE = "extra_title"

    /** 启动目标，如 `data.xp3`；为空则由扫描器自动推断。 */
    const val EXTRA_LAUNCH_TARGET = "extra_launch_target"

    /** 引擎名，当前只接受 `KIRIKIRI`。 */
    const val EXTRA_ENGINE = "extra_engine"

    /** 支持的引擎白名单。 */
    val SUPPORTED_ENGINES = setOf("KIRIKIRI")
}
