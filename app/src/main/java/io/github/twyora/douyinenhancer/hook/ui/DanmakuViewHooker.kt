package io.github.twyora.douyinenhancer.hook.ui

import android.view.View
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.invokeMethodOnly
import io.github.twyora.douyinenhancer.utils.invokeStaticMethod
import io.github.twyora.douyinenhancer.utils.resolveMethod
import java.util.Collections
import java.util.WeakHashMap

@HookOnMainProcess
object DanmakuViewHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !ConfigManager.module.verboseDisabled.value

    private val danmakuViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun onHook() {
        if (!ConfigManager.ui.keepDanmakuVisible.value && !ConfigManager.ui.cleanMode.value) {
            if (verbose) {
                YLog.debug("$TAG: keep danmaku visible disabled, skip danmaku hooks")
            }
            return
        }
        installRegisterDanmakuContainerHook()
        installAssignDanmakuViewIdHook()
        installAddDanmakuViewIdToCleanModeWhiteListHook()
        installPartitionDanmakuAncestorHook()
    }

    private fun register(view: View) {
        if (view.id == View.NO_ID) view.id = View.generateViewId()
        danmakuViews.add(view)
    }

    private fun installRegisterDanmakuContainerHook(): YukiMemberHookCreator.MemberHookCreator.Result? =
        packageInstance.danmakuView.containerClass?.resolveMethod(
            packageInstance.danmakuView.createContainer()
        )?.hook {
            after {
                // Both traditional and Compose renderers live in this dedicated container.
                // Register before attachment so the first clean command can already preserve it.
                register(checkNotNull(result as? View) { "Danmaku module did not create a View" })
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to register danmaku container", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook danmaku container creation", throwable)
            }
        }

    private fun installAssignDanmakuViewIdHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.danmakuView.selfClass?.resolveMethod(
            packageInstance.danmakuView.onAttachedToWindow()
        )?.hook {
            after {
                val danmakuView = instance as? View ?: run {
                    YLog.error("$TAG: ${instance::class.qualifiedName} is not View")
                    return@after
                }

                register(danmakuView)
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to assign danmaku view id", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook danmaku view id assignment", throwable)
            }
        }
    }

    private fun installAddDanmakuViewIdToCleanModeWhiteListHook(): YukiMemberHookCreator.MemberHookCreator.Result? =
        packageInstance.cleanModePresenter.selfClass?.resolveMethod(
            packageInstance.cleanModePresenter.enterCleanMode()
        )?.hook {
            before {
                val index = checkNotNull(packageInstance.cleanModePresenter.enterCleanMode().parameters).indexOf("java.util.List")
                check(index >= 0) { "Clean mode whitelist parameter is missing from HookInfo" }
                val whiteList = checkNotNull(args[index]) as List<*>
                // Native traversal keeps only these descendants and hides their siblings.
                args[index] = ArrayList((whiteList + danmakuViews.map { it.id }).distinct())
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to add danmaku view id to clean mode white list", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook clean mode white list adding", throwable)
            }
        }

    private fun installPartitionDanmakuAncestorHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        val presenter = packageInstance.cleanModePresenter
        val presenterClass = presenter.selfClass ?: return null
        return presenterClass.resolveMethod(presenter.handleView())?.hook {
            before {
                val view = args[0] as View
                val ids = danmakuViews.map { it.id }
                if (ids.none { view.findViewById<View>(it) != null }) return@before
                // The host handles its slide-out container directly, bypassing the whitelist.
                // Split that request with the host's own traversal so siblings still hide and
                // retain the original priority/restoration policy, while danmaku stays visible.
                val targets = checkNotNull(
                    presenterClass.invokeStaticMethod<List<View>>(
                        presenter.collectHiddenViews(),
                        view,
                        ArrayList(ids)
                    )
                )
                check(targets.none { it === view }) { "Native traversal did not preserve danmaku" }
                targets.forEach { target ->
                    instance.invokeMethodOnly(presenter.handleView(), target, args[1], args[2], args[3])
                }
                result = null
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to partition danmaku ancestor", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook native clean view handling", throwable)
            }
        }
    }
}
