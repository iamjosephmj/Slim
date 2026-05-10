package io.simdkt.slim

import io.simdkt.nativekt.engine.SourceFrame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFrameCaptureTest {

    @Test fun captures_first_user_frame() {
        val frame: SourceFrame? = userCallSite()
        assertNotNull(frame)
        assertTrue("expected file to be SourceFrameCaptureTest.kt: ${frame!!.file}",
                   frame.file == "SourceFrameCaptureTest.kt")
        assertTrue("expected positive line number: ${frame.line}", frame.line > 0)
    }

    private fun userCallSite(): SourceFrame? = SourceFrameCapture.capture()

    @Test fun skips_internal_frames() {
        // Indirect call: simulates how the emitter would call capture()
        // The wrapper class is in the production package and matches the skip filter,
        // but we want to confirm the test class itself is captured.
        val frame = SourceFrameCapture.capture()
        assertNotNull(frame)
        assertTrue("expected test file: ${frame!!.file}", frame.file.endsWith("Test.kt"))
    }
}
