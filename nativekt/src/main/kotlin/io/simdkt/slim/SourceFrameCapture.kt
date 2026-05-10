package io.simdkt.slim

import io.simdkt.nativekt.engine.SourceFrame

internal object SourceFrameCapture {

    /**
     * Simple class-name suffixes that identify internal Slim/nativekt frames to skip.
     * We match by suffix (simple class name) rather than full package prefix so that
     * test classes (e.g. SourceFrameCaptureTest) in the same package are NOT skipped.
     */
    private val skipSimpleNames = setOf(
        "SourceFrameCapture",
        "Slim",
        "SlimScope",
        "Arm64Emitter",
        "Asm",
        "Arm64Decoder",
        "Arm64",
        "Trampoline",
        "MemoryExecutor",
    )

    /**
     * Returns the first JVM stack frame that does not belong to the Slim/nativekt
     * internal classes. The simple class name (after the last '.') is checked against
     * [skipSimpleNames] so that classes in the same package that are *not* internal
     * helpers (e.g. test classes) are still captured.
     *
     * Returns `null` if [StackTraceElement.fileName] is unavailable for the matching
     * frame, or if every frame belongs to an internal class.
     */
    fun capture(): SourceFrame? {
        val frames = Throwable().stackTrace
        for (frame in frames) {
            val simpleName = frame.className.substringAfterLast('.')
                .substringBefore('$')          // strip inner-class / lambda suffix
            if (simpleName !in skipSimpleNames) {
                val file = frame.fileName ?: return null
                return SourceFrame(file, frame.lineNumber)
            }
        }
        return null
    }
}
