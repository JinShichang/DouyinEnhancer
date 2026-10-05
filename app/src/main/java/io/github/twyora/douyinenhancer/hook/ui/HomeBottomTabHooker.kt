package io.github.twyora.douyinenhancer.hook.ui

import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookParam
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.resolveMethodOrNull

@HookOnMainProcess
object HomeBottomTabHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val tabIdsToRemove by lazy {
        val config = ConfigManager.homeTab
        listOf(
            config.hideHomepageHome.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_HOME,
            config.hideHomepageMall.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_MALL,
            config.hideHomepagePublish.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_PUBLISH,
            config.hideHomepageNotification.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_NOTIFICATION,
            config.hideHomepageProfile.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_PROFILE
        ).filter {
            it.first
        }.map {
            it.second
        }.toSet()
    }

    private val pruneBottomTabIds: HookParam.() -> Unit = pruneBottom@{
        val bottomTabIds = result as? MutableList<*> ?: run {
            YLog.error("$TAG: bottom tab ids is not a mutable list")
            return@pruneBottom
        }

        val removed = bottomTabIds.removeIf {
            it in tabIdsToRemove
        }
        if (!removed) {
            YLog.warn("$TAG: no blocklisted bottom tab ids were removed. blocklist: $tabIdsToRemove, bottomTabIds: $bottomTabIds")
        }
    }

    override fun onHook() {
        if (!ConfigManager.homeTab.bottomMainSwitch.value) {
            YLog.info("$TAG: bottom tab hiding disabled, skipping hook")
            return
        }

        installRemoveBottomTabItemsRemoteHook()
        installRemoveBottomTabItemsDefaultHook()
    }

    private fun installRemoveBottomTabItemsRemoteHook(): YukiMemberHookCreator.MemberHookCreator.Result? =
        packageInstance.homeTabDataSourceServer.selfClass?.resolveMethodOrNull(
            packageInstance.homeTabDataSourceServer.getShowingBottomTabIds()
        )?.hook {
            after {
                this.run(pruneBottomTabIds)
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to remove blocklisted ids from bottom tab", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook bottom tab hiding", throwable)
            }
        }

    private fun installRemoveBottomTabItemsDefaultHook(): YukiMemberHookCreator.MemberHookCreator.Result? =
        packageInstance.homeTabDataSourceDefault.selfClass?.resolveMethodOrNull(
            packageInstance.homeTabDataSourceDefault.getShowingBottomTabIds()
        )?.hook {
            after {
                this.run(pruneBottomTabIds)
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to remove blocklisted ids from default bottom tab", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook default bottom tab hiding", throwable)
            }
        }
}
