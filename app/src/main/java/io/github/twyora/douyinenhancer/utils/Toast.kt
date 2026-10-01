package io.github.twyora.douyinenhancer.utils

import android.app.Activity
import android.widget.Toast
import androidx.annotation.StringRes

fun Activity.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    this.runOnUiThread {
        Toast.makeText(this, text, duration).show()
    }
}

fun Activity.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    toast(this.getString(resId), duration)
}
