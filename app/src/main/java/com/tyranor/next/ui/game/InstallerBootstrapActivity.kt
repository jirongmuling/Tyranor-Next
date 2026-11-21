package com.tyranor.next.ui.game

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tyranor.next.MainActivity
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.launch.LaunchResult
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 桌面入口分发器。
 *
 * 本 Activity 是 `<activity-alias>` 的 `targetActivity`，因此**所有点图标启动都会先到这里**。
 *
 * ### 分发逻辑
 * 1. APK 内存在 `assets/tyn_packager/game.json` → 这是**直装包**：
 *    解包内置游戏资源并直接启动游戏；
 * 2. 不存在 → 这是**原版 Tyranor**：立即转发到 [MainActivity]，行为与以前完全一致。
 *
 * ### 为什么放在这里
 * 直装包由 Tyranor 本体改造而来，运行时组件必须存在于 Tyranor 侧。
 * 把分发放在启动入口，可以零成本地同时支持两种形态。
 */
class InstallerBootstrapActivity : ComponentActivity() {

    /** 直装包元数据路径（与 Tyn Packager 的写入位置一致）。 */
    private val metadataAssetPath = "assets/tyn_packager/game.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val metadata = readMetadata()

        // 原版 Tyranor：直接进主界面
        if (metadata == null) {
            toMainActivity()
            return
        }

        // 直装包：准备资源后启动游戏
        prepareAndLaunch(metadata)
    }

    /** 读取内置元数据；不存在或格式异常都视为「非直装包」。 */
    private fun readMetadata(): InstallerInfo? = runCatching {
        assets.open(metadataAssetPath).bufferedReader().use { it.readText() }
    }.getOrNull()?.let(InstallerInfo::parse)

    private fun prepareAndLaunch(info: InstallerInfo) {
        val targetDir = File(
            getExternalFilesDir(null) ?: filesDir,
            "game",
        )
        val marker = File(targetDir, ".unpack_done")

        val alreadyUnpacked = marker.isFile &&
            runCatching { marker.readText().trim().toIntOrNull() }.getOrNull() == SCHEMA_VERSION

        if (alreadyUnpacked) {
            launchGame(targetDir, info)
            return
        }

        Toast.makeText(this, R.string.installer_unpacking, Toast.LENGTH_SHORT).show()

        scope.launch {
            val ok = withContext(Dispatchers.IO) { unpack(info, targetDir, marker) }
            if (ok) {
                launchGame(targetDir, info)
            } else {
                Toast.makeText(
                    this@InstallerBootstrapActivity,
                    R.string.installer_unpack_failed,
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    /** 解包 APK 内 assets 到目标目录。 */
    private fun unpack(info: InstallerInfo, targetDir: File, marker: File): Boolean = runCatching {
        val prefix = info.assetPrefix.trimEnd('/') + "/"
        targetDir.mkdirs()

        // 清掉旧内容，避免残留
        targetDir.listFiles()
            ?.filter { it.name != marker.name }
            ?.forEach { it.deleteRecursively() }

        ZipFile(applicationInfo.sourceDir).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.startsWith(prefix) && !it.isDirectory }
                .toList()

            entries.forEach { entry ->
                val rel = entry.name.removePrefix(prefix)
                if (rel.isEmpty() || rel.contains("..")) return@forEach
                val out = File(targetDir, rel)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
        }

        marker.writeText(SCHEMA_VERSION.toString())
        true
    }.getOrDefault(false)

    /** 构造 ScanGame 直接启动，不经过游戏库。 */
    private fun launchGame(dir: File, info: InstallerInfo) {
        val launchTarget = info.launchTarget.ifBlank { inferLaunchTarget(dir) }
        val game = ScanGame(
            title = info.title.ifBlank { dir.name },
            uri = Uri.fromFile(dir).toString(),
            engine = EngineType.KIRIKIRI,
            launchTarget = launchTarget,
        )

        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { EngineLauncher.launch(this@InstallerBootstrapActivity, game) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Toast.makeText(
                    this@InstallerBootstrapActivity,
                    getString(R.string.installer_launch_failed_reason, e.message.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }

            when (result) {
                is LaunchResult.Success -> finish()
                is LaunchResult.Failure.AllFilesAccessRequested -> finish()
                else -> {
                    Toast.makeText(
                        this@InstallerBootstrapActivity,
                        getString(
                            R.string.installer_launch_failed_reason,
                            result.javaClass.simpleName,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
            }
        }
    }

    /** 推断启动文件：startup.tjs 优先，其次第一个 xp3。 */
    private fun inferLaunchTarget(dir: File): String {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        files.firstOrNull { it.name.equals("startup.tjs", ignoreCase = true) }
            ?.let { return it.name }
        files.firstOrNull { it.name.endsWith(".xp3", ignoreCase = true) }
            ?.let { return it.name }
        return ""
    }

    private fun toMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
        // 保持原启动体验：不播放额外转场
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 直装包元数据。 */
    private data class InstallerInfo(
        val title: String,
        val engine: String,
        val launchTarget: String,
        val assetPrefix: String,
    ) {
        companion object {
            fun parse(json: String): InstallerInfo? = runCatching {
                val o = JSONObject(json)
                InstallerInfo(
                    title = o.optString("title"),
                    engine = o.optString("engine", "KIRIKIRI"),
                    launchTarget = o.optString("launchTarget"),
                    assetPrefix = o.optString("assetPrefix", "assets/game"),
                )
            }.getOrNull()
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}
