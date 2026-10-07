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
object HomeTopTabHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val tabIdsToRemove by lazy {
        val config = ConfigManager.homeTab
        listOf(
            config.hideHomepageFollow.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_FOLLOW,
            config.hideHomepageFamiliar.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_FAMILIAR,
            config.hideHomepageGroupon.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_GROUPON,
            config.hideHomepageHotContainer.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_HOT_CONTAINER,
            config.hideHomepageNearby.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_NEARBY,
            config.hideHomepageMediumvideo.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_MEDIUMVIDEO,
            config.hideHomepagePadHot.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_PAD_HOT,
            config.hideHomepageHangout.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_HANGOUT,
            config.hideHomepageTablive.value to DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_TABLIVE
        ).filter {
            it.first
        }.map {
            it.second
        }.toSet()
    }

    private val pruneTopTabIds: HookParam.() -> Unit = pruneTop@{
        val topTabIds = result as? MutableList<*> ?: run {
            YLog.error("$TAG: top tab ids is not a mutable list")
            return@pruneTop
        }

        val removed = topTabIds.removeIf {
            it in tabIdsToRemove
        }
        if (!removed) {
            YLog.warn("$TAG: no blocklisted top tab ids were removed. blocklist: $tabIdsToRemove, topTabIds: $topTabIds")
        }
    }

    override fun onHook() {
        if (!ConfigManager.homeTab.topMainSwitch.value) {
            YLog.info("$TAG: top tab hiding disabled, skipping hook")
            return
        }

        installRemoveTopTabItemsRemoteHook()
        installRemoveTopTabItemsDefaultHook()
    }

    private fun installRemoveTopTabItemsRemoteHook(): YukiMemberHookCreator.MemberHookCreator.Result? =
        packageInstance.homeTabDataSourceServer.selfClass?.resolveMethodOrNull(
            packageInstance.homeTabDataSourceServer.getShowingTopTabIds()
        )?.hook {
            after {
                this.run(pruneTopTabIds)
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to remove blocklisted ids from top tab", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook top tab hiding", throwable)
            }
        }

    private fun installRemoveTopTabItemsDefaultHook(): YukiMemberHookCreator.MemberHookCreator.Result? =
        packageInstance.homeTabDataSourceDefault.selfClass?.resolveMethodOrNull(
            packageInstance.homeTabDataSourceDefault.getShowingTopTabIds()
        )?.hook {
            after {
                this.run(pruneTopTabIds)
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to remove blocklisted ids from default top tab", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook default top tab hiding", throwable)
            }
        }
}
