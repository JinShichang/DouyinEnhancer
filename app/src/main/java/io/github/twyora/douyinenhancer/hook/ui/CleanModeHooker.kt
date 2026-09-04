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
 * 规则（已与需求确认）：
 * 1. 清爽开关开启期间：整个抖音 App 全程沉浸全屏（状态栏/导航栏永不出现，任何界面、任何时刻），
 *    从启动的一瞬间生效，关掉开关并重启后恢复；
 * 2. 顶栏/底栏/右侧互动列/作者文案/音乐等悬浮控件：播放时隐藏、暂停（或播放结束）恢复；
 * 3. feed 面板顶/底留白保持清零，让视频铺满全屏（不随暂停恢复）。
 *
 * 注：抖音冷启动首条视频的部分原生行为（个别控件晚出现等）不属于本模块职责范围。
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
     * 沉浸标志位（含 layout 标志，避免隐藏系统栏时内容重排抖动）：
     * LAYOUT_STABLE(256) | LAYOUT_FULLSCREEN(1024) | LAYOUT_HIDE_NAVIGATION(512) |
     * FULLSCREEN(4) | HIDE_NAVIGATION(2) | IMMERSIVE_STICKY(4096) = 5894
     */
    private const val IMMERSIVE_FLAGS = 5894

    // 状态栏/导航栏是否被隐藏的判定位：FULLSCREEN(4) | HIDE_NAVIGATION(2)
    private const val HIDDEN_BAR_FLAGS = 6

    private const val HOST_PANEL_CLASS = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel"
    private const val HOST_AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"

    /** 播放/恢复类方法：调用即隐藏悬浮控件 */
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

    /** 暂停类方法：调用即恢复悬浮控件 */
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

    // 已对哪些留白视图做过首次 requestLayout（只做一次，避免反复重排）
    private val spaceZeroedOnce = HashSet<Int>()

    // MainScrollableViewPager 原始底部 padding（清爽隐藏时清零以让页面铺满全屏）
    private val pagerOriginalBottomPadding = HashMap<Int, Int>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rehideRunnable = object : Runnable {
        override fun run() {
            if (overlaysHidden) {
                // 补扫只处理悬浮控件，不再清零留白，避免与抖音的布局逻辑打架
                applyOverlayVisibility(hidden = true, log = false, zeroSpaces = false)
            }
        }
    }

    /** 播放（隐藏）期间每 1s 持续压制，抖音一旦重新显示控件就再次隐藏 */
    private val suppressRunnable = object : Runnable {
        override fun run() {
            if (overlaysHidden) {
                applyOverlayVisibility(hidden = true, log = false, zeroSpaces = false)
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private var mainActivityRef: WeakReference<Activity>? = null
    private var panelRef: WeakReference<Any>? = null
    private var immersiveDecorRef: WeakReference<View>? = null

    /** 悬浮控件是否处于隐藏状态（播放中=隐藏，暂停=恢复） */
    @Volatile
    private var overlaysHidden = false

    /** 内容流正在滚动/切页：期间忽略“恢复悬浮控件”，避免闪烁 */
    @Volatile
    private var pagerDragging = false

    override fun onHook() {
        if (!FastKVConfigManager.settings.getBoolean(CleanModeKey.MAIN_SWITCH, false)) {
            if (verbose) {
                YLog.debug("$TAG: clean mode disabled, skip hook")
            }
            return
        }
        YLog.debug("$TAG: CleanModeHooker v6.8 active (no id-container hiding)")
        installGlobalImmersiveHook()
        installPlaybackStateHooks()
    }

    /** 任何 Activity 恢复时都强制沉浸全屏（清爽开启期间状态栏/导航栏永不出现） */
    private fun installGlobalImmersiveHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        // onCreate 之前就生效：保证 feed 首次布局即处于全屏，避免“视频铺不到底”的时机竞争
        Activity::class.java.resolveMethod(
            Method("onCreate", listOf("android.os.Bundle"))
        )?.hook {
            before {
                val activity = instance as? Activity ?: return@before
                mainActivityRef = WeakReference(activity)
                applyPersistentImmersive(activity)
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to enforce immersive before activity create", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook activity create", throwable)
            }
        }

        return Activity::class.java.resolveMethod(
            Method("onResume", emptyList())
        )?.hook {
            after {
                val activity = instance as? Activity ?: return@after
                mainActivityRef = WeakReference(activity)
                applyPersistentImmersive(activity)
                if (verbose) {
                    YLog.debug("$TAG: immersive enforced on ${activity::class.java.name}")
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to enforce immersive on activity resume", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook activity resume", throwable)
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
                    applyOverlayMode(hidden = true)
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
                    applyOverlayMode(hidden = false)
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
                            applyOverlayMode(hidden = true)
                        }
                    } else {
                        applyOverlayMode(hidden = true)
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
                    videoType in hidePlayVideoTypes -> applyOverlayMode(hidden = true)
                    videoType in showPlayVideoTypes -> applyOverlayMode(hidden = false)
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
                if (code == PLAYER_EVENT_PAUSED) {
                    // 仅在真正暂停时恢复；播完自动重播不恢复，避免重播瞬间元素闪现
                    applyOverlayMode(hidden = false)
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

    /** 调用当前 feed 项 VideoViewHolder.openCleanMode，让抖音原生清爽逻辑隐藏/恢复其全部控件 */
    private fun setNativeItemClean(hidden: Boolean) {
        val panel = panelRef?.get() ?: return
        val holder = runCatching {
            panel.javaClass.getMethod("getCurViewHolder").invoke(panel)
        }.getOrNull() ?: return
        runCatching {
            val method = holder.javaClass.getMethod("openCleanMode", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(holder, hidden)
            if (verbose) {
                YLog.debug("$TAG: native openCleanMode($hidden) invoked on ${holder.javaClass.name}")
            }
        }.onFailure { throwable ->
            YLog.error("$TAG: failed to invoke native openCleanMode", throwable)
        }
    }

    /** 只控制悬浮控件显隐；系统栏由全程沉浸负责，不再随暂停恢复 */
    private fun applyOverlayMode(hidden: Boolean) {
        if (!hidden && pagerDragging) {
            return
        }
        if (hidden == overlaysHidden) {
            return
        }
        overlaysHidden = hidden
        // 调用抖音原生 per-holder 清爽接口，让抖音自己隐藏/恢复其控件（避免手动 GONE 打架）
        setNativeItemClean(hidden)
        // 只在进入隐藏的瞬间清零留白（避免抖音重置后我们反复清零造成抖动）
        applyOverlayVisibility(hidden, log = true, zeroSpaces = hidden)
        mainHandler.removeCallbacks(rehideRunnable)
        mainHandler.removeCallbacks(suppressRunnable)
        if (hidden) {
            mainHandler.postDelayed(rehideRunnable, 800L)
            mainHandler.postDelayed(rehideRunnable, 2500L)
            mainHandler.postDelayed(suppressRunnable, 1000L)
            if (verbose) {
                dumpDecorChildren()
                dumpHolderTree()
            }
        }
    }

    private fun applyOverlayVisibility(hidden: Boolean, log: Boolean, zeroSpaces: Boolean) {
        val activity = mainActivityRef?.get()
        if (activity == null) {
            if (log) {
                YLog.debug("$TAG: main activity not ready, skip applying overlay visibility")
            }
            return
        }
        val classLoader = packageInstance.baseListFragmentPanel.selfClass?.classLoader
        val decorView = activity.window?.decorView
        if (classLoader == null || decorView == null) {
            YLog.error("$TAG: unable to resolve host class loader or decor view")
            return
        }

        if (zeroSpaces) {
            zeroPanelSpaces()
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
        adjustFeedPagerForFullscreen(decorView, classLoader, hidden)
        if (log || changed > 0) {
            YLog.debug("$TAG: overlays ${if (hidden) "hidden" else "shown"}, $changed views changed, ${overlayViews.size} total")
        }
    }

    /** 清爽隐藏时清零 feed 竖向 ViewPager 的底部 padding，让每页高度=全屏（dyoo 删 spacer 的 38.8.0 等价做法） */
    private fun adjustFeedPagerForFullscreen(decorView: View, classLoader: ClassLoader, hidden: Boolean) {
        val pagers = ArrayList<View>()
        collectByClassName(decorView, classLoader, "com.ss.android.ugc.aweme.homepage.ui.view.MainScrollableViewPager", pagers)
        pagers.forEach { pager ->
            val key = System.identityHashCode(pager)
            val original = pagerOriginalBottomPadding[key] ?: pager.paddingBottom
            pagerOriginalBottomPadding[key] = original
            val targetBottom = if (hidden) 0 else original
            if (pager.paddingBottom != targetBottom) {
                pager.setPadding(pager.paddingLeft, pager.paddingTop, pager.paddingRight, targetBottom)
                if (verbose) {
                    YLog.debug("$TAG: feed pager bottom padding -> $targetBottom (original $original)")
                }
            }
        }
    }

    /** 清爽开启期间：强制沉浸全屏，并挂看守，防止抖音/系统把状态栏放出来 */
    private fun applyPersistentImmersive(activity: Activity) {
        val decorView = activity.window?.decorView ?: return
        val current = decorView.systemUiVisibility
        if (current and IMMERSIVE_FLAGS != IMMERSIVE_FLAGS) {
            decorView.systemUiVisibility = current or IMMERSIVE_FLAGS
        }

        // 每次活动恢复都重新挂看守（抖音可能覆盖监听器）
        decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and HIDDEN_BAR_FLAGS != HIDDEN_BAR_FLAGS) {
                decorView.systemUiVisibility = decorView.systemUiVisibility or IMMERSIVE_FLAGS
            }
        }
        immersiveDecorRef = WeakReference(decorView)
    }

    /** feed 面板顶/底留白清零（视频铺满），清爽开启期间保持 */
    private fun zeroPanelSpaces() {
        val panel = panelRef?.get() ?: return
        val panelClass = panel.javaClass
        listOf("mTopSpace", "mBottomSpace").forEach { fieldName ->
            val field = runCatching {
                panelClass.getField(fieldName)
            }.getOrNull() ?: return@forEach
            val view = runCatching {
                field.get(panel) as? View
            }.getOrNull() ?: return@forEach
            val layoutParams = view.layoutParams ?: return@forEach
            if (layoutParams.height != 0) {
                layoutParams.height = 0
                if (spaceZeroedOnce.add(System.identityHashCode(view))) {
                    // 首次清零才主动布局一次以生效，之后交给抖音自身布局，避免反复重排抖动
                    view.requestLayout()
                }
                if (verbose) {
                    YLog.debug("$TAG: panel space $fieldName height set to 0")
                }
            }
        }
    }

    /** 打印 decor 视图树（深 7 层，限量），用于定位“挡在视频底部”的视图 */
    private fun dumpDecorChildren() {
        val activity = mainActivityRef?.get() ?: return
        val decor = activity.window?.decorView ?: return
        YLog.debug("$TAG: decor children dump (screen=${activity.resources.displayMetrics.heightPixels})")
        val counter = intArrayOf(0)
        fun dumpView(view: View, prefix: String, depth: Int) {
            if (depth > 7 || counter[0] > 400) {
                return
            }
            counter[0]++
            val lp = view.layoutParams
            val lpText = if (lp is ViewGroup.MarginLayoutParams) {
                "h=${lp.height} bottomMargin=${lp.bottomMargin}"
            } else {
                "lp=${lp?.javaClass?.simpleName}"
            }
            val idText = if (view.id != View.NO_ID) "id=0x${view.id.toString(16)}" else "id=no"
            YLog.debug(
                "$TAG: $prefix ${view.javaClass.name} vis=${view.visibility} " +
                    "top=${view.top} bottom=${view.bottom} $idText $lpText"
            )
            if (view is ViewGroup) {
                val count = view.childCount
                for (i in 0 until count) {
                    dumpView(view.getChildAt(i), "$prefix$i.", depth + 1)
                }
            }
        }
        dumpView(decor, "  ", 0)
    }

    /** 打印当前 feed 项根视图子树，定位底部被遮挡的视图 */
    private fun dumpHolderTree() {
        val panel = panelRef?.get()
        if (panel == null) {
            YLog.debug("$TAG: holder dump skipped, panel not captured")
            return
        }
        var root: View? = null
        runCatching {
            val holder = panel.javaClass.getMethod("getCurViewHolder").invoke(panel)
            root = holder?.javaClass?.getField("itemView")?.get(holder) as? View
            if (root == null) {
                YLog.debug("$TAG: holder dump: holder or itemView null (holder=${holder?.javaClass?.name})")
            }
        }.onFailure { e ->
            YLog.debug("$TAG: holder dump via holder failed: $e")
        }
        if (root == null) {
            // 回退：在 decor 里找第一个 VideoViewHolderRootView 打印
            val activity = mainActivityRef?.get()
            val loader = packageInstance.baseListFragmentPanel.selfClass?.classLoader
            if (activity == null || loader == null) {
                YLog.debug("$TAG: holder dump skipped, no fallback root")
                return
            }
            val found = ArrayList<View>()
            collectByClassName(
                activity.window?.decorView ?: return,
                loader,
                "com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView",
                found
            )
            root = found.firstOrNull()
            if (root == null) {
                YLog.debug("$TAG: holder dump skipped, no VideoViewHolderRootView found")
                return
            }
            YLog.debug("$TAG: holder dump fallback: VideoViewHolderRootView")
        }
        YLog.debug("$TAG: holder itemView dump (root=${root.javaClass.name})")
        val rootHeight = root.bottom - root.top
        YLog.debug("$TAG: bottom-strip candidates (rootHeight=$rootHeight)")
        val counter = intArrayOf(0)
        fun dumpView(view: View, prefix: String, depth: Int) {
            if (depth > 10 || counter[0] > 350) {
                return
            }
            counter[0]++
            val lp = view.layoutParams
            val lpText = if (lp is ViewGroup.MarginLayoutParams) {
                "h=${lp.height} bottomMargin=${lp.bottomMargin} topMargin=${lp.topMargin}"
            } else {
                "lp=${lp?.javaClass?.simpleName}"
            }
            val idText = if (view.id != View.NO_ID) "id=0x${view.id.toString(16)}" else "id=no"
            YLog.debug(
                "$TAG:   $prefix ${view.javaClass.name} vis=${view.visibility} " +
                    "top=${view.top} bottom=${view.bottom} $idText $lpText"
            )
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    dumpView(view.getChildAt(i), "$prefix$i.", depth + 1)
                }
            }
        }
        dumpView(root, "", 0)
        // 底部 600px 内、可见且高度有限的视图候选（可能遮挡视频底部）
        val strip = ArrayList<View>()
        collectBottomStripCandidates(root, rootHeight, strip)
        for (v in strip) {
            val lp = v.layoutParams
            val lpText = if (lp is ViewGroup.MarginLayoutParams) {
                "h=${lp.height} bottomMargin=${lp.bottomMargin}"
            } else {
                "lp=${lp?.javaClass?.simpleName}"
            }
            val idText = if (v.id != View.NO_ID) "id=0x${v.id.toString(16)}" else "id=no"
            YLog.debug(
                "$TAG:   [strip] ${v.javaClass.name} vis=${v.visibility} " +
                    "top=${v.top} bottom=${v.bottom} $idText $lpText"
            )
        }
    }

    private fun collectBottomStripCandidates(view: View, rootHeight: Int, out: MutableList<View>) {
        if (view is ViewGroup) {
            val h = view.bottom - view.top
            if (view.visibility == View.VISIBLE && view.bottom >= rootHeight - 600 && h in 1..1600) {
                out.add(view)
            }
            for (i in 0 until view.childCount) {
                collectBottomStripCandidates(view.getChildAt(i), rootHeight, out)
            }
        } else if (view.visibility == View.VISIBLE) {
            val h = view.bottom - view.top
            if (view.bottom >= rootHeight - 600 && h in 1..1600) {
                out.add(view)
            }
        }
    }

    private fun collectByClassName(view: View, classLoader: ClassLoader, className: String, out: MutableList<View>) {
        val clazz = classCache.getOrPut(className) {
            runCatching {
                classLoader.loadClass(className)
            }.getOrNull()
        }
        if (clazz != null && clazz.isInstance(view)) {
            out.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectByClassName(view.getChildAt(i), classLoader, className, out)
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
