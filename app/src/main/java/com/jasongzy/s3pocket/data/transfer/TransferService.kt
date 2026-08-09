package com.jasongzy.s3pocket.data.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.S3PocketApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TransferService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var observer: Job? = null
  private val manager by lazy { (application as S3PocketApplication).container.transferManager }

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notifications = getSystemService(NotificationManager::class.java)
      notifications.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, getString(R.string.transfers), NotificationManager.IMPORTANCE_LOW),
      )
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    showNotification(manager.items.value.filter { !it.status.isFinished })
    if (observer?.isActive != true) {
      observer = scope.launch {
        manager.items.collect { items ->
          val active = items.filter { !it.status.isFinished }
          if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
          } else {
            showNotification(active)
          }
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    manager.cancelActive()
    stopSelf(startId)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun showNotification(active: List<TransferItem>) {
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(active),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )
  }

  private fun buildNotification(active: List<TransferItem>): android.app.Notification {
    val completed = active.sumOf { it.completedBytes.coerceAtMost(it.totalBytes) }
    val total = active.sumOf { it.totalBytes }
    val progress = if (total > 0) (completed * 100 / total).toInt() else 0
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ms_upload_file_24)
      .setContentTitle(getString(R.string.transfers))
      .setContentText(getString(R.string.transfer_notification, active.size))
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setProgress(100, progress, total <= 0)
      .build()
  }

  private companion object {
    const val CHANNEL_ID = "transfers"
    const val NOTIFICATION_ID = 1001
  }
}
