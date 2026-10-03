package io.github.twyora.douyinenhancer.ui.content.home

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.twyora.douyinenhancer.BuildConfig
import io.github.twyora.douyinenhancer.R
import io.github.twyora.douyinenhancer.config.ConfigManager
import io.github.twyora.douyinenhancer.config.provider.ModuleConfigProvider
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HomeContentViewModel(application: Application) : AndroidViewModel(application) {
    private val launcherAlias = ComponentName(
        application,
        "io.github.twyora.douyinenhancer.ui.MainActivityAlias"
    )

    private val uiStateInternal = MutableStateFlow(
        UiState(
            launcherIconHidden = false,
            moduleActivationState = false,
            updateState = UpdateState(
                latestVersionName = BuildConfig.VERSION_NAME,
                releaseBody = null
            )
        )
    )
    val uiState: StateFlow<UiState> get() = uiStateInternal

    private val updateNotifyEventInternal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updateNotifyEvent: SharedFlow<Unit> get() = updateNotifyEventInternal

    init {
        viewModelScope.launch {
            uiStateInternal.update {
                it.copy(
                    launcherIconHidden = getApplication<Application>().packageManager.getComponentEnabledSetting(
                        launcherAlias
                    ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    moduleActivationState = YukiHookAPI.Status.isModuleActive
                )
            }
            fetchUpdateInfo()?.let { updateInfo ->
                uiStateInternal.update {
                    it.copy(updateState = updateInfo)
                }

                ConfigManager.module.notifyUpdateCooldown.run {
                    val period = ModuleConfigProvider.NOTIFY_UPDATE_COOLDOWN_PERIOD
                    value = (value - 1 + period) % period

                    if (value == 0) {
                        updateNotifyEventInternal.tryEmit(Unit)
                    }
                }
            }
        }
    }

    fun setLauncherIconHidden(hidden: Boolean) {
        runCatching {
            getApplication<Application>().packageManager.setComponentEnabledSetting(
                launcherAlias,
                if (hidden) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                },
                PackageManager.DONT_KILL_APP
            )
        }.onSuccess {
            uiStateInternal.update {
                it.copy(launcherIconHidden = hidden)
            }
        }
    }

    private suspend fun fetchUpdateInfo(): UpdateState? = runCatching {
        val latestReleaseJson = withContext(Dispatchers.IO) {
            JSONObject(
                URL(
                    getApplication<Application>().getString(
                        R.string.latest_release_api_url
                    )
                ).readText()
            )
        }

        val latestVersionName = latestReleaseJson.optString("name").removePrefix("v").removePrefix("V")
        val releaseBody = latestReleaseJson.optString("body")

        if (latestVersionName.isNotBlank() && BuildConfig.VERSION_NAME != latestVersionName) {
            UpdateState(
                latestVersionName = latestVersionName,
                releaseBody = releaseBody
            )
        } else {
            null
        }
    }.onFailure {
        YLog.error("$TAG: fetch latest release failed", it)
    }.getOrNull()

    companion object {
        private val TAG = this::class.simpleName
    }
}
