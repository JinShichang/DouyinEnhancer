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
        HomeContentUiState(
            launcherIconHidden = false,
            moduleActivationState = false,
            updateState = HomeContentUpdateState(
                latestVersionName = BuildConfig.VERSION_NAME,
                releaseBody = null
            )
        )
    )
    val uiState: StateFlow<HomeContentUiState> get() = uiStateInternal

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

                if (BuildConfig.VERSION_NAME != updateInfo.latestVersionName) {
                    updateNotifyEventInternal.tryEmit(Unit)
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

    private suspend fun fetchUpdateInfo(): HomeContentUpdateState? = runCatching {
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

        HomeContentUpdateState(
            latestVersionName = latestVersionName,
            releaseBody = releaseBody
        )
    }.onFailure {
        YLog.error("$TAG: fetch latest release failed", it)
    }.getOrNull()

    companion object {
        private val TAG = this::class.simpleName
    }
}
