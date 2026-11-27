package io.github.caffelatte23.futaba.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import io.github.caffelatte23.futaba.EXTRA_FOCUS_COMPOSER
import io.github.caffelatte23.futaba.MainActivity

class CaptureTileService : TileService() {
    // Android 13 and earlier require the Intent overload.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val capture =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_FOCUS_COMPOSER, true)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, capture, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(capture)
        }
    }
}
