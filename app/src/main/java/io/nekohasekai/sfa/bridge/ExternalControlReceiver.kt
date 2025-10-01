package io.nekohasekai.sfa.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.ui.MainActivity

class ExternalControlReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START = "io.nekohasekai.sfa.ACTION_START"
        const val ACTION_STOP  = "io.nekohasekai.sfa.ACTION_STOP"
        const val EXTRA_SHOW_UI = "show_ui"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                runCatching { BoxService.start() }
                    .onFailure { Log.e("SFA-Bridge", "start failed", it) }
                if (intent.getBooleanExtra(EXTRA_SHOW_UI, false)) {
                    val i = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    runCatching { context.startActivity(i) }
                }
            }
            ACTION_STOP -> {
                runCatching { BoxService.stop() }
            }
        }
    }
}