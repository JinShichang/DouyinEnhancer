package io.github.twyora.douyinenhancer.utils

import android.content.Context
import android.os.Handler
import android.widget.Toast
import androidx.annotation.StringRes

fun Context.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Handler(mainLooper).post {
        Toast.makeText(this, text, duration).show()
    }
}

fun Context.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    toast(this.getString(resId), duration)
}
