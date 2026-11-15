package com.lumo.studio.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lumo.studio.MainActivity

object Notifier {
    private const val CH_PROGRESS = "lumo_progress"
    private const val CH_DONE = "lumo_done"
    const val ID_PROGRESS = 1001
    private const val ID_DONE = 1002

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "پیشرفت عملیات", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "پایان عملیات", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(ctx: Context, title: String, done: Int, total: Int, paused: Boolean): Notification {
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (paused) "متوقف شد · $done از $total" else "$done از $total")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx))
        if (total > 0) b.setProgress(total, done, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    fun updateProgress(ctx: Context, title: String, done: Int, total: Int, paused: Boolean) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(ID_PROGRESS, progress(ctx, title, done, total, paused))
        }
    }

    /** اعلان پایان عملیات (قابلیت ۲۵). بدون مجوز اعلان، بی‌صدا نادیده گرفته می‌شود. */
    fun done(ctx: Context, title: String, text: String) {
        runCatching {
            val n = NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("PhotoFlow · $title")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open(ctx))
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
        }
    }
}

/** سرویس پیش‌زمینه تا پردازش‌های طولانی با خروج از برنامه متوقف نشوند (قابلیت ۲۳). */
class JobService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val n = Notifier.progress(this, intent?.getStringExtra("title") ?: "Lumo", 0, 0, false)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.ID_PROGRESS, n)
        }
        return START_NOT_STICKY
    }
}
