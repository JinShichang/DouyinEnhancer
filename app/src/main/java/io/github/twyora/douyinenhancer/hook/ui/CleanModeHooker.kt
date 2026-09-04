package io.github.twyora.douyinenhancer.hook.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
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
import io.github.twyora.douyinenhancer.utils.Method
import io.github.twyora.douyinenhancer.utils.getField
import io.github.twyora.douyinenhancer.utils.resolveMethod
import io.github.twyora.douyinenhancer.utils.toClass
import java.lang.ref.WeakReference

/**
 * 清爽模式（防烧屏模式 / OLED 救星）。
 *
 * 参考 dyoo 的思路：不删除组件，而是按“播放/暂停”状态整体隐藏/恢复悬浮控件容器。
 * - 播放/恢复/切到下一条自动播放 -> 隐藏顶栏、底栏、右侧互动栏、左下作者/文案/音乐等，仅保留视频与进度条；
 * - 暂停/播放结束 -> 全部恢复显示；
 * - 抖音冷启动首条视频的“控件后出现/单击不暂停”是抖音原生行为，与是否开启本功能无关。
 *
 * 触发源直接挂钩 BaseListFragmentPanel 上所有播放/暂停方法调用（方法名在 38.8.0 未混淆），
 * 并保留原有视频事件兜底；进入隐藏后还会延迟重扫，避免“顶栏/底栏后出现”漏隐藏。
 */
@HookOnMainProcess
object CleanModeHooker : YukiBaseHooker() {
    private const val TAG = "CleanModeHooker"

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !FastKVConfigManager.module.getBoolean(ModuleKey.DISABLE_VERBOSE_LOGS, false)

    // handleVideoEvent 的 videoType（内容流播放事件，作为兜底）
    private const val VIDEO_EVENT_TEXTURE_AVAILABLE = 0
    private const val VIDEO_EVENT_PAUSE_CLICK = 16
    private const val VIDEO_EVENT_PAUSE_1 = 45
    private const val VIDEO_EVENT_PAUSE_2 = 47
    private const val VIDEO_EVENT_RESUME_1 = 46
    private const val VIDEO_EVENT_RESUME_2 = 48

    // onVideoPlayerEvent 的 code（播放器状态事件，作为兜底）
    private const val PLAYER_EVENT_PAUSED = 4
    private const val PLAYER_EVENT_COMPLETED = 7

    private val hidePlayVideoTypes = setOf(VIDEO_EVENT_TEXTURE_AVAILABLE, VIDEO_EVENT_RESUME_1, VIDEO_EVENT_RESUME_2)
    private val showPlayVideoTypes = setOf(VIDEO_EVENT_PAUSE_CLICK, VIDEO_EVENT_PAUSE_1, VIDEO_EVENT_PAUSE_2)

    private const val HOST_PANEL_CLASS = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel"
    private const val HOST_AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"

    /** 播放/恢复类方法：调用即进入隐藏 */
    private val playMethods = listOf(
        Method("handlePlay", listOf(HOST_AWEME_CLASS)),
        Method("handlePlay", listOf(HOST_AWEME_CLASS, "boolean")),
        Method("handlePlay", listOf(HOST_AWEME_CLASS, "boolean", "boolean")),
        Method("tryResumePlay", emptyList()),
        Method("tryResumePlay", listOf("boolean")),
        Method("tryResumePlay", listOf(HOST_AWEME_CLASS)),
        Method("tryResumePlay", listOf(HOST_AWEME_CLASS, "boolean")),
        Method("tryResumePlayByOnResume", listOf("boolean")),
        Method("resumePlay", listOf(HOST_AWEME_CLASS)),
        Method("handleResumeP", null)
    )

    /** 暂停类方法：调用即恢复显示 */
    private val pauseMethods = listOf(
        Method("handlePause", listOf("boolean")),
        Method("pausePlayer", emptyList()),
        Method("pausePlayerWithListener", emptyList()),
        Method("pauseCurrentPlayerWithListener", emptyList())
    )

    /** 需要整体隐藏的悬浮控件容器（类名在抖音 38.8.0 未混淆） */
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rehideRunnable = Runnable {
        if (cleanHidden) {
            applyOverlayVisibility(hidden = true, log = false)
        }
    }

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

    private fun installPlaybackStateHooks() {
        val classLoader = packageInstance.baseListFragmentPanel.selfClass?.classLoader
        if (classLoader == null) {
            YLog.error("$TAG: unable to resolve host class loader")
            return
        }
        val panelClass = HOST_PANEL_CLASS.toClass(classLoader)

        playMethods.forEach { method ->
            panelClass.resolveMethod(method)?.hook {
                after {
                    applyCleanMode(hidden = true)
                }
            }?.result {
                onConductFailure { _, throwable ->
                    YLog.error("$TAG: failed to hook ${method.name} for clean mode", throwable)
                }
                onHookingFailure { throwable ->
                    YLog.error("$TAG: failed to hook ${method.name} for clean mode", throwable)
                }
            }
        }

        pauseMethods.forEach { method ->
            panelClass.resolveMethod(method)?.hook {
                after {
                    applyCleanMode(hidden = false)
                }
            }?.result {
                onConductFailure { _, throwable ->
                    YLog.error("$TAG: failed to hook ${method.name} for clean mode", throwable)
                }
                onHookingFailure { throwable ->
                    YLog.error("$TAG: failed to hook ${method.name} for clean mode", throwable)
                }
            }
        }

        // 兜底：视频事件
        packageInstance.baseListFragmentPanel.selfClass?.resolveMethod(
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

        // 兜底：播放器状态事件（暂停/结束）
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
        applyOverlayVisibility(hidden, log = true)
        if (hidden) {
            mainHandler.removeCallbacks(rehideRunnable)
            mainHandler.postDelayed(rehideRunnable, 600L)
            mainHandler.postDelayed(rehideRunnable, 2000L)
        }
    }

    private fun applyOverlayVisibility(hidden: Boolean, log: Boolean) {
        val activity = mainActivityRef?.get()
        if (activity == null) {
            if (log) {
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
        var changed = 0
        overlayViews.forEach { view ->
            if (view.visibility != targetVisibility) {
                view.visibility = targetVisibility
                changed++
            }
        }
        if (log || changed > 0) {
            YLog.debug("$TAG: clean mode ${if (hidden) "hidden" else "shown"}, $changed views changed, ${overlayViews.size} total")
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
