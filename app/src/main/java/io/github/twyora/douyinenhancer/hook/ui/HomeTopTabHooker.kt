package io.github.twyora.douyinenhancer.hook.ui

import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookParam
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.resolveMethodOrNull

@HookOnMainProcess
object HomeTopTabHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val tabIdsToRemove by lazy {
        // TODO
        setOf(DouyinPackage.TabNodeModule.TAB_ID_HOMEPAGE_TABLIVE)
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
        installRemoveTopTabItemsRemoteHook()
        installRemoveTopTabItemsDefaultHook()
    }


    private fun installRemoveTopTabItemsRemoteHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.homeTabDataSourceServer.selfClass?.resolveMethodOrNull(
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
    }

    private fun installRemoveTopTabItemsDefaultHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.homeTabDataSourceDefault.selfClass?.resolveMethodOrNull(
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
}