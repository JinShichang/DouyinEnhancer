@file:Suppress("DEPRECATION")

package io.github.twyora.douyinenhancer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceFragment
import android.view.ContextThemeWrapper
import com.highcapable.yukihookapi.hook.factory.injectModuleAppResources
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.R
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.config.gate.ConfigStateMode
import io.github.twyora.douyinenhancer.config.kvstorage.FastKVStorage
import io.github.twyora.douyinenhancer.utils.Field
import io.github.twyora.douyinenhancer.utils.setFieldOrNull

class BottomTabBlockDialog(context: Context) : AlertDialog.Builder(ContextThemeWrapper(context, R.style.MainTheme)) {
    class PrefsFragment : PreferenceFragment() {
        @Deprecated("Deprecated in Java")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            preferenceManager.setFieldOrNull(
                Field("mSharedPreferences"),
                ((ConfigManager.bottomTab.kvConfig as FastKVStorage).fastKV) as SharedPreferences
            )
            preferenceManager.setFieldOrNull(Field("mEditor"), null)
            addPreferencesFromResource(R.xml.pref_bottom_tab_block)

            ConfigManager.bottomTab.allConfigItems.filter { configItem ->
                configItem.status != ConfigStateMode.NORMAL
            }.forEach { hiddenConfigItem ->
                findPreference(hiddenConfigItem.key)?.let {
                    preferenceScreen?.removePreference(it)
                }
            }
        }
    }

    init {
        val activity = context as Activity

        val prefsFragment = PrefsFragment()
        activity.fragmentManager.beginTransaction().add(prefsFragment, "BottomTabBlock").commit()
        activity.fragmentManager.executePendingTransactions()

        setView(prefsFragment.view)
        setTitle(R.string.bottom_tab_block_dialog_title)
        setNegativeButton(android.R.string.cancel, null)
        setPositiveButton(android.R.string.ok, null)
        setOnDismissListener {
            activity.fragmentManager.beginTransaction().remove(prefsFragment).commitAllowingStateLoss()
        }
    }

    companion object {
        private val TAG = this::class.simpleName

        fun show(context: Context) {
            runCatching {
                (context as? Activity)?.injectModuleAppResources()
                BottomTabBlockDialog(context).show()
            }.onFailure {
                YLog.error("$TAG: failed to show bottom tab block dialog", it)
            }
        }
    }
}
