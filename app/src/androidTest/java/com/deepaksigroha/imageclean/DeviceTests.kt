package com.deepaksigroha.imageclean

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepaksigroha.imageclean.core.model.ImageFormat
import com.deepaksigroha.imageclean.data.CleanOptions
import com.deepaksigroha.imageclean.data.LoadedImage
import com.deepaksigroha.imageclean.data.MetadataCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsBrandingAndDeveloperCredit() {
        rule.onAllNodesWithText("ImageClean Pro").onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("Developed by Deepak Sigroha").onFirst().assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class CleanerDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ctx.applicationContext as ImageCleanApp

    private fun jpegWithGps(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 120, 200)) }
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val f = File(ctx.cacheDir, "t.jpg"); f.writeBytes(out.toByteArray())
        ExifInterface(f).apply {
            setLatLong(25.4358, 81.8463); setAttribute("Make", "TestCam"); setAttribute("Model", "X1")
            setAttribute("DateTimeOriginal", "2026:11:24 10:00:00"); setAttribute("Orientation", "6"); saveAttributes()
        }
        return f.readBytes()
    }

    @Test fun removesGpsAndDeviceButKeepsOrientation() {
        val bytes = jpegWithGps()
        val a = app.container.analyzer.analyze(LoadedImage(Uri.EMPTY, "t.jpg", bytes, false))
        assertTrue(a.hasGps)
        assertEquals(ImageFormat.JPEG, a.format)
        val res = app.container.cleaner.clean(a, CleanOptions(removeTags = MetadataCleaner.SENSITIVE))
        val after = app.container.analyzer.analyze(LoadedImage(Uri.EMPTY, "c.jpg", res.bytes, false))
        assertFalse(after.hasGps)
        assertFalse(after.exif.fields.any { it.tag == "Make" || it.tag == "Model" || it.tag == "DateTimeOriginal" })
        assertEquals(6, after.exif.orientation)
        assertFalse(res.reencoded)
    }
}
