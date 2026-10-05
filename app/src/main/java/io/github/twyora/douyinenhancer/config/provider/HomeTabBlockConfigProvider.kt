package io.github.twyora.douyinenhancer.config.provider

import io.github.twyora.douyinenhancer.config.kvstorage.IKVStorage
import io.github.twyora.douyinenhancer.config.rule.IRule

class HomeTabBlockConfigProvider(
    kvConfig: IKVStorage,
    ruleContextProvider: () -> IRule.Context = {
        IRule.Context(hiddenFeatureEnabled = true)
    }
) : AbsConfigProvider(kvConfig, ruleContextProvider) {
    val bottomMainSwitch = configItem(BOTTOM_MAIN_SWITCH, false)
    val hideHomepageHome = configItem(HIDE_HOMEPAGE_HOME, false)
    val hideHomepageMall = configItem(HIDE_HOMEPAGE_MALL, false)
    val hideHomepagePublish = configItem(HIDE_HOMEPAGE_PUBLISH, false)
    val hideHomepageNotification = configItem(HIDE_HOMEPAGE_NOTIFICATION, false)
    val hideHomepageProfile = configItem(HIDE_HOMEPAGE_PROFILE, false)

    val topMainSwitch = configItem(TOP_MAIN_SWITCH, false)
    val hideHomepageFollow = configItem(HIDE_HOMEPAGE_FOLLOW, false)
    val hideHomepageFamiliar = configItem(HIDE_HOMEPAGE_FAMILIAR, false)
    val hideHomepageGroupon = configItem(HIDE_HOMEPAGE_GROUPON, false)
    val hideHomepageHotContainer = configItem(HIDE_HOMEPAGE_HOT_CONTAINER, false)
    val hideHomepageNearby = configItem(HIDE_HOMEPAGE_NEARBY, false)
    val hideHomepageMediumvideo = configItem(HIDE_HOMEPAGE_MEDIUMVIDEO, false)
    val hideHomepagePadHot = configItem(HIDE_HOMEPAGE_PAD_HOT, false)
    val hideHomepageHangout = configItem(HIDE_HOMEPAGE_HANGOUT, false)
    val hideHomepageTablive = configItem(HIDE_HOMEPAGE_TABLIVE, false)

    companion object {
        const val BOTTOM_MAIN_SWITCH = "bottom_tab_main_switch"
        const val HIDE_HOMEPAGE_HOME = "bottom_tab_hide_homepage_home"
        const val HIDE_HOMEPAGE_MALL = "bottom_tab_hide_homepage_mall"
        const val HIDE_HOMEPAGE_PUBLISH = "bottom_tab_hide_homepage_publish"
        const val HIDE_HOMEPAGE_NOTIFICATION = "bottom_tab_hide_homepage_notification"
        const val HIDE_HOMEPAGE_PROFILE = "bottom_tab_hide_homepage_profile"

        const val TOP_MAIN_SWITCH = "top_tab_main_switch"
        const val HIDE_HOMEPAGE_FOLLOW = "top_tab_hide_homepage_follow"
        const val HIDE_HOMEPAGE_FAMILIAR = "top_tab_hide_homepage_familiar"
        const val HIDE_HOMEPAGE_GROUPON = "top_tab_hide_homepage_groupon"
        const val HIDE_HOMEPAGE_HOT_CONTAINER = "top_tab_hide_homepage_hot_container"
        const val HIDE_HOMEPAGE_NEARBY = "top_tab_hide_homepage_nearby"
        const val HIDE_HOMEPAGE_MEDIUMVIDEO = "top_tab_hide_homepage_mediumvideo"
        const val HIDE_HOMEPAGE_PAD_HOT = "top_tab_hide_homepage_pad_hot"
        const val HIDE_HOMEPAGE_HANGOUT = "top_tab_hide_homepage_hangout"
        const val HIDE_HOMEPAGE_TABLIVE = "top_tab_hide_homepage_tablive"
    }
}
