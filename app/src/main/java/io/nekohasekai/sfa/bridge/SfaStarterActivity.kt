package io.nekohasekai.sfa.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sfa.bg.BoxService

class SfaStarterActivity : Activity() {
    companion object {
        const val EXTRA_OP = "op"  // "start" | "stop"
        const val EXTRA_SHOW_UI = "show_ui"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val op = intent.getStringExtra(EXTRA_OP) ?: "start"
        if (op == "stop") {
            BridgeStopContext.markRemote()
            runCatching { BoxService.stop() }
        } else {
            BridgeStartContext.markRemote()
            runCatching { BoxService.start() }
            if (intent.getBooleanExtra(EXTRA_SHOW_UI, false)) {
                val main = Intent().apply {
                    setClassName(packageName, "io.nekohasekai.sfa.compose.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { startActivity(main) }
            }
        }
        finish()
    }
}
