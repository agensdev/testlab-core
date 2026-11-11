package no.agens.testlab.services.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidGesturesTest {
    /** As a Pixel 6 Pro with screen mirroring printed it: virtual displays first, never rotated. */
    private val dump = """
        WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
          Display: mDisplayId=58
          overrideConfig={… mDisplayRotation=ROTATION_0 … mRotation=ROTATION_0}
            mRotation=0 mDeferredRotationPauseCount=0
            mUserRotationMode=USER_ROTATION_FREE mUserRotation=ROTATION_0
          Display: mDisplayId=57
            mRotation=0 mDeferredRotationPauseCount=0
          Display: mDisplayId=0 (organized)
          overrideConfig={… mBounds=Rect(0, 0 - 3120, 1440) mDisplayRotation=ROTATION_90 …}
            mRotation=1 mDeferredRotationPauseCount=0
    """.trimIndent()

    @Test
    fun onlyDisplayZeroCounts() {
        assertEquals(1, AndroidGestures.displayRotation(dump))
        assertEquals(3, AndroidGestures.displayRotation("  Display: mDisplayId=0\n  winConfig={ mDisplayRotation=ROTATION_270 }"), "the config line when there is no mRotation= line")
        assertEquals(null, AndroidGestures.displayRotation("  Display: mDisplayId=5\n    mRotation=1"), "no display 0: not the first one found")
        assertEquals("landscape-right", AndroidGestures.Orientation(3, false, 3).name)
    }
}
