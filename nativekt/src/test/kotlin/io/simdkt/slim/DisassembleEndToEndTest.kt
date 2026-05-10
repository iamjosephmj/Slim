package io.simdkt.slim

import io.simdkt.nativekt.KernelHandle
import io.simdkt.nativekt.engine.Arm64
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.reflect.KFunction1

class DisassembleEndToEndTest {

    @After fun reset() { Slim.debug = false }

    @Test fun preview_returns_disassembly_with_source_lines() {
        Slim.debug = true
        val asm: String = Slim.preview {
            mov(X1, X0)
            val loop = bindLabel("loop")
            ld1(V0, X1, S4)
            sub(W3, W3, 4)
            cbnz(W3, loop)
        }
        assertTrue("expected 'mov' in output:\n$asm",  asm.contains("mov"))
        assertTrue("expected 'loop:' in output:\n$asm", asm.contains("loop:"))
        assertTrue("expected source ref in output:\n$asm", asm.contains("DisassembleEndToEndTest.kt:"))
    }

    @Test fun preview_without_debug_omits_source_refs() {
        Slim.debug = false
        val asm = Slim.preview { mov(X1, X0) }
        assertTrue(asm.contains("mov"))
        assertTrue("no source comment expected: $asm", !asm.contains("// DisassembleEndToEndTest.kt:"))
    }

    /**
     * Type-level check: [KernelHandle.disassemble] must exist as a
     * public extension returning [String]. This test does not invoke
     * the function at runtime (that would require [Slim.initialize] and
     * an Android context), but the function-reference expression below
     * will not compile if the extension is absent, has the wrong receiver,
     * or returns the wrong type.
     */
    @Test fun kernelHandle_disassemble_extension_compiles() {
        // Obtaining a typed function reference is a compile-time proof
        // that the extension exists with the correct signature.
        @Suppress("UNUSED_VARIABLE")
        val ref: KFunction1<KernelHandle, String> = KernelHandle::disassemble
        // ref is not invoked — no Android runtime needed.
    }

    /**
     * Runtime path (skipped in host JVM tests): compile a kernel via the
     * public [slim] DSL and verify [KernelHandle.disassemble] produces
     * annotated output matching [Slim.preview].
     *
     * To run this test on a device or with Robolectric, wire up
     * [Slim.initialize] (requires an Android [android.content.Context])
     * and remove the [@Ignore] annotation.
     */
    @Ignore("requires Slim.initialize + Android context; run on-device")
    @Test fun kernelHandle_disassemble_matches_preview() {
        // Slim.initialize(context)  // <- supply real context on-device
        Slim.debug = true
        val preview = Slim.preview {
            mov(X1, X0)
            val loop = bindLabel("loop")
            sub(W3, W3, 4)
            cbnz(W3, loop)
        }
        // On-device: obtain a handle from slim {} and compare.
        // val handle: KernelHandle = ...  // from NativeKt.compileKernel(template)
        // val fromHandle = handle.disassemble()
        // assertEquals(preview, fromHandle)
        assertTrue("placeholder for on-device assertion", preview.isNotEmpty())
    }
}
