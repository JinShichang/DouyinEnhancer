package io.github.twyora.douyinenhancer.ui.content.home

data class HomeContentUiActions(
    val onLauncherIconChange: (Boolean) -> Unit = {},
    val onOpenHelp: () -> Unit = {},
    val onOpenAuthor: () -> Unit = {},
    val onOpenRepository: () -> Unit = {},
    val onViewRelease: () -> Unit = {},
    val onJoinTelegramGroup: () -> Unit = {},
    val onNavigateToSettings: () -> Unit = {}
)
