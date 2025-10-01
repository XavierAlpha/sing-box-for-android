package io.nekohasekai.sfa.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.constant.Action

class SfaStarterReceiver : BroadcastReceiver() {

    companion object {
        const val ACT_FROM_SX_START = "com.simplexray.an.CTRL_FROM_SX_START"
        const val ACT_FROM_SX_STOP  = "com.simplexray.an.CTRL_FROM_SX_STOP"

        const val ACT_ACK_TO_SX     = "io.nekohasekai.sfa.ACK_TO_SX"

        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CALLER_PKG = "caller_pkg"
        const val EXTRA_CAUSE      = "cause"    // "user" | "remote" | "system"
        const val EXTRA_SHOW_UI    = "show_ui"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val op = when (intent.action) {
            ACT_FROM_SX_START -> "start"
            ACT_FROM_SX_STOP  -> "stop"
            else -> return
        }

        if (op == "stop") {
            Application.application.sendBroadcast(
                Intent(io.nekohasekai.sfa.constant.Action.SERVICE_CLOSE)
                    .setPackage(Application.application.packageName)
                    .putExtra(EXTRA_CAUSE, "remote")
            )
        } else {
            BridgeStartContext.markRemote()

            val svc = Intent(Application.application, Settings.serviceClass())
            ContextCompat.startForegroundService(Application.application, svc)
        }

        val reqId  = intent.getStringExtra(EXTRA_REQUEST_ID)
        val caller = intent.getStringExtra(EXTRA_CALLER_PKG)
        if (!reqId.isNullOrEmpty() && !caller.isNullOrEmpty()) {
            val ack = Intent(ACT_ACK_TO_SX).apply {
                setPackage(caller)
                putExtra(EXTRA_REQUEST_ID, reqId)
            }
            runCatching { context.sendBroadcast(ack) }
        }
    }
}
