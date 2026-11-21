package com.tyranor.next.core.sharing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.storage.GameEntity
import com.tyranor.next.core.game.storage.GameLibraryDatabase
import com.tyranor.next.core.game.storage.ScanRootEntity
import kotlinx.coroutines.runBlocking

/**
 * 游戏库只读共享 Provider。
 *
 * 只实现 [query]，所有写操作一律抛 [UnsupportedOperationException]；
 * [call] 仅提供 `version` / `capabilities` 两个只读方法，其余拒绝。
 *
 * 数据直接取自游戏库 Room 库（与 App 内一致），不触发重新扫描。
 */
class GameLibraryProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val path = normalizePath(uri)
        return when (path) {
            GameLibraryContract.PATH_GAMES -> queryGames(ctx, uri, onlyKrkr = false)
            GameLibraryContract.PATH_KRKR_GAMES -> queryGames(ctx, uri, onlyKrkr = true)
            GameLibraryContract.PATH_ROOTS -> queryRoots(ctx)
            else -> throw IllegalArgumentException("Unknown path: $path")
        }
    }

    // ==================== games ====================

    private fun queryGames(ctx: Context, uri: Uri, onlyKrkr: Boolean): Cursor {
        val dao = GameLibraryDatabase.get(ctx).gameLibraryDao()
        var games: List<GameEntity> = runBlocking { dao.getAllGames() }

        // 1) 引擎过滤：krkr 视图强制 KIRIKIRI，其余按参数可多次传
        val engineFilter = if (onlyKrkr) {
            setOf(EngineType.KIRIKIRI.name)
        } else {
            uri.queryParameterNames
                .asSequence()
                .filter { it == GameLibraryContract.PARAM_ENGINE }
                .flatMap { name -> uri.getQueryParameters(name).asSequence() }
                .filter { it.isNotBlank() }
                .map { it.uppercase() }
                .toSet()
        }
        if (engineFilter.isNotEmpty()) {
            games = games.filter { engineFilter.contains(it.engine.uppercase()) }
        }

        // 2) 标题搜索：与 App 内同口径（子串匹配，忽略大小写）
        val term = uri.getQueryParameter(GameLibraryContract.PARAM_QUERY)?.trim()
        if (!term.isNullOrEmpty()) {
            games = games.filter { it.title.contains(term, ignoreCase = true) }
        }

        // 3) 排序：最近打开优先，其次标题
        games = games.sortedWith(
            compareByDescending<GameEntity> { it.lastOpenedAt }
                .thenBy { it.sortTitle },
        )

        // 4) 数量限制
        val limit = uri.getQueryParameter(GameLibraryContract.PARAM_LIMIT)?.toIntOrNull() ?: 0
        if (limit > 0) games = games.take(limit)

        val columns = GAME_COLUMNS
        val cursor = MatrixCursor(columns, games.size)
        for (g in games) {
            val manual = runCatching { EngineType.valueOf(g.engine).isManual }.getOrDefault(false)
            cursor.addRow(
                arrayOf<Any?>(
                    g.uri,
                    GamePathUtils.safUriToPath(g.uri),
                    g.rootUri,
                    GamePathUtils.safUriToPath(g.rootUri),
                    g.title,
                    g.engine,
                    runCatching { EngineType.valueOf(g.engine).displayName }.getOrDefault(g.engine),
                    g.launchTarget,
                    g.launchFile,
                    g.coverUri,
                    g.coverSource,
                    g.metadataTitle,
                    g.discoveredAt,
                    g.updatedAt,
                    g.lastOpenedAt,
                    if (manual) 1 else 0,
                ),
            )
        }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    // ==================== roots ====================

    private fun queryRoots(ctx: Context): Cursor {
        val dao = GameLibraryDatabase.get(ctx).gameLibraryDao()
        val roots: List<ScanRootEntity> = runBlocking { dao.getRoots() }
        val columns = ROOT_COLUMNS
        val cursor = MatrixCursor(columns, roots.size)
        for (r in roots) {
            cursor.addRow(
                arrayOf<Any?>(
                    r.uri,
                    GamePathUtils.safUriToPath(r.uri),
                    r.displayName,
                    r.addedAt,
                    r.lastScannedAt,
                ),
            )
        }
        return cursor
    }

    // ==================== 只读约束 ====================

    override fun getType(uri: Uri): String = when (normalizePath(uri)) {
        GameLibraryContract.PATH_ROOTS -> GameLibraryContract.MIME_ROOTS
        else -> GameLibraryContract.MIME_GAMES
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throwReadOnly()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throwReadOnly()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throwReadOnly()

    /**
     * 仅暴露只读元信息，避免调用方误以为可以走 call 写入。
     * - `version`：接口版本号
     * - `capabilities`：能力清单（逗号分隔）
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "version" -> Bundle().apply { putInt("version", API_VERSION) }
        "capabilities" -> Bundle().apply {
            putString("engines", EngineType.entries.joinToString(",") { it.name })
            putString("paths", "${GameLibraryContract.PATH_GAMES},${GameLibraryContract.PATH_ROOTS}")
            putString("packagerEngines", EngineType.KIRIKIRI.name)
        }
        else -> throw UnsupportedOperationException("Read-only provider: unsupported call '$method'")
    }

    // ==================== internals ====================

    private fun normalizePath(uri: Uri): String {
        val path = uri.path?.trim('/') ?: return GameLibraryContract.PATH_GAMES
        return path.ifEmpty { GameLibraryContract.PATH_GAMES }
    }

    private fun throwReadOnly(): Nothing =
        throw UnsupportedOperationException("GameLibraryProvider is read-only")

    companion object {
        /** 接口版本；不兼容变更时递增。 */
        const val API_VERSION = 1

        private val GAME_COLUMNS = arrayOf(
            GameLibraryContract.COL_URI,
            GameLibraryContract.COL_PATH,
            GameLibraryContract.COL_ROOT_URI,
            GameLibraryContract.COL_ROOT_PATH,
            GameLibraryContract.COL_TITLE,
            GameLibraryContract.COL_ENGINE,
            GameLibraryContract.COL_ENGINE_DISPLAY,
            GameLibraryContract.COL_LAUNCH_TARGET,
            GameLibraryContract.COL_LAUNCH_FILE,
            GameLibraryContract.COL_COVER_URI,
            GameLibraryContract.COL_COVER_SOURCE,
            GameLibraryContract.COL_METADATA_TITLE,
            GameLibraryContract.COL_DISCOVERED_AT,
            GameLibraryContract.COL_UPDATED_AT,
            GameLibraryContract.COL_LAST_OPENED_AT,
            GameLibraryContract.COL_IS_MANUAL,
        )

        private val ROOT_COLUMNS = arrayOf(
            GameLibraryContract.COL_URI,
            GameLibraryContract.COL_ROOT_PATH,
            GameLibraryContract.COL_DISPLAY_NAME,
            GameLibraryContract.COL_ADDED_AT,
            GameLibraryContract.COL_LAST_SCANNED_AT,
        )
    }
}
