package io.github.twyora.douyinenhancer.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.twyora.douyinenhancer.R
import io.github.twyora.douyinenhancer.ui.navigation.AppRoutes
import io.github.twyora.douyinenhancer.ui.content.home.HomeContent
import io.github.twyora.douyinenhancer.ui.content.home.UiState as HomeContentUiState
import io.github.twyora.douyinenhancer.ui.content.home.HomeContentViewModel
import io.github.twyora.douyinenhancer.utils.openUrl
import kotlinx.coroutines.launch
import io.github.twyora.douyinenhancer.ui.content.home.UiActions as HomeContentUiActions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = run {
                    val currLocalContext = LocalContext.current
                    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    val dark = isSystemInDarkTheme()
                    when {
                        dynamic && dark -> dynamicDarkColorScheme(currLocalContext)
                        dynamic -> dynamicLightColorScheme(currLocalContext)
                        dark -> darkColorScheme()
                        else -> lightColorScheme()
                    }
                }
            ) {
                val backstack = rememberNavBackStack(AppRoutes.Home)
                NavDisplay(
                    backStack = backstack,
                    entryProvider = { key ->
                        when (key) {
                            AppRoutes.Home -> NavEntry(key) {
                                HomeScreen()
                            }

                            else -> NavEntry(key) {
                            }
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun HomeScreen(modifier: Modifier = Modifier) {
        val mainVm: HomeContentViewModel = viewModel()
        val mainUiState by mainVm.uiState.collectAsStateWithLifecycle()
        val currLocalContext = LocalContext.current

        val snackbarHostState = remember {
            SnackbarHostState()
        }
        val scope = rememberCoroutineScope()

        val docsUrl = stringResource(R.string.docs_url)
        val authorUrl = stringResource(R.string.author_url)
        val releasePageUrl = stringResource(R.string.latest_release_page_url)
        val repositoryUrl = stringResource(R.string.repository_url)
        val telegramUrl = stringResource(R.string.telegram_url)
        val deactivatedHint = stringResource(R.string.pref_about_activation_status_deactivated_summary)

        Scaffold(
            modifier = modifier,
            snackbarHost = {
                SnackbarHost(snackbarHostState)
            }
        ) { paddingValues ->
            val uiActions = remember {
                HomeContentUiActions(
                    onLauncherIconChange = {
                        mainVm.setLauncherIconHidden(it)
                    },
                    onOpenHelp = {
                        currLocalContext.openUrl(docsUrl)
                    },
                    onOpenAuthor = {
                        currLocalContext.openUrl(authorUrl)
                    },
                    onOpenRepository = {
                        currLocalContext.openUrl(repositoryUrl)
                    },
                    onViewRelease = {
                        currLocalContext.openUrl(releasePageUrl)
                    },
                    onJoinTelegramGroup = {
                        currLocalContext.openUrl(telegramUrl)
                    },
                    onNavigateToSettings = {
                        // Before the settings screen is migrated to the module side,
                        // we still need to use startActivity
                        if (mainUiState.moduleActivationState) {
                            currLocalContext.packageManager.getLaunchIntentForPackage(
                                "com.ss.android.ugc.aweme"
                            )?.run {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra("douyinenhancer_start_settings", true)
                                currLocalContext.startActivity(this)
                            }
                        } else if (snackbarHostState.currentSnackbarData != null) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = deactivatedHint,
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    }
                )
            }
            HomeContent(
                uiState = HomeContentUiState(
                    launcherIconHidden = mainUiState.launcherIconHidden,
                    moduleActivationState = mainUiState.moduleActivationState,
                    updateState = mainUiState.updateState
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                uiActions = uiActions,
            )
        }
    }
}