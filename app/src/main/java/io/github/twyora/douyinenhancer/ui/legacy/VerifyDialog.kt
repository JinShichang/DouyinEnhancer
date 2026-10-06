package io.github.twyora.douyinenhancer.ui.legacy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import com.highcapable.yukihookapi.hook.factory.injectModuleAppResources
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.BuildConfig
import io.github.twyora.douyinenhancer.R
import io.github.twyora.douyinenhancer.bridge.ModuleApp
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.databinding.VerifyDialogBinding
import io.github.twyora.douyinenhancer.utils.toast
import java.util.Locale

class VerifyDialog(context: Context) : AlertDialog.Builder(ContextThemeWrapper(context, R.style.MainTheme)) {
    private val binding = VerifyDialogBinding.inflate(
        LayoutInflater.from(ContextThemeWrapper(context, R.style.MainTheme))
    )

    init {
        setView(binding.root)
        setTitle(ModuleApp.instance.resources.getString(R.string.verify_dialog_title))
        setNegativeButton(android.R.string.cancel, null)
        // just shows the positive button; click handling is set in show
        setPositiveButton(android.R.string.ok, null)
    }

    override fun show(): AlertDialog {
        val dialog = super.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val inputUrl = binding.verifyInput.text.toString()
            val valid = ModuleApp.instance.resources.getStringArray(
                R.array.valid_verification_urls
            ).any {
                inputUrl.contains(it, ignoreCase = true)
            }
            if (valid) {
                ConfigManager.module.lastVerifiedVersion.value = BuildConfig.VERSION_CODE
                dialog.dismiss()

                (context as? Activity)?.toast(ModuleApp.instance.resources.getString(R.string.verify_toast_success))
            } else {
                (context as? Activity)?.toast(ModuleApp.instance.resources.getString(R.string.verify_toast_failure))
            }
        }
        return dialog
    }

    companion object {
        private val TAG = this::class.simpleName

        fun show(context: Context) {
            if (!shouldVerify(context)) {
                YLog.info("$TAG: no verification required, skipping VerifyDialog")
                return
            }

            runCatching {
                context.injectModuleAppResources()
                VerifyDialog(context).show()
            }.onFailure {
                YLog.error("$TAG: failed to show VerifyDialog", it)
            }
        }

        fun shouldVerify(context: Context): Boolean {
            val isChineseLocale = runCatching {
                context.resources.configuration.locales[0].language ==
                    Locale.CHINESE.language
            }.onFailure {
                YLog.error("$TAG: failed to read device locale", it)
            }.getOrDefault(false)
            val lastVerifiedVersion = ConfigManager.module.lastVerifiedVersion.value

            return !(
                BuildConfig.DEBUG || !isChineseLocale ||
                    BuildConfig.VERSION_CODE == lastVerifiedVersion
                )
        }
    }
}
