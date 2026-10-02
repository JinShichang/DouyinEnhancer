package io.github.twyora.douyinenhancer.hook.comment

import android.content.Context
import android.net.Uri
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.FileTypeDetector
import io.github.twyora.douyinenhancer.utils.HookTransaction
import io.github.twyora.douyinenhancer.utils.getFieldOrNull
import io.github.twyora.douyinenhancer.utils.invokeMethodOnly
import io.github.twyora.douyinenhancer.utils.invokeMethodOrNull
import io.github.twyora.douyinenhancer.utils.invokeStaticMethodOrNull
import io.github.twyora.douyinenhancer.utils.resolveMethodOrNull
import io.github.twyora.douyinenhancer.utils.setFieldOrNull
import java.io.FileInputStream
import org.json.JSONObject

@HookOnMainProcess
object CommentAudioHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !ConfigManager.module.verboseDisabled.value

    override fun onHook() {
        if (!ConfigManager.save.downloadCommentAudio.value) {
            if (verbose) {
                YLog.debug("$TAG: download comment audio is disabled, skipping hook")
            }
            return
        }

        val transaction = HookTransaction(TAG)

        transaction.add(::installForceSaveImageVisibleHook.name) {
            installForceSaveImageVisibleHook()
        }
        transaction.add(::installAddSaveImageToWhiteListHook.name) {
            installAddSaveImageToWhiteListHook()
        }
        transaction.add(::installInjectAudioUrlOnSaveClickHook.name) {
            installInjectAudioUrlOnSaveClickHook()
        }
        transaction.add(::installSaveDownloadedAudioHook.name) {
            installSaveDownloadedAudioHook()
        }

        transaction.commit()
    }

    private fun installForceSaveImageVisibleHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.saveImageActionItem.selfClass?.resolveMethodOrNull(
            packageInstance.saveImageActionItem.isVisible()
        )?.hook {
            after {
                if ((result as? Boolean) == true) {
                    return@after
                }

                // if the comment contains audio, show the save image button
                val commentAudio = instance.getFieldOrNull<Any>(
                    packageInstance.commentLongPressItemModel.commentActionParams()
                )?.getFieldOrNull<Any>(
                    packageInstance.commentActionParams.comment()
                )?.getFieldOrNull<Any>(
                    packageInstance.comment.commentAudio()
                )
                if (commentAudio != null) {
                    if (verbose) {
                        YLog.debug("$TAG: comment contains audio, showing save image button")
                    }
                    resultTrue()
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to make save image button visible for audio", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook save image button for audio", throwable)
            }
        }
    }

    private fun installAddSaveImageToWhiteListHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.commentLongPressWhiteListProvider.selfClass?.resolveMethodOrNull(
            packageInstance.commentLongPressWhiteListProvider.buildWhiteList()
        )?.hook {
            after {
                @Suppress("UNCHECKED_CAST")
                val whiteList = result as? MutableSet<String> ?: run {
                    YLog.error("$TAG: white list result is not a MutableSet, cannot add save action")
                    return@after
                }
                if (whiteList.contains("save_image")) {
                    return@after
                }

                val commentAudio = args[0]?.getFieldOrNull<Any>(
                    packageInstance.commentActionParams.comment()
                )?.getFieldOrNull<Any>(
                    packageInstance.comment.commentAudio()
                )
                if (commentAudio != null) {
                    if (verbose) {
                        YLog.debug("$TAG: comment contains audio, added \"save_image\" to white list")
                    }
                    whiteList.add("save_image")
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to add \"save_image\" to white list", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook white list provider for audio", throwable)
            }
        }
    }

    private fun installInjectAudioUrlOnSaveClickHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.saveImageActionItem.selfClass?.resolveMethodOrNull(
            packageInstance.saveImageActionItem.onClick()
        )?.hook {
            before {
                val comment = instance.getFieldOrNull<Any>(
                    packageInstance.commentLongPressItemModel.commentActionParams()
                )?.getFieldOrNull<Any>(
                    packageInstance.commentActionParams.comment()
                ) ?: return@before

                val commentAudio = comment.getFieldOrNull<Any>(
                    packageInstance.comment.commentAudio()
                ) ?: return@before
                val commentAudioContent = commentAudio.getFieldOrNull<String>(
                    packageInstance.commentAudioStruct.content()
                ) ?: return@before

                val commentAudioJson = runCatching {
                    JSONObject(commentAudioContent)
                }.onFailure {
                    YLog.error("$TAG: failed to parse comment audio content as JSON", it)
                }.getOrNull() ?: return@before
                val commentAudioList = commentAudioJson.optJSONArray("video_list") ?: return@before
                val firstCommentAudio = commentAudioList.optJSONObject(0) ?: return@before
                val commentAudioMainUrl = firstCommentAudio.optString("main_url")
                val commentAudioBackupUrl = firstCommentAudio.optString("backup_url")
                val audioUrls = listOf(
                    commentAudioMainUrl,
                    commentAudioBackupUrl
                ).filter {
                    !it.isNullOrBlank()
                }.takeIf {
                    it.isNotEmpty()
                } ?: run {
                    YLog.error("$TAG: no valid audio URL found in comment audio content, cannot inject")
                    return@before
                }

                injectAudioUrl(comment, audioUrls)
                args[0] = 0

                if (verbose) {
                    YLog.debug("$TAG: injected ${audioUrls.size} audio URL(s) into comment")
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to inject audio URLs into save image download pipeline", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook save image download pipeline for audio", throwable)
            }
        }
    }

    private fun installSaveDownloadedAudioHook(): YukiMemberHookCreator.MemberHookCreator.Result? {
        return packageInstance.commentImageSaveDownloadListener.selfClass?.resolveMethodOrNull(
            packageInstance.commentImageSaveDownloadListener.onSuccessed()
        )?.hook {
            before {
                val downloadInfo = args[0] ?: return@before

                val sourcePath = downloadInfo.invokeMethodOrNull<String>(
                    packageInstance.downloadInfo.getTargetFilePath()
                ) ?: return@before
                val ftypeInfo = FileTypeDetector.detect(sourcePath)
                val dlUrl = downloadInfo.getFieldOrNull<String>(
                    packageInstance.downloadInfo.url()
                ) ?: return@before

                if (!dlUrl.contains("mime_type=audio")) {
                    if (verbose) {
                        YLog.debug("$TAG: download url is not an audio url, skipping saving audio")
                    }
                    return@before
                } else if (!ftypeInfo.mimeType.startsWith("audio/")) {
                    if (verbose) {
                        YLog.debug("$TAG: downloaded file MIME type is ${ftypeInfo.mimeType}, not an audio file, skipping saving audio")
                    }
                    return@before
                }

                val targetFileName = "audio_${
                    packageInstance.digestUtils.selfClass?.invokeStaticMethodOrNull<String>(
                        packageInstance.digestUtils.md5Hex(),
                        dlUrl + System.currentTimeMillis().toString()
                    )
                }.${ftypeInfo.extensions.first()}"

                if (verbose) {
                    YLog.debug("$TAG: audio file name: $targetFileName")
                }

                val context = instance.getFieldOrNull<Any>(
                    packageInstance.commentImageSaveDownloadListener.listenerProviderParam()
                )?.getFieldOrNull<Context>(
                    packageInstance.listenerProviderParam.context()
                ) ?: run {
                    YLog.error("$TAG: unable to get Context from download listener")
                    return@before
                }

                val targetUri = packageInstance.ugFileUtils.selfClass?.invokeStaticMethodOrNull<Uri>(
                    packageInstance.ugFileUtils.getAudioUri(),
                    context,
                    targetFileName,
                    ftypeInfo.mimeType,
                    "Music/douyin/audio",
                    instance.getFieldOrNull<Any>(
                        packageInstance.commentImageSaveDownloadListener.listenerProviderParam()
                    )?.getFieldOrNull<Any>(
                        packageInstance.listenerProviderParam.cert()
                    )
                ) ?: run {
                    YLog.error("$TAG: unable to create audio media URI for saving")
                    return@before
                }
                if (targetUri == Uri.EMPTY) {
                    YLog.error("$TAG: audio media URI is empty")
                    return@before
                }

                val copyState = runCatching {
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(sourcePath).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    true
                }.onFailure {
                    YLog.error("$TAG: failed to copy audio file to media store", it)
                }.getOrDefault(false)

                instance.invokeMethodOnly(
                    packageInstance.commentImageSaveDownloadListener.notifyResult(),
                    context,
                    copyState
                )

                resultNull()
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to save downloaded audio to media store", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook audio download completion callback", throwable)
            }
        }
    }

    private fun injectAudioUrl(comment: Any, audioUrls: List<String>): Boolean {
        val existingImageUrlList = comment.getFieldOrNull<List<*>>(
            packageInstance.comment.imageList()
        )
        val imageUrlList = if (existingImageUrlList.isNullOrEmpty()) {
            val newImageUrlList = listOf(
                packageInstance.commentImageStruct.selfClass?.createInstance()
            )
            comment.setFieldOrNull(
                packageInstance.comment.imageList(),
                newImageUrlList
            )
            newImageUrlList
        } else {
            existingImageUrlList
        }

        val existingImageUrlModel = imageUrlList.first()?.getFieldOrNull<Any?>(
            packageInstance.commentImageStruct.downloadUrl()
        )
        val imageUrlModel = if (existingImageUrlModel == null) {
            val newImageUrlModel = packageInstance.urlModel.selfClass?.createInstance()
            imageUrlList.first()?.setFieldOrNull(
                packageInstance.commentImageStruct.downloadUrl(),
                newImageUrlModel
            )
            newImageUrlModel
        } else {
            existingImageUrlList
        } ?: return false

        imageUrlModel.setFieldOrNull(
            packageInstance.urlModel.urlList(),
            audioUrls
        )

        return true
    }
}
