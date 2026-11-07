package io.github.twyora.douyinenhancer.hook.ui

/** Identity of the paused item, including the account that would perform the action. */
internal data class QuickBlockTarget(val videoId: String, val authorId: String, val accountId: String) {
    fun canBlock(current: QuickBlockTarget, alreadyBlocked: Boolean): Boolean =
        this == current && videoId.isNotBlank() && authorId.isNotBlank() &&
            accountId.isNotBlank() && accountId != "0" && authorId != accountId && !alreadyBlocked
}
