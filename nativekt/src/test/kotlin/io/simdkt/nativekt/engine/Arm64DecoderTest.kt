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

    // Paired assertDec for every assertEnc in Arm64Test.kt:bitmaskImm()
    @Test fun bitmaskImm() {
        // and x0, x1, #0xff  — Arm64Test.kt:bitmaskImm line 236
        assertDec(
            0x92401c20.toInt(),
            DecodedInsn(
                "and",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Imm(0xFFL, ImmFormat.HEX)),
                0x92401c20.toInt(),
            ),
        )
        // and x0, x1, #0xffff  — Arm64Test.kt:bitmaskImm line 237
        assertDec(
            0x92403c20.toInt(),
            DecodedInsn(
                "and",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Imm(0xFFFFL, ImmFormat.HEX)),
                0x92403c20.toInt(),
            ),
        )
        // and w0, w1, #0xff  — Arm64Test.kt:bitmaskImm line 238
        assertDec(
            0x12001c20.toInt(),
            DecodedInsn(
                "and",
                listOf(Operand.Reg("w0"), Operand.Reg("w1"), Operand.Imm(0xFFL, ImmFormat.HEX)),
                0x12001c20.toInt(),
            ),
        )
        // orr x0, x1, #0xf0f0f0f0f0f0f0f0  — Arm64Test.kt:bitmaskImm line 239-241
        assertDec(
            0xb204cc20.toInt(),
            DecodedInsn(
                "orr",
                listOf(
                    Operand.Reg("x0"),
                    Operand.Reg("x1"),
                    Operand.Imm(0xf0f0f0f0f0f0f0f0UL.toLong(), ImmFormat.HEX),
                ),
                0xb204cc20.toInt(),
            ),
        )
        // eor x0, x1, #0x5555555555555555  — Arm64Test.kt:bitmaskImm line 242-244
        assertDec(
            0xd200f020.toInt(),
            DecodedInsn(
                "eor",
                listOf(
                    Operand.Reg("x0"),
                    Operand.Reg("x1"),
                    Operand.Imm(0x5555555555555555L, ImmFormat.HEX),
                ),
                0xd200f020.toInt(),
            ),
        )
        // tst x0, #0xff  — Arm64Test.kt:bitmaskImm line 245
        assertDec(
            0xf2401c1f.toInt(),
            DecodedInsn(
                "tst",
                listOf(Operand.Reg("x0"), Operand.Imm(0xFFL, ImmFormat.HEX)),
                0xf2401c1f.toInt(),
            ),
        )
    }

    @Test fun aliasesAndEdges() {
        // Bug 1 regression: sub sp, sp, #16  (function prologue — Rd=31 must be SP when S=0)
        // Encodes as: subImm(X(31), X(31), 16)
        assertDec(
            0xd10043ff.toInt(),
            DecodedInsn(
                "sub",
                listOf(Operand.Reg("sp"), Operand.Reg("sp"), Operand.Imm(16, ImmFormat.DEC)),
                0xd10043ff.toInt(),
            ),
        )

        // Bug 2: movn — rd=31 must emit xzr (movn xzr, #5 is legal, just pointless)
        // Encodes as: movn(X(31), 5)  — sf=1, opc=00, hw=0, imm16=5, rd=31
        assertDec(
            0x928000bf.toInt(),
            DecodedInsn(
                "movn",
                listOf(Operand.Reg("xzr"), Operand.Imm(5, ImmFormat.HEX)),
                0x928000bf.toInt(),
            ),
        )

        // movn x0, #5 (normal form)
        assertDec(
            0x928000a0.toInt(),
            DecodedInsn(
                "movn",
                listOf(Operand.Reg("x0"), Operand.Imm(5, ImmFormat.HEX)),
                0x928000a0.toInt(),
            ),
        )

        // bfm x0, x1, #4, #8  (non-aliased bitfield)
        // sf=1, opc=01, n=1, immr=4, imms=8, rn=1, rd=0
        assertDec(
            0xb3442020.toInt(),
            DecodedInsn(
                "bfm",
                listOf(
                    Operand.Reg("x0"),
                    Operand.Reg("x1"),
                    Operand.Imm(4, ImmFormat.DEC),
                    Operand.Imm(8, ImmFormat.DEC),
                ),
                0xb3442020.toInt(),
            ),
        )

        // uxtb w0, w1  (ubfm w0, w1, #0, #7)
        assertDec(
            0x53001c20,
            DecodedInsn(
                "uxtb",
                listOf(Operand.Reg("w0"), Operand.Reg("w1")),
                0x53001c20,
            ),
        )

        // uxth w0, w1  (ubfm w0, w1, #0, #15)
        assertDec(
            0x53003c20,
            DecodedInsn(
                "uxth",
                listOf(Operand.Reg("w0"), Operand.Reg("w1")),
                0x53003c20,
            ),
        )

        // sxtb w0, w1  (sbfm w0, w1, #0, #7 — 32-bit form)
        assertDec(
            0x13001c20,
            DecodedInsn(
                "sxtb",
                listOf(Operand.Reg("w0"), Operand.Reg("w1")),
                0x13001c20,
            ),
        )

        // sxth w0, w1  (sbfm w0, w1, #0, #15 — 32-bit form)
        assertDec(
            0x13003c20,
            DecodedInsn(
                "sxth",
                listOf(Operand.Reg("w0"), Operand.Reg("w1")),
                0x13003c20,
            ),
        )

        // sxtw x0, w1  (sbfm x0, x1, #0, #31 — 64-bit form, Rn displayed as w-reg)
        assertDec(
            0x93407c20.toInt(),
            DecodedInsn(
                "sxtw",
                listOf(Operand.Reg("x0"), Operand.Reg("w1")),
                0x93407c20.toInt(),
            ),
        )

        // mov x0, #0xff alias from logical-imm  (orr x0, xzr, #0xff)
        // sf=1, opc=01, n=1, immr=0, imms=7, rn=31, rd=0
        assertDec(
            0xb2401fe0.toInt(),
            DecodedInsn(
                "mov",
                listOf(Operand.Reg("x0"), Operand.Imm(0xFFL, ImmFormat.HEX)),
                0xb2401fe0.toInt(),
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

    // ------------------------------------------------------------------
    // Data-processing — register: paired assertDec for every assertEnc in
    // Arm64Test.kt:{arithReg, compare(reg), mulDiv, logical(reg), condSelect}
    // ------------------------------------------------------------------

    @Test fun arithReg() {
        // add x0, x1, x2  — Arm64Test.kt:arithReg line 54
        assertDec(
            0x8b020020.toInt(),
            DecodedInsn(
                "add",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2")),
                0x8b020020.toInt(),
            ),
        )
        // sub x0, x1, x2  — Arm64Test.kt:arithReg line 55
        assertDec(
            0xcb020020.toInt(),
            DecodedInsn(
                "sub",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2")),
                0xcb020020.toInt(),
            ),
        )
        // cmp x0, x1  — Arm64Test.kt:compare line 64 (register form)
        assertDec(
            0xeb01001f.toInt(),
            DecodedInsn(
                "cmp",
                listOf(Operand.Reg("x0"), Operand.Reg("x1")),
                0xeb01001f.toInt(),
            ),
        )
        // mov x1, x0  (orr x1, xzr, x0) — Arm64Test.kt:logical line 105
        assertDec(
            0xaa0103e0.toInt(),
            DecodedInsn(
                "mov",
                listOf(Operand.Reg("x0"), Operand.Reg("x1")),
                0xaa0103e0.toInt(),
            ),
        )
    }

    @Test fun mulDiv() {
        // mul x0, x1, x2  — Arm64Test.kt:mulDiv line 94
        assertDec(
            0x9b027c20.toInt(),
            DecodedInsn(
                "mul",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2")),
                0x9b027c20.toInt(),
            ),
        )
        // madd x0, x1, x2, x3  — Arm64Test.kt:mulDiv line 95
        assertDec(
            0x9b020c20.toInt(),
            DecodedInsn(
                "madd",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2"), Operand.Reg("x3")),
                0x9b020c20.toInt(),
            ),
        )
        // udiv x0, x1, x2  — Arm64Test.kt:mulDiv line 96
        assertDec(
            0x9ac20820.toInt(),
            DecodedInsn(
                "udiv",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2")),
                0x9ac20820.toInt(),
            ),
        )
        // sdiv w0, w1, w2  — Arm64Test.kt:mulDiv line 97
        assertDec(
            0x1ac20c20.toInt(),
            DecodedInsn(
                "sdiv",
                listOf(Operand.Reg("w0"), Operand.Reg("w1"), Operand.Reg("w2")),
                0x1ac20c20.toInt(),
            ),
        )
    }

    @Test fun logicalReg() {
        // and x0, x1, x2  — Arm64Test.kt:logical line 101
        assertDec(
            0x8a020020.toInt(),
            DecodedInsn(
                "and",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2")),
                0x8a020020.toInt(),
            ),
        )
        // orr x0, x1, x2  — Arm64Test.kt:logical line 102
        assertDec(
            0xaa020020.toInt(),
            DecodedInsn(
                "orr",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2")),
                0xaa020020.toInt(),
            ),
        )
        // eor x0, x1, x2  — Arm64Test.kt:logical line 103
        assertDec(
            0xca020020.toInt(),
            DecodedInsn(
                "eor",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2")),
                0xca020020.toInt(),
            ),
        )
        // mvn x0, x1  — Arm64Test.kt:logical line 104
        assertDec(
            0xaa2103e0.toInt(),
            DecodedInsn(
                "mvn",
                listOf(Operand.Reg("x0"), Operand.Reg("x1")),
                0xaa2103e0.toInt(),
            ),
        )
        // mov x0, x1  (orr x0, xzr, x1) — Arm64Test.kt:logical line 105
        assertDec(
            0xaa0103e0.toInt(),
            DecodedInsn(
                "mov",
                listOf(Operand.Reg("x0"), Operand.Reg("x1")),
                0xaa0103e0.toInt(),
            ),
        )
    }

    @Test fun condSelectReg() {
        // csel x0, x1, x2, eq  — Arm64Test.kt:condSelect line 206-207
        assertDec(
            0x9a820020.toInt(),
            DecodedInsn(
                "csel",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2"), Operand.CondCode("eq")),
                0x9a820020.toInt(),
            ),
        )
        // csel w0, w1, w2, ne  — Arm64Test.kt:condSelect line 208-209
        assertDec(
            0x1a821020.toInt(),
            DecodedInsn(
                "csel",
                listOf(Operand.Reg("w0"), Operand.Reg("w1"), Operand.Reg("w2"), Operand.CondCode("ne")),
                0x1a821020.toInt(),
            ),
        )
        // csinc x0, x1, x2, lt  — Arm64Test.kt:condSelect line 210-211
        assertDec(
            0x9a82b420.toInt(),
            DecodedInsn(
                "csinc",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2"), Operand.CondCode("lt")),
                0x9a82b420.toInt(),
            ),
        )
        // csinv x0, x1, x2, gt  — Arm64Test.kt:condSelect line 212-213
        assertDec(
            0xda82c020.toInt(),
            DecodedInsn(
                "csinv",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2"), Operand.CondCode("gt")),
                0xda82c020.toInt(),
            ),
        )
        // csneg x0, x1, x2, le  — Arm64Test.kt:condSelect line 214-215
        assertDec(
            0xda82d420.toInt(),
            DecodedInsn(
                "csneg",
                listOf(Operand.Reg("x0"), Operand.Reg("x1"), Operand.Reg("x2"), Operand.CondCode("le")),
                0xda82d420.toInt(),
            ),
        )
    }
}
