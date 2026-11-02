package com.armorberserk.rotip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground-планировщик: крутит Engine.rotate каждые Engine.cooldownMs.
 * specialUse_foreground — по-минимальному без Google сносов.
 * Остановка: Intent ACTION_STOP.
 */
class RotateSchedulerService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startFgs()
        scope.launch {
            while (scope.isActive) {
                val cooldown = Engine.cooldownRemaining()
                kotlinx.coroutines.delay(cooldown.coerceAtLeast(engineTickMs))
                val r = Engine.rotate(applicationContext)
                withContext(Dispatchers.Main) {
                    updateNotification("посл. IP: ${r.ipAfter ?: "—"}")
                }
            }
        }
        return START_STICKY
    }

    private fun startFgs() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_scheduler), NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(getString(R.string.idle_notification))
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun buildNotification(text: String): Notification {
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rotate)
            .setContentTitle("RotIP")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        return b.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "rotip_sched"
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.armorberserk.rotip.ACTION_STOP_SCHEDULER"
        const val engineTickMs = 5_000L
    }
}
