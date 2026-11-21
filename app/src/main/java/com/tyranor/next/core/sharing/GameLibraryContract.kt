package com.tyranor.next.core.sharing

/**
 * 游戏库只读共享契约（供外部工具，如 Tyn Packager 读取）。
 *
 * ### 设计约束
 * 1. **只读**：本 Provider 仅实现 [android.content.ContentProvider.query]，
 *    不实现 insert / update / delete / call 的写入语义，一律抛
 *    [UnsupportedOperationException]。
 * 2. **不做签名校验**：任何应用均可读取，但只能读取。
 * 3. **数据来源**：与 Tyranor 游戏库同一个 Room 数据库（games / scan_roots），
 *    不重新扫描、不缓存副本，调用方看到的内容与 App 内一致。
 *
 * ### 授权
 * 使用 `android:readPermission` + `android:grantUriPermissions`，
 * 声明 [readPermissionOf] 返回的权限即可读取；该权限为 normal 级，安装即授予，
 * 因此对调用方无额外配置成本。
 */
object GameLibraryContract {

    /** Provider authority。**调用方应通过 [authorityOf] 按包名推导，不要硬编码**。 */
    const val AUTHORITY_SUFFIX = ".gamelibrary"

    /** 只读权限名后缀（normal 级，声明即可用）。完整名请用 [readPermissionOf]。 */
    const val PERMISSION_READ_SUFFIX = ".permission.READ_GAME_LIBRARY"

    /**
     * 按 Tyranor 包名推导只读权限完整名。
     *
     * 权限名由 Tyranor 构建时的 applicationId 拼接，**不要硬编码**
     * `com.tyranor.next.permission.READ_GAME_LIBRARY`：第三方构建会换前缀。
     */
    fun readPermissionOf(packageName: String): String = packageName + PERMISSION_READ_SUFFIX

    /**
     * 按 Tyranor 包名推导 authority。
     *
     * 请优先使用本函数而不是硬编码字符串：Tyranor 存在不同的 applicationId
     * （如第三方构建），硬编码会直接失效。
     *
     * @param packageName Tyranor 的包名，例如 `com.tyranor.next`
     */
    fun authorityOf(packageName: String): String = packageName + AUTHORITY_SUFFIX

    // ==================== 路径 ====================

    /** 全部已识别游戏。 */
    const val PATH_GAMES = "games"

    /** 全部扫描根目录（用户添加、且被扫描过的文件夹）。 */
    const val PATH_ROOTS = "roots"

    /**
     * 可制作直装版的游戏（当前仅 Kirikiri 引擎）。
     *
     * 这是给「直装包制作」场景的预过滤视图：等价于 games 中
     * `engine = KIRIKIRI` 且 `launch_target` 非空的行。
     */
    const val PATH_KRKR_GAMES = "games/krkr"

    // ==================== Query 参数 ====================

    /** 按引擎过滤，值为 EngineType.name，可多次传。 */
    const val PARAM_ENGINE = "engine"

    /** 按标题模糊搜索（与 App 内搜索同口径）。 */
    const val PARAM_QUERY = "q"

    /** 限制返回条数，`0` 或非法值表示不限制。 */
    const val PARAM_LIMIT = "limit"

    // ==================== 列名 ====================
    // 列名与 Room 表列一致，方便调用方建立稳定的数据模型。

    /** 游戏目录的 SAF tree/document URI 字符串。 */
    const val COL_URI = "uri"

    /**
     * 游戏目录的**真实文件路径**（如 `/storage/emulated/0/Games/xxx`）。
     *
     * 由 [com.tyranor.next.core.game.model.GamePathUtils.safUriToPath] 从 [COL_URI] 换算；
     * 无法换算（非主存储卷、URI 异常）时为 null。
     *
     * 调用方持有 `MANAGE_EXTERNAL_STORAGE`（所有文件访问权限）时，应直接使用本列读文件，
     * 不必处理 SAF 授权；否则才需要走 [COL_URI] 并自行申请目录访问权限。
     */
    const val COL_PATH = "path"

    /** 所属扫描根的真实文件路径；换算失败为 null。 */
    const val COL_ROOT_PATH = "root_path"

    /** 所属扫描根 URI；可能为 null（历史数据）。 */
    const val COL_ROOT_URI = "root_uri"

    /** 显示标题。 */
    const val COL_TITLE = "title"

    /** 引擎名，取值见 EngineType.name（如 `KIRIKIRI`）。 */
    const val COL_ENGINE = "engine"

    /** 引擎显示名（如 `Kirikiri`）。 */
    const val COL_ENGINE_DISPLAY = "engine_display"

    /** 启动目标（相对游戏目录，通常是某个 .xp3 文件名或目录标记）。 */
    const val COL_LAUNCH_TARGET = "launch_target"

    /** 用户手动指定的启动文件；null 表示自动。 */
    const val COL_LAUNCH_FILE = "launch_file"

    /** 封面 URI：本地图片或已抓取的远程封面。 */
    const val COL_COVER_URI = "cover_uri"

    /** 封面来源标识（如 vndb / bangumi / local）。 */
    const val COL_COVER_SOURCE = "cover_source"

    const val COL_METADATA_TITLE = "metadata_title"

    const val COL_DISCOVERED_AT = "discovered_at"

    const val COL_UPDATED_AT = "updated_at"

    /** 最近打开时间戳（毫秒），0 表示从未打开。 */
    const val COL_LAST_OPENED_AT = "last_opened_at"

    /** 是否为手动添加（PC / Android 应用），不参与扫描。 */
    const val COL_IS_MANUAL = "is_manual"

    // ==================== 扫描根列名 ====================

    /** 扫描根展示名；null 表示未缓存，调用方可从路径末段推导。 */
    const val COL_DISPLAY_NAME = "display_name"

    const val COL_ADDED_AT = "added_at"

    const val COL_LAST_SCANNED_AT = "last_scanned_at"

    // ==================== MIME ====================

    const val MIME_GAMES = "vnd.android.cursor.dir/vnd.tyranor.game"
    const val MIME_ROOTS = "vnd.android.cursor.dir/vnd.tyranor.root"
}
