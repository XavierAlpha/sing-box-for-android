package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.ui.MainActivity
import io.nekohasekai.sfa.bridge.BridgeStartContext
import io.nekohasekai.sfa.bridge.BridgeStopContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import android.os.Handler
import android.os.Looper

class BoxService(
    private val service: Service, private val platformInterface: PlatformInterface
) : CommandServerHandler {

    companion object {

        private const val ACK_TIMEOUT_MS: Long = 3000
        private const val ACK_RETRY_AT: Long   = 1200

        fun start() {
            val intent = runBlocking {
                withContext(Dispatchers.IO) {
                    Intent(Application.application, Settings.serviceClass())
                }
            }
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            val cause = BridgeStopContext.consume()
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName
                ).apply { if (cause != null) putExtra(Bridge.EXTRA_CAUSE, cause) }
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private var boxService: BoxService? = null
    private var commandServer: CommandServer? = null
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Action.SERVICE_CLOSE -> {
                    suppressLinkOnStop = (intent.getStringExtra(Bridge.EXTRA_CAUSE) == "remote")
                    stopService()
                }


                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        serviceUpdateIdleMode()
                    }
                }
            }
        }
    }

    private fun startCommandServer() {
        val commandServer = CommandServer(this, 300)
        commandServer.start()
        this.commandServer = commandServer
    }

    private var lastStartCause: String = "user" // "user" | "remote" | "system"
    private var suppressLinkOnStop: Boolean = false
    object Bridge {
        const val SX_PKG = "com.simplexray.an"

        const val ACT_FROM_SFA_START = "io.nekohasekai.sfa.CTRL_FROM_SFA_START"
        const val ACT_FROM_SFA_STOP  = "io.nekohasekai.sfa.CTRL_FROM_SFA_STOP"
        const val ACT_ACK_TO_SFA     = "com.simplexray.an.ACK_TO_SFA"

        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CALLER_PKG = "caller_pkg"
        const val EXTRA_CAUSE      = "cause"
        const val EXTRA_SHOW_UI    = "show_ui"
    }

    private fun isSimpleXrayInstalled(): Boolean = try {
        service.packageManager.getApplicationInfo("com.simplexray.an", 0); true
    } catch (_: Exception) { false }

    private fun linkSimpleXray(start: Boolean) {
        if (lastStartCause != "user") return
        if (!isSimpleXrayInstalled()) return

        val requestId = System.currentTimeMillis().toString()
        val ackAction = Bridge.ACT_ACK_TO_SFA
        val action = if (start) Bridge.ACT_FROM_SFA_START else Bridge.ACT_FROM_SFA_STOP

        val handler = Handler(Looper.getMainLooper())
        val filter = android.content.IntentFilter(ackAction)
        var gotAck = false
        val ackReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (requestId == i.getStringExtra(Bridge.EXTRA_REQUEST_ID)) {
                    gotAck = true
                    try { service.unregisterReceiver(this) } catch (_: Exception) { }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            service.registerReceiver(ackReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            service.registerReceiver(ackReceiver, filter)
        }

        val req = Intent(action).apply {
            setPackage(Bridge.SX_PKG)
            putExtra(Bridge.EXTRA_REQUEST_ID, requestId)
            putExtra(Bridge.EXTRA_CALLER_PKG, service.packageName)
            putExtra(Bridge.EXTRA_CAUSE, "user")
        }
        runCatching {
            service.sendBroadcast(req, "com.simplexray.an.permission.EXTERNAL_CONTROL")
        }

        handler.postDelayed({
            if (!gotAck) {
                runCatching {
                    service.sendBroadcast(req, "com.simplexray.an.permission.EXTERNAL_CONTROL")
                }
            }
        }, ACK_RETRY_AT)

        handler.postDelayed({
            try { service.unregisterReceiver(ackReceiver) } catch (_: Exception) { }
            if (!gotAck) {
                val i = Intent().apply {
                    setClassName(Bridge.SX_PKG, "com.simplexray.an.integration.SxStarterActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("op", if (start) "start" else "stop")
                }
                runCatching { service.startActivity(i) }
            }
        }, ACK_TIMEOUT_MS)
    }

    private var lastProfileName = ""
    private suspend fun startService() {
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = Settings.selectedProfile
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val content = File(profile.typed.path).readText()
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()
            Libbox.setMemoryLimit(!Settings.disableMemoryLimit)

            val newService = try {
                Libbox.newService(content, platformInterface)
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            newService.start()

            if (newService.needWIFIState()) {
                val wifiPermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
                if (!service.hasPermission(wifiPermission)) {
                    newService.close()
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            boxService = newService
            commandServer?.setService(boxService)
            status.postValue(Status.Started)
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
            linkSimpleXray(start = true)
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    override fun serviceReload() {
        notification.close()
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        boxService?.apply {
            runCatching {
                close()
            }.onFailure {
                writeLog("service: error when closing: $it")
            }
            Seq.destroyRef(refnum)
        }
        commandServer?.setService(null)
        commandServer?.resetLog()
        boxService = null
        runBlocking {
            startService()
        }
    }

    override fun postServiceClose() {
        // Not used on Android
    }

    override fun getSystemProxyStatus(): SystemProxyStatus {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (Application.powerManager.isDeviceIdleMode) {
            boxService?.pause()
        } else {
            boxService?.wake()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value != Status.Started) return
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            boxService?.apply {
                runCatching {
                    close()
                }.onFailure {
                    writeLog("service: error when closing: $it")
                }
                Seq.destroyRef(refnum)
            }
            commandServer?.setService(null)
            boxService = null
            DefaultNetworkMonitor.stop()

            commandServer?.apply {
                close()
                Seq.destroyRef(refnum)
            }
            commandServer = null
            Settings.startedByUser = false
            withContext(Dispatchers.Main) {
                status.value = Status.Stopped
                service.stopSelf()
            }
            if (!suppressLinkOnStop) {
                linkSimpleXray(start = false)
            } else {
                suppressLinkOnStop = false
            }
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        Settings.startedByUser = false
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        lastStartCause = BridgeStartContext.consumeOrDefault()

        if (status.value != Status.Stopped) return Service.START_NOT_STICKY
        status.value = Status.Starting

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(service, receiver, IntentFilter().apply {
                addAction(Action.SERVICE_CLOSE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = true
            try {
                startCommandServer()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
                return@launch
            }
            startService()
        }
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder {
        return binder
    }

    internal fun onDestroy() {
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun writeLog(message: String) {
        commandServer?.writeMessage(message)
    }

    internal fun sendNotification(notification: Notification) {
        val builder =
            NotificationCompat.Builder(service, notification.identifier).setShowWhen(false)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service, MainActivity::class.java
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                )
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        notification.identifier,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            Application.notification.notify(notification.typeID, builder.build())
        }
    }
}