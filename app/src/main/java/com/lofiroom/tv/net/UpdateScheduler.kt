package com.lofiroom.tv.net

/**
 * 更新检测调度（纯逻辑，无 Android 依赖，可 JVM 单测）。
 * 参照 aerial-tv UpdateCheck.kt 的频率/退避/防抖策略裁剪：
 *
 * 1. 双档新鲜度：启动/回前台 3 小时内不重复检测；面板打开（用户主动找新版）缩到 10 分钟；
 * 2. 失败退避：30s → 2min → 10min → 30min 封顶，成功清零；重试只在前台（调用方保证）；
 * 3. 网络恢复事件合并：settle 窗口内多条网络变化只排一次检测，且不推迟已到期的退避重试；
 * 4. 并发防抖：begin() 占住 in-flight 标志，检测在飞时后续触发直接跳过；
 * 5. 时钟回拨保护：age < 0（墙钟被拨回）不算 fresh，不会永久压制检测。
 */
class UpdateScheduler(
    private val now: () -> Long = System::currentTimeMillis,
    private val homeMaxAgeMs: Long = HOME_MAX_AGE_MS,
    private val panelMaxAgeMs: Long = PANEL_MAX_AGE_MS,
    private val retryDelays: LongArray = RETRY_DELAYS,
    private val networkSettleMs: Long = NETWORK_SETTLE_MS,
    private val networkMinIntervalMs:Long = NETWORK_MIN_INTERVAL_MS,
) {

    companion object {
        const val HOME_MAX_AGE_MS = 3 * 60 * 60 * 1000L          // 启动/回前台
        const val PANEL_MAX_AGE_MS = 10 * 60 * 1000L             // 打开控制面板
        val RETRY_DELAYS = longArrayOf(30_000L, 120_000L, 600_000L, 1_800_000L)
        const val NETWORK_SETTLE_MS = 2_000L                     // 网络事件合并窗口
        const val NETWORK_MIN_INTERVAL_MS = 5_000L               // 网络触发两次检测最小间隔
    }

    enum class Trigger { STARTUP, PANEL_OPEN, NETWORK, RETRY, MANUAL }

    enum class Decision { RUN, SKIP_CHECKING, SKIP_BACKOFF, SKIP_INTERVAL, SKIP_FRESH }

    /** 上次成功检测的墙钟时间（0 = 从未成功）；由调用方持久化（SharedPreferences "updates"/"checked_at"） */
    var checkedAt: Long = 0L

    var isChecking: Boolean = false
        private set

    private var failures = 0               // 连续失败次数（成功清零）
    private var backoffUntil = 0L          // 退避截止时刻
    private var lastNetworkEventAt = 0L    // 最近一次网络恢复事件（合并判据）
    private var lastNetworkAttemptAt = 0L  // 最近一次网络触发的检测（最小间隔判据）

    /**
     * 判定并占用检测：Decision.RUN 时已置 in-flight 标志，
     * 调用方必须在结束时调用 [onSuccess] / [onFailure] 释放。
     */
    fun begin(trigger: Trigger): Decision {
        val t = now()
        if (isChecking) return Decision.SKIP_CHECKING
        if (trigger != Trigger.MANUAL) {
            if (t < backoffUntil) return Decision.SKIP_BACKOFF
            if (trigger == Trigger.NETWORK && t - lastNetworkAttemptAt in 0 until networkMinIntervalMs)
                return Decision.SKIP_INTERVAL
            val threshold = if (trigger == Trigger.PANEL_OPEN) panelMaxAgeMs else homeMaxAgeMs
            val age = t - checkedAt
            // 时钟回拨（age < 0）不算 fresh：宁可多查一次，不永久压制
            if (checkedAt > 0 && age >= 0 && age < threshold) return Decision.SKIP_FRESH
        }
        isChecking = true
        if (trigger == Trigger.NETWORK) lastNetworkAttemptAt = t
        return Decision.RUN
    }

    /** 检测成功：刷新"已检测"时间戳并清空退避 */
    fun onSuccess() {
        isChecking = false
        failures = 0
        backoffUntil = 0L
        checkedAt = now()
    }

    /** 检测失败：进入下一档退避（30s/2min/10min/30min 封顶） */
    fun onFailure() {
        isChecking = false
        val delay = retryDelays[failures.coerceAtMost(retryDelays.size - 1)]
        failures++
        backoffUntil = now() + delay
    }

    /** 下次自动重试还需等待的毫秒数；null = 无待重试（从未失败 / 已成功） */
    fun retryDelayMs(): Long? {
        if (failures == 0) return null
        return (backoffUntil - now()).coerceAtLeast(0L)
    }

    /**
     * 网络恢复事件（固件可能反复抖动报同一变化）：
     * 返回是否应排定 settle 定时器——settle 窗口内只排一次，到点调 [begin]（Trigger.NETWORK）。
     */
    fun onNetworkEvent(): Boolean {
        val t = now()
        val schedule = t - lastNetworkEventAt >= networkSettleMs
        lastNetworkEventAt = t
        return schedule
    }
}
