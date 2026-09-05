package io.github.twyora.douyinenhancer.hook.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
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

    // 被我们隐藏的“底部全宽纯占位 View”（dyoo 删 spacer 的等价物）
    private val hiddenBottomSpacers = HashMap<Int, WeakReference<View>>()

    // 翻页器父容器原始底部 padding（清爽时清零让 pager 长到全屏）
    private val pagerAncestorPadding = HashMap<Int, Int>()

    // RTViewPager 原始布局高度（清爽时直接撑到全屏）
    private val pagerOriginalHeight = HashMap<Int, Int>()

    // 已由我们改成“全屏”的翻页器实例（暂停时据此还原；记录即改，避免快速暂停/继续的竞态）
    private val pagerFullscreenChanged = HashSet<Int>()

    // 冷启动后是否已做过一次性“原生↔全屏”往返（用于去掉首次进入时的底部圆角残留）
    private var roundingRoundTripDone = false

    // RTViewPager 原始 layout_above/below 锚定 id（暂停时还原抖音原生布局用）
    private val pagerOriginalRuleAbove = HashMap<Int, Int>()
    private val pagerOriginalRuleBelow = HashMap<Int, Int>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rehideRunnable = object : Runnable {
        override fun run() {
            if (overlaysHidden) {
                // 补扫只处理悬浮控件，不再清零留白，避免与抖音的布局逻辑打架
                applyOverlayVisibility(hidden = true, log = false, zeroSpaces = false)
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
        YLog.debug("$TAG: CleanModeHooker v7.17 active (module 0.11.1)")
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
                        // 任何翻页状态都补扫隐藏：切页时抖音会短暂重新显示新页控件
                        applyOverlayMode(hidden = true)
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
        if (hidden) {
            val transition = !overlaysHidden
            overlaysHidden = true
            // 只在进入隐藏的瞬间清零留白（避免抖音重置后我们反复清零造成抖动）
            applyOverlayVisibility(hidden = true, log = transition, zeroSpaces = transition)
            // 隐藏后做几次短间隔补扫，兜住切页/重渲染后新冒出的控件
            mainHandler.removeCallbacks(rehideRunnable)
            mainHandler.postDelayed(rehideRunnable, 200L)
            mainHandler.postDelayed(rehideRunnable, 700L)
            mainHandler.postDelayed(rehideRunnable, 1800L)
            if (transition && verbose) {
                dumpLayoutStructure()
            }
            if (transition && !roundingRoundTripDone) {
                // 一次性“底栏 显示→隐藏”切换：抖音按底栏可见性决定视频是否画圆角；
                // 等效暂停再继续对底栏的那次真实切换，把内部状态翻成“底栏已隐藏=方角”
                roundingRoundTripDone = true
                mainHandler.postDelayed({ doFirstEntryRoundTrip() }, 2000L)
            }
        } else {
            if (!overlaysHidden) {
                return
            }
            overlaysHidden = false
            applyOverlayVisibility(hidden = false, log = true, zeroSpaces = false)
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
        adjustBottomPlainSpacer(decorView, hidden)
        adjustPagerAncestors(decorView, classLoader, hidden)
        adjustPagerHeight(decorView, classLoader, hidden)
        // 不做逐元素位移：暂停时由 adjustPagerHeight 还原整页原生布局，页内元素自然回原生位置
        if (log || changed > 0) {
            YLog.debug("$TAG: overlays ${if (hidden) "hidden" else "shown"}, $changed views changed, ${overlayViews.size} total")
        }
        // 隐藏后仍可见的右列/底部疑似悬浮控件（合集等页面可能漏网），verbose 时打印类名
        if (hidden && verbose) {
            dumpLeftoverOverlays(activity)
        }
    }

    /**
     * 打印隐藏后仍可见的“疑似悬浮控件”（右列/底部区域、非通用布局、非弹幕/进度条），
     * 附最近的非通用祖先类名，用于定位合集等页面漏网的控件容器。
     */
    private fun dumpLeftoverOverlays(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val screenW = decor.right - decor.left
        val screenH = decor.bottom - decor.top
        val found = ArrayList<View>()
        val counter = intArrayOf(0)
        fun walk(view: View, depth: Int) {
            if (depth > 14 || counter[0] > 4000) {
                return
            }
            counter[0]++
            if (view.visibility == View.VISIBLE && view.width > 0 && view.height > 0) {
                val loc = IntArray(2)
                view.getLocationInWindow(loc)
                // 只看真正落在可视窗口内的视图，过滤掉水平/垂直方向离屏的兄弟页与评论面板等
                if (loc[0] in 0 until screenW && loc[1] in 0 until screenH) {
                    val rightBand = loc[0] >= screenW - 220
                    val bottomBand = loc[1] >= screenH - 560
                    if (rightBand || bottomBand) {
                        val name = view.javaClass.name
                        val isGeneric = name.startsWith("android.widget.FrameLayout") ||
                            name.startsWith("android.widget.RelativeLayout") ||
                            name.startsWith("android.widget.LinearLayout") ||
                            name.startsWith("android.widget.HorizontalScrollView") ||
                            name.startsWith("android.widget.ScrollView") ||
                            name == "android.view.View" || name.startsWith("android.view.ViewGroup") ||
                            name.startsWith("X.") || name.endsWith("ViewStub") || name.endsWith("ViewGroup")
                        val isKeep = name.contains("DanmakuView") || name.contains("SeekBar") ||
                            name.contains("VerticalViewPager") || name.contains("RTViewPager") ||
                            name.contains("SurfaceView") || name.contains("TextureView")
                        if (!isGeneric && !isKeep && found.size < 60) {
                            found.add(view)
                        }
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), depth + 1)
                }
            }
        }
        walk(decor, 0)
        if (found.isEmpty()) {
            return
        }
        YLog.debug("$TAG: leftover visible overlays in right/bottom bands (${found.size})")
        found.forEach { view ->
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val idText = if (view.id != View.NO_ID) "id=0x${view.id.toString(16)}" else "id=no"
            val ancestors = StringBuilder()
            var parent = view.parent
            var d = 0
            while (parent is ViewGroup && d < 5) {
                val pName = parent.javaClass.name
                ancestors.append(
                    if (pName.contains("aweme") ||
                        pName.contains("bytedance")
                    ) {
                        pName
                    } else {
                        pName.substringAfterLast('.')
                    }
                ).append(" > ")
                parent = parent.parent
                d++
            }
            YLog.debug(
                "$TAG:   [leftover] ${view.javaClass.name} top=${loc[1]} bottom=${loc[1] + view.height} " +
                    "x=${loc[0]} w=${view.width} $idText ancestors=$ancestors"
            )
        }
    }

    /** 清爽隐藏时隐藏屏幕底部“全宽纯 View 占位”（dyoo 删 spacer 思路），暂停恢复 */
    private fun adjustBottomPlainSpacer(decorView: View, hidden: Boolean) {
        if (hidden) {
            val screenH = decorView.bottom - decorView.top
            val screenW = decorView.right - decorView.left
            collectPlainBottomViews(decorView, screenW, screenH, this::recordAndHideSpacer)
        } else {
            val it = hiddenBottomSpacers.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val v = e.value.get()
                it.remove()
                if (v != null && v.visibility != View.VISIBLE) {
                    v.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun recordAndHideSpacer(view: View) {
        hiddenBottomSpacers[System.identityHashCode(view)] = WeakReference(view)
        if (view.visibility != View.GONE) {
            view.visibility = View.GONE
            if (verbose) {
                YLog.debug("$TAG: bottom plain spacer hidden ${view.javaClass.name} top=${view.top} bottom=${view.bottom}")
            }
        }
    }

    private fun collectPlainBottomViews(view: View, screenW: Int, screenH: Int, onFound: (View) -> Unit) {
        if (view !is ViewGroup) {
            if (view.javaClass.name == "android.view.View" && view.visibility == View.VISIBLE &&
                view.bottom >= screenH - 300 && (view.right - view.left) >= screenW - 4 && (view.bottom - view.top) in 50..400
            ) {
                onFound(view)
            }
            return
        }
        for (i in 0 until view.childCount) {
            collectPlainBottomViews(view.getChildAt(i), screenW, screenH, onFound)
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

    /** feed 面板顶部留白清零（视频铺满顶部）；底部留白保持原生，供暂停还原 pager 布局 */
    private fun zeroPanelSpaces() {
        val panel = panelRef?.get() ?: return
        val panelClass = panel.javaClass
        listOf("mTopSpace").forEach { fieldName ->
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

    /** 打印窗口最底部 region 内所有可见视图（类/id/边界/高度），用于定位挡住视频底部的容器 */
    private fun dumpBottomRegionViews() {
        val activity = mainActivityRef?.get() ?: return
        val decor = activity.window?.decorView ?: return
        val screenH = decor.bottom - decor.top
        YLog.debug("$TAG: bottom-region views (screenH=$screenH)")
        val counter = intArrayOf(0)
        fun walk(view: View, prefix: String, depth: Int) {
            if (depth > 12 || counter[0] > 400) {
                return
            }
            counter[0]++
            val h = view.bottom - view.top
            if (view.visibility == View.VISIBLE && view.bottom >= screenH - 320 && h in 1..1800) {
                val idText = if (view.id != View.NO_ID) "id=0x${view.id.toString(16)}" else "id=no"
                val lp = view.layoutParams
                val lpText = if (lp is ViewGroup.MarginLayoutParams) {
                    "h=${lp.height} bottomMargin=${lp.bottomMargin}"
                } else {
                    "lp=${lp?.javaClass?.simpleName}"
                }
                YLog.debug(
                    "$TAG:   $prefix ${view.javaClass.name} top=${view.top} bottom=${view.bottom} $idText $lpText"
                )
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), "$prefix$i.", depth + 1)
                }
            }
        }
        walk(decor, "", 0)
    }

    /** 打印底栏/翻页器及其父容器结构与高度，定位 196px 由谁占用 */
    private fun dumpLayoutStructure() {
        val activity = mainActivityRef?.get() ?: return
        val decor = activity.window?.decorView ?: return
        val loader = packageInstance.baseListFragmentPanel.selfClass?.classLoader ?: return
        YLog.debug("$TAG: layout structure probe")
        listOf(
            "com.ss.android.ugc.aweme.homepage.ui.bottombar.MainBottomTabContainer",
            "com.ss.android.ugc.aweme.common.widget.VerticalViewPager",
            "com.ss.android.ugc.aweme.homepage.ui.view.MainScrollableViewPager"
        ).forEach { clsName ->
            val found = ArrayList<View>()
            collectByClassName(decor, loader, clsName, found)
            YLog.debug("$TAG:   $clsName -> ${found.size} instance(s)")
            found.forEachIndexed { idx, v ->
                YLog.debug(
                    "$TAG:     [$idx] ${v.javaClass.name} vis=${v.visibility} top=${v.top} bottom=${v.bottom} " +
                        "w=${v.right - v.left} lp=${describeLp(v.layoutParams)}"
                )
                var parent = v.parent
                var depth = 0
                while (parent is ViewGroup && depth < 4) {
                    val pv = parent
                    YLog.debug(
                        "$TAG:        parent$depth ${pv.javaClass.name} vis=${pv.visibility} top=${pv.top} " +
                            "bottom=${pv.bottom} w=${pv.right - pv.left} lp=${describeLp(pv.layoutParams)} children=${pv.childCount}"
                    )
                    parent = pv.parent
                    depth++
                }
            }
        }
    }

    /** 清爽隐藏时清零翻页器各级父容器底部 padding，让 RTViewPager 高度=父高（页面=全屏） */
    private fun adjustPagerAncestors(decorView: View, classLoader: ClassLoader, hidden: Boolean) {
        val pagers = ArrayList<View>()
        collectByClassName(decorView, classLoader, "com.ss.android.ugc.aweme.common.widget.VerticalViewPager", pagers)
        val touched = HashSet<Int>()
        pagers.forEach { pager ->
            var parent = pager.parent
            var depth = 0
            while (parent is ViewGroup && depth < 6) {
                val key = System.identityHashCode(parent)
                if (touched.add(key)) {
                    val original = pagerAncestorPadding[key] ?: parent.paddingBottom
                    pagerAncestorPadding[key] = original
                    val target = if (hidden) 0 else original
                    if (parent.paddingBottom != target) {
                        parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, target)
                        if (verbose) {
                            YLog.debug("$TAG: pager ancestor ${parent.javaClass.name} bottom padding -> $target (orig $original)")
                        }
                    }
                }
                parent = parent.parent
                depth++
            }
        }
        if (!hidden) {
            pagerAncestorPadding.clear()
            hiddenBottomSpacers.clear()
        }
    }

    /**
     * 清爽隐藏(play)时把翻页器(RTViewPager)撑到全屏；暂停(show)时还原抖音原生布局。
     *
     * 逆向结论（38.8.0 feed 布局 wk6/wk7.xml）：RTViewPager 在 DisallowInterceptRelativeLayout
     * 里被 layout_above 锚在底部 BottomSpace 之上（BottomSpace 运行时约 196px，为底栏预留的
     * 不可见占位），所以父容器虽然全高 3168，pager 却只有 2972。
     * - 播放（隐藏底栏）：移除 above/below 锚定并显式给全高 → 视频铺满到屏幕底；
     * - 暂停（恢复底栏）：还原锚定并把高度改回 MATCH_PARENT(-1) → 抖音重新按 BottomSpace
     *   把 pager 排回原生高度，页内作者/描述/右侧列/唱片（不同视频类型高度不同）全部按
     *   该类型原生布局归位，无需逐元素位移。
     * 状态管理：只要改过 lp 就记入 pagerFullscreenChanged（不依赖“是否已长到全高”），
     * 避免快速 暂停→继续 时把“已撑到 3168 的 lp”误存为原始高度（v7.14 日志曾出现
     * restored native height=3168，导致还原无效）。
     */
    private fun adjustPagerHeight(decorView: View, classLoader: ClassLoader, hidden: Boolean) {
        val pagers = ArrayList<View>()
        collectByClassName(decorView, classLoader, "com.ss.android.ugc.aweme.common.widget.VerticalViewPager", pagers)
        if (pagers.isEmpty()) {
            return
        }
        val screenH = decorView.bottom - decorView.top
        pagers.forEach { pager ->
            val key = System.identityHashCode(pager)
            val lp = pager.layoutParams ?: return@forEach
            val rlp = lp as? RelativeLayout.LayoutParams
            if (hidden) {
                if (!pagerFullscreenChanged.contains(key)) {
                    pagerOriginalHeight[key] = lp.height
                    pagerOriginalRuleAbove[key] = rlp?.getRule(RelativeLayout.ABOVE) ?: 0
                    pagerOriginalRuleBelow[key] = rlp?.getRule(RelativeLayout.BELOW) ?: 0
                    pagerFullscreenChanged.add(key)
                }
                var layoutChanged = false
                var anchors = ""
                if (rlp != null) {
                    val above = rlp.getRule(RelativeLayout.ABOVE)
                    val below = rlp.getRule(RelativeLayout.BELOW)
                    if (above != 0) {
                        rlp.removeRule(RelativeLayout.ABOVE)
                        anchors += "above=0x${above.toString(16)} "
                        layoutChanged = true
                    }
                    if (below != 0) {
                        rlp.removeRule(RelativeLayout.BELOW)
                        anchors += "below=0x${below.toString(16)}"
                        layoutChanged = true
                    }
                } else if (verbose && pagerFullscreenChanged.contains(key)) {
                    YLog.debug("$TAG: RTViewPager lp is ${lp.javaClass.name}, not RelativeLayout.LayoutParams; skip anchor removal")
                }
                if (lp.height != screenH) {
                    lp.height = screenH
                    layoutChanged = true
                }
                if (layoutChanged) {
                    pager.layoutParams = lp
                }
                val laidOutHeight = pager.bottom - pager.top
                if (pagerFullscreenChanged.contains(key) && laidOutHeight < screenH - 2) {
                    // 已改过但还没长到全高：主动重排（MeasureOnce 可能跳过重测则下次事件再补）
                    pager.requestLayout()
                    (pager.parent as? View)?.requestLayout()
                    if (verbose) {
                        YLog.debug(
                            "$TAG: RTViewPager full -> $screenH (orig ${pagerOriginalHeight[key]}, anchors ${anchors.ifEmpty {
                                "none"
                            }})"
                        )
                    }
                }
            } else {
                // 暂停：还原原生 pager（锚定 + MATCH_PARENT），让页内元素按该视频类型原生布局归位
                if (pagerFullscreenChanged.remove(key)) {
                    if (rlp != null) {
                        val above = pagerOriginalRuleAbove.remove(key) ?: 0
                        val below = pagerOriginalRuleBelow.remove(key) ?: 0
                        if (above != 0) {
                            rlp.addRule(RelativeLayout.ABOVE, above)
                        }
                        if (below != 0) {
                            rlp.addRule(RelativeLayout.BELOW, below)
                        }
                    } else {
                        pagerOriginalRuleAbove.remove(key)
                        pagerOriginalRuleBelow.remove(key)
                    }
                    pagerOriginalHeight.remove(key)
                    // feed 翻页器原生高度恒为 MATCH_PARENT(-1)，锚定恢复后由抖音按 BottomSpace 排回原生
                    if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    pager.layoutParams = lp
                    pager.requestLayout()
                    (pager.parent as? View)?.requestLayout()
                    if (verbose) {
                        YLog.debug("$TAG: RTViewPager restored native (lp.height -> MATCH_PARENT)")
                    }
                    verifyPagerNativeLater(pager, screenH)
                }
            }
        }
    }

    /** 还原后延迟确认 pager 是否真的缩回原生高度（BottomSpace 生效/MeasureOnce 是否重测） */
    private fun verifyPagerNativeLater(pager: View, screenH: Int) {
        mainHandler.postDelayed({
            if (verbose) {
                val barTop = runCatching {
                    val activity = mainActivityRef?.get()
                    val loader = packageInstance.baseListFragmentPanel.selfClass?.classLoader
                    val decor = activity?.window?.decorView
                    val bars = ArrayList<View>()
                    if (decor != null && loader != null) {
                        collectByClassName(
                            decor,
                            loader,
                            "com.ss.android.ugc.aweme.homepage.ui.bottombar.MainBottomTabContainer",
                            bars
                        )
                    }
                    bars.firstOrNull { it.visibility == View.VISIBLE && it.bottom > it.top }?.top
                }.getOrNull()
                YLog.debug(
                    "$TAG: pager native check h=${pager.bottom - pager.top} top=${pager.top} " +
                        "bottom=${pager.bottom} lp.h=${pager.layoutParams?.height} (screenH=$screenH barTop=$barTop)"
                )
            }
        }, 350L)
    }

    /**
     * 首次进入清爽约 2s 后，忠实模拟一次“暂停→继续”的布局动作（仅底栏+pager，不闪其它控件）：
     * 底栏显示 + pager 还原原生尺寸 → 300ms 后 底栏隐藏 + pager 回全屏。
     * 目的：让抖音把“feed 视频底部圆角（底栏可见时的原生形态）”的状态翻成“底栏已隐藏=方角”。
     */
    private fun doFirstEntryRoundTrip() {
        val activity = mainActivityRef?.get() ?: return
        val loader = packageInstance.baseListFragmentPanel.selfClass?.classLoader ?: return
        val decor = activity.window?.decorView ?: return
        val bars = ArrayList<View>()
        collectByClassName(decor, loader, "com.ss.android.ugc.aweme.homepage.ui.bottombar.MainBottomTabContainer", bars)
        val bar = bars.firstOrNull() ?: return
        val pagers = ArrayList<View>()
        collectByClassName(decor, loader, "com.ss.android.ugc.aweme.common.widget.VerticalViewPager", pagers)
        val pager = pagers.firstOrNull()
        val screenH = decor.bottom - decor.top
        // 阶段1：显示底栏 + pager 原生尺寸
        bar.visibility = View.VISIBLE
        if (pager != null) {
            applyPagerState(pager, screenH, native = true)
        }
        if (verbose) {
            YLog.debug("$TAG: first-entry round trip -> bottom bar shown, pager native")
        }
        mainHandler.postDelayed({
            // 阶段2：隐藏底栏 + pager 回全屏
            bar.visibility = View.GONE
            if (pager != null) {
                applyPagerState(pager, screenH, native = false)
            }
            if (verbose) {
                YLog.debug("$TAG: first-entry round trip -> bottom bar hidden, pager fullscreen")
            }
        }, 300L)
    }

    /** 按“原生/全屏”直接设置 pager 的锚定与高度（不触碰 pagerFullscreenChanged 记账，避免破坏暂停还原） */
    private fun applyPagerState(pager: View, screenH: Int, native: Boolean) {
        val lp = pager.layoutParams ?: return
        val rlp = lp as? RelativeLayout.LayoutParams ?: return
        val key = System.identityHashCode(pager)
        if (native) {
            val above = pagerOriginalRuleAbove[key] ?: rlp.getRule(RelativeLayout.ABOVE)
            val below = pagerOriginalRuleBelow[key] ?: rlp.getRule(RelativeLayout.BELOW)
            if (above != 0) {
                rlp.addRule(RelativeLayout.ABOVE, above)
            }
            if (below != 0) {
                rlp.addRule(RelativeLayout.BELOW, below)
            }
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            rlp.removeRule(RelativeLayout.ABOVE)
            rlp.removeRule(RelativeLayout.BELOW)
            lp.height = screenH
        }
        pager.layoutParams = lp
        pager.requestLayout()
        (pager.parent as? View)?.requestLayout()
    }

    private fun describeLp(lp: ViewGroup.LayoutParams?): String {
        if (lp == null) {
            return "null"
        }
        val mlp = lp as? ViewGroup.MarginLayoutParams
        val weight = runCatching {
            lp.javaClass.getField("weight").get(lp)
        }.getOrNull()
        return "h=${lp.height} w=${lp.width} bottomMargin=${mlp?.bottomMargin} weight=$weight"
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
