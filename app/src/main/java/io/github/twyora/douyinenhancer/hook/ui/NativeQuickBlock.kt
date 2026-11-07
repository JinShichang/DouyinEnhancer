package io.github.twyora.douyinenhancer.hook.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import io.github.twyora.douyinenhancer.hook.DouyinPackage
import io.github.twyora.douyinenhancer.hook.NativeCleanModeSymbols
import io.github.twyora.douyinenhancer.utils.Method
import io.github.twyora.douyinenhancer.utils.getField
import io.github.twyora.douyinenhancer.utils.invokeMethod
import io.github.twyora.douyinenhancer.utils.resolveMethod
import java.io.Serializable

/** Named feed and profile entry points verified against the 38.8.0 host. */
internal class NativeQuickBlock(panelClass: Class<*>, private val host: DouyinPackage) {
    private val loader = checkNotNull(panelClass.classLoader)
    private val playIconField = panelClass.getField("mLazyIvPlay")

    private val userService = loader.loadClass("com.ss.android.ugc.aweme.account.AccountProxyService").getMethod("userService")
    private val blockFlag = checkNotNull(host.user.selfClass).getField("isBlock")
    private val secureId = checkNotNull(host.user.selfClass).getField("secUid")
    private val dialogClass = loader.loadClass("com.ss.android.ugc.aweme.profile.ui.ProfileBlockPeopleFragment")
    val block = dialogClass.resolveMethod(Method("doBlock", emptyList()))
    val unblock = dialogClass.resolveMethod(Method("doUnBlock", emptyList()))
    private val newDialog = dialogClass.getConstructor()
    private val arguments = dialogClass.getMethod("setArguments", Bundle::class.java)
    private val showNow = dialogClass.getMethod(
        "showNow",
        loader.loadClass("androidx.fragment.app.FragmentManager"),
        String::class.java
    )

    data class Selection(
        val key: QuickBlockTarget,
        val holder: Any,
        val root: View,
        val pauseIcon: View,
        val author: Serializable,
        val activity: Activity,
        val source: String
    )

    fun isBlocked(author: Serializable): Boolean = blockFlag.getBoolean(author)

    fun pauseIcon(panel: Any): View? = (playIconField.get(panel) as? View)?.takeIf {
        it.isAttachedToWindow && it.isShown && it.hasWindowFocus()
    }

    fun authorAvatar(root: View): View? {
        val id = root.resources.getIdentifier("user_avatar", "id", root.context.packageName)
        return if (id != 0) root.findViewById(id) else null
    }

    private fun signedInAccount(): String? {
        val account = userService.invoke(null) ?: return null
        if (account.invokeMethod<Boolean>(Method("isLogin", emptyList())) != true) return null
        return account.invokeMethod<String>(Method("getCurUserId", emptyList()))
    }

    fun quickBlockAccount(dialog: Any): String? =
        dialog.invokeMethod<Bundle>(Method("getArguments", emptyList()))?.getString(QUICK_BLOCK_ACCOUNT)

    fun stillSignedIn(accountId: String): Boolean = runCatching { signedInAccount() == accountId }.getOrDefault(false)

    fun selection(panel: Any, onSkip: (String) -> Unit = {}): Selection? {
        fun skip(reason: String): Selection? {
            onSkip(reason)
            return null
        }

        val icon = pauseIcon(panel) ?: return skip("pause icon is not visible")
        if (panel.invokeMethod<Boolean>(NativeCleanModeSymbols.userVisible) != true) return skip("feed panel is hidden")
        val fragment = panel.invokeMethod<Any>(NativeCleanModeSymbols.getFragment) ?: return skip("missing feed fragment")
        if (fragment.invokeMethod<Boolean>(NativeCleanModeSymbols.fragmentAdded) != true) return skip("feed fragment is detached")
        val activity = fragment.invokeMethod<Activity>(NativeCleanModeSymbols.fragmentActivity) ?: return skip("missing feed activity")
        if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) return skip("feed window is inactive")
        val aweme = panel.invokeMethod<Any>(host.baseListFragmentPanel.getCurrentAweme()) ?: return skip("missing current video")
        if (aweme.invokeMethod<Boolean>(NativeCleanModeSymbols.isLive) != false ||
            aweme.invokeMethod<Any>(Method("getVideo", emptyList())) == null
        ) {
            return skip("unsupported feed item")
        }

        val holder = panel.invokeMethod<Any>(Method("getCurFeedViewHolder", emptyList())) ?: return skip("missing current holder")
        val holderAweme = holder.invokeMethod<Any>(Method("getAweme", emptyList())) ?: return skip("missing holder video")
        val videoId = aweme.getField<String>(host.aweme.aid()) ?: return skip("missing video identity")
        if (holderAweme.getField<String>(host.aweme.aid()) != videoId) return skip("holder video changed")
        val author = holderAweme.getField<Serializable>(host.aweme.author()) ?: return skip("missing author model")
        val authorId = author.getField<String>(host.user.uid()) ?: return skip("missing author identity")
        val panelAuthor = aweme.getField<Any>(host.aweme.author()) ?: return skip("missing panel author")
        if (panelAuthor.getField<String>(host.user.uid()) != authorId ||
            (secureId.get(author) as? String).isNullOrBlank()
        ) {
            return skip("author identity is incomplete or changed")
        }

        val accountId = signedInAccount() ?: return skip("not signed in")
        val key = QuickBlockTarget(videoId, authorId, accountId)
        if (!key.canBlock(key, isBlocked(author))) return skip("author is not eligible")
        val root = holder.invokeMethod<Any>(Method("getFeedUGView", emptyList()))
            ?.invokeMethod<View>(Method("getRootView", emptyList())) ?: return skip("missing holder layout")
        if (!root.isAttachedToWindow || !root.isShown) return skip("holder layout is hidden")
        val source = panel.invokeMethod<String>(NativeCleanModeSymbols.getEventType).orEmpty()
        return Selection(key, holder, root, icon, author, activity, source)
    }

    fun showConfirmation(selection: Selection) {
        val manager = checkNotNull(selection.activity.invokeMethod<Any>(Method("getSupportFragmentManager", emptyList())))
        if (manager.invokeMethod<Boolean>(Method("isStateSaved", emptyList())) != false ||
            manager.invokeMethod<Any>(Method("findFragmentByTag", listOf("java.lang.String")), DIALOG_TAG) != null
        ) {
            return
        }
        val dialog = newDialog.newInstance()
        arguments.invoke(
            dialog,
            Bundle().apply {
                putSerializable("user", selection.author)
                putString("enter_from", selection.source)
                putBoolean("is_show_block_it_see_me_view", false)
                putString(QUICK_BLOCK_ACCOUNT, selection.key.accountId)
            }
        )
        // Same confirmation Fragment as the profile menu, including its native request and result handling.
        showNow.invoke(dialog, manager, DIALOG_TAG)
    }

    private companion object {
        const val DIALOG_TAG = "ProfileBlockPeopleFragment"
        const val QUICK_BLOCK_ACCOUNT = "douyinenhancer.quick_block_account"
    }
}
