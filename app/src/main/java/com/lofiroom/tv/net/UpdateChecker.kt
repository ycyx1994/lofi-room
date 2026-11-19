package com.lofiroom.tv.net

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 最新版本信息（更新源 = 可选静态镜像 + GitHub Releases，apk 多候选源顺序降级） */
data class ReleaseInfo(
    val versionName: String,   // "1.8"（tag 去掉 v 前缀，已过白名单）
    val tagName: String,       // "v1.8"
    val notes: String,         // release 说明（展示用）
    val apkUrls: List<String>, // 候选下载源，顺序尝试：镜像 → 固定名直链 → API 资产
    val apkSize: Long,         // 字节（0 = 未知）
)

/**
 * 应用内更新检查：多源拉取最新版本、版本号白名单校验、断点续传下载、卡死换源、安装前三道闸。
 * 全部为阻塞 IO —— 调用方负责放后台线程，结果回主线程。
 *
 * 版本源设计（参照 aerial-tv：版本信息尽量不依赖单一可达性差的端点）：
 *   ① 静态镜像 version.txt（国内可达、无 API 限流；[MIRROR_BASE] 未配置时自动跳过）
 *   ② GitHub API（兜底）
 * 下载源顺序：镜像 APK → 固定名直链 lofiroom-latest.apk（release.yml 同名资产）→ API 资产直链。
 */
object UpdateChecker {

    // ── 版本源 ─────────────────────────────────────────────
    /** 静态镜像站根地址，形如 "https://[MIRROR_HOST]/lofiroom"；留空 = 未部署镜像，自动跳过 ① */
    private const val MIRROR_BASE = "https://pub-099f4cba787b49ee8c5b91f97b51bb2b.r2.dev/lofiroom"
    private const val GITHUB_API =
        "https://api.github.com/repos/ycyx1994/lofi-room/releases/latest"
    /** 固定名直链：release 流程同时上传 lofiroom-latest.apk，可脱离 API 直接取包 */
    private const val DIRECT_APK =
        "https://github.com/ycyx1994/lofi-room/releases/latest/download/lofiroom-latest.apk"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    // ── 下载健壮性（参照 aerial-tv：家庭宽带断流 + 运营商限速路由） ──
    private const val RESUME_TRIES = 3            // 同源最多续传次数（须已有部分字节）
    private const val STALL_MS = 30_000L          // 卡死判定窗口
    private const val STALL_BYTES = 256L * 1024   // 窗口内字节增量低于此值判 stalled → 换源

    /** 版本号白名单：去 v 前缀后只接受点分数字（防劫持页/脏数据冒充版本号） */
    private val VERSION_RE = Regex("^v?(\\d+(\\.\\d+){0,3})$")

    /** 校验并归一化版本串；非法（HTML/劫持页/空）返回 null，按检测失败处理 */
    fun parseVersion(raw: String): String? =
        VERSION_RE.matchEntire(raw.trim())?.groupValues?.get(1)

    /** 数值版本比较：latest > current 时返回 true（有更新）。"1.10" > "1.9" */
    fun hasUpdate(current: String, latest: String): Boolean {
        val c = current.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val l = latest.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(c.size, l.size)) {
            val cv = c.getOrNull(i) ?: 0
            val lv = l.getOrNull(i) ?: 0
            if (cv != lv) return cv < lv
        }
        return false
    }

    // ── 检测 ───────────────────────────────────────────────

    /** 拉取最新 release（镜像优先，失败降级 GitHub API）；网络/解析/白名单失败返回 null */
    fun fetchLatest(): ReleaseInfo? = fetchFromMirror() ?: fetchFromGithub()

    private fun fetchFromMirror(): ReleaseInfo? {
        if (MIRROR_BASE.isEmpty()) return null
        val raw = httpText("$MIRROR_BASE/version.txt") ?: return null
        val version = parseVersion(raw) ?: return null // 非法响应按失败处理 → 降级 GitHub
        val notes = httpText("$MIRROR_BASE/notes.txt") ?: ""
        return ReleaseInfo(
            versionName = version,
            tagName = "v$version",
            notes = notes,
            apkUrls = listOf("$MIRROR_BASE/lofiroom-latest.apk", DIRECT_APK),
            apkSize = 0L,
        )
    }

    private fun fetchFromGithub(): ReleaseInfo? {
        val body = httpText(GITHUB_API) ?: return null
        return try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            val version = parseVersion(tag) ?: return null // 版本号白名单
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl = ""
            var apkSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size")
                    break
                }
            }
            if (apkUrl.isEmpty()) return null
            val urls = mutableListOf(DIRECT_APK) // 固定名直链优先（少一次 302 跳转、不依赖 API 限流）
            if (apkUrl !in urls) urls.add(apkUrl)
            ReleaseInfo(
                versionName = version,
                tagName = tag,
                notes = json.optString("body"),
                apkUrls = urls,
                apkSize = apkSize,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun httpText(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "LofiRoom-TV")
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    // ── 下载 ───────────────────────────────────────────────

    /** 代际文件名派生：`update-1.8.apk` + tag="g3" → `update-1.8-g3.apk`；tag 为空 = 原样返回 */
    private fun tagged(target: File, tag: String?): File =
        if (tag.isNullOrBlank()) target
        else File(target.parentFile, "${target.nameWithoutExtension}-$tag.${target.extension}")

    /** 本代落地文件路径（调用方与 [download] 用同一套派生规则，保证拿到的是同一个路径） */
    fun downloadTarget(dir: File, versionName: String, tag: String? = null): File =
        tagged(File(dir, "update-$versionName.apk"), tag)

    /**
     * 多源下载 APK 到 target；进度回调 0-100（-1 = 服务端未报总大小时回调 -1 一次）。
     * 断点续传：写 "<落地文件>.part"，Range 续传，206 才续、200 重下；同源最多 [RESUME_TRIES] 次。
     * 卡死检测：[STALL_MS] 窗口内字节增量 < [STALL_BYTES] 判 stalled，直接换下一个源。
     * cancelled() 返回 true 时中止并清理半成品。
     * 成功才 rename 为落地文件；失败保留 .part 供下次同版本续传（[cleanupStale] 负责过期清理），取消清掉。
     *
     * [tag] 为"下载代"后缀：非空时落地文件变成 `update-<ver>-<tag>.apk`、中间文件变成
     * `update-<ver>-<tag>.apk.part`，两次尝试（旧线程未退 vs 新线程）不再指向同一个 inode；
     * 传 null 保持老命名 `update-<ver>.apk` / `update-<ver>.apk.part`，老调用方行为不变。
     * 注意：tag 只影响文件名，不影响 [cleanupStale] —— 它按前缀 `update-` + 后缀 `.apk` / `.apk.part`
     * 匹配，带 tag 的残留同样会被清掉。
     */
    fun download(
        urls: List<String>,
        target: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false },
        tag: String? = null,      // 新增；null = 老命名
    ): Boolean {
        val out = tagged(target, tag)                  // tag 非空 → update-<ver>-<tag>.apk
        val part = File(out.absolutePath + ".part")    // 与 out 同代共存，跨代不共享写句柄
        for (url in urls) {
            var tries = 0
            while (tries < RESUME_TRIES) {
                if (cancelled()) {
                    part.delete()
                    return false
                }
                when (downloadOnce(url, part, onProgress, cancelled)) {
                    Outcome.DONE -> {
                        // 收尾前再核一次取消：已被作废的代不许落地，免得覆盖/删除新代的同名产物
                        if (cancelled()) {
                            part.delete()
                            return false
                        }
                        if (out.exists()) out.delete()
                        val renamed = part.renameTo(out)
                        if (!renamed) part.delete()
                        return renamed
                    }

                    Outcome.CANCELLED -> {
                        part.delete()
                        return false
                    }

                    Outcome.STALLED -> break // 限速路由，续传也是慢死 → 换源

                    Outcome.FAILED -> {
                        tries++
                        if (part.length() == 0L) break // 无部分字节可续，换源
                    }
                }
            }
        }
        return false // 全源失败：保留 .part，下次同版本下载断点续传
    }

    /**
     * 启动清理：删掉过期的 update-*.apk / update-*.apk.part（非当前版本的残留）。
     * 按"前缀 update- + 后缀 .apk/.apk.part"匹配，因此 [download] 的带 tag 命名
     * （update-<ver>-<tag>.apk / .apk.part）同样在清理范围内；只保留无 tag 的本版本产物。
     */
    fun cleanupStale(dir: File, keepVersion: String) {
        val keep = "update-$keepVersion.apk"
        dir.listFiles()?.forEach { f ->
            val n = f.name
            if ((n.startsWith("update-") && n.endsWith(".apk") && n != keep) ||
                (n.startsWith("update-") && n.endsWith(".apk.part") && n != "$keep.part")
            ) {
                f.delete()
            }
        }
    }

    private enum class Outcome { DONE, FAILED, STALLED, CANCELLED }

    private fun downloadOnce(
        url: String,
        part: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): Outcome {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            val have = part.length()
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            when (conn.responseCode) {
                200 -> if (have > 0) part.delete() // 服务端不支持 Range，重下
                206 -> Unit                          // 断点续传
                else -> return Outcome.FAILED
            }
            val base = if (conn.responseCode == 206) have else 0L
            val total = resolveTotal(conn, base)
            if (total <= 0) onProgress(-1) // 大小未知：UI 转"下载中…"

            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, base > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = base
                    var lastPct = -2
                    var windowStart = System.currentTimeMillis()
                    var windowBytes = 0L
                    while (true) {
                        if (cancelled()) return Outcome.CANCELLED
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        windowBytes += n
                        // 卡死检测：窗口内字节太少 = 运营商限速路由挂着 TLS 每秒 1 字节
                        val now = System.currentTimeMillis()
                        if (now - windowStart >= STALL_MS) {
                            if (windowBytes < STALL_BYTES) return Outcome.STALLED
                            windowStart = now
                            windowBytes = 0
                        }
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    // 完整性：字节数必须对上（截断下载不让过）
                    return if (total > 0 && done != total) Outcome.FAILED else Outcome.DONE
                }
            }
        } catch (_: Exception) {
            return Outcome.FAILED
        } finally {
            conn?.disconnect()
        }
    }

    /** 解析应下载总字节：206 从 Content-Range 的 total，200 用 Content-Length */
    private fun resolveTotal(conn: HttpURLConnection, base: Long): Long {
        if (conn.responseCode == 206) {
            val cr = conn.getHeaderField("Content-Range") ?: return -1L
            val total = Regex("bytes \\d+-\\d+/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull()
            return total ?: -1L
        }
        return if (base == 0L) conn.contentLengthLongCompat() else -1L
    }

    private fun HttpURLConnection.contentLengthLongCompat(): Long =
        if (Build.VERSION.SDK_INT >= 24) contentLengthLong else contentLength.toLong()

    // ── 安装前三道闸（fail-closed：任何解析/比对异常一律拒收） ──

    /** 闸一：APK 可解析且包名 == 本应用（防装错包/防损坏包） */
    fun verify(context: Context, apk: File): Boolean = try {
        archiveInfo(context, apk)?.packageName == context.packageName
    } catch (_: Exception) {
        false
    }

    /** 闸二：签名与本机已装一致（同签名才能覆盖安装）；异常一律拒收，交给用户手动处理 */
    fun sameSigner(context: Context, apk: File): Boolean = try {
        @Suppress("DEPRECATION") // GET_SIGNING_CERTIFICATES 需 API 28，minSdk 21 用 GET_SIGNATURES 全版本可用
        val apkSig = context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?.signatures?.firstOrNull() ?: return false
        @Suppress("DEPRECATION")
        val ownSig = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures?.firstOrNull() ?: return false
        apkSig == ownSig // Signature.equals 比对证书字节
    } catch (_: Exception) {
        false
    }

    /** 闸三：归档 versionCode ≥ 当前安装（防镜像/CDN 未更新导致回滚） */
    fun notOlder(context: Context, apk: File): Boolean = try {
        val archive = archiveInfo(context, apk) ?: return false
        val own = context.packageManager.getPackageInfo(context.packageName, 0)
        versionCodeOf(archive) >= versionCodeOf(own)
    } catch (_: Exception) {
        false
    }

    private fun archiveInfo(context: Context, apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)

    private fun versionCodeOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
}
