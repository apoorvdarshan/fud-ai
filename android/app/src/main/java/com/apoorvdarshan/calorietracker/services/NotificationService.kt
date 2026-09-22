package com.apoorvdarshan.calorietracker.services

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.apoorvdarshan.calorietracker.FudAIApp
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.FudAILinks
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateChecker
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.apoorvdarshan.calorietracker.MainActivity
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.formatFastingDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Schedules + fires the local reminders the app supports:
 *   - Weight log reminder: daily at 8:00am — gated by the master Notifications
 *     toggle so it tracks the system permission state and never fires silently
 *   - Streak reminder + Daily summary: present in code for parity with iOS but
 *     not wired to the Settings UI yet
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
            context.getString(R.string.notif_channel_streak),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_streak_desc) }

        val daily = NotificationChannel(
            CHANNEL_DAILY,
            context.getString(R.string.notif_channel_daily),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = context.getString(R.string.notif_channel_daily_desc) }

        val goal = NotificationChannel(
            CHANNEL_WEIGHT_GOAL,
            context.getString(R.string.notif_channel_weight_goal),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.notif_channel_weight_goal_desc) }

        val weight = NotificationChannel(
            CHANNEL_WEIGHT_LOG,
            context.getString(R.string.notif_channel_weight_log),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_weight_log_desc) }

        val bodyFat = NotificationChannel(
            CHANNEL_BODY_FAT_LOG,
            context.getString(R.string.notif_channel_body_fat),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_body_fat_desc) }

        val appUpdate = NotificationChannel(
            CHANNEL_APP_UPDATE,
            context.getString(R.string.notif_channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_updates_desc) }

        val water = NotificationChannel(
            CHANNEL_WATER,
            context.getString(R.string.notif_channel_water),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_water_desc) }

        val announcements = NotificationChannel(
            CHANNEL_ANNOUNCEMENTS,
            context.getString(R.string.notif_channel_announcements),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_announcements_desc) }

        mgr.createNotificationChannels(listOf(streak, daily, goal, weight, bodyFat, appUpdate, water, announcements))
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureFastingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FASTING,
                context.getString(R.string.notif_channel_fasting),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_fasting_desc) }
        )
    }

    fun removeFastingChannel() {
        cancelFastingGoal()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.deleteNotificationChannel(CHANNEL_FASTING)
        }
    }

    fun scheduleFastingGoal(session: FastingSession?) {
        cancelFastingGoal()
        if (session == null || !session.isActive) return
        val triggerAt = session.goalAt.toEpochMilli()
        if (triggerAt <= System.currentTimeMillis()) return
        ensureFastingChannel()

        val intent = explicitBroadcastIntent(FastingGoalReceiver::class.java) {
            putExtra(EXTRA_FASTING_GOAL_MINUTES, session.goalMinutes)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_FASTING,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    fun cancelFastingGoal() {
        val pending = broadcastPendingIntent(
            REQUEST_FASTING,
            FastingGoalReceiver::class.java,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pending != null) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(pending)
            pending.cancel()
        }
    }

    fun showGoalReached() {
        val content = activityPendingIntent(0, MainActivity::class.java)
        val notif = NotificationCompat.Builder(context, CHANNEL_WEIGHT_GOAL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_goal_weight_title))
            .setContentText(context.getString(R.string.notif_goal_weight_text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notifySafely(context, GOAL_NOTIFICATION_ID, notif)
    }

    /** Post a "new version available" notification. Tapping it opens the Play Store listing
     *  (market:// → web fallback), mirroring the About screen's open-store behavior. */
    fun showUpdateAvailable() {
        val pi = AndroidUpdateChecker.playStoreLaunchPendingIntent(context, REQUEST_APP_UPDATE)
        val notif = NotificationCompat.Builder(context, CHANNEL_APP_UPDATE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_update_title))
            .setContentText(context.getString(R.string.notif_update_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notifySafely(context, APP_UPDATE_NOTIFICATION_ID, notif)
    }

    // -- Product Hunt launch (one-shot, absolute time) -------------------

    /**
     * Arms the one-shot Product Hunt launch reminder exactly once. Safe to call on every
     * launch: no-op once the flag is set, and retried on later launches while the
     * notification permission is still missing.
     *
     * @return true when the reminder is wanted but POST_NOTIFICATIONS is not granted, so the
     *   caller can run the permission request and call this again on grant.
     */
    suspend fun scheduleProductHuntLaunchReminderIfNeeded(prefs: PreferencesStore): Boolean {
        if (prefs.productHuntLaunchNotificationScheduled.first()) return false
        // Respect the in-app Notifications master toggle — never ask or arm while opted out.
        // Leave the scheduled flag clear so enabling notifications later can still arm it.
        if (!prefs.notificationsEnabled.first()) return false
        when (val plan = ProductHuntLaunchReminder.plan(System.currentTimeMillis())) {
            ProductHuntLaunchReminder.Plan.Skip -> {
                prefs.setProductHuntLaunchNotificationScheduled(true)
                return false
            }
            ProductHuntLaunchReminder.Plan.FireNow -> {
                if (!canPostNotifications()) return true
                showProductHuntLaunch()
            }
            is ProductHuntLaunchReminder.Plan.ScheduleAt -> {
                if (!canPostNotifications()) return true
                scheduleProductHuntLaunch(plan.triggerAtMillis)
            }
        }
        prefs.setProductHuntLaunchNotificationScheduled(true)
        return false
    }

    fun scheduleProductHuntLaunch(triggerAtMillis: Long) {
        val pending = broadcastPendingIntent(
            REQUEST_PRODUCT_HUNT,
            ProductHuntLaunchReceiver::class.java,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ) ?: return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
    }

    fun cancelProductHuntLaunch() = cancel(REQUEST_PRODUCT_HUNT)

    /** Post the launch notification now. Tapping it opens the Product Hunt page. */
    fun showProductHuntLaunch() {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_PRODUCT_HUNT,
            Intent(Intent.ACTION_VIEW, Uri.parse(FudAILinks.PRODUCT_HUNT)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = context.getString(R.string.notif_product_hunt_text)
        val notif = NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_product_hunt_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notifySafely(context, PRODUCT_HUNT_NOTIFICATION_ID, notif)
    }

    // -- Scheduling ------------------------------------------------------

    fun scheduleStreakReminder(hour: Int, minute: Int) = schedule(
        REQUEST_STREAK, hour, minute, CHANNEL_STREAK,
        title = context.getString(R.string.notif_streak_title),
        text = context.getString(R.string.notif_streak_text)
    )

    fun scheduleDailySummary(hour: Int, minute: Int) = schedule(
        REQUEST_DAILY, hour, minute, CHANNEL_DAILY,
        title = context.getString(R.string.notif_summary_title),
        text = context.getString(R.string.notif_summary_text)
    )

    fun scheduleWeightReminder(hour: Int = 8, minute: Int = 0) = schedule(
        REQUEST_WEIGHT, hour, minute, CHANNEL_WEIGHT_LOG,
        title = context.getString(R.string.notif_weight_log_title),
        text = context.getString(R.string.notif_weight_log_text)
    )

    fun scheduleBodyFatReminder(hour: Int = 8, minute: Int = 0) = schedule(
        REQUEST_BODY_FAT, hour, minute, CHANNEL_BODY_FAT_LOG,
        title = context.getString(R.string.notif_body_fat_log_title),
        text = context.getString(R.string.notif_body_fat_log_text)
    )

    fun scheduleWaterReminder(hour: Int = 14, minute: Int = 0) = schedule(
        REQUEST_WATER, hour, minute, CHANNEL_WATER,
        title = context.getString(R.string.notif_water_title),
        text = context.getString(R.string.notif_water_text)
    )

    fun cancelStreakReminder() = cancel(REQUEST_STREAK)
    fun cancelDailySummary() = cancel(REQUEST_DAILY)
    fun cancelWeightReminder() = cancel(REQUEST_WEIGHT)
    fun cancelBodyFatReminder() = cancel(REQUEST_BODY_FAT)
    fun cancelWaterReminder() = cancel(REQUEST_WATER)

    private fun explicitBroadcastIntent(
        cls: Class<*>,
        configure: Intent.() -> Unit = {}
    ): Intent = Intent(context, cls).apply {
        component = ComponentName(context, cls)
        setPackage(context.packageName)
        configure()
    }

    private fun broadcastPendingIntent(
        requestCode: Int,
        cls: Class<*>,
        flags: Int,
        configure: Intent.() -> Unit = {}
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        requestCode,
        explicitBroadcastIntent(cls, configure),
        flags
    )

    private fun activityPendingIntent(requestCode: Int, cls: Class<*>): PendingIntent {
        val intent = Intent(context, cls).apply {
            component = ComponentName(context, cls)
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun schedule(requestCode: Int, hour: Int, minute: Int, channel: String, title: String, text: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = broadcastPendingIntent(
            requestCode,
            ReminderReceiver::class.java,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ) {
            putExtra(EXTRA_CHANNEL, channel)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_REQUEST, requestCode)
        } ?: return

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
        val pi = broadcastPendingIntent(
            requestCode,
            ReminderReceiver::class.java,
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
        const val CHANNEL_WEIGHT_LOG = "weight_log_reminder"
        const val CHANNEL_BODY_FAT_LOG = "body_fat_log_reminder"
        const val CHANNEL_APP_UPDATE = "app_update"
        const val CHANNEL_WATER = "water_reminder"
        const val CHANNEL_FASTING = "fasting_goal"
        const val CHANNEL_ANNOUNCEMENTS = "announcements"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_FASTING_GOAL_MINUTES = "fasting_goal_minutes"
        private const val GOAL_NOTIFICATION_ID = 4242
        private const val APP_UPDATE_NOTIFICATION_ID = 5555
        private const val PRODUCT_HUNT_NOTIFICATION_ID = 6666
        private const val REQUEST_STREAK = 1001
        private const val REQUEST_DAILY = 1002
        private const val REQUEST_WEIGHT = 1003
        private const val REQUEST_BODY_FAT = 1004
        private const val REQUEST_APP_UPDATE = 1005
        private const val REQUEST_WATER = 1006
        private const val REQUEST_FASTING = 1007
        private const val REQUEST_PRODUCT_HUNT = 1008
    }
}

/**
 * When to remind users that Fud AI is live on Product Hunt: at the launch moment
 * (Sept 27, 2026, 12:01 AM Pacific), immediately if the user updates during launch
 * day, and never once that day has passed. Pure so the window logic is testable.
 */
object ProductHuntLaunchReminder {
    val LAUNCH_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    val LAUNCH_AT_MILLIS: Long =
        ZonedDateTime.of(2026, 9, 27, 0, 1, 0, 0, LAUNCH_ZONE).toInstant().toEpochMilli()
    private const val LAUNCH_DAY_MILLIS = 24L * 60 * 60 * 1000

    sealed interface Plan {
        /** Launch day is over — a late reminder would just be noise. */
        data object Skip : Plan
        /** Already inside launch day; fire right away so the user can still vote. */
        data object FireNow : Plan
        data class ScheduleAt(val triggerAtMillis: Long) : Plan
    }

    fun plan(nowMillis: Long, launchAtMillis: Long = LAUNCH_AT_MILLIS): Plan = when {
        nowMillis >= launchAtMillis + LAUNCH_DAY_MILLIS -> Plan.Skip
        nowMillis >= launchAtMillis -> Plan.FireNow
        else -> Plan.ScheduleAt(launchAtMillis)
    }
}

/** Fired by the one-shot Product Hunt launch alarm. */
class ProductHuntLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext as? FudAIApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = app?.container?.prefs?.notificationsEnabled?.first() == true &&
                    NotificationService(context).canPostNotifications()
                if (enabled) {
                    NotificationService(context).showProductHuntLaunch()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** One-shot fasting-goal alert. The session itself stays active until the user ends it. */
class FastingGoalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val goalMinutes = intent.getIntExtra(
            NotificationService.EXTRA_FASTING_GOAL_MINUTES,
            16 * 60
        )
        val openIntent = Intent(context, MainActivity::class.java).apply {
            component = ComponentName(context, MainActivity::class.java)
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = context.getString(
            R.string.notif_fasting_goal_text,
            formatFastingDuration(goalMinutes.toLong() * 60)
        )
        val notification = NotificationCompat.Builder(context, NotificationService.CHANNEL_FASTING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_fasting_goal_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notifySafely(context, 1007, notification)
    }
}

/** Restores an active fast's one-shot goal alarm after reboot or app replacement. */
class FastingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        val app = context.applicationContext as FudAIApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = app.container.prefs.notificationsEnabled.first() &&
                    app.container.prefs.fastingTrackingEnabled.first() &&
                    app.container.prefs.fastingGoalNotificationEnabled.first() &&
                    app.container.notifications.canPostNotifications()
                if (enabled) {
                    app.container.notifications.scheduleFastingGoal(app.container.fastingRepository.active())
                } else {
                    app.container.notifications.cancelFastingGoal()
                }
                // Alarms don't survive a reboot. If we already intended to remind the user,
                // re-arm a future ScheduleAt — or fire immediately when boot lands during launch day.
                val phEnabled = app.container.prefs.notificationsEnabled.first() &&
                    app.container.prefs.productHuntLaunchNotificationScheduled.first() &&
                    app.container.notifications.canPostNotifications()
                if (phEnabled) {
                    when (val phPlan = ProductHuntLaunchReminder.plan(System.currentTimeMillis())) {
                        is ProductHuntLaunchReminder.Plan.ScheduleAt ->
                            app.container.notifications.scheduleProductHuntLaunch(phPlan.triggerAtMillis)
                        ProductHuntLaunchReminder.Plan.FireNow ->
                            app.container.notifications.showProductHuntLaunch()
                        ProductHuntLaunchReminder.Plan.Skip -> Unit
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Fired by the alarm. Posts the notification when eligible and re-schedules it +24h. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channel = intent.getStringExtra(NotificationService.EXTRA_CHANNEL) ?: return
        val title = intent.getStringExtra(NotificationService.EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(NotificationService.EXTRA_TEXT) ?: return
        val request = intent.getIntExtra(NotificationService.EXTRA_REQUEST, -1)

        // Re-arm first so suppressing today's streak nudge (or a transient storage failure)
        // never disables tomorrow's explicitly enabled reminder.
        rearm(context, intent, request)

        if (channel == NotificationService.CHANNEL_DAILY) {
            postDailySummary(context, title, text, request)
            return
        }

        if (channel != NotificationService.CHANNEL_STREAK) {
            postNotification(context, channel, title, text, request)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hasLoggedToday = try {
                    val app = context.applicationContext as FudAIApp
                    app.container.foodRepository
                        .entriesForDate(LocalDate.now())
                        .first()
                        .isNotEmpty()
                } catch (error: Exception) {
                    // Fail open: a temporary DataStore read problem should not silently drop a
                    // reminder the user explicitly enabled. Tomorrow is already re-armed above.
                    Log.w(TAG, "Unable to check today's food entries", error)
                    false
                }

                if (ReminderDispatchPolicy.shouldPost(
                        isStreakReminder = true,
                        hasLoggedToday = hasLoggedToday
                    )
                ) {
                    postNotification(context, channel, title, text, request)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postDailySummary(
        context: Context,
        title: String,
        fallbackText: String,
        request: Int
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dynamicText = runCatching { dailySummaryText(context) }
                    .onFailure { Log.w(TAG, "Unable to build measured daily summary", it) }
                    .getOrNull()
                postNotification(
                    context = context,
                    channel = NotificationService.CHANNEL_DAILY,
                    title = title,
                    text = dynamicText ?: fallbackText,
                    request = request
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dailySummaryText(context: Context): String? {
        val app = context.applicationContext as? FudAIApp ?: return null
        val container = app.container
        if (!container.prefs.healthConnectEnabled.first()) return null
        if (!container.health.hasEnergyRead() || !container.health.hasBackgroundRead()) return null

        val today = LocalDate.now()
        val energy = container.health.readEnergyForDay(today) ?: return null
        val profileBmr = container.profileRepository.current()?.bmr?.roundToInt()
        val burned = DailySummaryPolicy.resolveBurnedCalories(
            measuredTotalCalories = energy.totalCalories,
            externalActiveCalories = energy.activeCalories,
            profileBmrCalories = profileBmr
        ) ?: return null
        val eaten = container.foodRepository.entriesForDate(today).first().sumOf { it.calories }
        val balance = DailySummaryPolicy.balance(eatenCalories = eaten, burnedCalories = burned)

        return when (balance.direction) {
            CalorieBalanceDirection.DEFICIT -> context.getString(
                R.string.notif_summary_deficit,
                balance.eatenCalories,
                balance.burnedCalories,
                balance.differenceCalories
            )
            CalorieBalanceDirection.SURPLUS -> context.getString(
                R.string.notif_summary_surplus,
                balance.eatenCalories,
                balance.burnedCalories,
                balance.differenceCalories
            )
            CalorieBalanceDirection.BALANCED -> context.getString(
                R.string.notif_summary_balanced,
                balance.eatenCalories,
                balance.burnedCalories
            )
        }
    }

    private fun postNotification(
        context: Context,
        channel: String,
        title: String,
        text: String,
        request: Int
    ) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            component = ComponentName(context, MainActivity::class.java)
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notifySafely(context, request, notif)
    }

    private fun rearm(context: Context, intent: Intent, request: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextFire = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        val reIntent = Intent(context, ReminderReceiver::class.java).apply {
            component = ComponentName(context, ReminderReceiver::class.java)
            setPackage(context.packageName)
            putExtras(intent)
        }
        val pi = PendingIntent.getBroadcast(
            context, request, reIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFire, pi)
    }

    private companion object {
        const val TAG = "ReminderReceiver"
    }
}

private fun NotificationManagerCompat.notifySafely(
    context: Context,
    id: Int,
    notif: android.app.Notification
) {
    val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!canNotify) return

    try {
        notify(id, notif)
    } catch (_: SecurityException) {
        // Permission can still be revoked between the explicit check and notify().
    }
}
