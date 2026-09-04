package io.github.twyora.douyinenhancer.hook.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.FastKVConfigManager
import io.github.twyora.douyinenhancer.config.key.CleanModeKey
import io.github.twyora.douyinenhancer.config.key.ModuleKey
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.getField
import io.github.twyora.douyinenhancer.utils.resolveMethod
import java.lang.ref.WeakReference

/**
 * 清爽模式（防烧屏模式 / OLED 救星）。
 *
 * 参考 dyoo 的实现思路：不清除组件，而是把播放页/主页上的悬浮控件容器按播放状态整体隐藏。
 * - 播放中：隐藏顶部标签栏、底部导航、右侧互动栏、左下作者/文案/音乐等，仅保留视频与进度条；
 * - 暂停（含播放结束）：恢复显示所有控件；
 * - 再次播放：再次隐藏。
 * 这样既能获得干净的观看体验，又能在暂停时正常进行点赞/评论/分享等操作，同时降低 OLED 烧屏风险。
 */
@HookOnMainProcess
object CleanModeHooker : YukiBaseHooker() {
    private const val TAG = "CleanModeHooker"

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !FastKVConfigManager.module.getBoolean(ModuleKey.DISABLE_VERBOSE_LOGS, false)

    // handleVideoEvent 的 videoType（内容流播放事件）
    private const val VIDEO_EVENT_TEXTURE_AVAILABLE = 0
    private const val VIDEO_EVENT_PAUSE_1 = 45
    private const val VIDEO_EVENT_PAUSE_2 = 47
    private const val VIDEO_EVENT_RESUME_1 = 46
    private const val VIDEO_EVENT_RESUME_2 = 48

    // onVideoPlayerEvent 的 code（播放器状态事件）
    private const val PLAYER_EVENT_PAUSED = 4
    private const val PLAYER_EVENT_COMPLETED = 7

    private val hidePlayVideoTypes = setOf(
        VIDEO_EVENT_TEXTURE_AVAILABLE,
        VIDEO_EVENT_RESUME_1,
        VIDEO_EVENT_RESUME_2
    )

    private val showPlayVideoTypes = setOf(VIDEO_EVENT_PAUSE_1, VIDEO_EVENT_PAUSE_2)

    /**
     * 需要整体隐藏的悬浮控件容器。
     * 顶部/底部为首页全局栏，其余为 feed 播放项内的控件容器（类名在抖音 38.8.0 未混淆）。
     */
    private val hideClassNames = listOf(
        "com.ss.android.ugc.aweme.homepage.ui.titlebar.MainTitleBar",
        "com.ss.android.ugc.aweme.homepage.ui.bottombar.MainBottomTabContainer",
        "com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView",
        "com.ss.android.ugc.aweme.feed.ui.AwemeIntroInfoLayout",
        "com.ss.android.ugc.aweme.feed.ui.musiccover.MusicCoverContainerLayout",
        "com.ss.android.ugc.aweme.feed.widget.feedbottommusicanchor.FeedBottomMusicAnchorLayout",
        "com.ss.android.ugc.aweme.feed.widget.MarqueeView"
    )

    private val classCache = HashMap<String, Class<*>?>()

    private var mainActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var cleanHidden = false

    override fun onHook() {
        if (!FastKVConfigManager.settings.getBoolean(CleanModeKey.MAIN_SWITCH, false)) {
            if (verbose) {
                YLog.debug("$TAG: clean mode disabled, skip hook")
            }
            return
        }
        installMainActivityCaptureHook()
        installPlaybackStateHooks()
    }

    private fun installMainActivityCaptureHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.mainActivity.selfClass?.resolveMethod(
            packageInstance.mainActivity.onResume()
        )?.hook {
            after {
                val activity = instance as? Activity ?: return@after
                mainActivityRef = WeakReference(activity)
                if (verbose) {
                    YLog.debug("$TAG: captured main activity ${activity::class.java.name}")
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to capture main activity", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook main activity", throwable)
            }
        }
    }

    private fun installPlaybackStateHooks(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.baseListFragmentPanel.selfClass?.resolveMethod(
            packageInstance.baseListFragmentPanel.handleVideoEvent()
        )?.hook {
            after {
                val videoEvent = args[0] ?: return@after
                val videoType = videoEvent.getField<Int>(
                    packageInstance.videoEvent.videoType()
                ) ?: return@after
                when {
                    videoType in hidePlayVideoTypes -> applyCleanMode(hidden = true)
                    videoType in showPlayVideoTypes -> applyCleanMode(hidden = false)
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to listen feed video events", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook feed video events", throwable)
            }
        }

        packageInstance.baseListFragmentPanel.selfClass?.resolveMethod(
            packageInstance.baseListFragmentPanel.onVideoPlayerEvent()
        )?.hook {
            after {
                val playerEvent = args[0] ?: return@after
                val code = playerEvent.getField<Int>(
                    packageInstance.videoPlayerEvent.code()
                ) ?: return@after
                if (code == PLAYER_EVENT_PAUSED || code == PLAYER_EVENT_COMPLETED) {
                    applyCleanMode(hidden = false)
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to listen player state events", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook player state events", throwable)
            }
        }
    }

    private fun applyCleanMode(hidden: Boolean) {
        if (hidden == cleanHidden) {
            return
        }
        cleanHidden = hidden

        val activity = mainActivityRef?.get()
        if (activity == null) {
            if (verbose) {
                YLog.debug("$TAG: main activity not ready, skip applying clean mode")
            }
            return
        }
        val classLoader = packageInstance.baseListFragmentPanel.selfClass?.classLoader
        val decorView = activity.window?.decorView
        if (classLoader == null || decorView == null) {
            YLog.error("$TAG: unable to resolve host class loader or decor view")
            return
        }

        val overlayViews = ArrayList<View>()
        collectOverlayViews(decorView, classLoader, overlayViews)
        val targetVisibility = if (hidden) View.GONE else View.VISIBLE
        overlayViews.forEach { view ->
            if (view.visibility != targetVisibility) {
                view.visibility = targetVisibility
            }
        }
        if (verbose) {
            YLog.debug("$TAG: clean mode ${if (hidden) "hidden" else "shown"} for ${overlayViews.size} overlay views")
        }
    }

    private fun collectOverlayViews(view: View, classLoader: ClassLoader, out: MutableList<View>) {
        if (matchesOverlayClass(view, classLoader)) {
            out.add(view)
        }
        if (view is ViewGroup) {
            val childCount = view.childCount
            for (i in 0 until childCount) {
                collectOverlayViews(view.getChildAt(i), classLoader, out)
            }
        }
    }

    private fun matchesOverlayClass(view: View, classLoader: ClassLoader): Boolean = hideClassNames.any { className ->
        val clazz = classCache.getOrPut(className) {
            runCatching {
                classLoader.loadClass(className)
            }.getOrNull()
        }
        clazz != null && clazz.isInstance(view)
    }
}
