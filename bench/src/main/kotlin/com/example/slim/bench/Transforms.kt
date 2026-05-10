package com.example.slim.bench

import io.simdkt.nativekt.KernelTemplate
import io.simdkt.nativekt.compileTemplate
import io.simdkt.nativekt.engine.Arm64

/**
 * The full 8-stage pipeline (invert → contrast → brighten(40) → darken(20),
 * repeated twice) fused into a single Slim kernel. Each 16-byte chunk is
 * loaded once into V0, run through all 8 transforms back-to-back in NEON
 * registers (no intermediate stores), then stored once.
 *
 * Memory traffic per byte: 1 read + 1 write. Mirrors
 * kernel_pipeline_fused in kernels.cpp instruction-for-instruction so the
 * bench measures only the dispatch difference between Slim and JNI.
 *
 * Register allocation:
 *   X0     dataPtr (from prologue)
 *   X1     running byte pointer
 *   W3     remaining-byte counter
 *   X4/W4  scratch for constant loads
 *   V0     active 16-byte chunk
 *   V1     0xFF × 16  (for invert)
 *   V2       64 × 16  (for contrast bias)
 *   V3       40 × 16  (for brighten)
 *   V4       20 × 16  (for darken)
 */
internal fun fusedPipelineTemplate(n: Int): KernelTemplate = compileTemplate {
    placeholderDataPtr()
    add(Arm64.loadImm32(Arm64.W3, n))
    add(Arm64.mov(Arm64.X1, Arm64.X0))

    // Hoist all four broadcast constants out of the loop.
    add(Arm64.movz(Arm64.W4, 0xFF))
    add(Arm64.dup(Arm64.V1, Arm64.X4, Arm64.VArr.B16))
    add(Arm64.movz(Arm64.W4, 64))
    add(Arm64.dup(Arm64.V2, Arm64.X4, Arm64.VArr.B16))
    add(Arm64.movz(Arm64.W4, 40))
    add(Arm64.dup(Arm64.V3, Arm64.X4, Arm64.VArr.B16))
    add(Arm64.movz(Arm64.W4, 20))
    add(Arm64.dup(Arm64.V4, Arm64.X4, Arm64.VArr.B16))

    val loop = bindLabel()
    add(Arm64.ld1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))

    // Stage 1: invert
    add(Arm64.subVec(Arm64.V0, Arm64.V1, Arm64.V0, Arm64.VArr.B16))
    // Stage 2: contrast — clamp(2*y - 128, 0, 255)
    add(Arm64.uqsub(Arm64.V0, Arm64.V0, Arm64.V2, Arm64.VArr.B16))
    add(Arm64.uqadd(Arm64.V0, Arm64.V0, Arm64.V0, Arm64.VArr.B16))
    // Stage 3: brighten(40)
    add(Arm64.uqadd(Arm64.V0, Arm64.V0, Arm64.V3, Arm64.VArr.B16))
    // Stage 4: darken(20)
    add(Arm64.uqsub(Arm64.V0, Arm64.V0, Arm64.V4, Arm64.VArr.B16))
    // Stage 5: invert
    add(Arm64.subVec(Arm64.V0, Arm64.V1, Arm64.V0, Arm64.VArr.B16))
    // Stage 6: contrast
    add(Arm64.uqsub(Arm64.V0, Arm64.V0, Arm64.V2, Arm64.VArr.B16))
    add(Arm64.uqadd(Arm64.V0, Arm64.V0, Arm64.V0, Arm64.VArr.B16))
    // Stage 7: brighten(40)
    add(Arm64.uqadd(Arm64.V0, Arm64.V0, Arm64.V3, Arm64.VArr.B16))
    // Stage 8: darken(20)
    add(Arm64.uqsub(Arm64.V0, Arm64.V0, Arm64.V4, Arm64.VArr.B16))

    add(Arm64.st1(Arm64.V0, Arm64.X1, Arm64.VArr.B16))
    add(Arm64.addImm(Arm64.X1, Arm64.X1, 16))
    add(Arm64.subImm(Arm64.W3, Arm64.W3, 16))
    cbnz(Arm64.W3, loop)

    add(Arm64.ret())
}
