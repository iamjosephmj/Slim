package com.example.slim.bench

import java.lang.reflect.Field
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * JNI path: a single native entry that runs the fused 8-stage pipeline
 * on the calling thread. Mirrors [SlimFusedPipeline] exactly in
 * algorithm — only the dispatch mechanism differs.
 */
internal object JniPipeline {

    init {
        System.loadLibrary("slimbench")
    }

    fun runFused(buf: ByteBuffer) {
        require(buf.isDirect) { "buffer must be direct" }
        runFused(directBufferAddress(buf), buf.capacity())
    }

    /**
     * Dispatch-baseline path. Invokes a no-op native function so the
     * measured time is purely the JNI crossing — no kernel work, no
     * memory traffic. The buffer address is passed for ABI symmetry with
     * [runFused] but ignored by the native side.
     */
    fun runEmpty(buf: ByteBuffer) {
        require(buf.isDirect) { "buffer must be direct" }
        runEmpty(directBufferAddress(buf))
    }

    @JvmStatic private external fun runFused(dataPtr: Long, n: Int)
    @JvmStatic private external fun runEmpty(dataPtr: Long)

    private val addressField: Field by lazy {
        Buffer::class.java.getDeclaredField("address").apply { isAccessible = true }
    }

    private fun directBufferAddress(buf: ByteBuffer): Long = addressField.getLong(buf)
}
