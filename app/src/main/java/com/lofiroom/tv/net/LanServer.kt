package com.lofiroom.tv.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lofiroom.tv.custom.CustomLibrary
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

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

    private inner class Impl(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response = try {
            route(session)
        } catch (e: Exception) {
            json(Status.INTERNAL_ERROR, JSONObject().put("error", e.toString()))
        }
    }

    private fun route(session: IHTTPSession): Response {
        val prefix = "/t/$token"
        val uri = session.uri
        if (uri != prefix && !uri.startsWith("$prefix/")) return plain(Status.NOT_FOUND, "not found")
        val path = uri.substring(prefix.length).trimStart('/')
        val method = session.method

        if (method == Method.OPTIONS) return corsPreflight()
        if (path.isEmpty() && method == Method.GET) return indexHtml()

        val seg = path.split('/')
        // api/thumbs/wall/{id}
        if (seg.size == 4 && seg[0] == "api" && seg[1] == "thumbs" && seg[2] == "wall" && method == Method.GET) {
            return thumb(seg[3].toIntOrNull() ?: return bad("id 无效"))
        }
        when {
            path == "api/state" && method == Method.GET -> return state()
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
                    val title = session.parms["title"] ?: ""
                    val sub = session.parms["sub"] ?: ""
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

    // ── 上传 ────────────────────────────────────────────────

    private fun uploadWallpaper(session: IHTTPSession): Response {
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (len > MAX_WALL_BYTES + FORM_OVERHEAD) return payloadTooLarge()
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val tmp = File(files["file"] ?: return bad("缺少文件字段 file"))
        if (tmp.length() > MAX_WALL_BYTES) return payloadTooLarge()
        val origName = session.parms["filename"]?.ifBlank { null } ?: tmp.name
        val ext = origName.substringAfterLast('.', "").lowercase()
        if (ext !in WALL_EXTS) return bad("仅支持 jpg/png/webp")
        // BitmapFactory 边界校验：损坏文件直接拒收
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return bad("图片无法解析")
        val index = CustomLibrary.addWallpaper(tmp, origName)
        return if (index >= 0) json(JSONObject().put("ok", true).put("id", index))
        else storageFull()
    }

    private fun uploadTrack(session: IHTTPSession): Response {
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (len > MAX_TRACK_BYTES + FORM_OVERHEAD) return payloadTooLarge()
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val tmp = File(files["file"] ?: return bad("缺少文件字段 file"))
        if (tmp.length() > MAX_TRACK_BYTES) return payloadTooLarge()
        val origName = session.parms["filename"]?.ifBlank { null } ?: tmp.name
        val ext = origName.substringAfterLast('.', "").lowercase()
        if (ext !in TRACK_EXTS) return bad("仅支持 mp3/m4a/wav/aac/flac/ogg")
        val index = CustomLibrary.addTrack(
            tmp,
            session.parms["title"] ?: "",
            session.parms["sub"] ?: ""
        )
        return if (index >= 0) json(JSONObject().put("ok", true).put("id", index))
        else storageFull()
    }

    // ── 响应工具 ────────────────────────────────────────────

    private fun ok() = json(JSONObject().put("ok", true))

    private fun bad(msg: String) = json(Status.BAD_REQUEST, JSONObject().put("error", msg))

    private fun payloadTooLarge() = json(Status.PAYLOAD_TOO_LARGE, JSONObject().put("error", "文件过大"))

    private fun storageFull(): Response {
        val status = object : Response.IStatus {
            override fun getDescription() = "507 Insufficient Storage"
            override fun getRequestStatus() = 507
        }
        return json(status, JSONObject().put("error", "存储写入失败"))
    }

    private fun json(o: JSONObject): Response = json(Status.OK, o)

    private fun json(status: Response.IStatus, o: JSONObject): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun plain(status: Response.IStatus, msg: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun corsPreflight(): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Status.NO_CONTENT, "text/plain", "")
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return r
    }

    companion object {
        private const val START_PORT = 8788
        private const val LAST_PORT = 8792
        private const val SOCKET_READ_TIMEOUT = 50_000 // 大文件 Wi-Fi 上传留足读超时
        private const val MAX_WALL_BYTES = 20L * 1024 * 1024
        private const val MAX_TRACK_BYTES = 100L * 1024 * 1024
        private const val FORM_OVERHEAD = 1L * 1024 * 1024 // multipart 边界/字段余量
        private val WALL_EXTS = setOf("jpg", "jpeg", "png", "webp")
        private val TRACK_EXTS = setOf("mp3", "m4a", "wav", "aac", "flac", "ogg")

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
