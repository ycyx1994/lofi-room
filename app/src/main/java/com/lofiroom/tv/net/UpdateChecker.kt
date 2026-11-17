package com.lofiroom.tv.net

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Release 信息（更新源 = 仓库 Releases 页的 apk 资产） */
data class ReleaseInfo(
    val versionName: String, // "1.8"（tag 去掉 v 前缀）
    val tagName: String,     // "v1.8"
    val notes: String,       // release 说明（展示用）
    val apkUrl: String,      // browser_download_url
    val apkSize: Long,       // 字节
)

/**
 * 应用内更新检查：拉取 GitHub Releases 最新版本、比较版本号、下载 APK、校验签名。
 * 全部为阻塞 IO —— 调用方负责放后台线程，结果回主线程。
 */
object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/ycyx1994/lofi-room/releases/latest"

    /** 拉取最新 release；网络/解析失败返回 null */
    fun fetchLatest(): ReleaseInfo? {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "LofiRoom-TV")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isEmpty()) return null
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
            return ReleaseInfo(
                versionName = tag.removePrefix("v"),
                tagName = tag,
                notes = json.optString("body"),
                apkUrl = apkUrl,
                apkSize = apkSize,
            )
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

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

    /** 下载 APK 到 target；进度回调 0-100。失败自动删除半成品并返回 false */
    fun download(url: String, target: File, onProgress: (Int) -> Unit): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            // GitHub 资产 302 跳 objects.githubusercontent.com，HttpURLConnection 同协议自动跟随
            if (conn.responseCode != 200) return false
            val total = conn.contentLength
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            return true
        } catch (_: Exception) {
            target.delete()
            return false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 覆盖安装前置校验：APK 签名必须与本机已装签名一致，
     * 否则系统安装器必然报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，提前给可读提示。
     * 校验不了（异常）时放行，交给系统安装器判定。
     */
    fun signatureMatches(context: Context, apk: File): Boolean {
        return try {
            @Suppress("DEPRECATION") // GET_SIGNING_CERTIFICATES 需 API 28，minSdk 21 用 GET_SIGNATURES 全版本可用
            val apkSig = context.packageManager
                .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?.signatures?.firstOrNull() ?: return true
            @Suppress("DEPRECATION")
            val ownSig = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull() ?: return true
            apkSig == ownSig // Signature.equals 比对证书字节
        } catch (_: Exception) {
            true
        }
    }
}
