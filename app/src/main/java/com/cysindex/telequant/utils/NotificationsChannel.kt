package com.cysindex.telequant.utils

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cysindex.telequant.R
 class NotificationsChannel{

    private fun createChannelIfNeeded(context: Context) {
        NotificationChannelCompat.Builder("set.location", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setName(context.getString(R.string.title))
            setDescription(context.getString(R.string.des))
        }.build().also {
            NotificationManagerCompat.from(context).createNotificationChannel(it)
        }
    }

    private fun createNotification(context: Context, options: (NotificationCompat.Builder) -> Unit): Notification {
        createChannelIfNeeded(context)
        return NotificationCompat.Builder(context, "set.location").apply { options(this) }.build()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Returns null when POST_NOTIFICATIONS has not been granted. Android 13+
     * silently drops the notification in that case, so the caller gets to know
     * rather than assuming it was shown.
     */
    fun showNotification(context: Context, options: (NotificationCompat.Builder) -> Unit): Notification? {
        if (!hasPermission(context)) return null
        val notification = createNotification(context, options)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        return notification
    }

    fun cancelAllNotifications(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
    }

    private companion object {
        const val NOTIFICATION_ID = 123
    }
}