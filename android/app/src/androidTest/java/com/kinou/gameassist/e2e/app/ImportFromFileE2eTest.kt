package com.kinou.gameassist.e2e.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.Intents.intending
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kinou.gameassist.MainActivity
import com.kinou.gameassist.R
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.e2e.Profiles
import com.kinou.gameassist.e2e.waitFor
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Import profiles from .json files: the system file picker is replaced by a canned answer. */
@RunWith(AndroidJUnit4::class)
class ImportFromFileE2eTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ProfileRepository.getInstance(context)
    private fun text(id: Int) = context.getString(id)
    private val files = ArrayList<File>()

    @Before
    fun interceptTheFilePicker() {
        Intents.init()
    }

    @After
    fun cleanUp() {
        Intents.release()
        files.forEach { it.delete() }
        repository.getAllProfiles().filter { it.name.startsWith("E2E") }.forEach { repository.deleteProfile(it.id) }
    }

    private fun file(name: String, content: String): Uri {
        val file = File(context.cacheDir, name).apply { writeText(content) }
        files += file
        return Uri.fromFile(file)
    }

    private fun profileJson(id: String, name: String) =
        repository.exportProfileToJson(Profiles.profile(id, emptyList()).copy(name = name))

    /** The user opens "Import", taps "Import from file(s)" and picks [uris]. */
    private fun importFiles(vararg uris: Uri) {
        val result = Intent().apply {
            clipData = ClipData.newRawUri("files", uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        }
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, result))

        rule.onAllNodesWithText(text(R.string.nav_profiles)).onFirst().performClick()
        rule.onNodeWithText(text(R.string.btn_import)).performClick()
        rule.onNodeWithText(text(R.string.btn_import_from_file)).performClick()
    }

    private fun saved(name: String) = repository.getAllProfiles().any { it.name == name }

    @Test
    fun aProfileFileIsImported() {
        importFiles(file("e2e_one.json", profileJson("e2e_file_one", "E2E File One")))

        waitFor("the profile to be imported") { saved("E2E File One") }
    }

    @Test
    fun severalFilesAreImportedAndABadOneIsSkipped() {
        val before = repository.getAllProfiles().size
        importFiles(
            file("e2e_a.json", profileJson("e2e_file_a", "E2E File A")),
            file("e2e_bad.json", "this is not a profile"),
            file("e2e_b.json", profileJson("e2e_file_b", "E2E File B"))
        )

        waitFor("both good files to be imported") { saved("E2E File A") && saved("E2E File B") }
        assertEquals("only the two good profiles were added", before + 2, repository.getAllProfiles().size)
    }
}
