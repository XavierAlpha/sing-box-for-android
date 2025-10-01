package io.nekohasekai.sfa.bridge

import java.util.concurrent.atomic.AtomicBoolean

object BridgeFlags {
    private val flag = AtomicBoolean(false)

    @JvmStatic
    fun markSuppressOnce() {
        flag.set(true)
    }

    @JvmStatic
    fun consumeSuppressOnce(): Boolean {
        return flag.getAndSet(false)
    }
}
