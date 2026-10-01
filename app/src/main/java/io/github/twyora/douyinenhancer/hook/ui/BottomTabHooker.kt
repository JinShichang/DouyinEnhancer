package io.github.twyora.douyinenhancer.hook.ui

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.getFieldOrNull
import io.github.twyora.douyinenhancer.utils.resolveMethodOrNull

@HookOnMainProcess
object BottomTabHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !ConfigManager.module.verboseDisabled.value

    private val removeTabIds by lazy {
        setOf(
            DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_PUBLISH
        )
    }

    override fun onHook() {
        packageInstance.mpfBottomTabComponent.selfClass?.resolveMethodOrNull(
            packageInstance.mpfBottomTabComponent.buildTabViews()
        )?.hook {
            before {
                val tabNodes = instance.getFieldOrNull<Any>(
                    packageInstance.mpfBottomTabComponent.tabRoot()
                )?.getFieldOrNull<MutableList<Any>>(
                    packageInstance.tabNode.children()
                ) ?: run {
                    YLog.error("$TAG: tabNodes is not a mutable list")
                    return@before
                }

                if (verbose) {
                    YLog.debug("$TAG: removing publish button from bottom tabs")
                }

                tabNodes.removeIf {
                    it.getFieldOrNull<String>(
                        packageInstance.tabNode.tabId()
                    ) in removeTabIds
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to remove publish button", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook for removing publish button", throwable)
            }
        }
    }
}
