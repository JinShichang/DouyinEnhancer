package io.github.twyora.douyinenhancer.utils

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

fun Context.openUrl(url: String) = runCatching {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}
