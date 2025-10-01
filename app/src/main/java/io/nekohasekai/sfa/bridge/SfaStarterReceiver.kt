package io.nekohasekai.sfa.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bridge.BridgeFlags

class SfaStarterReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START = "io.nekohasekai.sfa.ACTION_START"
        const val ACTION_STOP  = "io.nekohasekai.sfa.ACTION_STOP"
        const val ACTION_ACK   = "io.nekohasekai.sfa.ACTION_ACK"
        const val EXTRA_SHOW_UI = "show_ui"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CALLER_PKG = "caller_pkg"
    }

    override fun onReceive(context: Context, intent: Intent) {
        BridgeFlags.markSuppressOnce()

        when (intent.action) {
            ACTION_STOP -> runCatching { BoxService.stop() }
            ACTION_START -> {
                runCatching { BoxService.start() }
                if (intent.getBooleanExtra(EXTRA_SHOW_UI, false)) {
                    val main = Intent().apply {
                        setClassName(context.packageName, "io.nekohasekai.sfa.ui.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    runCatching { context.startActivity(main) }
                }
            }
        }

        val reqId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val caller = intent.getStringExtra(EXTRA_CALLER_PKG)
        if (!reqId.isNullOrEmpty() && !caller.isNullOrEmpty()) {
            val ack = Intent(ACTION_ACK).apply {
                setPackage(caller)
                putExtra(EXTRA_REQUEST_ID, reqId)
            }
            runCatching {
                context.sendBroadcast(ack, "com.simplexray.an.permission.EXTERNAL_CONTROL")
            }
        }
    }
}
