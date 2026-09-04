package io.github.twyora.douyinenhancer.hook.ui

import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.FastKVConfigManager
import io.github.twyora.douyinenhancer.config.key.CleanModeKey
import io.github.twyora.douyinenhancer.config.key.ModuleKey
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.resolveMethod

/**
 * 清爽模式：隐藏播放页悬浮控件（头像、点赞、评论、分享、文案、音乐等），
 * 仅保留视频画面与底部进度条，实现干净清爽的全屏观看体验。
 */
@HookOnMainProcess
object CleanModeHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !FastKVConfigManager.module.getBoolean(ModuleKey.DISABLE_VERBOSE_LOGS, false)

    override fun onHook() {
        if (!FastKVConfigManager.settings.getBoolean(CleanModeKey.MAIN_SWITCH, false)) {
            if (verbose) {
                YLog.debug("$TAG: clean mode disabled, skip hook")
            }
            return
        }
        installCleanModeHook()
    }

    private fun installCleanModeHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.fluxComponentDataAction.selfClass?.resolveMethod(
            packageInstance.fluxComponentDataAction.getSet()
        )?.hook {
            after {
                val allowComponentSet = result as? MutableSet<*> ?: run {
                    YLog.error("$TAG: ${result?.javaClass?.name} is not a mutable set")
                    return@after
                }
                val fluxComponentClass = packageInstance.fluxComponentId.selfClass ?: run {
                    YLog.error("$TAG: unable to resolve FluxComponentId class")
                    return@after
                }
                // only clear feed-player component sets to avoid affecting other flux usages
                if (allowComponentSet.none { fluxComponentClass.isInstance(it) }) {
                    return@after
                }
                if (allowComponentSet.isNotEmpty()) {
                    if (verbose) {
                        YLog.debug("$TAG: clean mode clearing ${allowComponentSet.size} playback components")
                    }
                    allowComponentSet.clear()
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to enable clean mode", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook for enabling clean mode", throwable)
            }
        }
    }
}
