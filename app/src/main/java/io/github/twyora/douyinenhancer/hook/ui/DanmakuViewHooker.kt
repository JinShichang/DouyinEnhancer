package io.github.twyora.douyinenhancer.hook.ui

import android.view.View
import androidx.collection.ArraySet
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.resolveMethod

@HookOnMainProcess
object DanmakuViewHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !ConfigManager.module.verboseDisabled.value

    private val danmakuViewIds = ArraySet<Int>().apply {
        add(View.generateViewId())
    }

    override fun onHook() {
        if (!ConfigManager.ui.keepDanmakuVisible.value && !ConfigManager.ui.cleanMode.value) {
            if (verbose) {
                YLog.debug("$TAG: keep danmaku visible disabled, skip danmaku hooks")
            }
            return
        }
        installAssignDanmakuViewIdHook()
        installAddDanmakuViewIdToCleanModeWhiteListHook()
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

                if (danmakuView.id == View.NO_ID) {
                    val danmakuViewId = danmakuViewIds.first()
                    if (verbose) {
                        YLog.debug("$TAG: assign danmaku view id($danmakuViewId) to view without id")
                    }
                    danmakuView.id = danmakuViewId
                } else {
                    if (verbose) {
                        YLog.debug("$TAG: collect existing danmaku view id(${danmakuView.id})")
                    }
                    danmakuViewIds.add(danmakuView.id)
                }
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
                args[index] = ArrayList((whiteList + danmakuViewIds).distinct())
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to add danmaku view id to clean mode white list", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook clean mode white list adding", throwable)
            }
        }
}
