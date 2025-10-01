package io.nekohasekai.sfa.bridge

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object BridgeStartContext {
    private val causeRef = AtomicReference<String?>(null)
    private val ts = AtomicLong(0)

    @JvmStatic
    fun markRemote() {
        causeRef.set("remote")
        ts.set(SystemClock.elapsedRealtime())
    }

    @JvmStatic
    fun markUser() {
        causeRef.set("user")
        ts.set(SystemClock.elapsedRealtime())
    }

    @JvmStatic
    fun consumeOrDefault(): String {
        val now = SystemClock.elapsedRealtime()
        val c = causeRef.getAndSet(null)
        return if (c != null && (now - ts.get()) <= 5000) c else "user"
    }
}

object BridgeStopContext {
    private val causeRef = AtomicReference<String?>(null)

    @JvmStatic
    fun markRemote() { causeRef.set("remote") }

    @JvmStatic
    fun consume(): String? = causeRef.getAndSet(null)
}
