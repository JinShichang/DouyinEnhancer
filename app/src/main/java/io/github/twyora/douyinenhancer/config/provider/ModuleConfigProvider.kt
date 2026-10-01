package io.github.twyora.douyinenhancer.config.provider

import io.github.twyora.douyinenhancer.config.kvstorage.IKVStorage
import io.github.twyora.douyinenhancer.config.rule.IRule

class ModuleConfigProvider(
    kvConfig: IKVStorage,
    ruleContextProvider: () -> IRule.Context = {
        IRule.Context(hiddenFeatureEnabled = true)
    }
) : AbsConfigProvider(kvConfig, ruleContextProvider) {
    val verboseDisabled = configItem(DISABLE_VERBOSE_LOGS, false)
    val notifyUpdateCooldown = configItem(NOTIFY_UPDATE_COOLDOWN, NOTIFY_UPDATE_COOLDOWN_PERIOD)
    val lastVerifiedVersion = configItem(LAST_VERIFIED_VERSION, 0)
    val hookInfoGeneration = configItem(HOOK_INFO_GENERATION, 0)
    val hookInfoSource = configItem(HOOK_INFO_SOURCE, HOOK_INFO_SOURCE_BUILTIN)

    companion object {
        const val DISABLE_VERBOSE_LOGS = "disable_verbose_logs"
        const val NOTIFY_UPDATE_COOLDOWN = "notify_update_cooldown"
        const val LAST_VERIFIED_VERSION = "last_verified_version"
        const val HOOK_INFO_GENERATION = "hook_info_generation"
        const val HOOK_INFO_SOURCE = "hook_info_source"

        const val NOTIFY_UPDATE_COOLDOWN_PERIOD = 3

        const val HOOK_INFO_SOURCE_BUILTIN = 0
        const val HOOK_INFO_SOURCE_TRUSTED_OVERLAY = 1
        const val HOOK_INFO_SOURCE_UNTRUSTED_OVERLAY = 2
    }
}
