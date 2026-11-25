package com.lofiroom.tv.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.lofiroom.tv.custom.CustomLibrary
import com.lofiroom.tv.custom.ImportGuard
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * #23 局域网 H5 管理页服务：NanoHTTPD 内嵌 HTTP。
 * - 全部路由挂在 /t/{token}/ 下（token 由 MainActivity 持久化生成），裸路径一律 404
 * - 上传走 multipart（NanoHTTPD parseBody 落临时文件，落库即拷贝，响应后临时文件自动清理）
 * - 状态类接口主线程桥接见 [Host]，库变更直接调 [CustomLibrary]（线程安全）
 */
class LanServer(
    private val context: Context,
    private val host: Host,
    private val token: String,
) {

    /** 与 UI/播放器的桥（MainActivity 在主线程消费） */
    interface Host {
        fun currentPlaying(): Boolean
        fun currentTrackIndex(): Int
        fun currentWallpaperIndex(): Int
        fun applyWallpaper(index: Int)
        fun playTrack(index: Int)

        /** 有人扫码打开了 H5 管理页（index.html 被请求），UI 侧收起菜单/遮罩便于预览 */
        fun onManagerOpened()
    }

    var port = 0
        private set
    private var server: Impl? = null

    val isRunning: Boolean get() = server != null

    /** 8788 起试端口，占用则顺延（8788..8792） */
    fun start(): Boolean {
        for (p in START_PORT..LAST_PORT) {
            try {
                val s = Impl(p)
                s.start(SOCKET_READ_TIMEOUT, false)
                server = s
                port = p
                return true
            } catch (e: Exception) {
                // 端口占用，试下一个
            }
        }
        return false
    }

    fun stop() {
        server?.stop()
        server = null
    }

    /** 扫码地址（局域网 IP 由 MainActivity 解析后传入） */
    fun url(ip: String) = "http://$ip:$port/t/$token/"

    // ── NanoHTTPD 实现 ─────────────────────────────────────

    // P0 连接配额：NanoHTTPD 每连接一线程且无上限，靠入口计数闸控制瞬时并发
    private val activeConns = AtomicInteger()
    private val ipConns = ConcurrentHashMap<String, AtomicInteger>()

    private inner class Impl(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            // P0 全局并发闸：超上限立即 503，不进业务逻辑；finally 递减保证计数复位
            if (activeConns.incrementAndGet() > MAX_CONCURRENT) {
                activeConns.decrementAndGet()
                return busy()
            }
            // P0 单 IP 并发闸：NanoHTTPD 不暴露 accept 钩子，在 serve 入口按 remoteIpAddress 计数，
            // 超限请求在 parseBody/业务逻辑之前被拒（效果等价 accept 层限流）。LAN 客户端 IP 数有限，条目常驻避免计数竞态
            val ip = session.remoteIpAddress ?: "unknown"
            // putIfAbsent（API 1）替代 computeIfAbsent（API 24）：minSdk 21 不能用后者
            val ipCnt = ipConns[ip] ?: run {
                val created = AtomicInteger()
                ipConns.putIfAbsent(ip, created) ?: created
            }
            if (ipCnt.incrementAndGet() > MAX_CONCURRENT_PER_IP) {
                ipCnt.decrementAndGet()
                activeConns.decrementAndGet()
                return busy()
            }
            try {
                // P2-1 Referrer-Policy：管理页外跳不带 Referer，/t/<token>/ 不经 Referer 外泄
                return route(session).apply { addHeader("Referrer-Policy", "no-referrer") }
            } catch (e: Exception) {
                // P2：异常细节（内部路径/类名）只留本地日志，对外固定文案
                Log.w(TAG, "serve error", e)
                return json(Status.INTERNAL_ERROR, JSONObject().put("error", "internal error"))
            } finally {
                ipCnt.decrementAndGet()
                activeConns.decrementAndGet()
            }
        }
    }

    private fun route(session: IHTTPSession): Response {
        val prefix = "/t/$token"
        val uri = session.uri
        if (uri != prefix && !uri.startsWith("$prefix/")) return plain(Status.NOT_FOUND, "not found")
        val path = uri.substring(prefix.length).trimStart('/')
        val method = session.method

        if (method == Method.OPTIONS) return corsPreflight()
        // P2-1 跨站写闸：POST/PATCH/DELETE 的 Origin/Referer 必须等于本站源（防恶意网页借用户浏览器改库）
        if (method == Method.POST || method == Method.PATCH || method == Method.DELETE) {
            if (!sameOriginOk(session)) return json(Status.FORBIDDEN, JSONObject().put("error", "forbidden"))
        }
        if (path.isEmpty() && method == Method.GET) {
            host.onManagerOpened() // 扫码进入管理页 → 通知 UI 收起菜单/二维码遮罩
            return indexHtml()
        }

        val seg = path.split('/')
        // api/thumbs/wall/{id}
        if (seg.size == 4 && seg[0] == "api" && seg[1] == "thumbs" && seg[2] == "wall" && method == Method.GET) {
            return thumb(seg[3].toIntOrNull() ?: return bad("id 无效"))
        }
        when {
            path == "api/state" && method == Method.GET -> return state()
            path == "api/export" && method == Method.GET -> return export(session)
            path == "api/import" && method == Method.POST -> return importBackup(session)
            path == "api/wallpapers" && method == Method.GET -> return wallpapers()
            path == "api/wallpapers" && method == Method.POST -> return uploadWallpaper(session)
            path == "api/tracks" && method == Method.GET -> return tracks()
            path == "api/tracks" && method == Method.POST -> return uploadTrack(session)
        }
        // api/wallpapers/{id}/apply · api/wallpapers/{id} · api/tracks/{id}[/play|/move]
        if (seg.size >= 3 && seg[0] == "api") {
            val id = seg[2].toIntOrNull() ?: return bad("id 无效")
            return when {
                seg[1] == "wallpapers" && seg.size == 4 && seg[3] == "apply" && method == Method.POST -> {
                    if (id !in CustomLibrary.wallpaperNames.indices) bad("id 越界")
                    else {
                        host.applyWallpaper(id)
                        ok()
                    }
                }

                seg[1] == "wallpapers" && seg.size == 3 && method == Method.DELETE -> {
                    if (CustomLibrary.removeWallpaper(id)) ok() else bad("仅可删除自定义背景")
                }

                seg[1] == "tracks" && seg.size == 4 && seg[3] == "play" && method == Method.POST -> {
                    if (id !in CustomLibrary.tracks.indices) bad("id 越界")
                    else {
                        host.playTrack(id)
                        ok()
                    }
                }

                seg[1] == "tracks" && seg.size == 4 && seg[3] == "move" && method == Method.POST -> {
                    val dir = session.parms["dir"]?.toIntOrNull() ?: return bad("缺少 dir")
                    if (CustomLibrary.moveTrack(id, dir)) ok() else bad("仅可移动自定义曲目")
                }

                seg[1] == "tracks" && seg.size == 3 && method == Method.PATCH -> {
                    // P2：title/sub 限长 200（超长截断），防 MB 级字符串灌进 meta.json
                    val title = (session.parms["title"] ?: "").take(MAX_TEXT_LEN)
                    val sub = (session.parms["sub"] ?: "").take(MAX_TEXT_LEN)
                    if (CustomLibrary.renameTrack(id, title, sub)) ok() else bad("仅可重命名自定义曲目")
                }

                seg[1] == "tracks" && seg.size == 3 && method == Method.DELETE -> {
                    if (CustomLibrary.removeTrack(id)) ok() else bad("仅可删除自定义曲目")
                }

                else -> plain(Status.NOT_FOUND, "not found")
            }
        }
        return plain(Status.NOT_FOUND, "not found")
    }

    // ── 跨站请求闸（P2-1）──────────────────────────────────

    /** 从完整 URL 提取 scheme://authority（Referer 是整条 URL，要先摘源再比对） */
    private val originFromUrl = Regex("^https?://[^/?#]+", RegexOption.IGNORE_CASE)

    /**
     * P2-1 写方法跨站闸：Origin（有则优先）或 Referer 的源必须等于本站源（http://<Host 头>）。
     * - 恶意网页的请求带对方 Origin/Referer → 拒（CSRF：借用户浏览器 POST/DELETE/PATCH 改库）；
     * - "null" 等不透明源按不匹配拒绝；
     * - 两者皆无 = 非浏览器客户端（curl 等脚本），无 CSRF 面，放行——URL token 仍是必过凭证。
     */
    private fun sameOriginOk(session: IHTTPSession): Boolean {
        val self = "http://${session.headers["host"] ?: return false}"
        val origin = session.headers["origin"]
        if (origin != null) return origin.equals(self, ignoreCase = true)
        val referer = session.headers["referer"] ?: return true
        return originFromUrl.find(referer.trim())?.value.equals(self, ignoreCase = true)
    }

    // ── 页面 / 状态 ────────────────────────────────────────

    private fun indexHtml(): Response {
        val bytes = try {
            context.assets.open("h5/index.html").use { it.readBytes() }
        } catch (e: Exception) {
            return plain(Status.NOT_FOUND, "h5 missing")
        }
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong())
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun state(): Response {
        val ti = host.currentTrackIndex()
        val t = CustomLibrary.tracks.getOrNull(ti)
        return json(
            JSONObject()
                .put("playing", host.currentPlaying())
                .put("trackIndex", ti)
                .put("trackTitle", t?.title ?: "")
                .put("wallpaperIndex", host.currentWallpaperIndex())
                .put("count", CustomLibrary.tracks.size)
        )
    }

    private fun wallpapers(): Response {
        val arr = JSONArray()
        CustomLibrary.wallpaperNames.forEachIndexed { i, n ->
            arr.put(
                JSONObject()
                    .put("id", i)
                    // 缓存键：落盘名唯一（时间戳前缀）。H5 缩略图 URL 带上它做 cache-buster——
                    // 删除再上传后下标会漂移，若只按下标缓存，浏览器会把旧缩略图串给新图
                    .put("key", n)
                    .put("name", CustomLibrary.wallpaperDisplayName(i))
                    .put("builtin", !n.startsWith(CustomLibrary.CUSTOM_PREFIX))
            )
        }
        return json(JSONObject().put("wallpapers", arr))
    }

    private fun tracks(): Response {
        val arr = JSONArray()
        CustomLibrary.tracks.forEachIndexed { i, t ->
            arr.put(JSONObject().put("id", i).put("title", t.title).put("sub", t.sub).put("builtin", !t.isCustom))
        }
        return json(JSONObject().put("tracks", arr))
    }

    /** 壁纸缩略图（≤320px JPEG，cacheDir/thumbs/ 缓存） */
    private fun thumb(id: Int): Response {
        val names = CustomLibrary.wallpaperNames
        val name = names.getOrNull(id) ?: return bad("id 越界")
        val cacheDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val cached = File(cacheDir, name.replace('/', '_') + ".jpg")
        if (!cached.exists() || cached.length() == 0L) {
            val bmp = CustomLibrary.decodeWallpaper(name, 320) ?: return bad("解码失败")
            cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            bmp.recycle()
        }
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "image/jpeg", cached.inputStream(), cached.length())
        r.addHeader("Cache-Control", "max-age=3600")
        return r
    }

    /** 导出备份：自定义内容打包 zip 供手机下载。part: all 全量 / wall 照片包 / music 音乐包 */
    private fun export(session: IHTTPSession): Response {
        val part = when (session.parms["part"]) {
            "wall" -> "wall"; "music" -> "music"; else -> "all"
        }
        // P3-10：导出含用户自定义内容（隐私），只落应用私有 cache——外部缓存在 Android 9- 可被他 app 读取
        val tmpDir = context.cacheDir
        sweepStaleExports(tmpDir) // 兜底：下载中断时 close 不触发，清掉超过 1 小时的残留包
        val tmp = File.createTempFile("lofiroom-export", ".zip", tmpDir)
        try {
            tmp.outputStream().use { CustomLibrary.exportZip(it, part) }
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "export error", e) // P2：异常细节只留本地日志，不外泄内部路径/类名
            return bad("导出失败")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val label = when (part) {
            "wall" -> "lofiroom-wallpapers"; "music" -> "lofiroom-music"; else -> "lofiroom-backup"
        }
        // 响应流关闭（下载完成/中断）即删临时包，避免 cache 无限堆积
        val body = object : FilterInputStream(tmp.inputStream()) {
            override fun close() {
                super.close()
                tmp.delete()
            }
        }
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "application/zip", body, tmp.length())
        r.addHeader("Content-Disposition", "attachment; filename=\"$label-$stamp.zip\"")
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    /** 清理超过 1 小时的残留导出临时包（下载中断等场景流关闭未走到） */
    private fun sweepStaleExports(dir: File) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("lofiroom-export") && f.name.endsWith(".zip") && f.lastModified() < cutoff) {
                f.delete()
            }
        }
    }

    /** 导入备份：手机上传 zip → 校验 → 恢复。mode: replace 覆盖恢复（默认）/ merge 合并导入 */
    private fun importBackup(session: IHTTPSession): Response {
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (len > MAX_IMPORT_ZIP_BYTES + FORM_OVERHEAD) return payloadTooLarge()
        quotaGuard()?.let { return it } // P1 写盘前配额闸（临时落盘/解压均在写盘，提前拒收）
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val tmp = File(files["file"] ?: return bad("缺少文件字段 file"))
        if (tmp.length() > MAX_IMPORT_ZIP_BYTES) return payloadTooLarge()
        val replace = session.parms["mode"] != "merge"
        return try {
            val r = CustomLibrary.importZip(tmp.inputStream(), replace)
            json(
                JSONObject()
                    .put("ok", true)
                    .put("walls", r.walls)
                    .put("tracks", r.tracks)
                    .put("bytes", r.bytes)
                    .put("mode", if (replace) "replace" else "merge")
            )
        } catch (e: Exception) {
            // P2：细节只留本地日志。ImportGuard/CustomLibrary 的校验文案（zip 条目过多等）可回显；
            // 其包装异常（"导入失败：$e"）含内部类名/路径，收敛为固定文案
            Log.w(TAG, "importBackup error", e)
            val msg = e.message ?: "导入失败"
            bad(if (msg.startsWith("导入失败：")) "导入失败" else msg)
        }
    }

    // ── 上传 ────────────────────────────────────────────────

    private fun uploadWallpaper(session: IHTTPSession): Response {
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (len > MAX_WALL_BYTES + FORM_OVERHEAD) return payloadTooLarge()
        quotaGuard()?.let { return it } // P1 写盘前配额闸
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val tmp = File(files["file"] ?: return bad("缺少文件字段 file"))
        if (tmp.length() > MAX_WALL_BYTES) return payloadTooLarge()
        // P2：文件名限长 200，截断保留扩展名（否则会破坏后缀白名单判断）
        val origName = capFileName(session.parms["filename"]?.ifBlank { null } ?: tmp.name)
        val ext = origName.substringAfterLast('.', "").lowercase()
        if (ext !in WALL_EXTS) return bad("仅支持 jpg/png/webp")
        // BitmapFactory 边界校验：损坏文件直接拒收
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return bad("图片无法解析")
        val index = CustomLibrary.addWallpaper(tmp, origName)
        return if (index >= 0) {
            host.applyWallpaper(index) // 上传成功即切换到刚上传的图（TV 立即展示，H5 高亮同步跟上）
            json(JSONObject().put("ok", true).put("id", index))
        } else storageFull()
    }

    private fun uploadTrack(session: IHTTPSession): Response {
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (len > MAX_TRACK_BYTES + FORM_OVERHEAD) return payloadTooLarge()
        quotaGuard()?.let { return it } // P1 写盘前配额闸
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val tmp = File(files["file"] ?: return bad("缺少文件字段 file"))
        if (tmp.length() > MAX_TRACK_BYTES) return payloadTooLarge()
        // P2：文件名限长 200，截断保留扩展名（否则会破坏后缀白名单判断）
        val origName = capFileName(session.parms["filename"]?.ifBlank { null } ?: tmp.name)
        val ext = origName.substringAfterLast('.', "").lowercase()
        if (ext !in TRACK_EXTS) return bad("仅支持 mp3/m4a/wav/aac/flac/ogg")
        // P2：title/sub 限长 200（超长截断），防 MB 级字符串灌进 meta.json
        val index = CustomLibrary.addTrack(
            tmp,
            (session.parms["title"] ?: "").take(MAX_TEXT_LEN),
            (session.parms["sub"] ?: "").take(MAX_TEXT_LEN)
        )
        return if (index >= 0) json(JSONObject().put("ok", true).put("id", index))
        else storageFull()
    }

    // ── 响应工具 ────────────────────────────────────────────

    private fun ok() = json(JSONObject().put("ok", true))

    private fun bad(msg: String) = json(Status.BAD_REQUEST, JSONObject().put("error", msg))

    private fun payloadTooLarge() = json(Status.PAYLOAD_TOO_LARGE, JSONObject().put("error", "文件过大"))

    private fun storageFull(msg: String = "存储写入失败"): Response {
        val status = object : Response.IStatus {
            override fun getDescription() = "507 Insufficient Storage"
            override fun getRequestStatus() = 507
        }
        return json(status, JSONObject().put("error", msg))
    }

    // ── P0/P1 加固辅助 ─────────────────────────────────────

    /** P0 过载快拒：503 + Retry-After，让客户端退避 */
    private fun busy(): Response {
        val r = json(Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "server busy"))
        r.addHeader("Retry-After", "3")
        return r
    }

    /**
     * P1 写盘前配额闸：(a) 目标卷可用空间 < 256MB 拒收；(b) custom 壁纸+音乐现有总量 ≥ 1.5GB 拒收新增。
     * 判据取"现有状态"而非叠加上传量估算——replace 模式导入会整体替换旧内容，按增量估算会误拒恢复。
     * 返回 null 放行，否则为 507 响应（文案区分两种原因）。
     */
    private fun quotaGuard(): Response? {
        // 目标卷取 getExternalFilesDir（系统保证已创建；custom 目录首写前不存在，不能用它取可用空间）
        val volume = context.getExternalFilesDir(null) ?: context.filesDir
        if (volume.usableSpace < MIN_FREE_BYTES) return storageFull("存储空间不足（可用空间低于 256MB）")
        if (customBytes() >= CUSTOM_QUOTA_BYTES) return storageFull("自定义壁纸/音乐已达 1.5GB 配额上限")
        return null
    }

    /** custom 壁纸+音乐现有总量（镜像 CustomLibrary 的 rootDir 解析：external/custom 或内部 files/custom） */
    private fun customBytes(): Long {
        val root = context.getExternalFilesDir(null)?.resolve("custom") ?: File(context.filesDir, "custom")
        return dirBytes(File(root, "wallpapers")) + dirBytes(File(root, "music"))
    }

    private fun dirBytes(dir: File): Long {
        var total = 0L
        dir.listFiles()?.forEach { f -> total += if (f.isDirectory) dirBytes(f) else f.length() }
        return total
    }

    /** P2：文件名限长 200，截断时保留扩展名（防止截断破坏后缀白名单判断） */
    private fun capFileName(name: String): String {
        if (name.length <= MAX_TEXT_LEN) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot in 1 until name.length - 1) name.substring(dot) else ""
        return if (ext.length < MAX_TEXT_LEN) name.take(MAX_TEXT_LEN - ext.length) + ext else name.take(MAX_TEXT_LEN)
    }

    private fun json(o: JSONObject): Response = json(Status.OK, o)

    private fun json(status: Response.IStatus, o: JSONObject): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
        r.addHeader("Cache-Control", "no-store")
        // P1：H5 与 API 同源（同 host:port），不下发 ACAO 通配，避免跨域读接口
        return r
    }

    private fun plain(status: Response.IStatus, msg: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)
        // P1：同源无需 CORS，不下发 ACAO
        return r
    }

    private fun corsPreflight(): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Status.NO_CONTENT, "text/plain", "")
        // P1：删除 ACAO 通配（同源不需要）；保留其余预检头
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return r
    }

    companion object {
        private const val TAG = "LanServer"
        private const val START_PORT = 8788
        private const val LAST_PORT = 8792
        // P0：SO_TIMEOUT 是"每次 read"的超时——50s 时 slow-drip（每 40s 发 1 字节）即可长期占住连接线程，
        // 收紧到 10s 后滴注流必然断连。权衡：极慢 Wi-Fi 上大文件上传若 10s 内凑不出一次 read 会被切断，
        // 但 LAN 正常上传持续有数据到达（每次 read 重置计时），可接受
        private const val SOCKET_READ_TIMEOUT = 10_000
        private const val MAX_WALL_BYTES = 20L * 1024 * 1024
        private const val MAX_TRACK_BYTES = 100L * 1024 * 1024
        private const val MAX_IMPORT_ZIP_BYTES = 512L * 1024 * 1024 // 导入备份 zip 上限
        private const val FORM_OVERHEAD = 1L * 1024 * 1024 // multipart 边界/字段余量
        private const val MAX_CONCURRENT = 24 // P0 全局并发闸：防同网段海量连接把"每连接一线程"打到 ANR/线程 OOM
        private const val MAX_CONCURRENT_PER_IP = 8 // P0 单 IP 并发上限，防单一恶意设备吃满全局名额
        private const val MIN_FREE_BYTES = 256L * 1024 * 1024 // P1 写盘前可用空间下限，低于即拒收
        private const val CUSTOM_QUOTA_BYTES = 1536L * 1024 * 1024 // P1 custom 壁纸+音乐总量配额（1.5GB）
        private const val MAX_TEXT_LEN = 200 // P2 title/sub/filename 字符上限，防 MB 级字符串灌进 meta.json
        // 白名单唯一来源在 ImportGuard（导入 zip 与上传共用一套），此处只做别名，防止两处漂移
        private val WALL_EXTS = ImportGuard.WALL_EXTS
        private val TRACK_EXTS = ImportGuard.TRACK_EXTS

        /** 局域网 IPv4（非回环、接口启用），无网返回 null */
        /** #23 只认真局域网卡：wlan（WiFi）/eth（有线）/usb·rndis（USB 网络共享）/ap（热点）。
         *  蜂窝 rmnet（CGNAT 假内网）、隧道 tun/tap/wg（VPN 的 172.18.x 扫码不可达）一律不算 */
        private val LAN_IFACES = Regex("^(wlan|eth|usb|ap|rndis)")

        fun localIpv4(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && LAN_IFACES.containsMatchIn(it.name) }
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it is Inet4Address && !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
                .firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
