package io.github.twyora.douyinenhancer.ui.content.home

data class UiState(val launcherIconHidden: Boolean, val moduleActivationState: Boolean, val updateState: UpdateState)

data class UpdateState(val latestVersionName: String, val releaseBody: String?)
