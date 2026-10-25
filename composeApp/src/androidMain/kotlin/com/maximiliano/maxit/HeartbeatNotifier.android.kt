// Modified for Maxit, 2026-10-20.
package com.maximiliano.maxit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.shared.R
import kotlinx.coroutines.runBlocking
import maxit.composeapp.generated.resources.Res
import maxit.composeapp.generated.resources.notification_channel_description
import maxit.composeapp.generated.resources.notification_channel_name
import maxit.composeapp.generated.resources.notification_response_channel_description
import maxit.composeapp.generated.resources.notification_response_channel_name
import org.jetbrains.compose.resources.getString
import org.koin.java.KoinJavaComponent.inject

/**
 * Intent extra read by MainActivity when the user taps a heartbeat notification. The
 * receiver forwards the signal to `DataRepository.requestOpenHeartbeat()` so the
 * ChatViewModel observer can load the heartbeat conversation.
 */
const val EXTRA_OPEN_HEARTBEAT = "com.maximiliano.maxit.OPEN_HEARTBEAT"

/** Shared with the AI `send_notification` tool — ensures the channel is created once. */
private const val CHANNEL_ID = "kai_ai_notifications"

// New ID: Android cannot raise the importance of the already-created default channel.
private const val RESPONSE_CHANNEL_ID = "maxit_response_alerts"

/**
 * Fixed ID so a new heartbeat report replaces any earlier unread one in the tray
 * instead of piling up. The app only ever has one pending heartbeat conversation.
 */
private const val HEARTBEAT_NOTIFICATION_ID = 9002

actual fun sendHeartbeatNotification(title: String, body: String) {
    postNotification(title, body, openHeartbeat = true, notificationId = HEARTBEAT_NOTIFICATION_ID)
}

actual fun shouldSendLlmNotification(): Boolean {
    val scheduler: TaskScheduler by inject(TaskScheduler::class.java)
    return !scheduler.appInForeground
}

const val EXTRA_OPEN_CONVERSATION = "com.maximiliano.maxit.OPEN_CONVERSATION"

actual fun sendLlmNotification(title: String, body: String, conversationId: String?, requestId: String?) {
    // Separate request IDs keep simultaneous approvals visible; the completion uses its own ID.
    postNotification(title, body, openHeartbeat = false, notificationId = requestId?.hashCode() ?: 9003, conversationId = conversationId, channelId = RESPONSE_CHANNEL_ID)
}

private fun postNotification(title: String, body: String, openHeartbeat: Boolean, notificationId: Int, conversationId: String? = null, channelId: String = CHANNEL_ID) {
    val context: Context by inject(Context::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    ensureChannel(notificationManager, channelId)

    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (openHeartbeat) putExtra(EXTRA_OPEN_HEARTBEAT, true)
        if (conversationId != null) putExtra(EXTRA_OPEN_CONVERSATION, conversationId)
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        notificationId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(if (channelId == RESPONSE_CHANNEL_ID) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    try {
        notificationManager.notify(notificationId, notification)
    } catch (_: SecurityException) {
        // Permission may be revoked between the check and posting.
    }
}

private fun ensureChannel(manager: NotificationManager, channelId: String) {
    if (manager.getNotificationChannel(channelId) != null) return
    val name = runBlocking { getString(if (channelId == RESPONSE_CHANNEL_ID) Res.string.notification_response_channel_name else Res.string.notification_channel_name) }
    val description = runBlocking { getString(if (channelId == RESPONSE_CHANNEL_ID) Res.string.notification_response_channel_description else Res.string.notification_channel_description) }
    manager.createNotificationChannel(
        NotificationChannel(channelId, name, if (channelId == RESPONSE_CHANNEL_ID) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT).apply {
            this.description = description
        },
    )
}
