package com.lofiroom.tv.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File

/**
 * 安装执行器：PackageInstaller Session 静默安装（Android 9+ 主路径）+ ACTION_VIEW 系统安装器降级。
 * 参照 aerial-tv 的降级链与超时策略：
 *   session commit → 8s 内系统应答 → 120s 静默装完成 → 失败/超时/确认页闪退(1.5s) → ACTION_VIEW。
 * 静默资格由系统判定（同包名更新 + 同签名 + 非降级 + 调用者是安装来源）；
 * 资格不足时系统回 STATUS_PENDING_USER_ACTION + confirm Intent → 拉起传统确认页。
 * listener 可空：为空时失败/取消走内部兜底（ACTION_VIEW），供无 UI 的演练钩子复用。
 */
object UpdateInstaller {

    interface Listener {
        /** 系统要求用户确认（已拉起传统确认页） */
        fun onWaitingUserAction() {}

        /** session 成功（成功时进程通常已被替换，多数设备走不到） */
        fun onSuccess() {}

        /** 用户取消（确认页返回 / STATUS_FAILURE_ABORTED） */
        fun onAborted() {}

        /** 已唤起 ACTION_VIEW 系统安装器 */
        fun onOpened() {}

        /** 安装失败且降级 ACTION_VIEW 也没走起来 */
        fun onFailed() {}
    }

    /** UpdateResultReceiver 的 action（manifest 中 exported=false） */
    const val ACTION_SESSION_RESULT = "com.lofiroom.tv.ACTION_SESSION_RESULT"

    private const val ANSWER_TIMEOUT_MS = 8_000L        // 等系统应答（session 已提交但无任何回执）
    private const val INSTALL_TIMEOUT_MS = 120_000L     // 静默装总超时
    private const val PAGE_DIED_MS = 1_500L             // 确认页闪退判定窗口（issue #1482 类固件）

    private val handler = Handler(Looper.getMainLooper())
    var listener: Listener? = null

    private var appContext: Context? = null
    private var apkFile: File? = null
    private var sessionId = -1
    private val abandoned = HashSet<Int>()   // 已放弃 session 的迟到回执过滤
    private var confirmLaunchedAt = 0L       // 确认页拉起时刻（0 = 未拉起）
    private var answered = false             // 是否收到过任何 session 回执

    fun canUseSession(): Boolean = Build.VERSION.SDK_INT >= 28

    /** 主入口：三道闸通过后的安装。返回 false = session 与 ACTION_VIEW 都没走起来。 */
    fun install(context: Context, apk: File): Boolean {
        val app = context.applicationContext
        appContext = app
        apkFile = apk
        if (canUseSession() && installBySession(app, apk)) return true
        return openSystemInstaller(app, apk)
    }

    // ── PackageInstaller Session（API 28+） ─────────────────

    private fun installBySession(context: Context, apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName) // 声明是更新自身（静默资格前提）
            if (Build.VERSION.SDK_INT >= 33)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER) // 按"其他来源"安装，避免 ACCESS_RESTRICTED_SETTINGS 风险
            if (Build.VERSION.SDK_INT >= 31)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED) // 允许静默
        }
        val id = try {
            installer.createSession(params) // 返回 session id（Int）
        } catch (_: Exception) {
            return false
        }
        try {
            val session = installer.openSession(id)
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateResultReceiver::class.java)
                .setAction(ACTION_SESSION_RESULT)
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, id, intent, flags)
            sessionId = id
            answered = false
            confirmLaunchedAt = 0L
            session.commit(pi.intentSender)
            scheduleTimeouts()
            return true
        } catch (_: Exception) {
            abandon(context, id)
            return false
        }
    }

    /** UpdateResultReceiver 回调（主线程）。 */
    fun onSessionResult(context: Context, status: Int, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        if (id in abandoned) return // 已放弃 session 的迟到回执，按 aerial-tv 规则忽略
        answered = true
        handler.removeCallbacks(answerTimeout)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    fallback("no confirm intent")
                    return
                }
                confirmLaunchedAt = System.currentTimeMillis()
                listener?.onWaitingUserAction()
                try {
                    context.startActivity(confirm.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (_: Exception) {
                    fallback("confirm launch failed")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                clearTimeouts()
                listener?.onSuccess()
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                clearTimeouts()
                abandon(context, id)
                listener?.onAborted()
            }

            else -> {
                // 静默装失败（CONFIRM 未弹/固件不支持/写盘失败等）→ 降级 ACTION_VIEW
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status=$status"
                fallback(msg)
            }
        }
    }

    /** MainActivity.onResume 调用：确认页 1.5s 内闪退 = 固件 bug → 放弃 session 走 ACTION_VIEW；
     *  >= 1.5s 回来 = 用户按 BACK 取消 → 等 STATUS_FAILURE_ABORTED 或安装超时兜底。 */
    fun onAppResumed() {
        if (confirmLaunchedAt == 0L) return
        val since = System.currentTimeMillis() - confirmLaunchedAt
        confirmLaunchedAt = 0L
        if (since < PAGE_DIED_MS) fallback("confirm page died")
    }

    // ── ACTION_VIEW 降级（C2，保留 FileProvider 路径） ────────

    private fun openSystemInstaller(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        listener?.onOpened()
        true
    } catch (_: Exception) {
        listener?.onFailed()
        false
    }

    /** 降级：放弃当前 session → 拉起系统安装器 */
    private fun fallback(reason: String) {
        clearTimeouts()
        appContext?.let { abandon(it, sessionId) }
        val apk = apkFile ?: run {
            listener?.onFailed()
            return
        }
        val ctx = appContext ?: run {
            listener?.onFailed()
            return
        }
        openSystemInstaller(ctx, apk)
    }

    private fun abandon(context: Context, id: Int) {
        if (id >= 0 && id !in abandoned) {
            abandoned.add(id)
            runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        }
        if (id == sessionId) sessionId = -1
    }

    // ── 超时 ───────────────────────────────────────────────

    private val answerTimeout = Runnable {
        if (!answered) fallback("session answer timeout")
    }
    private val installTimeout = Runnable {
        fallback("silent install timeout")
    }

    private fun scheduleTimeouts() {
        handler.removeCallbacks(answerTimeout)
        handler.removeCallbacks(installTimeout)
        handler.postDelayed(answerTimeout, ANSWER_TIMEOUT_MS)
        handler.postDelayed(installTimeout, INSTALL_TIMEOUT_MS)
    }

    private fun clearTimeouts() {
        handler.removeCallbacks(answerTimeout)
        handler.removeCallbacks(installTimeout)
    }
}
