package com.lofiroom.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UpdateScheduler 纯逻辑：新鲜度双档 / 失败退避 / 网络合并 / 时钟回拨 / 防抖 / MANUAL 旁路 */
class UpdateSchedulerTest {

    private var t = 1_000_000L
    private val s = UpdateScheduler(now = { t })

    private fun at(ms: Long) {
        t = ms
    }

    // ── 双档新鲜度（STARTUP 3h / PANEL_OPEN 10min） ──

    @Test
    fun startup_fresh_within_3h_then_runs() {
        s.checkedAt = t
        at(t + 60 * 60 * 1000L)                         // 1 小时后：仍在新鲜期
        assertEquals(UpdateScheduler.Decision.SKIP_FRESH, s.begin(UpdateScheduler.Trigger.STARTUP))
        at(t + 3 * 60 * 60 * 1000L + 1)                 // 3 小时 + 1ms：过期
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.STARTUP))
    }

    @Test
    fun panel_open_uses_10min_threshold() {
        s.checkedAt = t
        at(t + 5 * 60 * 1000L)                          // 5 分钟：面板档仍 fresh
        assertEquals(UpdateScheduler.Decision.SKIP_FRESH, s.begin(UpdateScheduler.Trigger.PANEL_OPEN))
        at(t + 10 * 60 * 1000L + 1)                     // 10 分钟 + 1ms：面板档过期
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.PANEL_OPEN))
    }

    @Test
    fun never_checked_runs_immediately() {
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.STARTUP))
    }

    // ── 失败退避序列 30s/2min/10min/30min 封顶 ──

    @Test
    fun backoff_sequence_then_capped_at_30min() {
        val expected = longArrayOf(30_000L, 120_000L, 600_000L, 1_800_000L, 1_800_000L)
        for (e in expected) {
            assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.STARTUP))
            s.onFailure()
            assertEquals(e, s.retryDelayMs())           // 退避截止前 = 满额等待
            assertEquals(UpdateScheduler.Decision.SKIP_BACKOFF, s.begin(UpdateScheduler.Trigger.STARTUP))
            at(t + e + 1)                               // 退避过期后可重试
        }
    }

    @Test
    fun success_clears_backoff_and_refreshes_checked_at() {
        s.begin(UpdateScheduler.Trigger.STARTUP)
        s.onFailure()
        at(t + 10)
        s.onSuccess()
        assertNull(s.retryDelayMs())                    // 成功即清退避
        assertEquals(t, s.checkedAt)                    // checkedAt 仅成功时推进
    }

    // ── 时钟回拨保护 ──

    @Test
    fun clock_rollback_not_treated_fresh() {
        s.checkedAt = t
        at(t - 60_000L)                                 // 墙钟被拨回 1 分钟
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.STARTUP))
    }

    // ── checking 防抖 + MANUAL 旁路 ──

    @Test
    fun checking_debounces_concurrent_triggers() {
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.STARTUP))
        assertEquals(UpdateScheduler.Decision.SKIP_CHECKING, s.begin(UpdateScheduler.Trigger.MANUAL))
        assertEquals(UpdateScheduler.Decision.SKIP_CHECKING, s.begin(UpdateScheduler.Trigger.NETWORK))
        s.onSuccess()                                   // 释放 in-flight
        assertEquals(UpdateScheduler.Decision.SKIP_FRESH, s.begin(UpdateScheduler.Trigger.STARTUP))
    }

    @Test
    fun manual_bypasses_backoff_and_freshness() {
        s.begin(UpdateScheduler.Trigger.STARTUP)
        s.onFailure()                                   // 进入 30s 退避
        assertEquals(UpdateScheduler.Decision.SKIP_BACKOFF, s.begin(UpdateScheduler.Trigger.STARTUP))
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.MANUAL)) // 退避不拦手动
        s.onSuccess()                                   // 手动成功 → checkedAt = t（新鲜期）
        assertEquals(UpdateScheduler.Decision.SKIP_FRESH, s.begin(UpdateScheduler.Trigger.STARTUP))
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.MANUAL)) // 新鲜度不拦手动
        s.onFailure()
    }

    // ── 网络恢复：2s 合并窗口 + 5s 最小间隔 ──

    @Test
    fun network_events_merged_in_settle_window() {
        assertTrue(s.onNetworkEvent())                  // 首次：排定 settle 定时器
        at(t + 500)
        assertFalse(s.onNetworkEvent())                 // settle 窗口内：合并，不重排
        at(t + 2_500)
        assertTrue(s.onNetworkEvent())                  // 窗口过后：新抖动重新排定
    }

    @Test
    fun network_trigger_enforces_min_interval() {
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.NETWORK))
        s.onSuccess()
        at(t + 1_000)
        assertEquals(UpdateScheduler.Decision.SKIP_INTERVAL, s.begin(UpdateScheduler.Trigger.NETWORK))
        at(t + 6_000)
        s.checkedAt = 0L                                // 清新鲜度闸，单测最小间隔闸
        assertEquals(UpdateScheduler.Decision.RUN, s.begin(UpdateScheduler.Trigger.NETWORK))
    }
}
