package com.example.patientalert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || !AlarmState.monitoring(context)) return
        val accessibilityId = "${context.packageName}/${ShutterKeyService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (!enabledServices.contains(accessibilityId, ignoreCase = true)) {
            showResumeNotification(context, "افتح منقذ وأعد تفعيل استقبال زر الريموت")
            return
        }
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 blocks mediaPlayback foreground services from BOOT_COMPLETED.
            // Ask for a user tap so monitoring starts through the normal app flow.
            showResumeNotification(context, "اضغط لفتح منقذ واستعادة المراقبة بعد إعادة التشغيل")
            return
        }
        try {
            val serviceIntent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (_: RuntimeException) {
            showResumeNotification(context, "افتح منقذ لاستعادة المراقبة بعد إعادة التشغيل")
        }
    }

    private fun showResumeNotification(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "استعادة المراقبة", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        val openApp = PendingIntent.getActivity(
            context,
            92,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL)
        } else {
            Notification.Builder(context)
        }.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("منقذ يحتاج إلى إعادة تشغيل المراقبة")
            .setContentText(message)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "resume_after_boot"
        private const val NOTIFICATION_ID = 72
    }
}
