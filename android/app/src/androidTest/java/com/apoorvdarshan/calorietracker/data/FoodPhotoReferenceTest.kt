package com.apoorvdarshan.calorietracker.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodPhotoReferenceTest {
    @Test
    fun missingDiaryPreventsPruningButExplicitlyEmptyDiaryDoesNot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.fudaiDataStore
        val key = stringPreferencesKey("foodEntries")
        val prior = store.data.first()[key]
        try {
            val prefs = PreferencesStore(context)
            store.edit { it.remove(key) }
            assertNull(prefs.foodImageReferenceFilenames())
            store.edit { it[key] = "[]" }
            assertNotNull(prefs.foodImageReferenceFilenames())
        } finally {
            store.edit { if (prior == null) it.remove(key) else it[key] = prior }
        }
    }
}
