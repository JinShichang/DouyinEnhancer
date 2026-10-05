package io.github.twyora.douyinenhancer.hook.feed

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.HookOnMainProcess
import io.github.twyora.douyinenhancer.utils.getFieldOrNull
import io.github.twyora.douyinenhancer.utils.invokeMethodOrNull
import io.github.twyora.douyinenhancer.utils.resolveMethodOrNull

@HookOnMainProcess
object RecommendedFeedHooker : YukiBaseHooker() {
    private val TAG = this::class.simpleName

    private val packageInstance
        get() = DouyinPackage.instance

    private val verbose
        get() = !ConfigManager.module.verboseDisabled.value

    private val kwdFilterTitleRegexes by lazy {
        val titleList = ConfigManager.recommendedFeedFilter.titleKeywords.value
        val regexMode = ConfigManager.recommendedFeedFilter.titleRegexMode.value
        if (regexMode) {
            titleList.map {
                it.toRegex()
            }
        } else {
            titleList.map {
                Regex.escape(it).toRegex()
            }
        }
    }

    private val kwdFilterAuthorNicknameRegexes by lazy {
        val nicknameList = ConfigManager.recommendedFeedFilter.authorNicknameKeywords.value
        val regexMode = ConfigManager.recommendedFeedFilter.authorNicknameRegexMode.value
        if (regexMode) {
            nicknameList.map {
                it.toRegex()
            }
        } else {
            nicknameList.map {
                Regex.escape(it).toRegex()
            }
        }
    }

    private val kwdFilterDescRegexes by lazy {
        val descList = ConfigManager.recommendedFeedFilter.descKeywords.value
        val regexMode = ConfigManager.recommendedFeedFilter.descRegexMode.value
        if (regexMode) {
            descList.map {
                it.toRegex()
            }
        } else {
            descList.map {
                Regex.escape(it).toRegex()
            }
        }
    }

    override fun onHook() {
        if (!ConfigManager.recommendedFeedFilter.mainSwitch.value) {
            if (verbose) {
                YLog.debug("$TAG: recommended feed filter master switch disabled, skip feed filter hook")
            }
            return
        }

        packageInstance.feedResponseHandler.selfClass?.resolveMethodOrNull(
            packageInstance.feedResponseHandler.processAwemeList()
        )?.hook {
            before {
                val awemeList = args[2] as? MutableList<*> ?: return@before

                val removed = awemeList.removeIf {
                    with(ConfigManager.recommendedFeedFilter) {
                        // begin unvalidated filter condition
                        (blockAd.value &&
                            it?.invokeMethodOrNull<Boolean>(packageInstance.aweme.getAd()) == true) ||
                            (blockEcom.value &&
                                it?.invokeMethodOrNull<Boolean>(packageInstance.aweme.isEcomAweme()) == true) ||
                            (blockGrouponLargeCard.value &&
                                it?.getFieldOrNull<Any>(packageInstance.aweme.grouponLargeCard()) != null) ||
                            (blockGrouponLargeCard.value &&
                                it?.getFieldOrNull<Any>(packageInstance.aweme.grouponLargeCard()) != null) ||
                            (blockLive.value &&
                                it?.invokeMethodOrNull<Boolean>(packageInstance.aweme.isLive()) == true) ||
                            (blockMultiImage.value &&
                                it?.invokeMethodOrNull<Boolean>(packageInstance.aweme.isMultiImage()) == true) ||
                            // end unvalidated filter condition
                            (blockFollowedAuthor.value && it?.invokeMethodOrNull<Int>(
                                packageInstance.aweme.getFollowStatus()
                            ) != DouyinPackage.AwemeModule.FOLLOW_STATUS_UNFOLLOWED) ||
                            (it?.let { aweme ->
                                shouldFilterByDuration(aweme)
                            } == true) ||
                            (it?.let { aweme ->
                                shouldFilterByInteractionStats(aweme)
                            } == true) ||
                            (it?.let { aweme ->
                                shouldFilterByKeyword(aweme)
                            } == true)
                    }
                }
                if (!removed) {
                    YLog.warn("$TAG: no recommended feed aweme removed")
                }
            }
        }?.result {
            onConductFailure { _, throwable ->
                YLog.error("$TAG: failed to filter recommended feed aweme", throwable)
            }
            onHookingFailure { throwable ->
                YLog.error("$TAG: failed to hook for filtering recommended feed aweme", throwable)
            }
        }
    }

    private fun shouldFilterByDuration(aweme: Any): Boolean {
        val filter = ConfigManager.recommendedFeedFilter
        if (filter.shortDurationLimit.value > filter.longDurationLimit.value) {
            return false
        }
        if (aweme.invokeMethodOrNull<Boolean>(packageInstance.aweme.isNormalVideo()) == false) {
            return false
        }
        val duration = aweme.getFieldOrNull<Int>(packageInstance.aweme.duration()) ?: return false
        return duration != 0 && duration !in filter.shortDurationLimit.value..filter.longDurationLimit.value
    }

    private fun shouldFilterByInteractionStats(aweme: Any): Boolean {
        val statsMinLEMax = with(ConfigManager.recommendedFeedFilter) {
            collectCountMin.value <= collectCountMax.value || commentCountMin.value <= commentCountMax.value ||
                diggCountMin.value <= diggCountMax.value || shareCountMin.value <= shareCountMax.value
        }
        if (!statsMinLEMax) {
            return false
        }

        val statsObj = aweme.getFieldOrNull<Any>(packageInstance.aweme.statistics())
            ?: return false

        if (ConfigManager.recommendedFeedFilter.collectCountMin.value <= ConfigManager.recommendedFeedFilter.collectCountMax.value) {
            val collectCount = statsObj.getFieldOrNull<Long>(packageInstance.awemeStatistics.collectCount())
            if (collectCount != null && with(ConfigManager.recommendedFeedFilter) {
                    collectCount !in collectCountMin.value..collectCountMax.value
                }
            ) {
                if (verbose) {
                    YLog.debug("$TAG: filtered by collect count: $collectCount")
                }
                return true
            }
        }

        if (ConfigManager.recommendedFeedFilter.commentCountMin.value <= ConfigManager.recommendedFeedFilter.commentCountMax.value) {
            val commentCount = statsObj.getFieldOrNull<Long>(packageInstance.awemeStatistics.commentCount())
            if (commentCount != null && with(ConfigManager.recommendedFeedFilter) {
                    commentCount !in commentCountMin.value..commentCountMax.value
                }
            ) {
                if (verbose) {
                    YLog.debug("$TAG: filtered by comment count: $commentCount")
                }
                return true
            }
        }

        if (ConfigManager.recommendedFeedFilter.diggCountMin.value <= ConfigManager.recommendedFeedFilter.diggCountMax.value) {
            val diggCount = statsObj.getFieldOrNull<Long>(packageInstance.awemeStatistics.diggCount())
            if (diggCount != null && with(ConfigManager.recommendedFeedFilter) {
                    diggCount !in diggCountMin.value..diggCountMax.value
                }
            ) {
                if (verbose) {
                    YLog.debug("$TAG: filtered by digg count: $diggCount")
                }
                return true
            }
        }

        if (ConfigManager.recommendedFeedFilter.shareCountMin.value <= ConfigManager.recommendedFeedFilter.shareCountMax.value) {
            val shareCount = statsObj.getFieldOrNull<Long>(packageInstance.awemeStatistics.shareCount())
            if (shareCount != null && with(ConfigManager.recommendedFeedFilter) {
                    shareCount !in shareCountMin.value..shareCountMax.value
                }
            ) {
                if (verbose) {
                    YLog.debug("$TAG: filtered by share count: $shareCount")
                }
                return true
            }
        }

        return false
    }

    private fun shouldFilterByKeyword(aweme: Any): Boolean {
        val titleRegexes = kwdFilterTitleRegexes
        if (titleRegexes.isNotEmpty()) {
            val title = aweme.getFieldOrNull<String>(
                packageInstance.aweme.itemTitle()
            )
            if (!title.isNullOrBlank() && titleRegexes.any {
                    title.contains(it)
                }
            ) {
                if (verbose) {
                    YLog.debug("$TAG: filtered by title: $title")
                }
                return true
            }
        }

        val uidFilters = ConfigManager.recommendedFeedFilter.authorUidKeywords.value
        if (uidFilters.isNotEmpty()) {
            val authorObj = aweme.getFieldOrNull<Any>(packageInstance.aweme.author())
            if (authorObj != null) {
                val uid = authorObj.getFieldOrNull<String>(packageInstance.user.uid())
                if (uid != null && uid in uidFilters) {
                    if (verbose) {
                        YLog.debug("$TAG: filtered by author uid: $uid")
                    }
                    return true
                }
            }
        }

        val nicknameRegexes = kwdFilterAuthorNicknameRegexes
        if (nicknameRegexes.isNotEmpty()) {
            val authorObj = aweme.getFieldOrNull<Any>(packageInstance.aweme.author())
            if (authorObj != null) {
                val nickname = authorObj.getFieldOrNull<String>(packageInstance.user.nickname())
                if (!nickname.isNullOrBlank() && nicknameRegexes.any {
                        nickname.contains(it)
                    }
                ) {
                    if (verbose) {
                        YLog.debug("$TAG: filtered by author nickname: $nickname")
                    }
                    return true
                }
            }
        }

        val descRegexes = kwdFilterDescRegexes
        if (descRegexes.isNotEmpty()) {
            val desc = aweme.getFieldOrNull<String>(packageInstance.aweme.desc())
            if (!desc.isNullOrBlank() && descRegexes.any {
                    desc.contains(it)
                }
            ) {
                if (verbose) {
                    YLog.debug("$TAG: filtered by desc: $desc")
                }
                return true
            }
        }

        return false
    }
}
