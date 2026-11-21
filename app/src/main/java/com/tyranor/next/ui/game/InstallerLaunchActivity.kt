package com.tyranor.next.ui.game

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.launch.LaunchResult
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.sharing.InstallerLaunchContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 直装包启动入口：**按路径直接拉起游戏**。
 *
 * 与 [GameShortcutActivity] 的差别：这里不查游戏库、不写游戏库，
 * 直接接收路径与启动参数构造 [ScanGame] 后交给 [EngineLauncher]。
 * 因此直装包无需让游戏先入库即可运行。
 *
 * ### 输入（见 [InstallerLaunchContract]）
 * - `extra_game_path`：游戏目录真实路径（必填）
 * - `extra_title`：标题（可选）
 * - `extra_launch_target`：启动文件，如 `data.xp3`（可选）
 * - `extra_engine`：引擎，当前仅支持 KIRIKIRI
 *
 * ### 安全
 * - 路径必须是**已存在的目录**，且不得包含 `..` 等越界片段；
 * - 只启动游戏，不修改游戏库与设置。
 */
class InstallerLaunchActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val path = intent?.getStringExtra(InstallerLaunchContract.EXTRA_GAME_PATH)
        if (path.isNullOrBlank()) {
            failAndFinish(getString(R.string.installer_missing_path))
            return
        }

        val engineName = intent.getStringExtra(InstallerLaunchContract.EXTRA_ENGINE)
            ?: EngineType.KIRIKIRI.name
        if (engineName !in InstallerLaunchContract.SUPPORTED_ENGINES) {
            failAndFinish(getString(R.string.installer_engine_unsupported, engineName))
            return
        }

        val dir = resolveDir(path)
        if (dir == null) {
            failAndFinish(getString(R.string.installer_dir_unavailable, path))
            return
        }

        val title = intent.getStringExtra(InstallerLaunchContract.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: dir.name
        val launchTarget = intent.getStringExtra(InstallerLaunchContract.EXTRA_LAUNCH_TARGET)
            .orEmpty()
            .ifBlank { inferLaunchTarget(dir) }

        val game = ScanGame(
            title = title,
            uri = Uri.fromFile(dir).toString(),
            engine = EngineType.KIRIKIRI,
            launchTarget = launchTarget,
        )

        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { EngineLauncher.launch(this@InstallerLaunchActivity, game) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                finish()
                return@launch
            }

            when (result) {
                is LaunchResult.Success -> finish()
                is LaunchResult.Failure.AllFilesAccessRequested -> {
                    // 引擎已自行拉起授权页，本次结束
                    finish()
                }
                else -> failAndFinish(describeFailure(result))
            }
        }
    }

    /** 校验并规整路径，拒绝越界与不存在的目录。 */
    private fun resolveDir(rawPath: String): File? {
        if (rawPath.contains("..")) return null
        val dir = File(rawPath)
        return dir.takeIf { it.isDirectory && it.canRead() }
    }

    /** 自动推断启动文件：优先 startup.tjs，其次第一个 xp3。 */
    private fun inferLaunchTarget(dir: File): String {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        files.firstOrNull { it.name.equals("startup.tjs", ignoreCase = true) }
            ?.let { return it.name }
        files.firstOrNull { it.name.endsWith(".xp3", ignoreCase = true) }
            ?.let { return it.name }
        return ""
    }

    private fun describeFailure(result: LaunchResult): String = when (result) {
        is LaunchResult.Failure.GameDirUnresolved -> getString(R.string.installer_err_game_dir)
        is LaunchResult.Failure.AllFilesAccessMissing ->
            getString(R.string.installer_err_all_files)
        is LaunchResult.Failure.StorageAccessMissing -> getString(R.string.installer_err_storage)
        is LaunchResult.Failure.PluginBootstrapFailed -> getString(R.string.installer_err_plugin)
        is LaunchResult.Failure.EngineLibraryPackFailed ->
            getString(R.string.installer_err_runtime_pack)
        else -> getString(
            R.string.installer_launch_failed_reason,
            result.javaClass.simpleName,
        )
    }

    private fun failAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * 构造启动 Intent。
         *
         * @param gamePath 游戏目录真实路径
         */
        fun createIntent(
            context: Context,
            gamePath: String,
            title: String? = null,
            launchTarget: String? = null,
            engine: String = EngineType.KIRIKIRI.name,
        ): Intent = Intent(context, InstallerLaunchActivity::class.java).apply {
            putExtra(InstallerLaunchContract.EXTRA_GAME_PATH, gamePath)
            title?.let { putExtra(InstallerLaunchContract.EXTRA_TITLE, it) }
            launchTarget?.let { putExtra(InstallerLaunchContract.EXTRA_LAUNCH_TARGET, it) }
            putExtra(InstallerLaunchContract.EXTRA_ENGINE, engine)
        }
    }
}
