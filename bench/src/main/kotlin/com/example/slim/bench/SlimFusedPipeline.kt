package com.example.slim.bench

import io.simdkt.nativekt.KernelHandle
import io.simdkt.nativekt.NativeKt
import java.nio.ByteBuffer

/**
 * Single-handle Slim path that compiles the entire 8-stage pipeline into
 * one kernel via [fusedPipelineTemplate]. One EP-hijack dispatch per
 * pipeline run, one DRAM read + one DRAM write per byte for the whole
 * pipeline. This is the regime where Slim's runtime code-generation
 * structurally beats a JNI library shipping fixed kernels.
 */
internal class SlimFusedPipeline(totalBytes: Int) : AutoCloseable {

    private val handle: KernelHandle =
        NativeKt.compileKernel(fusedPipelineTemplate(totalBytes))

    private var closed = false

    fun run(buf: ByteBuffer) {
        check(!closed) { "pipeline closed" }
        handle.run(buf)
    }

    override fun close() {
        if (closed) return
        closed = true
        handle.close()
    }
}
