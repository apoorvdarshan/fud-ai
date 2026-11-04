package com.apoorvdarshan.calorietracker.services

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class TrialReminderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val service = NotificationService(context)

    private fun pending(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 7091,
        Intent(context, ReminderReceiver::class.java).apply {
            component = ComponentName(context, ReminderReceiver::class.java)
            setPackage(context.packageName)
        }, PendingIntent.FLAG_IMMUTABLE or flags
    )

    @Test
    fun refreshPreservesDelayedReminderAndCancellationRemovesIt() {
        // Simulate the system retaining an alarm whose desired delivery time
        // passed while the phone was asleep, but whose trial has not expired.
        val expiry = Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000)
        val original = pending(PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSharedPreferences("hosted-trial-reminder", Context.MODE_PRIVATE)
            .edit().putLong("expiry", expiry.time).commit()
        try {
            service.updateTrialReminder(expiry)
            assertEquals(original, pending(PendingIntent.FLAG_NO_CREATE))
            service.updateTrialReminder(null)
            assertNull(pending(PendingIntent.FLAG_NO_CREATE))
        } finally {
            service.updateTrialReminder(null)
        }
    }

    @Test
    fun refreshRestoresLostSystemAlarmInsteadOfTrustingStoredMarker() {
        service.updateTrialReminder(null)
        val expiry = Date(System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L)
        context.getSharedPreferences("hosted-trial-reminder", Context.MODE_PRIVATE)
            .edit().putLong("expiry", expiry.time).commit()
        try {
            assertNull(pending(PendingIntent.FLAG_NO_CREATE))
            service.updateTrialReminder(expiry)
            assertNotNull(pending(PendingIntent.FLAG_NO_CREATE))
        } finally {
            service.updateTrialReminder(null)
        }
    }
}
