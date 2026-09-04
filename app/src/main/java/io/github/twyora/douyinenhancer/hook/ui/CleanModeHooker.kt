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
 * 参考 dyoo：
 * - 播放/恢复/切页自动播放 -> 整体隐藏悬浮控件，同时进入沉浸（隐藏状态栏/导航栏），
 *   并把 feed 面板的顶/底 spacer 高度清零，让视频铺满全屏；
 * - 暂停/播放结束 -> 全部恢复（控件、状态栏、spacer）；
 * - 滚动切页期间不执行“恢复”，状态栏在清爽隐藏期间由看守逻辑保持不出现。
 */
@HookOnMainProcess
object CleanModeHooker : YukiBaseHooker() {
    private const val TAG = "CleanModeHooker"

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !FastKVConfigManager.module.getBoolean(ModuleKey.DISABLE_VERBOSE_LOGS, false)

    // handleVideoEvent 的 videoType（内容流播放事件，兜底）
    private const val VIDEO_EVENT_TEXTURE_AVAILABLE = 0
    private const val VIDEO_EVENT_PAUSE_CLICK = 16
    private const val VIDEO_EVENT_PAUSE_1 = 45
    private const val VIDEO_EVENT_PAUSE_2 = 47
    private const val VIDEO_EVENT_RESUME_1 = 46
    private const val VIDEO_EVENT_RESUME_2 = 48

    // onVideoPlayerEvent 的 code（播放器状态事件，兜底）
    private const val PLAYER_EVENT_PAUSED = 4
    private const val PLAYER_EVENT_COMPLETED = 7

    private val hidePlayVideoTypes = setOf(VIDEO_EVENT_TEXTURE_AVAILABLE, VIDEO_EVENT_RESUME_1, VIDEO_EVENT_RESUME_2)
    private val showPlayVideoTypes = setOf(VIDEO_EVENT_PAUSE_CLICK, VIDEO_EVENT_PAUSE_1, VIDEO_EVENT_PAUSE_2)

    /**
     * 沉浸标志位（含 layout 标志，避免隐藏系统栏时内容区重排导致抖动）：
     * LAYOUT_STABLE(256) | LAYOUT_FULLSCREEN(1024) | LAYOUT_HIDE_NAVIGATION(512) |
     * FULLSCREEN(4) | HIDE_NAVIGATION(2) | IMMERSIVE_STICKY(4096) = 5894
     */
    private const val IMMERSIVE_FLAGS = 5894

    // 状态栏/导航栏是否被隐藏的判定位：FULLSCREEN(4) | HIDE_NAVIGATION(2)
    private const val HIDDEN_BAR_FLAGS = 6

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

    /** 页面切换类方法：切到新页后进入隐藏（新视频会自动播放） */
    private val pageSelectedMethods = listOf(
        Method("onPageSelected", emptyList()),
        Method("onPageScrollStateChanged", listOf("int"))
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

    // 面板顶/底 spacer 的原始高度（按 view 的 identityHashCode 保存）
    private val spaceOriginalHeights = HashMap<Int, Int>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rehideRunnable = Runnable {
        if (cleanHidden) {
            applyOverlayVisibility(hidden = true, log = false)
        }
    }

    private var mainActivityRef: WeakReference<Activity>? = null
    private var panelRef: WeakReference<Any>? = null
    private var systemUiKeeperDecor: WeakReference<View>? = null
    private var systemUiKeeperListener: View.OnSystemUiVisibilityChangeListener? = null

    @Volatile
    private var cleanHidden = false

    /** 内容流正在滚动/切页：期间忽略“恢复显示”，避免状态栏闪出 */
    @Volatile
    private var pagerDragging = false

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
                if (cleanHidden) {
                    // 清爽隐藏期间回到前台时保持沉浸
                    applyOverlayVisibility(hidden = true, log = false)
                }
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
                    capturePanel(instance)
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
                    capturePanel(instance)
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

        pageSelectedMethods.forEach { method ->
            panelClass.resolveMethod(method)?.hook {
                after {
                    capturePanel(instance)
                    if (method.name == "onPageScrollStateChanged") {
                        val state = args[0] as? Int ?: return@after
                        pagerDragging = state != 0
                        if (state == 0) {
                            applyCleanMode(hidden = true)
                        }
                    } else {
                        applyCleanMode(hidden = true)
                    }
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
                capturePanel(instance)
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
                capturePanel(instance)
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

    private fun capturePanel(instance: Any?) {
        if (instance != null) {
            panelRef = WeakReference(instance)
        }
    }

    private fun applyCleanMode(hidden: Boolean) {
        if (!hidden && pagerDragging) {
            // 滚动切页期间不恢复，避免状态栏/控件闪出
            return
        }
        if (hidden == cleanHidden) {
            return
        }
        cleanHidden = hidden
        applyOverlayVisibility(hidden, log = true)
        mainHandler.removeCallbacks(rehideRunnable)
        if (hidden) {
            // 顶栏/底栏等可能晚于播放事件出现，延迟再扫两次
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

        applySystemBars(decorView, hidden)
        applyPanelSpaces(hidden)

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

    private fun applySystemBars(decorView: View, hidden: Boolean) {
        if (hidden) {
            if (decorView.systemUiVisibility and IMMERSIVE_FLAGS != IMMERSIVE_FLAGS) {
                decorView.systemUiVisibility = decorView.systemUiVisibility or IMMERSIVE_FLAGS
                if (verbose) {
                    YLog.debug("$TAG: immersive flags applied 0x${IMMERSIVE_FLAGS.toString(16)}")
                }
            }
            attachSystemUiKeeper(decorView)
        } else {
            detachSystemUiKeeper(decorView)
            if (decorView.systemUiVisibility and IMMERSIVE_FLAGS != 0) {
                decorView.systemUiVisibility = decorView.systemUiVisibility and IMMERSIVE_FLAGS.inv()
                if (verbose) {
                    YLog.debug("$TAG: immersive flags cleared")
                }
            }
        }
    }

    /** 清爽隐藏期间：一旦系统栏可见性被抖音改回，立刻再次隐藏 */
    private fun attachSystemUiKeeper(decorView: View) {
        val previous = systemUiKeeperDecor?.get()
        if (previous != null && previous !== decorView) {
            detachSystemUiKeeper(previous)
        }
        if (systemUiKeeperListener != null) {
            return
        }
        val listener = View.OnSystemUiVisibilityChangeListener { visibility ->
            if (cleanHidden && visibility and HIDDEN_BAR_FLAGS != HIDDEN_BAR_FLAGS) {
                decorView.systemUiVisibility = decorView.systemUiVisibility or IMMERSIVE_FLAGS
            }
        }
        systemUiKeeperListener = listener
        systemUiKeeperDecor = WeakReference(decorView)
        decorView.setOnSystemUiVisibilityChangeListener(listener)
    }

    private fun detachSystemUiKeeper(decorView: View) {
        val listener = systemUiKeeperListener ?: return
        decorView.setOnSystemUiVisibilityChangeListener(null)
        systemUiKeeperListener = null
        systemUiKeeperDecor = null
    }

    private fun applyPanelSpaces(hidden: Boolean) {
        val panel = panelRef?.get() ?: return
        val panelClass = panel.javaClass
        listOf("mTopSpace", "mBottomSpace").forEach { fieldName ->
            val field = runCatching {
                panelClass.getField(fieldName)
            }.getOrNull() ?: return@forEach
            val view = runCatching {
                field.get(panel) as? View
            }.getOrNull() ?: return@forEach
            val key = System.identityHashCode(view)
            val layoutParams = view.layoutParams ?: return@forEach
            val original = spaceOriginalHeights[key] ?: layoutParams.height
            spaceOriginalHeights[key] = original
            val target = if (hidden) 0 else original
            if (layoutParams.height != target) {
                layoutParams.height = target
                view.layoutParams = layoutParams
                view.requestLayout()
                if (verbose) {
                    YLog.debug("$TAG: panel space $fieldName height -> $target (original $original)")
                }
            }
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
