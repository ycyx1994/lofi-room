package com.lofiroom.tv.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 发布演练钩子（E3）：不经过网络下载，直接走"三道闸 → 安装"整条链路，验证多轮发布安全。
 * 用法（adb）：
 *   adb push update-test.apk /sdcard/Android/data/com.lofiroom.tv/files/update-test.apk
 *   adb shell am broadcast -a com.lofiroom.tv.UPDATE_TEST --ez update_test true
 * 可选 --es apk <绝对路径> 指定 APK。
 * 任何校验不过（包名/签名/版本回滚）直接丢弃——演练包也不会破坏已装应用。
 */
class UpdateTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.getBooleanExtra("update_test", false)) return
        val app = context.applicationContext
        val explicit = intent.getStringExtra("apk")
        goAsync().let { pending ->
            Thread {
                try {
                    val apk = explicit?.let(::File)
                        ?: File(app.getExternalFilesDir(null) ?: app.filesDir, "update-test.apk")
                    if (!apk.isFile) return@Thread
                    // 三道闸：可解析+包名一致 / 签名一致 / 归档 versionCode ≥ 当前
                    val ok = UpdateChecker.verify(app, apk) &&
                        UpdateChecker.sameSigner(app, apk) &&
                        UpdateChecker.notOlder(app, apk)
                    if (ok) UpdateInstaller.install(app, apk)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
