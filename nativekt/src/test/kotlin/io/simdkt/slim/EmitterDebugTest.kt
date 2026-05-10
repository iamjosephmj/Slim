package io.simdkt.slim

import io.simdkt.nativekt.engine.Arm64
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmitterDebugTest {

    @After fun reset() { Slim.debug = false }

    @Test fun debug_flag_captures_source_frames() {
        Slim.debug = true
        val scope = SlimScope()                       // internal — accessible from same module's test source set
        scope.mov(scope.X1, scope.X0)                 // file:line should be captured
        scope.ld1(scope.V0, scope.X1, scope.S4)
        val metadata = scope.toMetadata()
        val frames = metadata.sourceFrames
        assertTrue("expected ≥2 source frames, got ${frames.size}", frames.size >= 2)
        for ((_, frame) in frames) {
            assertNotNull(frame.file)
            assertTrue("file: ${frame.file}", frame.file.endsWith("EmitterDebugTest.kt"))
        }
    }

    @Test fun debug_off_means_no_frames() {
        Slim.debug = false
        val scope = SlimScope()
        scope.mov(scope.X1, scope.X0)
        val metadata = scope.toMetadata()
        assertTrue(metadata.sourceFrames.isEmpty())
    }
}
