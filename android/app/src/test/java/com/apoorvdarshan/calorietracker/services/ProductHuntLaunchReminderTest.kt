package com.apoorvdarshan.calorietracker.services

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class ProductHuntLaunchReminderTest {
    private val launch = ProductHuntLaunchReminder.LAUNCH_AT_MILLIS
    private val hour = 60L * 60 * 1000

    @Test
    fun `launch moment is Sept 29 2026 12 01 AM Pacific`() {
        val expected = ZonedDateTime.of(2026, 9, 29, 0, 1, 0, 0, ProductHuntLaunchReminder.LAUNCH_ZONE)
        assertEquals(expected.toInstant().toEpochMilli(), launch)
    }

    @Test
    fun `before launch schedules for the launch moment`() {
        assertEquals(
            ProductHuntLaunchReminder.Plan.ScheduleAt(launch),
            ProductHuntLaunchReminder.plan(nowMillis = launch - 7 * 24 * hour, launchAtMillis = launch)
        )
    }

    @Test
    fun `during launch day fires immediately`() {
        assertEquals(
            ProductHuntLaunchReminder.Plan.FireNow,
            ProductHuntLaunchReminder.plan(nowMillis = launch + 6 * hour, launchAtMillis = launch)
        )
    }

    @Test
    fun `after launch day skips`() {
        assertEquals(
            ProductHuntLaunchReminder.Plan.Skip,
            ProductHuntLaunchReminder.plan(nowMillis = launch + 25 * hour, launchAtMillis = launch)
        )
    }
}
