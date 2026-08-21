package com.bigeyes.tv.config

import androidx.media3.ui.AspectRatioFrameLayout

/**
 * 电视端大屏播放器全局控制与交互参数配置
 */
object TvPlayerConfig {

    /**
     * 控制面板与定时器配置
     */
    object Overlay {
        /** 控制面板无操作自动隐藏时间 (毫秒) */
        const val AUTO_HIDE_DELAY_MS = 5000L

        /** 播放进度与时钟刷新周期 (毫秒) */
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }

    /**
     * 固定步长快进/快退按钮配置
     */
    object QuickSeek {
        /** 快退按钮步长 (毫秒, 15秒) */
        const val REWIND_STEP_MS = 15000L

        /** 快进按钮步长 (毫秒, 15秒) */
        const val FORWARD_STEP_MS = 15000L
    }

    /**
     * 遥控器长按快速变速手势配置
     */
    object HoldingSpeed {
        /** 长按触发阈值 (毫秒) */
        const val TRIGGER_DELAY_MS = 280L

        /** 长按右键快进倍速 */
        const val FAST_FORWARD_SPEED = 3.0f

        /** 长按左键慢放倍速 */
        const val SLOW_MOTION_SPEED = 0.5f

        /** 快进 HUD 提示文字 */
        const val HUD_FAST_FORWARD_TEXT = "⏩ 3.0X 快进中"

        /** 慢放 HUD 提示文字 */
        const val HUD_SLOW_MOTION_TEXT = "⏪ 0.5X 慢放中"
    }

    /**
     * 进度条类鼠标滑动微调与时间气泡预览配置 (Scrubbing)
     */
    object Scrubbing {
        /** 松开按键后触发准确定位的防抖延迟 (毫秒) */
        const val COMMIT_DEBOUNCE_DELAY_MS = 600L

        /** 气泡预览淡出动画时长 (毫秒) */
        const val TOOLTIP_FADE_DURATION_MS = 200L

        /** 阶梯 1 最大持续按住时间 (毫秒) */
        const val STAGE_1_MAX_HOLD_MS = 1000L
        /** 阶梯 1 步长 (毫秒, 10秒) */
        const val STAGE_1_STEP_MS = 10000L

        /** 阶梯 2 最大持续按住时间 (毫秒) */
        const val STAGE_2_MAX_HOLD_MS = 2500L
        /** 阶梯 2 步长 (毫秒, 30秒) */
        const val STAGE_2_STEP_MS = 30000L

        /** 阶梯 3 最大持续按住时间 (毫秒) */
        const val STAGE_3_MAX_HOLD_MS = 4500L
        /** 阶梯 3 步长 (毫秒, 60秒 / 1分钟) */
        const val STAGE_3_STEP_MS = 60000L

        /** 阶梯 4 步长 (毫秒, 180秒 / 3分钟) */
        const val STAGE_4_STEP_MS = 180000L
    }

    /**
     * 播放倍速与画面比例选项配置
     */
    object PlaybackOptions {
        /** 循环切换的倍速列表 */
        val SPEED_OPTIONS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)

        /** 画面比例模式列表 */
        val ASPECT_RATIOS = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        )

        /** 画面比例模式显示名称 */
        val ASPECT_RATIO_NAMES = arrayOf("比例: 适应", "比例: 铺满", "比例: 裁剪", "比例: 16:9")
    }
}
