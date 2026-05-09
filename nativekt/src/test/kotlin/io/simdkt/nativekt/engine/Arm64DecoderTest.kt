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

    // ------------------------------------------------------------------
    // Data-processing — immediate: paired assertDec for every assertEnc
    // in Arm64Test.kt:{moveWide, loadImmComposite, arithImm, compare(imm), shifts}
    // ------------------------------------------------------------------

    @Test fun moveWide() {
        // movz x3, #0x40  (smoke-test: task-provided representative)
        assertDec(
            0xd2800803.toInt(),
            DecodedInsn(
                "movz",
                listOf(Operand.Reg("x3"), Operand.Imm(0x40, ImmFormat.HEX)),
                0xd2800803.toInt(),
            ),
        )

        // movz x0, #0xCAFE  — Arm64Test.kt:moveWide line 22
        assertDec(
            0xd2995fc0.toInt(),
            DecodedInsn("movz", listOf(Operand.Reg("x0"), Operand.Imm(0xCAFE, ImmFormat.HEX)), 0xd2995fc0.toInt()),
        )

        // movk x0, #0xC0DE, lsl 16  — Arm64Test.kt:moveWide line 23
        assertDec(
            0xf2b81bc0.toInt(),
            DecodedInsn(
                "movk",
                listOf(Operand.Reg("x0"), Operand.Imm(0xC0DE, ImmFormat.HEX), Operand.Imm(16, ImmFormat.SHIFT_AMOUNT)),
                0xf2b81bc0.toInt(),
            ),
        )

        // movz w1, #0xCAFE  — Arm64Test.kt:moveWide line 24
        assertDec(
            0x52995fc1.toInt(),
            DecodedInsn("movz", listOf(Operand.Reg("w1"), Operand.Imm(0xCAFE, ImmFormat.HEX)), 0x52995fc1.toInt()),
        )

        // movk w1, #0xC0DE, lsl 16  — Arm64Test.kt:moveWide line 25
        assertDec(
            0x72b81bc1.toInt(),
            DecodedInsn(
                "movk",
                listOf(Operand.Reg("w1"), Operand.Imm(0xC0DE, ImmFormat.HEX), Operand.Imm(16, ImmFormat.SHIFT_AMOUNT)),
                0x72b81bc1.toInt(),
            ),
        )
    }

    @Test fun loadImmComposite() {
        // movz x0, #0xBABE  — Arm64Test.kt:loadImmComposite seq[0] (only assertEnc in that test)
        assertDec(
            0xd29757c0.toInt(),
            DecodedInsn("movz", listOf(Operand.Reg("x0"), Operand.Imm(0xBABE, ImmFormat.HEX)), 0xd29757c0.toInt()),
        )
    }

    @Test fun arithImm() {
        // add x0, x1, #4  — Arm64Test.kt:arithImm line 59
        assertDec(
            0x91001020.toInt(),
            DecodedInsn(
                "add",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Imm(4, ImmFormat.DEC)),
                0x91001020.toInt(),
            ),
        )

        // sub x0, x1, #4  — Arm64Test.kt:arithImm line 60
        assertDec(
            0xd1001020.toInt(),
            DecodedInsn(
                "sub",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Imm(4, ImmFormat.DEC)),
                0xd1001020.toInt(),
            ),
        )

        // cmp x0, #0  — Arm64Test.kt:compare line 65 (immediate portion only; register cmp is not data-proc-imm)
        assertDec(
            0xf100001f.toInt(),
            DecodedInsn(
                "cmp",
                listOf(Operand.Reg("x0"), Operand.Imm(0, ImmFormat.DEC)),
                0xf100001f.toInt(),
            ),
        )
    }

    @Test fun shifts() {
        // lsl x0, x1, #8  — Arm64Test.kt:shifts line 109
        assertDec(
            0xd378dc20.toInt(),
            DecodedInsn(
                "lsl",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Imm(8, ImmFormat.DEC)),
                0xd378dc20.toInt(),
            ),
        )

        // lsr x0, x1, #8  — Arm64Test.kt:shifts line 110
        assertDec(
            0xd348fc20.toInt(),
            DecodedInsn(
                "lsr",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Imm(8, ImmFormat.DEC)),
                0xd348fc20.toInt(),
            ),
        )

        // asr x0, x1, #8  — Arm64Test.kt:shifts line 111
        assertDec(
            0x9348fc20.toInt(),
            DecodedInsn(
                "asr",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Imm(8, ImmFormat.DEC)),
                0x9348fc20.toInt(),
            ),
        )
    }
}
