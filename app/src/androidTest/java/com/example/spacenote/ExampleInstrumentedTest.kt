package com.example.spacenote

import android.content.Intent
import android.provider.MediaStore
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spacenote.content.CategorySnapshot
import com.example.spacenote.content.Note
import com.example.spacenote.content.loadLastResult
import com.example.spacenote.content.saveLastResult
import com.example.spacenote.content.saveViewPng
import com.example.spacenote.platform.LaunchActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.spacenote", appContext.packageName)
    }

    @Test
    fun launcherActivityIsResolvable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context.packageManager.resolveActivity(Intent(context, LaunchActivity::class.java), 0))
    }

    @Test
    fun screenshotIsPublishedToPicturesMediaStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = View(context).apply { layout(0, 0, 32, 32) }
        val saved = saveViewPng(view, context)
        val cursor = context.contentResolver.query(
            saved.uri,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH),
            null,
            null,
            null
        )
        cursor.use {
            assertTrue(it != null && it.moveToFirst())
            assertEquals(saved.displayName, it!!.getString(0))
            assertEquals("DCIM/SpaceNoteWaterfall/", it.getString(1))
        }
        context.contentResolver.delete(saved.uri, null, null)
    }

    @Test
    fun completedResultRoundTripsThroughLocalStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "last_result_test_${System.currentTimeMillis()}"
        val expected = CategorySnapshot(
            today = listOf(Note(1, "今天完成的事")),
            later = listOf(Note(2, "稍后再看")),
            undecided = listOf(Note(3, "等待决定"))
        )

        assertTrue(saveLastResult(context, expected, prefsName))
        assertEquals(expected, loadLastResult(context, prefsName))
        context.getSharedPreferences(prefsName, 0).edit().clear().commit()
    }
}
