package io.github.twyora.douyinenhancer.hook.ui

import com.highcapable.kavaref.extension.createInstance
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.hook.NativeCleanModeSymbols
import io.github.twyora.douyinenhancer.utils.Method
import io.github.twyora.douyinenhancer.utils.getField
import io.github.twyora.douyinenhancer.utils.invokeMethod
import io.github.twyora.douyinenhancer.utils.resolveField
import io.github.twyora.douyinenhancer.utils.resolveMethod
import java.lang.ref.WeakReference

@HookOnMainProcess
object CleanModeHooker : YukiBaseHooker() {
    private const val TAG = "CleanModeHooker"
    private val packageInstance get() = DouyinPackage.instance
    private val playback = CleanModePlaybackState()
    private var panelRef = WeakReference<Any>(null)
    private var fragmentRef = WeakReference<Any>(null)
    private var eventType = ""
    private var activeCommand: Any? = null
    private var exitCommand: Any? = null

    override fun onHook() {
        if (!ConfigManager.ui.cleanMode.value) return
        val panel = checkNotNull(packageInstance.baseListFragmentPanel.selfClass)
        val native = packageInstance.nativeCleanMode
        if (native.serviceClass == null || native.commandClass == null) {
            YLog.error("$TAG: native clean mode mapping unavailable")
            return
        }
        panel.resolveMethod(packageInstance.baseListFragmentPanel.onVideoPlayerEvent())?.hook {
            after {
                val code = checkNotNull(args[0]).getField<Int>(packageInstance.videoPlayerStatus.code())
                when (code) {
                    NativeCleanModeSymbols.PLAYER_STARTED, NativeCleanModeSymbols.PLAYER_RESUMED ->
                        update(instance, CleanModePlaybackState.Event.PLAY)

                    NativeCleanModeSymbols.PLAYER_PAUSED -> update(instance, CleanModePlaybackState.Event.PAUSE)
                }
            }
        }?.result {
            onConductFailure { _, error -> YLog.error("$TAG: player state update failed", error) }
            onHookingFailure { error -> YLog.error("$TAG: player state hook failed", error) }
        }
        hookState(panel, packageInstance.baseListFragmentPanel.handlePause(), CleanModePlaybackState.Event.PAUSE)
        panel.resolveMethod(NativeCleanModeSymbols.spacePolicy)?.hook {
            after {
                // This predicate is consumed only by the native top/bottom spacer layout policy.
                if (panelRef.get() === instance && exitCommand != null) result = true
            }
        }?.result {
            onConductFailure { _, error -> YLog.error("$TAG: spacer policy failed", error) }
            onHookingFailure { error -> YLog.error("$TAG: spacer policy hook failed", error) }
        }
        panel.resolveMethod(NativeCleanModeSymbols.pageSelected)?.hook {
            after {
                val event = if (isLive(instance)) CleanModePlaybackState.Event.LIVE else CleanModePlaybackState.Event.VIDEO
                update(instance, event, reapply = true)
            }
        }?.result {
            onConductFailure { _, error -> YLog.error("$TAG: selected item update failed", error) }
            onHookingFailure { error -> YLog.error("$TAG: selected item hook failed", error) }
        }
        panel.resolveMethod(NativeCleanModeSymbols.scrollState)?.hook {
            after {
                val event = if (args[0] == 0) CleanModePlaybackState.Event.SCROLL_IDLE else CleanModePlaybackState.Event.SCROLL
                update(instance, event)
            }
        }?.result {
            onConductFailure { _, error -> YLog.error("$TAG: scroll state update failed", error) }
            onHookingFailure { error -> YLog.error("$TAG: scroll state hook failed", error) }
        }
        listOf(NativeCleanModeSymbols.onPause, NativeCleanModeSymbols.onDestroyView).forEach { method ->
            panel.resolveMethod(method)?.hook {
                before {
                    if (panelRef.get() === instance) {
                        leave()
                        playback.accept(CleanModePlaybackState.Event.LEAVE)
                        panelRef.clear()
                    }
                }
            }?.result {
                onConductFailure { _, error -> YLog.error("$TAG: clean mode cleanup failed", error) }
                onHookingFailure { error -> YLog.error("$TAG: lifecycle hook failed", error) }
            }
        }
    }

    private fun hookState(panel: Class<*>, method: Method, event: CleanModePlaybackState.Event) {
        panel.resolveMethod(method)?.hook {
            after { update(instance, event) }
        }?.result {
            onConductFailure { _, error -> YLog.error("$TAG: playback transition failed", error) }
            onHookingFailure { error -> YLog.error("$TAG: playback hook failed", error) }
        }
    }

    private fun isLive(panel: Any): Boolean = panel.invokeMethod<Any>(NativeCleanModeSymbols.getCurrentAweme)
        ?.invokeMethod<Boolean>(NativeCleanModeSymbols.isLive) == true

    private fun update(panel: Any, event: CleanModePlaybackState.Event, reapply: Boolean = false) {
        if (panel.invokeMethod<Boolean>(NativeCleanModeSymbols.userVisible) != true) return
        val fragment = panel.invokeMethod<Any>(NativeCleanModeSymbols.getFragment) ?: return
        if (fragment.invokeMethod<Boolean>(NativeCleanModeSymbols.fragmentAdded) != true) return
        if (panelRef.get() !== panel) {
            leave()
            playback.accept(CleanModePlaybackState.Event.LEAVE)
            panelRef = WeakReference(panel)
        }
        // An outgoing video can still report pause/start after a live card has been selected.
        val live = isLive(panel)
        if (live) playback.accept(CleanModePlaybackState.Event.LIVE)
        if (!live || event == CleanModePlaybackState.Event.SCROLL || event == CleanModePlaybackState.Event.SCROLL_IDLE) {
            playback.accept(event)
        }
        if (playback.clean && exitCommand == null) {
            enter(fragment, checkNotNull(panel.invokeMethod<String>(NativeCleanModeSymbols.getEventType)))
            adapt(panel)
        } else if (playback.clean && reapply) {
            toggle(fragment, eventType, checkNotNull(activeCommand))
            adapt(panel)
        } else if (!playback.clean) {
            leave()
        }
    }

    private fun adapt(panel: Any) {
        panel.invokeMethod<Any>(NativeCleanModeSymbols.updateSpaces)
        panel.invokeMethod<Any>(NativeCleanModeSymbols.adapt)
    }

    private fun enter(fragment: Any, type: String) {
        val native = packageInstance.nativeCleanMode
        val command = checkNotNull(native.commandClass).createInstance(true, NativeCleanModeSymbols.CALLER)
        checkNotNull(command.resolveField(native.autoQuit())).set(false)
        val content = checkNotNull(command.getField<Int>(native.content()))
        checkNotNull(command.resolveField(native.content())).set(content and NativeCleanModeSymbols.HIDE_SEEKBAR.inv())
        val inverse = checkNotNull(command.invokeMethod<Any>(native.inverse()))
        // Record ownership before notifying observers: native callbacks can re-enter the panel.
        fragmentRef = WeakReference(fragment)
        eventType = type
        activeCommand = command
        exitCommand = inverse
        try {
            toggle(fragment, type, command)
        } catch (error: Throwable) {
            leave()
            throw error
        }
    }

    private fun leave() {
        val command = exitCommand ?: return
        val fragment = fragmentRef.get()
        // Clear ownership first to make cleanup safe during nested native notifications.
        exitCommand = null
        activeCommand = null
        fragmentRef.clear()
        try {
            if (fragment != null && fragment.invokeMethod<Boolean>(NativeCleanModeSymbols.fragmentAdded) == true) {
                toggle(fragment, eventType, command)
            }
        } finally {
            panelRef.get()?.let(::adapt)
        }
    }

    private fun toggle(fragment: Any, type: String, command: Any) {
        val native = packageInstance.nativeCleanMode
        val service = checkNotNull(checkNotNull(native.serviceClass).resolveMethod(native.serviceInstance())).invoke()
        checkNotNull(checkNotNull(service).resolveMethod(native.toggle())).invoke(fragment, type, command)
    }
}
