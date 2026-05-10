package io.simdkt.slim

import io.simdkt.nativekt.engine.Arm64
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
