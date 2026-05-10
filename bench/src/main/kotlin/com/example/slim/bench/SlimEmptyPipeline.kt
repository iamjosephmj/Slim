package com.example.slim.bench

import io.simdkt.nativekt.KernelHandle
import io.simdkt.nativekt.KernelTemplate
import io.simdkt.nativekt.NativeKt
import io.simdkt.nativekt.compileTemplate
import io.simdkt.nativekt.engine.Arm64
import java.nio.ByteBuffer

/**
 * Slim's dispatch-baseline kernel: a template containing just the
 * standard prologue ([placeholderDataPtr] — the 4 movz/movk slots that
 * Slim's dispatcher patches per call) followed by [Arm64.ret].
 *
 * Mirrors JNI's `runEmpty` in spirit. Every per-dispatch overhead is
 * still paid: probe-pool acquire/release, the 4 reflective imm16 slot
 * patches, the EP read/write/restore, and the reflective Method.invoke.
 * The kernel body itself is ~5 instructions and finishes in ~3 ns on
 * Cortex-X4 — so the measured time is essentially pure dispatch cost.
 */
internal class SlimEmptyPipeline : AutoCloseable {

    private val handle: KernelHandle = NativeKt.compileKernel(emptyTemplate())

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

/** Just the prologue and a return — no work. */
internal fun emptyTemplate(): KernelTemplate = compileTemplate {
    placeholderDataPtr()
    add(Arm64.ret())
}
