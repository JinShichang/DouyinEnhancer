package io.github.twyora.douyinenhancer.ui.content.home

data class HomeContentUiState(val launcherIconHidden: Boolean, val moduleActivationState: Boolean, val updateState: HomeContentUpdateState)

data class HomeContentUpdateState(val latestVersionName: String, val releaseBody: String?)
