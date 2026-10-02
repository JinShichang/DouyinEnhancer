package io.github.twyora.douyinenhancer.hook.feed

import com.highcapable.kavaref.extension.createInstance
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.HookTransaction
import io.github.twyora.douyinenhancer.utils.getFieldOrNull
import io.github.twyora.douyinenhancer.utils.getStaticFieldOrNull
import io.github.twyora.douyinenhancer.utils.invokeMethodOrNull
import io.github.twyora.douyinenhancer.utils.invokeStaticMethodOrNull
import io.github.twyora.douyinenhancer.utils.resolveMethodOrNull
import io.github.twyora.douyinenhancer.utils.setFieldOrNull

@HookOnMainProcess
object FeedDownloadHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !ConfigManager.module.verboseDisabled.value

    override fun onHook() {
        if (!ConfigManager.save.feedDownloadBypass.value) {
            if (verbose) {
                YLog.debug("$TAG: bypass feed download is disabled, skipping hook")
            }
            return
        }
        val transaction = HookTransaction(TAG)

        transaction.add(::installForceActionStatusNormalHook.name) {
            installForceActionStatusNormalHook()
        }
        transaction.add(::installOverrideAwemeDownloadStatusHook.name) {
            installOverrideAwemeDownloadStatusHook()
        }
        transaction.add(::installOverridePrivacyVideoDownloadStatusHook.name) {
            installOverridePrivacyVideoDownloadStatusHook()
        }

        transaction.commit()
    }

    private fun installForceActionStatusNormalHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.absPermissionChecker.selfClass?.resolveMethodOrNull(
            packageInstance.absPermissionChecker.getActionCheckResult()
        )?.hook {
            after {
                val actionCheckResult = result ?: run {
                    YLog.warn(
                        "$TAG: action permission check result is null"
                    )
                    return@after
                }
                val actionStatus = actionCheckResult.getFieldOrNull<Any>(
                    packageInstance.actionCheckResult.actionStatus()
                ) ?: run {
                    YLog.warn("$TAG: action status is null")
                    return@after
                }

                val normalStatus = packageInstance.actionStatus.selfClass?.getStaticFieldOrNull<Any>(
                    packageInstance.actionStatus.normal()
                )

                if (actionStatus != normalStatus) {
                    if (verbose) {
                        YLog.debug("$TAG: forcing action status from $actionStatus to $normalStatus to allow download")
                    }
                    actionCheckResult.setFieldOrNull(
                        packageInstance.actionCheckResult.actionStatus(),
                        normalStatus
                    )
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to force feed download status to normal", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook action status check for forcing download allowed", throwable)
            }
        }
    }

    private fun installOverrideAwemeDownloadStatusHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.galleryShareHelper.selfClass?.resolveMethodOrNull(
            packageInstance.galleryShareHelper.startDownload()
        )?.hook {
            before {
                val aweme = args[0] ?: return@before
                val downloadStatus = aweme.invokeMethodOrNull<Int>(
                    packageInstance.aweme.getDownloadStatus()
                )

                if (verbose) {
                    YLog.debug("$TAG: aweme download status: $downloadStatus")
                }

                if (downloadStatus != 0) {
                    if (verbose) {
                        YLog.debug("$TAG: resetting aweme download status from $downloadStatus to 0 to allow download")
                    }
                    aweme.getFieldOrNull<Any>(
                        packageInstance.aweme.status()
                    )?.setFieldOrNull(
                        packageInstance.awemeStatus.downloadStatus(),
                        0
                    )
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to reset aweme download status", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook download action for resetting aweme status", throwable)
            }
        }
    }

    private fun installOverridePrivacyVideoDownloadStatusHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.sharePrivacyVideoApi.selfClass?.resolveMethodOrNull(
            packageInstance.sharePrivacyVideoApi.getDownloadStatus()
        )?.hook {
            before {
                val itemId = args[0] as? String
                if (verbose) {
                    YLog.debug("$TAG: privacy video download status query aweme id: $itemId")
                }

                val response = packageInstance.sharePrivacyVideoApi.privacyVideoResponse.selfClass?.createInstance() ?: run {
                    YLog.error("$TAG: failed to build the allowed-download response")
                    return@before
                }
                response.setFieldOrNull(
                    packageInstance.sharePrivacyVideoApi.privacyVideoResponse.msg(),
                    ""
                )
                response.setFieldOrNull(
                    packageInstance.sharePrivacyVideoApi.privacyVideoResponse.status(),
                    0
                )

                val observable = packageInstance.rxObservable.selfClass?.invokeStaticMethodOrNull<Any>(
                    packageInstance.rxObservable.just(),
                    response
                ) ?: run {
                    YLog.error("$TAG: failed to warp the allowed-download response")
                    return@before
                }
                result = observable
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to bypass privacy video download status check", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook privacy video download status query", throwable)
            }
        }
    }
}
