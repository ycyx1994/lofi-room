package com.lofiroom.tv.net

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 最新版本信息（更新源 = 可选静态镜像 + GitHub Releases，apk 多候选源顺序降级） */
data class ReleaseInfo(
    val versionName: String,   // "1.8"（tag 去掉 v 前缀，已过白名单）
    val tagName: String,       // "v1.8"
    val notes: String,         // release 说明（展示用）
    val apkUrls: List<String>, // 候选下载源，顺序尝试：镜像 → 固定名直链 → API 资产
    val apkSize: Long,         // 字节（0 = 未知）
    val sha256: String? = null, // 期望 APK SHA-256（小写十六进制；null = 发布端未提供，暂不强制校验）
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

    // ── 资源上限（防异常源写满存储/撑爆内存） ──
    private const val APK_MAX_BYTES = 300L * 1024 * 1024  // APK 下载硬上限 300MB
    private const val TEXT_MAX_BYTES = 256L * 1024        // 文本响应读取上限 256KB（超出截断）
    private const val TAG = "UpdateChecker"

    // ── 下载健壮性（参照 aerial-tv：家庭宽带断流 + 运营商限速路由） ──
    private const val RESUME_TRIES = 3            // 同源最多续传次数（须已有部分字节）
    private const val STALL_MS = 30_000L          // 卡死判定窗口
    private const val STALL_BYTES = 256L * 1024   // 窗口内字节增量低于此值判 stalled → 换源

    /** 版本号白名单：去 v 前缀后只接受点分数字（防劫持页/脏数据冒充版本号） */
    private val VERSION_RE = Regex("^v?(\\d+(\\.\\d+){0,3})$")

    /** 64 位十六进制 SHA-256 行：裸哈希 / "sha256=<hex>" / sha256sum 的 "<hex>  文件名" 都收 */
    private val SHA256_LINE_RE = Regex("(?i)^(?:sha256[:=]\\s*)?([0-9a-f]{64})(?:\\s+\\S+)?$")

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

    /** 最近一次 [fetchLatest] 的发布信息：[download] 据此取声明大小/期望哈希做校验闸（调用方显式传参时以参数为准） */
    @Volatile
    private var lastRelease: ReleaseInfo? = null

    /** 拉取最新 release（镜像优先，失败降级 GitHub API）；网络/解析/白名单失败返回 null */
    fun fetchLatest(): ReleaseInfo? =
        (fetchFromMirror() ?: fetchFromGithub())?.also { lastRelease = it }

    private fun fetchFromMirror(): ReleaseInfo? {
        if (MIRROR_BASE.isEmpty()) return null
        val raw = httpText("$MIRROR_BASE/version.txt") ?: return null
        // version.txt 可附带一行哈希（sha256=<hex> / sha256sum 格式）：先摘出哈希，剩余行仍须全过版本白名单
        val (versionRaw, hashInTxt) = splitVersionAndHash(raw)
        val version = parseVersion(versionRaw) ?: return null // 非法响应按失败处理 → 降级 GitHub
        val notes = httpText("$MIRROR_BASE/notes.txt") ?: ""
        // 期望哈希：lofiroom-latest.apk.sha256 旁文件优先，其次 version.txt 哈希行；都缺 = null（不强制，见 [download]）
        val sha256 = httpText("$MIRROR_BASE/lofiroom-latest.apk.sha256")?.let { parseSha256(it) } ?: hashInTxt
        return ReleaseInfo(
            versionName = version,
            tagName = "v$version",
            notes = notes,
            apkUrls = listOf("$MIRROR_BASE/lofiroom-latest.apk", DIRECT_APK),
            apkSize = 0L,
            sha256 = sha256,
        )
    }

    /** 从文本里解析期望 SHA-256（第一行命中即取，小写十六进制）；无命中返回 null */
    private fun parseSha256(text: String): String? {
        for (line in text.lineSequence()) {
            val m = SHA256_LINE_RE.matchEntire(line.trim()) ?: continue
            return m.groupValues[1].lowercase()
        }
        return null
    }

    /**
     * version.txt 可附带一行哈希：摘出哈希行，其余行拼回版本文本交 [parseVersion] 白名单。
     * 多条哈希行 = 脏数据，不摘、按整页原文交白名单拒收（fail-closed → 降级 GitHub）。
     */
    private fun splitVersionAndHash(raw: String): Pair<String, String?> {
        var hash: String? = null
        val versionLines = mutableListOf<String>()
        for (line in raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val h = SHA256_LINE_RE.matchEntire(line)?.groupValues?.get(1)?.lowercase()
            if (h != null) {
                if (hash != null) return raw to null // 多条哈希行：整页不可信，交白名单拒收
                hash = h
            } else {
                versionLines.add(line)
            }
        }
        return versionLines.joinToString("\n") to hash
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
            conn.inputStream.use { it.readBounded(TEXT_MAX_BYTES) } // 版本/说明/哈希文本超 256KB 截断
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 读取输入流最多 [maxBytes] 字节即截断（防异常源塞爆内存）；UTF-8 解码返回 */
    private fun InputStream.readBounded(maxBytes: Long): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val n = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            bos.write(buf, 0, n)
            remaining -= n
        }
        return bos.toString("UTF-8")
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
     *
     * 大小双闸：以 min([declaredSize]/ReleaseInfo 声明大小, 硬上限 [APK_MAX_BYTES]) 为上限，
     * 声明缺失（0/未传）时只用硬上限；下载一旦超限即中断并删除 .part（不换源、不续传）。
     * 哈希闸：期望 SHA-256 存在时落地前强制校验，对不上按篡改/损坏处理（fail-closed 删 .part 终止）。
     */
    fun download(
        urls: List<String>,
        target: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false },
        tag: String? = null,             // 新增；null = 老命名
        declaredSize: Long = 0L,         // ReleaseInfo.apkSize；0/缺失 = 只用硬上限
        expectedSha256: String? = null,  // 期望哈希；null = 回退最近一次 fetchLatest 解析值
    ): Boolean {
        val out = tagged(target, tag)                  // tag 非空 → update-<ver>-<tag>.apk
        val part = File(out.absolutePath + ".part")    // 与 out 同代共存，跨代不共享写句柄
        // 大小上限双闸：min(声明大小, 300MB 硬上限)；声明缺失时用硬上限
        val declared = if (declaredSize > 0L) declaredSize else lastRelease?.apkSize ?: 0L
        val maxBytes = if (declared > 0L) minOf(declared, APK_MAX_BYTES) else APK_MAX_BYTES
        // 待发布端补哈希后可强制：镜像提供 lofiroom-latest.apk.sha256 / version.txt 哈希行时自动强制校验；
        // 发布端尚未补哈希时（wantSha = null）保持现状跳过校验，不给现有部署加硬依赖
        val wantSha = (expectedSha256 ?: lastRelease?.sha256)?.lowercase()
        for (url in urls) {
            var tries = 0
            while (tries < RESUME_TRIES) {
                if (cancelled()) {
                    part.delete()
                    return false
                }
                when (downloadOnce(url, part, maxBytes, onProgress, cancelled)) {
                    Outcome.DONE -> {
                        // 收尾前再核一次取消：已被作废的代不许落地，免得覆盖/删除新代的同名产物
                        if (cancelled()) {
                            part.delete()
                            return false
                        }
                        // 哈希闸（期望哈希存在才强制）：对不上按篡改/损坏处理，删 .part 终止
                        if (wantSha != null && sha256Of(part)?.equals(wantSha, true) != true) {
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

                    Outcome.OVER_LIMIT -> {
                        // 超限（声明大小/硬上限双闸任一触发）：中断整个下载并删 .part
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

    private enum class Outcome { DONE, FAILED, STALLED, CANCELLED, OVER_LIMIT }

    private fun downloadOnce(
        url: String,
        part: File,
        maxBytes: Long,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): Outcome {
        var conn: HttpURLConnection? = null
        try {
            // 续传残留本身已超上限：按超限中断（.part 由 download 的 OVER_LIMIT 分支删除）
            if (part.length() > maxBytes) return Outcome.OVER_LIMIT
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
            if (total > maxBytes) return Outcome.OVER_LIMIT // 声明总大小已超闸，直接中断
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
                        if (done > maxBytes) return Outcome.OVER_LIMIT // 实流超过上限，中断删 .part
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
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            // API 28+：GET_SIGNING_CERTIFICATES + SigningInfo，比对全部证书（不只 firstOrNull）
            val apkSigners = contentSigners(
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ) ?: return false
            val ownSigners = contentSigners(
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            ) ?: return false
            // 新包全部签名与本应用一致才放行：数量一致 + 双向包含（Signature.equals 比对证书字节），fail-closed
            apkSigners.size == ownSigners.size &&
                apkSigners.all { s -> ownSigners.any { it == s } } &&
                ownSigners.all { s -> apkSigners.any { it == s } }
        } else {
            @Suppress("DEPRECATION") // 低版本无 GET_SIGNING_CERTIFICATES，回退 GET_SIGNATURES 全版本可用
            val apkSig = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?.signatures?.firstOrNull() ?: return false
            @Suppress("DEPRECATION")
            val ownSig = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull() ?: return false
            apkSig == ownSig // Signature.equals 比对证书字节
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 取包的全部内容签名（SigningInfo.apkContentsSigners），hasMultipleSigners 分支取值语义：
     * true（多签名者联合签名 APK）→ 必须拿到全部签名者（≥2）整集合比对，不能只看首签；
     * false（单签名者，含证书轮换后的当前签名）→ 唯一内容签名（=1）。
     * 两分支同取全量列表交 [sameSigner] 做集合全等比对；签名信息缺失/与分支不符按不可信处理返回 null（fail-closed）。
     */
    @RequiresApi(Build.VERSION_CODES.P) // 仅由 sameSigner 的 API 28+ 分支调用（调用点已做 SDK_INT 守卫）
    private fun contentSigners(pi: PackageInfo?): List<Signature>? {
        val info = pi?.signingInfo ?: return null
        val signers = info.apkContentsSigners?.toList() ?: return null
        val multi = info.hasMultipleSigners() // true=多签名者联合签名；false=单签名者（含轮换后当前签名）
        return signers.takeIf { if (multi) it.size >= 2 else it.size == 1 }
    }

    /** 计算文件 SHA-256（小写十六进制）；IO 异常返回 null（调用方按 fail-closed 拒收） */
    private fun sha256Of(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** 闸三：归档 versionCode ≥ 当前安装（防镜像/CDN 未更新导致回滚） */
    fun notOlder(context: Context, apk: File): Boolean = try {
        val archive = archiveInfo(context, apk) ?: return false
        val own = context.packageManager.getPackageInfo(context.packageName, 0)
        val archiveVc = versionCodeOf(archive)
        val ownVc = versionCodeOf(own)
        if (archiveVc == ownVc) {
            // 同 versionCode 照旧放行（保持 `>=` 兼容语义）：同号重打包可构成回滚面，记录告警；
            // 待发布端补哈希后该面由 [download] 哈希闸兜住
            Log.w(TAG, "notOlder: 归档与本机 versionCode 相同($ownVc)，同 versionCode 重打包存在回滚面，放行并告警")
        }
        archiveVc >= ownVc
    } catch (_: Exception) {
        false
    }

    private fun archiveInfo(context: Context, apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)

    private fun versionCodeOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
}
