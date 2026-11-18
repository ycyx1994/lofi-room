package com.lofiroom.tv.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * PackageInstaller Session 结果回执（manifest 注册，exported=false；
 * PendingIntent 由本应用发出，系统安装器只负责触发）。
 * 仅做转发：状态机/降级链在 [UpdateInstaller.onSessionResult]。
 */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_SESSION_RESULT) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
        )
        UpdateInstaller.onSessionResult(context, status, intent)
    }
}
