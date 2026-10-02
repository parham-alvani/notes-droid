package me.parham1995.notes.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a reader does in the first seconds: open the app, open the note it
 * was reading or the first in the list, and scroll it.
 *
 * That is where an app for reading is judged, and it is also the path that
 * runs the most code cold -- the database, the markdown parser, the
 * renderer, the icon set -- so it is the one worth compiling before the
 * first launch rather than after the twentieth.
 *
 * The vault on the phone is whatever is there: the profile names classes,
 * not notes, and any note exercises the same ones.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun readingANote() =
        rule.collect(
            packageName = PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), SETTLE_MS)
            device.waitForIdle()

            // The reader resumes on the last note; failing that, the browser
            // is up and the first row of it is a note or a folder. Either
            // way, the first scrollable thing on screen is read down and up.
            val list = device.wait(Until.findObject(By.scrollable(true)), SETTLE_MS)
            if (list != null) {
                list.setGestureMargin(device.displayWidth / GESTURE_MARGIN)
                list.fling(Direction.DOWN)
                device.waitForIdle()
                list.fling(Direction.UP)
                device.waitForIdle()
            }
        }

    private companion object {
        const val PACKAGE = "me.parham1995.notes"
        const val SETTLE_MS = 5_000L
        const val GESTURE_MARGIN = 5
    }
}
