# 游戏库只读共享接口（GameLibraryProvider）

供外部工具（如 Tyn Packager）读取 Tyranor-Next 已扫描并识别的游戏数据。

## 设计原则

- **只读**：仅实现 `query`，`insert` / `update` / `delete` 一律抛 `UnsupportedOperationException`；
  `call` 只响应 `version`、`capabilities` 两个只读方法。
- **不校验签名**：任何应用声明权限即可读取。
- **不重新扫描**：直接读 Tyranor 游戏库的 Room 数据库，与 App 内显示内容一致，
  **返回的都是"确实被检测到游戏"的目录**（`games` 表只存通过引擎识别的条目）。

## 契约

| 项 | 值 |
|---|---|
| authority | `${applicationId}.gamelibrary`，默认 `com.tyranor.next.gamelibrary` |
| 读权限 | `${applicationId}.permission.READ_GAME_LIBRARY`，protectionLevel = normal |
| 方言 | 无需特殊配置，声明权限即授予 |

> ⚠️ **不要硬编码包名**。Tyranor 的 applicationId 可被构建参数覆盖，
> 请按安装时实际包名推导（或遍历 `PackageManager.queryIntentContentProviders` 探测）。

### 路径

| 路径 | 内容 |
|---|---|
| `content://<authority>/games` | 全部游戏 |
| `content://<authority>/games/krkr` | 仅 Kirikiri 引擎（可直接做直装包） |
| `content://<authority>/roots` | 扫描根目录 |

### Query 参数（仅 games）

| 参数 | 说明 |
|---|---|
| `engine` | 按引擎过滤，值取 `EngineType.name`，可重复传 |
| `q` | 标题模糊搜索（忽略大小写） |
| `limit` | 返回条数上限，≤0 表示不限 |

### games 列

| 列 | 类型 | 说明 |
|---|---|---|
| `uri` | String | 游戏目录 SAF URI（**主键**） |
| `path` | String? | 游戏目录**真实文件路径**，可直接 `File()` 访问 |
| `root_uri` | String? | 所属扫描根 URI |
| `root_path` | String? | 所属扫描根真实路径 |
| `title` | String | 标题 |
| `engine` | String | `KIRIKIRI` / `ONS` / `TYRANO` … |
| `engine_display` | String | 显示名，如 `Kirikiri` |
| `launch_target` | String | 启动目标（通常为 .xp3 文件名） |
| `launch_file` | String? | 手动指定启动文件 |
| `cover_uri` | String? | 封面 URI |
| `cover_source` | String? | 封面来源（vndb/bangumi/local） |
| `metadata_title` | String? | 元数据标题 |
| `discovered_at` | Long | 发现时间 |
| `updated_at` | Long | 更新时间 |
| `last_opened_at` | Long | 最近打开，0 表示从未 |
| `is_manual` | Int | 1 = 手动添加（PC/安卓游戏），不参与扫描 |

### roots 列

`uri`、`root_path`、`display_name`、`added_at`、`last_scanned_at`

## 调用示例

```kotlin
// 1. 推导 authority（推荐用实际包名）
val authority = "$tyranorPkg.gamelibrary"

// 2. 读 Kirikiri 游戏
val uri = Uri.parse("content://$authority/games/krkr")
val cursor = contentResolver.query(
    uri,
    arrayOf("path", "title", "launch_target", "cover_uri"),
    null, null, null,
)
cursor?.use {
    while (it.moveToNext()) {
        val dirPath = it.getString(0)   // e.g. /storage/emulated/0/Games/xxx
        val title = it.getString(1)
        val files = File(dirPath).listFiles()   // 直接读，无需 SAF 授权
    }
}
```

## 注意事项

1. **目录访问**：优先使用 `path` 列（真实路径）。Tyn Packager 声明
   `MANAGE_EXTERNAL_STORAGE`（所有文件访问权限）即可直接 `File()` 读取，
   **不需要**再为 SAF URI 申请目录授权。`path` 为 null 时才需要回退到 `uri` + SAF。
2. **引擎范围**：当前直装包只支持 Kirikiri，故 `games/krkr` 为预过滤视图。
3. **版本协商**：`call("version")` 返回 `Bundle.version`，不兼容变更时递增；
   `call("capabilities")` 返回可用引擎与路径清单。
