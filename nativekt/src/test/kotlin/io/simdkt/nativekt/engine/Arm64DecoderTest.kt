package io.simdkt.nativekt.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class Arm64DecoderTest {

    private fun assertDec(opcode: Int, expected: DecodedInsn) {
        val actual = Arm64Decoder.decode(opcode)
        assertEquals("mnemonic for 0x${"%08x".format(opcode)}", expected.mnemonic, actual.mnemonic)
        assertEquals("operands for 0x${"%08x".format(opcode)}", expected.operands, actual.operands)
        assertEquals("raw for 0x${"%08x".format(opcode)}", expected.raw, actual.raw)
    }

    @Test fun branches() {
        // cbz x0, .+4
        assertDec(
            0xb4000020.toInt(),
            DecodedInsn("cbz", listOf(Operand.Reg("x0"), Operand.BranchOffset(4)), 0xb4000020.toInt()),
        )
        // cbnz w1, .+4
        assertDec(
            0x35000021.toInt(),
            DecodedInsn("cbnz", listOf(Operand.Reg("w1"), Operand.BranchOffset(4)), 0x35000021.toInt()),
        )
        // tbz x0, #0, .+4
        assertDec(
            0x36000020.toInt(),
            DecodedInsn("tbz", listOf(Operand.Reg("w0"), Operand.Imm(0L, ImmFormat.DEC), Operand.BranchOffset(4)), 0x36000020.toInt()),
        )
        // b .+8
        assertDec(
            0x14000002.toInt(),
            DecodedInsn("b", listOf(Operand.BranchOffset(8)), 0x14000002.toInt()),
        )
        // bl .+8
        assertDec(
            0x94000002.toInt(),
            DecodedInsn("bl", listOf(Operand.BranchOffset(8)), 0x94000002.toInt()),
        )
        // ret (default x30)
        assertDec(
            0xd65f03c0.toInt(),
            DecodedInsn("ret", emptyList(), 0xd65f03c0.toInt()),
        )
        // ret x0
        assertDec(
            0xd65f0000.toInt(),
            DecodedInsn("ret", listOf(Operand.Reg("x0")), 0xd65f0000.toInt()),
        )
        // blr x16
        assertDec(
            0xd63f0200.toInt(),
            DecodedInsn("blr", listOf(Operand.Reg("x16")), 0xd63f0200.toInt()),
        )
        // br x17
        assertDec(
            0xd61f0220.toInt(),
            DecodedInsn("br", listOf(Operand.Reg("x17")), 0xd61f0220.toInt()),
        )
        // b.eq .+4
        assertDec(
            0x54000020.toInt(),
            DecodedInsn("b.eq", listOf(Operand.BranchOffset(4)), 0x54000020.toInt()),
        )
        // b.ne .+8
        assertDec(
            0x54000041.toInt(),
            DecodedInsn("b.ne", listOf(Operand.BranchOffset(8)), 0x54000041.toInt()),
        )

        // All 16 condition codes (b.cond) using offset=4 (imm19=1)
        // b.eq (0)
        assertDec(0x54000020.toInt(), DecodedInsn("b.eq", listOf(Operand.BranchOffset(4)), 0x54000020.toInt()))
        // b.ne (1)
        assertDec(0x54000021.toInt(), DecodedInsn("b.ne", listOf(Operand.BranchOffset(4)), 0x54000021.toInt()))
        // b.cs (2)
        assertDec(0x54000022.toInt(), DecodedInsn("b.cs", listOf(Operand.BranchOffset(4)), 0x54000022.toInt()))
        // b.cc (3)
        assertDec(0x54000023.toInt(), DecodedInsn("b.cc", listOf(Operand.BranchOffset(4)), 0x54000023.toInt()))
        // b.mi (4)
        assertDec(0x54000024.toInt(), DecodedInsn("b.mi", listOf(Operand.BranchOffset(4)), 0x54000024.toInt()))
        // b.pl (5)
        assertDec(0x54000025.toInt(), DecodedInsn("b.pl", listOf(Operand.BranchOffset(4)), 0x54000025.toInt()))
        // b.vs (6)
        assertDec(0x54000026.toInt(), DecodedInsn("b.vs", listOf(Operand.BranchOffset(4)), 0x54000026.toInt()))
        // b.vc (7)
        assertDec(0x54000027.toInt(), DecodedInsn("b.vc", listOf(Operand.BranchOffset(4)), 0x54000027.toInt()))
        // b.hi (8)
        assertDec(0x54000028.toInt(), DecodedInsn("b.hi", listOf(Operand.BranchOffset(4)), 0x54000028.toInt()))
        // b.ls (9)
        assertDec(0x54000029.toInt(), DecodedInsn("b.ls", listOf(Operand.BranchOffset(4)), 0x54000029.toInt()))
        // b.ge (10)
        assertDec(0x5400002a.toInt(), DecodedInsn("b.ge", listOf(Operand.BranchOffset(4)), 0x5400002a.toInt()))
        // b.lt (11)
        assertDec(0x5400002b.toInt(), DecodedInsn("b.lt", listOf(Operand.BranchOffset(4)), 0x5400002b.toInt()))
        // b.gt (12)
        assertDec(0x5400002c.toInt(), DecodedInsn("b.gt", listOf(Operand.BranchOffset(4)), 0x5400002c.toInt()))
        // b.le (13)
        assertDec(0x5400002d.toInt(), DecodedInsn("b.le", listOf(Operand.BranchOffset(4)), 0x5400002d.toInt()))
        // b.al (14)
        assertDec(0x5400002e.toInt(), DecodedInsn("b.al", listOf(Operand.BranchOffset(4)), 0x5400002e.toInt()))
        // b.nv (15)
        assertDec(0x5400002f.toInt(), DecodedInsn("b.nv", listOf(Operand.BranchOffset(4)), 0x5400002f.toInt()))
    }
}
