package io.github.twyora.douyinenhancer.hook.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBlockTargetTest {
    private val paused = QuickBlockTarget("video-1", "author-1", "viewer-1")

    @Test
    fun onlyCurrentPausedSelectionCanBeBlocked() {
        assertTrue(paused.canBlock(paused.copy(), alreadyBlocked = false))
        assertFalse(paused.canBlock(paused.copy(videoId = "video-2"), alreadyBlocked = false))
        assertFalse(paused.canBlock(paused.copy(authorId = "author-2"), alreadyBlocked = false))
        assertFalse(paused.canBlock(paused.copy(accountId = "viewer-2"), alreadyBlocked = false))
    }

    @Test
    fun cannotBlockSelfAlreadyBlockedAuthorOrMissingIdentity() {
        assertFalse(paused.canBlock(paused, alreadyBlocked = true))
        listOf(
            paused.copy(accountId = paused.authorId),
            paused.copy(accountId = "0"),
            paused.copy(accountId = ""),
            paused.copy(videoId = ""),
            paused.copy(authorId = "")
        ).forEach { target -> assertFalse(target.canBlock(target, alreadyBlocked = false)) }
    }
}
