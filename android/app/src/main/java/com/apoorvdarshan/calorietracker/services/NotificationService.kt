package com.apoorvdarshan.calorietracker.services

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.apoorvdarshan.calorietracker.MainActivity
import com.apoorvdarshan.calorietracker.R
import java.util.Calendar

/**
 * Schedules + fires the two local reminders the app supports:
 *   - Streak reminder: daily at user-chosen time (default 7:00pm)
 *   - Daily summary: daily at user-chosen time (default 9:00pm)
 *
 * Uses inexact alarms (setAndAllowWhileIdle) — a daily nudge firing within a
 * few minutes of the chosen time is perfectly fine, and Play Store reserves
 * the exact-alarm permission for calendar / alarm-clock apps only.
 *
 * Also owns the "weight_goal" channel used from WeightRepository crossings.
 *
 * OriginOS / MIUI / HyperOS aggressive battery optimization can still kill
 * inexact alarms unless the app is whitelisted — Settings UI exposes a deep
 * link to the battery-optimization exception screen.
 */
class NotificationService(private val context: Context) {

    fun createChannels() {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val streak = NotificationChannel(
            CHANNEL_STREAK,
            "Streak Reminder",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Daily reminder to log a meal and keep your streak." }

        val daily = NotificationChannel(
            CHANNEL_DAILY,
            "Daily Summary",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "End-of-day summary of your calories + macros." }

        val goal = NotificationChannel(
            CHANNEL_WEIGHT_GOAL,
            "Weight Goal",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Celebration when you reach your goal weight." }

        mgr.createNotificationChannels(listOf(streak, daily, goal))
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun showGoalReached() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val content = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_WEIGHT_GOAL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Congratulations! 🎉")
            .setContentText("You reached your goal weight.")
            .setContentIntent(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notifySafely(GOAL_NOTIFICATION_ID, notif)
    }

    // -- Scheduling ------------------------------------------------------

    fun scheduleStreakReminder(hour: Int, minute: Int) = schedule(
        REQUEST_STREAK, hour, minute, CHANNEL_STREAK,
        title = "Don't forget to log your meals",
        text = "Keep your streak going — log something from today."
    )

    fun scheduleDailySummary(hour: Int, minute: Int) = schedule(
        REQUEST_DAILY, hour, minute, CHANNEL_DAILY,
        title = "Today's summary is ready",
        text = "Tap to see how today's macros lined up."
    )

    fun cancelStreakReminder() = cancel(REQUEST_STREAK)
    fun cancelDailySummary() = cancel(REQUEST_DAILY)

    private fun schedule(requestCode: Int, hour: Int, minute: Int, channel: String, title: String, text: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_CHANNEL, channel)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_REQUEST, requestCode)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val now = Calendar.getInstance()
        val fire = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }

        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire.timeInMillis, pi)
    }

    private fun cancel(requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    companion object {
        const val CHANNEL_STREAK = "streak_reminder"
        const val CHANNEL_DAILY = "daily_summary"
        const val CHANNEL_WEIGHT_GOAL = "weight_goal"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_REQUEST = "request"
        private const val GOAL_NOTIFICATION_ID = 4242
        private const val REQUEST_STREAK = 1001
        private const val REQUEST_DAILY = 1002
    }
}

/** Fired by the alarm. Posts the notification and re-schedules the same alarm +24h. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channel = intent.getStringExtra(NotificationService.EXTRA_CHANNEL) ?: return
        val title = intent.getStringExtra(NotificationService.EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(NotificationService.EXTRA_TEXT) ?: return
        val request = intent.getIntExtra(NotificationService.EXTRA_REQUEST, -1)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notifySafely(request, notif)

        // Re-arm for +24h so the reminder fires daily.
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextFire = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        val reIntent = Intent(context, ReminderReceiver::class.java).apply { putExtras(intent) }
        val pi = PendingIntent.getBroadcast(
            context, request, reIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFire, pi)
    }
}

private fun NotificationManagerCompat.notifySafely(id: Int, notif: android.app.Notification) {
    runCatching { notify(id, notif) } // swallow SecurityException if permission revoked.
}
