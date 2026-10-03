package io.github.twyora.douyinenhancer.config.provider

import io.github.twyora.douyinenhancer.config.kvstorage.IKVStorage
import io.github.twyora.douyinenhancer.config.rule.IRule

class BottomTabBlockConfigProvider(
    kvConfig: IKVStorage,
    ruleContextProvider: () -> IRule.Context = {
        IRule.Context(hiddenFeatureEnabled = true)
    }
) : AbsConfigProvider(kvConfig, ruleContextProvider) {
    val mainSwitch = configItem(MAIN_SWITCH, false)
    val hideHomepageHome = configItem(HIDE_HOMEPAGE_HOME, false)
    val hideHomepageMall = configItem(HIDE_HOMEPAGE_MALL, false)
    val hideHomepagePublish = configItem(HIDE_HOMEPAGE_PUBLISH, false)
    val hideHomepageNotification = configItem(HIDE_HOMEPAGE_NOTIFICATION, false)
    val hideHomepageProfile = configItem(HIDE_HOMEPAGE_PROFILE, false)

    companion object {
        const val MAIN_SWITCH = "bottom_tab_main_switch"
        const val HIDE_HOMEPAGE_HOME = "bottom_tab_hide_homepage_home"
        const val HIDE_HOMEPAGE_MALL = "bottom_tab_hide_homepage_mall"
        const val HIDE_HOMEPAGE_PUBLISH = "bottom_tab_hide_homepage_publish"
        const val HIDE_HOMEPAGE_NOTIFICATION = "bottom_tab_hide_homepage_notification"
        const val HIDE_HOMEPAGE_PROFILE = "bottom_tab_hide_homepage_profile"
    }
}
