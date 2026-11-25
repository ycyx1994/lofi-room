package com.lofiroom.tv.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 发布演练钩子（E3）：不经过网络下载，直接走"三道闸 → 安装"整条链路，验证多轮发布安全。
 * 用法（adb，仅 debug 包可用）：
 *   adb push update-test.apk /sdcard/Android/data/com.lofiroom.tv/files/updates/update-test.apk
 *   adb shell run-as com.lofiroom.tv am broadcast -a com.lofiroom.tv.UPDATE_TEST --ez update_test true
 * 可选 --es apk <绝对路径> 指定 APK。
 * 任何校验不过（包名/签名/版本回滚）直接丢弃——演练包也不会破坏已装应用。
 *
 * 仅 debug 源集编译：本文件位于 app/src/debug/java/，release 包不含此类，
 * 对应 receiver 声明也只写在 app/src/debug/AndroidManifest.xml 里。
 */
class UpdateTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 双保险：即便 debug 清单被误合并进非 debuggable 变体，也不执行安装链路
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!intent.getBooleanExtra("update_test", false)) return
        val app = context.applicationContext
        val explicit = intent.getStringExtra("apk")
        goAsync().let { pending ->
            Thread {
                try {
                    val apk = explicit?.let(::File)
                        ?: File(File(app.getExternalFilesDir(null) ?: app.filesDir, "updates"), "update-test.apk")
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
