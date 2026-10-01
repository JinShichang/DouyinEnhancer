package io.github.twyora.douyinenhancer.hook.ui

import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.getField
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
        val config = ConfigManager.bottomTab
        listOf(
            config.hideHomepageHome.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_HOME,
            config.hideHomepageMall.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_MALL,
            config.hideHomepagePublish.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_PUBLISH,
            config.hideHomepageNotification.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_NOTIFICATION,
            config.hideHomepageProfile.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_PROFILE,
        ).filter {
            it.first
        }.map {
            it.second
        }.toSet()
    }

    override fun onHook() {
        if (!ConfigManager.bottomTab.mainSwitch.value) {
            YLog.info("$TAG: bottom tab hiding disabled, skipping hook")
            return
        }

        installRemoveBottomTabItemsHook()
    }

    private fun installRemoveBottomTabItemsHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.mpfBottomTabComponent.selfClass?.resolveMethodOrNull(
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
                    it.getField<String>(
                        packageInstance.tabNode.tabId()
                    ) in removeTabIds
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to remove publish button", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook publish button removal", throwable)
            }
        }
    }
}
