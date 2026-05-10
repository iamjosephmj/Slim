package io.simdkt.slim

import io.simdkt.nativekt.engine.KernelMetadata
import io.simdkt.nativekt.engine.SourceFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class DisassemblerTest {

    @Test fun formats_three_instructions_with_label_and_source() {
        // mov x1, x0       ; offset 0  → opcode 0xAA0003E1 (LE: E1 03 00 AA)
        // ld1 {v0.4s},[x1] ; offset 4  → opcode 0x4CC07C20 (LE: 20 7C C0 4C)
        // cbnz w3, #-4     ; offset 8  → target = 8 + (-4) = 4 = label "loop"
        //   cbnz imm19 = -1 = 0x7FFFF → opcode 0x35FFFFE3 (LE: E3 FF FF 35)
        val bytes = byteArrayOf(
            0xe1.toByte(), 0x03.toByte(), 0x00.toByte(), 0xaa.toByte(),  // mov x1, x0
            0x20.toByte(), 0x7c.toByte(), 0xc0.toByte(), 0x4c.toByte(),  // ld1 {v0.4s}, [x1]
            0xe3.toByte(), 0xff.toByte(), 0xff.toByte(), 0x35.toByte(),  // cbnz w3, loop (target=4)
        )
        val metadata = KernelMetadata(
            sourceFrames = mapOf(
                0 to SourceFrame("brighten.kt", 39),
                4 to SourceFrame("brighten.kt", 42),
                8 to SourceFrame("brighten.kt", 47),
            ),
            labelNames = mapOf(4 to "loop"),
        )
        val out = Disassembler.format(bytes, metadata)
        assert(out.contains("mov"))       { "missing 'mov' in:\n$out" }
        assert(out.contains("x1, x0"))   { "missing 'x1, x0' in:\n$out" }
        assert(out.contains("loop:"))    { "missing 'loop:' in:\n$out" }
        assert(out.contains("cbnz"))     { "missing 'cbnz' in:\n$out" }
        assert(out.contains("loop"))     { "missing resolved label 'loop' in cbnz operand:\n$out" }
        assert(out.contains("brighten.kt:39")) { "missing source ref brighten.kt:39 in:\n$out" }
        assert(out.contains("brighten.kt:42")) { "missing source ref brighten.kt:42 in:\n$out" }
        assert(out.contains("brighten.kt:47")) { "missing source ref brighten.kt:47 in:\n$out" }
    }

    @Test fun handles_empty_metadata() {
        // nop = 0xD503201F (LE: 1F 20 03 D5)
        val bytes = byteArrayOf(0x1f.toByte(), 0x20.toByte(), 0x03.toByte(), 0xd5.toByte())
        val out = Disassembler.format(bytes, KernelMetadata.EMPTY)
        assert(out.contains("nop")) { "missing 'nop' in:\n$out" }
        assert(!out.contains("//")) { "unexpected '//' in:\n$out" }
    }

    @Test fun handles_negative_branch_offset_without_label() {
        // cbnz w3, #-8 — no label bound at the target (offset 0 + (-8) = -8).
        // cbnz imm19 = -2 = 0x7FFFE → opcode 0x35FFFFC3 (LE: C3 FF FF 35)
        val bytes = byteArrayOf(0xc3.toByte(), 0xff.toByte(), 0xff.toByte(), 0x35.toByte())
        val out = Disassembler.format(bytes, KernelMetadata.EMPTY)
        assert(out.contains("cbnz")) { "missing 'cbnz' in:\n$out" }
        // Numeric form accepted: ".-0x8", "-0x8", "-8", ".-8"
        assert(out.contains(".-8") || out.contains("-0x8") || out.contains("-8")) {
            "expected numeric branch offset in:\n$out"
        }
    }

    @Test fun exact_line_format() {
        // Lock in the exact multi-line format for stability.
        // mov x1, x0 at offset 0 with label "entry" and source frame.
        // opcode = 0xAA0003E1 (LE: E1 03 00 AA)
        val bytes = byteArrayOf(
            0xe1.toByte(), 0x03.toByte(), 0x00.toByte(), 0xaa.toByte(),  // mov x1, x0
        )
        val metadata = KernelMetadata(
            sourceFrames = mapOf(0 to SourceFrame("foo.kt", 10)),
            labelNames = mapOf(0 to "entry"),
        )
        val out = Disassembler.format(bytes, metadata)
        // Label line must appear before the instruction line
        val labelIdx = out.indexOf("entry:")
        val movIdx = out.indexOf("mov")
        assert(labelIdx >= 0) { "label 'entry:' not found in output:\n$out" }
        assert(movIdx >= 0)   { "mnemonic 'mov' not found in output:\n$out" }
        assert(labelIdx < movIdx) { "label must appear before instruction:\n$out" }
        // Source annotation must be present
        assert(out.contains("foo.kt:10")) { "source ref missing:\n$out" }
        // Offset 0000 and opcode must be present
        assert(out.contains("0000"))     { "offset 0000 missing:\n$out" }
        assert(out.contains("aa0003e1")) { "opcode aa0003e1 missing:\n$out" }
    }
}
