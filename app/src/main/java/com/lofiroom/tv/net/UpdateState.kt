package com.lofiroom.tv.net

/**
 * 应用内更新状态机（检测 → 下载 → 校验 → 安装 → 结果）。
 * 参照 aerial-tv 的 Install/Failure 状态机裁剪：
 * 失败终态带分类原因，UI（CUSTOM 页签更新行）由状态派生按钮文案/状态行/更新日志摘要。
 */
sealed interface UpdateState {

    /** 空闲：未检测或流程结束（按钮 = 检查更新） */
    data object Idle : UpdateState

    /** 检测中（手动/启动/面板/网络恢复/退避重试触发） */
    data object Checking : UpdateState

    /** 手动检测完成：当前已是最新 */
    data class UpToDate(val versionName: String) : UpdateState

    /** 发现新版（按钮切"下载 vX.Y"，附更新日志摘要） */
    data class Available(val versionName: String, val notes: String) : UpdateState

    /** 下载中；percent 0-100，-1 表示服务端未报总大小 */
    data class Downloading(val versionName: String, val percent: Int) : UpdateState

    /** 安装包校验中（三道闸：包名 / 签名 / versionCode） */
    data object Verifying : UpdateState

    /** 安装进行中（session 静默装或已唤起系统安装器） */
    data object Installing : UpdateState

    /** 刚完成自更新（lastUpdateTime 2 分钟内，展示本次更新日志） */
    data class Updated(val versionName: String, val notes: String) : UpdateState

    /** 失败终态（按钮回"检查更新"，可手动重试） */
    data class Failed(val reason: Reason) : UpdateState

    enum class Reason {
        Check,     // 检测失败：网络不可达 / 响应非法（正则白名单拒收）
        Download,  // 全部下载源失败（含断流续传耗尽、卡死换源后仍失败）
        Verify,    // 包名不一致 / 签名不一致 / APK 解析失败（fail-closed 拦截）
        NotNewer,  // 归档 versionCode 低于当前安装（镜像未更新导致的回滚，拦截）
        Install,   // session 与系统安装器均未能唤起
    }
}
