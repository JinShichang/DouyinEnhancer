package io.github.twyora.douyinenhancer.hook.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.R
import io.github.twyora.douyinenhancer.bridge.ModuleApp
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.hook.NativeCleanModeSymbols
import io.github.twyora.douyinenhancer.utils.Method
import io.github.twyora.douyinenhancer.utils.resolveMethod
import io.github.twyora.douyinenhancer.utils.resolveMethodOrNull
import io.github.twyora.douyinenhancer.utils.toast
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

@HookOnMainProcess
object QuickBlockHooker : YukiBaseHooker() {
    private const val TAG = "QuickBlockHooker"
    private val main = Handler(Looper.getMainLooper())
    private val scrolling = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private var overlay: Overlay? = null
    private var pendingOwner = WeakReference<Any>(null)
    private var generation = 0

    override fun onHook() {
        if (!ConfigManager.ui.pauseQuickBlock.value) return
        runCatching {
            val app = DouyinPackage.instance
            val panel = checkNotNull(app.baseListFragmentPanel.selfClass)
            val host = NativeQuickBlock(panel, app)
            val showPauseIcon = panel.resolveMethod(Method("showIvPlay", emptyList()))
            val hidePauseIcon = panel.resolveMethod(Method("hideIvPlay", emptyList()))
            val scroll = panel.resolveMethodOrNull(NativeCleanModeSymbols.scrollState)
            val cleanup = listOf(
                NativeCleanModeSymbols.pageSelected,
                NativeCleanModeSymbols.onPause,
                NativeCleanModeSymbols.onDestroyView
            ).mapNotNull { panel.resolveMethodOrNull(it) }

            host.block.hook {
                before {
                    val expectedAccount = host.quickBlockAccount(instance) ?: return@before
                    if (!host.stillSignedIn(expectedAccount)) result = null
                }
            }
            host.unblock.hook {
                before {
                    // The native sheet can switch to unblocking if its User changes while open.
                    if (host.quickBlockAccount(instance) != null) result = null
                }
            }
            showPauseIcon.hook {
                after {
                    val owner = instance
                    onMain { requestButton(host, owner) }
                }
            }.result {
                onHookingFailure { YLog.error("$TAG: could not observe pause icon", it) }
                onConductFailure { _, error -> YLog.error("$TAG: pause icon callback failed", error) }
            }
            scroll?.hook {
                after {
                    val owner = instance
                    val idle = args[0] == 0
                    onMain {
                        if (idle) scrolling.remove(owner) else scrolling.add(owner)
                        overlay?.takeIf { it.owner.get() === owner }?.let {
                            // Keep a paused item through a cancelled swipe, but never show a button while scrolling.
                            if (!idle) it.button.visibility = View.INVISIBLE else it.layer.invalidate()
                        }
                    }
                }
            }
            (cleanup + hidePauseIcon).forEach { method ->
                method.hook {
                    before {
                        val owner = instance
                        onMain { clearFor(owner) }
                    }
                }
            }
            YLog.info("$TAG: observing native pause icon and author avatar")
        }.onFailure { YLog.error("$TAG: native quick block unavailable", it) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun requestButton(host: NativeQuickBlock, owner: Any) {
        if (owner in scrolling) return
        pendingOwner = WeakReference(owner)
        val token = ++generation
        main.post {
            if (token != generation || owner in scrolling) return@post
            pendingOwner.clear()
            runCatching { display(host, owner) }
                .onFailure { YLog.error("$TAG: could not display quick block", it) }
        }
    }

    private fun display(host: NativeQuickBlock, owner: Any) {
        val selection = host.selection(owner) { YLog.debug("$TAG: skipped: $it") } ?: return
        overlay?.let {
            if (it.owner.get() === owner && it.selection.holder === selection.holder &&
                it.selection.key == selection.key && it.anchor.isAttachedToWindow
            ) {
                return
            }
            remove(it)
        }
        // Locate the live avatar in this holder, regardless of how the host inflated or reused it.
        val anchor = host.authorAvatar(selection.root) ?: run {
            YLog.debug("$TAG: skipped: no author avatar in the current holder")
            return
        }
        val layer = generateSequence(selection.root) { it.parent as? View }.filterIsInstance<FrameLayout>().firstOrNull() ?: return
        val density = layer.resources.displayMetrics.density
        val size = (48 * density).roundToInt()
        val gap = (6 * density).roundToInt() + 30
        val backgroundShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x88000000.toInt())
        }
        val button = TextView(layer.context).apply {
            text = ModuleApp.instance.resources.getString(R.string.pause_quick_block_button)
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), backgroundShape, null)
            elevation = 4 * density
            isFocusable = true
            visibility = View.INVISIBLE
        }
        val session = Overlay(host, WeakReference(owner), selection, anchor, layer, button, size, gap)
        button.setOnClickListener { confirm(session) }
        layer.addView(button, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT))
        overlay = session
        selection.root.addOnAttachStateChangeListener(session)
        layer.viewTreeObserver.addOnPreDrawListener(session)
    }

    private fun confirm(session: Overlay) {
        if (overlay !== session || session.button.visibility != View.VISIBLE) return
        val owner = session.owner.get() ?: return remove(session)
        if (owner in scrolling) return
        runCatching {
            val current = session.host.selection(owner)
            if (current == null || current.holder !== session.selection.holder || current.root !== session.selection.root ||
                !session.selection.key.canBlock(current.key, session.host.isBlocked(current.author))
            ) {
                remove(session)
                return
            }
            session.host.showConfirmation(current)
        }.onFailure {
            YLog.error("$TAG: could not open native block confirmation", it)
            session.button.context.toast(ModuleApp.instance.resources.getString(R.string.pause_quick_block_unavailable))
        }
    }

    private fun clearFor(owner: Any) {
        if (pendingOwner.get() === owner) {
            generation++
            pendingOwner.clear()
        }
        overlay?.takeIf { it.owner.get() === owner }?.let(::remove)
    }

    private fun remove(session: Overlay) {
        if (overlay !== session) return
        overlay = null
        session.selection.root.removeOnAttachStateChangeListener(session)
        session.layer.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(session)
        session.button.setOnClickListener(null)
        session.layer.removeView(session.button)
    }

    private class Overlay(
        val host: NativeQuickBlock,
        val owner: WeakReference<Any>,
        val selection: NativeQuickBlock.Selection,
        val anchor: View,
        val layer: FrameLayout,
        val button: TextView,
        val size: Int,
        val gap: Int
    ) : ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
        private val anchorLocation = IntArray(2)
        private val layerLocation = IntArray(2)
        private val visibleRect = Rect()

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) = remove(this)

        override fun onPreDraw(): Boolean {
            val panel = owner.get()
            if (panel == null || !anchor.isAttachedToWindow || host.isBlocked(selection.author)) {
                remove(this)
                return true
            }
            val visible = panel !in scrolling && selection.pauseIcon.isShown && selection.pauseIcon.alpha > 0f &&
                anchor.isShown && anchor.hasWindowFocus() &&
                anchor.getGlobalVisibleRect(visibleRect) &&
                generateSequence(anchor) { it.parent as? View }.none { it.alpha <= 0f }
            if (!visible) {
                button.visibility = View.INVISIBLE
                return true
            }
            anchor.getLocationInWindow(anchorLocation)
            layer.getLocationInWindow(layerLocation)
            val maxX = layer.width - size
            val y = anchorLocation[1] - layerLocation[1] - size - gap
            if (maxX < 0 || y < 0 || y + size > layer.height) {
                button.visibility = View.INVISIBLE
                return true
            }
            val x = (anchorLocation[0] - layerLocation[0] + (anchor.width - size) / 2).coerceIn(0, maxX)
            button.translationX = x.toFloat()
            button.translationY = y.toFloat()
            button.visibility = View.VISIBLE
            return true
        }
    }
}
