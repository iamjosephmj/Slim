package io.simdkt.nativekt.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden-bytes tests for [Arm64]. Reference values were captured from
 * `clang --target=aarch64-linux-android -c` followed by `llvm-objdump -d`.
 *
 * Each case asserts that the encoder emits the same 32-bit opcode the LLVM
 * assembler does for the same source instruction.
 */
class Arm64Test {

    private fun hex(i: Int): String = "0x%08x".format(i)

    private fun assertEnc(expected: Int, actual: Int, label: String) {
        assertEquals("$label: expected=${hex(expected)} actual=${hex(actual)}", expected, actual)
    }

    @Test fun moveWide() {
        assertEnc(0xd2995fc0.toInt(), Arm64.movz(Arm64.X0, 0xCAFE), "movz x0, #0xCAFE")
        assertEnc(0xf2b81bc0.toInt(), Arm64.movk(Arm64.X0, 0xC0DE, shift = 16), "movk x0, #0xC0DE, lsl 16")
        assertEnc(0x52995fc1.toInt(), Arm64.movz(Arm64.W1, 0xCAFE), "movz w1, #0xCAFE")
        assertEnc(0x72b81bc1.toInt(), Arm64.movk(Arm64.W1, 0xC0DE, shift = 16), "movk w1, #0xC0DE, lsl 16")
    }

    @Test fun loadImmComposite() {
        // loadImm64 should produce one movz + three movks for arbitrary 64-bit values.
        val seq = Arm64.loadImm64(Arm64.X0, 0xDEADBEEFCAFEBABEUL.toLong())
        assertEquals(4, seq.size)
        // First chunk: 0xBABE → movz x0, #0xBABE = 0xd29757c0
        assertEnc(0xd29757c0.toInt(), seq[0], "movz x0, #0xBABE")
    }

    @Test fun gpLoadStoreImm() {
        assertEnc(0xb9000001.toInt(), Arm64.strW(Arm64.W1, Arm64.X0, 0), "str w1, [x0]")
        assertEnc(0xb9000801.toInt(), Arm64.strW(Arm64.W1, Arm64.X0, 8), "str w1, [x0, #8]")
        assertEnc(0xf9400020.toInt(), Arm64.ldr(Arm64.X0, Arm64.X1, 0), "ldr x0, [x1]")
        assertEnc(0xf9400820.toInt(), Arm64.ldr(Arm64.X0, Arm64.X1, 16), "ldr x0, [x1, #16]")
        assertEnc(0xf9000462.toInt(), Arm64.str(Arm64.X2, Arm64.X3, 8), "str x2, [x3, #8]")
        assertEnc(0xb9400420.toInt(), Arm64.ldrW(Arm64.W0, Arm64.X1, 4), "ldr w0, [x1, #4]")
        assertEnc(0x39400420.toInt(), Arm64.ldrB(Arm64.W0, Arm64.X1, 1), "ldrb w0, [x1, #1]")
        assertEnc(0x39000420.toInt(), Arm64.strB(Arm64.W0, Arm64.X1, 1), "strb w0, [x1, #1]")
    }

    @Test fun ldpStp() {
        assertEnc(0xa9017bfd.toInt(), Arm64.stp(Arm64.X29, Arm64.X30, Arm64.SP, 16), "stp x29, x30, [sp, #16]")
        assertEnc(0xad400440.toInt(), Arm64.ldp_q(Arm64.V0, Arm64.V1, Arm64.X2, 0), "ldp q0, q1, [x2]")
        assertEnc(0xad000440.toInt(), Arm64.stp_q(Arm64.V0, Arm64.V1, Arm64.X2, 0), "stp q0, q1, [x2]")
    }

    @Test fun arithReg() {
        assertEnc(0x8b020020.toInt(), Arm64.add(Arm64.X0, Arm64.X1, Arm64.X2), "add x0, x1, x2")
        assertEnc(0xcb020020.toInt(), Arm64.sub(Arm64.X0, Arm64.X1, Arm64.X2), "sub x0, x1, x2")
    }

    @Test fun arithImm() {
        assertEnc(0x91001020.toInt(), Arm64.addImm(Arm64.X0, Arm64.X1, 4), "add x0, x1, #4")
        assertEnc(0xd1001020.toInt(), Arm64.subImm(Arm64.X0, Arm64.X1, 4), "sub x0, x1, #4")
    }

    @Test fun compare() {
        assertEnc(0xeb01001f.toInt(), Arm64.cmp(Arm64.X0, Arm64.X1), "cmp x0, x1")
        assertEnc(0xf100001f.toInt(), Arm64.cmpImm(Arm64.X0, 0), "cmp x0, #0")
    }

    @Test fun branches() {
        assertEnc(0xb4000020.toInt(), Arm64.cbz(Arm64.X0, 4), "cbz x0, .+4")
        assertEnc(0x35000021.toInt(), Arm64.cbnz(Arm64.W1, 4), "cbnz w1, .+4")
        assertEnc(0x36000020.toInt(), Arm64.tbz(Arm64.X0, 0, 4), "tbz x0, #0, .+4")
        assertEnc(0x14000002.toInt(), Arm64.b(8), "b .+8")
        assertEnc(0x94000002.toInt(), Arm64.bl(8), "bl .+8")
        assertEnc(0xd65f03c0.toInt(), Arm64.ret(), "ret")
        assertEnc(0xd65f0000.toInt(), Arm64.ret(Arm64.X0), "ret x0")
        assertEnc(0xd63f0200.toInt(), Arm64.blr(Arm64.X16), "blr x16")
        assertEnc(0xd61f0220.toInt(), Arm64.br(Arm64.X17), "br x17")
        assertEnc(0x54000020.toInt(), Arm64.bCond(Arm64.Cond.EQ, 4), "b.eq .+4")
        assertEnc(0x54000041.toInt(), Arm64.bCond(Arm64.Cond.NE, 8), "b.ne .+8")
    }

    @Test fun systemAndPac() {
        assertEnc(0xd503201f.toInt(), Arm64.nop(), "nop")
        assertEnc(0xd5033fdf.toInt(), Arm64.isb(), "isb")
        assertEnc(0xd503233f.toInt(), Arm64.paciasp(), "paciasp")
        assertEnc(0xd50323bf.toInt(), Arm64.autiasp(), "autiasp")
        assertEnc(0xd503245f.toInt(), Arm64.btiC(), "bti c")
        assertEnc(0xd503249f.toInt(), Arm64.btiJ(), "bti j")
        assertEnc(0xd50324df.toInt(), Arm64.btiJC(), "bti jc")
        assertEnc(0xd5033fbf.toInt(), Arm64.dmb(0xF), "dmb sy")
    }

    @Test fun mulDiv() {
        assertEnc(0x9b027c20.toInt(), Arm64.mul(Arm64.X0, Arm64.X1, Arm64.X2), "mul x0, x1, x2")
        assertEnc(0x9b020c20.toInt(), Arm64.madd(Arm64.X0, Arm64.X1, Arm64.X2, Arm64.X3), "madd x0, x1, x2, x3")
        assertEnc(0x9ac20820.toInt(), Arm64.udiv(Arm64.X0, Arm64.X1, Arm64.X2), "udiv x0, x1, x2")
        assertEnc(0x1ac20c20.toInt(), Arm64.sdiv(Arm64.W0, Arm64.W1, Arm64.W2), "sdiv w0, w1, w2")
    }

    @Test fun logical() {
        assertEnc(0x8a020020.toInt(), Arm64.and(Arm64.X0, Arm64.X1, Arm64.X2), "and x0, x1, x2")
        assertEnc(0xaa020020.toInt(), Arm64.orr(Arm64.X0, Arm64.X1, Arm64.X2), "orr x0, x1, x2")
        assertEnc(0xca020020.toInt(), Arm64.eor(Arm64.X0, Arm64.X1, Arm64.X2), "eor x0, x1, x2")
        assertEnc(0xaa2103e0.toInt(), Arm64.mvn(Arm64.X0, Arm64.X1), "mvn x0, x1")
        assertEnc(0xaa0103e0.toInt(), Arm64.mov(Arm64.X0, Arm64.X1), "mov x0, x1")
    }

    @Test fun shifts() {
        assertEnc(0xd378dc20.toInt(), Arm64.lsl(Arm64.X0, Arm64.X1, 8), "lsl x0, x1, #8")
        assertEnc(0xd348fc20.toInt(), Arm64.lsr(Arm64.X0, Arm64.X1, 8), "lsr x0, x1, #8")
        assertEnc(0x9348fc20.toInt(), Arm64.asr(Arm64.X0, Arm64.X1, 8), "asr x0, x1, #8")
    }

    @Test fun fpVector() {
        assertEnc(0x4e22d420.toInt(), Arm64.fadd(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fadd v0.4s, v1.4s, v2.4s")
        assertEnc(0x4e62d420.toInt(), Arm64.fadd(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.D2), "fadd v0.2d, v1.2d, v2.2d")
        assertEnc(0x4ea2d420.toInt(), Arm64.fsub(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fsub v0.4s, v1.4s, v2.4s")
        assertEnc(0x6e22dc20.toInt(), Arm64.fmul(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fmul v0.4s, v1.4s, v2.4s")
        assertEnc(0x6e22fc20.toInt(), Arm64.fdiv(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fdiv v0.4s, v1.4s, v2.4s")
        assertEnc(0x4e22cc20.toInt(), Arm64.fmla(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fmla v0.4s, v1.4s, v2.4s")
        assertEnc(0x4ea2cc20.toInt(), Arm64.fmls(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fmls v0.4s, v1.4s, v2.4s")
        assertEnc(0x4ea2f420.toInt(), Arm64.fmin(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fmin v0.4s, v1.4s, v2.4s")
        assertEnc(0x4e22f420.toInt(), Arm64.fmax(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fmax v0.4s, v1.4s, v2.4s")
    }

    @Test fun fpScalar() {
        assertEnc(0x1e222820.toInt(), Arm64.faddS(Arm64.V0, Arm64.V1, Arm64.V2), "fadd s0, s1, s2")
        assertEnc(0x1e622820.toInt(), Arm64.faddD(Arm64.V0, Arm64.V1, Arm64.V2), "fadd d0, d1, d2")
    }

    @Test fun intVector() {
        assertEnc(0x4e228420.toInt(), Arm64.addVec(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.B16), "add v0.16b, v1.16b, v2.16b")
        assertEnc(0x4ea28420.toInt(), Arm64.addVec(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "add v0.4s, v1.4s, v2.4s")
        assertEnc(0x6ea28420.toInt(), Arm64.subVec(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "sub v0.4s, v1.4s, v2.4s")
        assertEnc(0x4ea29c20.toInt(), Arm64.mulVec(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "mul v0.4s, v1.4s, v2.4s")
        assertEnc(0x4ea29420.toInt(), Arm64.mlaVec(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "mla v0.4s, v1.4s, v2.4s")
        assertEnc(0x6ea29420.toInt(), Arm64.mlsVec(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "mls v0.4s, v1.4s, v2.4s")
    }

    @Test fun neonMisc() {
        assertEnc(0x4e040c20.toInt(), Arm64.dup(Arm64.V0, Arm64.X1, Arm64.VArr.S4), "dup v0.4s, w1")
        assertEnc(0x4e010c20.toInt(), Arm64.dup(Arm64.V0, Arm64.X1, Arm64.VArr.B16), "dup v0.16b, w1")
        assertEnc(0x0e612820.toInt(), Arm64.xtn(Arm64.V0, Arm64.V1, Arm64.VArr.H4), "xtn v0.4h, v1.4s")
        assertEnc(0x4e612820.toInt(), Arm64.xtn2(Arm64.V0, Arm64.V1, Arm64.VArr.H8), "xtn2 v0.8h, v1.4s")
        assertEnc(0x2f10a420.toInt(), Arm64.uxtl(Arm64.V0, Arm64.V1, Arm64.VArr.H4), "uxtl v0.4s, v1.4h")
        assertEnc(0x0f10a420.toInt(), Arm64.sxtl(Arm64.V0, Arm64.V1, Arm64.VArr.H4), "sxtl v0.4s, v1.4h")
    }

    @Test fun simdMemory() {
        assertEnc(0x4c407020.toInt(), Arm64.ld1(Arm64.V0, Arm64.X1, Arm64.VArr.B16), "ld1 {v0.16b}, [x1]")
        assertEnc(0x4c007020.toInt(), Arm64.st1(Arm64.V0, Arm64.X1, Arm64.VArr.B16), "st1 {v0.16b}, [x1]")
        assertEnc(0x4d40c820.toInt(), Arm64.ld1r(Arm64.V0, Arm64.X1, Arm64.VArr.S4), "ld1r {v0.4s}, [x1]")
    }

    @Test fun neonLogical() {
        assertEnc(0x4e221c20.toInt(), Arm64.andVec(Arm64.V0, Arm64.V1, Arm64.V2), "and v0.16b, v1.16b, v2.16b")
        assertEnc(0x4ea21c20.toInt(), Arm64.orrVec(Arm64.V0, Arm64.V1, Arm64.V2), "orr v0.16b, v1.16b, v2.16b")
        assertEnc(0x6e221c20.toInt(), Arm64.eorVec(Arm64.V0, Arm64.V1, Arm64.V2), "eor v0.16b, v1.16b, v2.16b")
        assertEnc(0x4e621c20.toInt(), Arm64.bicVec(Arm64.V0, Arm64.V1, Arm64.V2), "bic v0.16b, v1.16b, v2.16b")
    }

    // ---------------------------------------------------------------
    // Tier 2: FP convert / compare / csel / reciprocal / bitmask /
    //         pre-post-index / register-offset loads.
    // ---------------------------------------------------------------

    @Test fun fpConvertScalar() {
        assertEnc(0x1e380000.toInt(), Arm64.fcvtzsW(Arm64.W0, Arm64.V0), "fcvtzs w0, s0")
        assertEnc(0x9e780000.toInt(), Arm64.fcvtzsX(Arm64.X0, Arm64.V0), "fcvtzs x0, d0")
        assertEnc(0x1e380020.toInt(), Arm64.fcvtzsW(Arm64.W0, Arm64.V1), "fcvtzs w0, s1")
        assertEnc(0x1e220000.toInt(), Arm64.scvtfS(Arm64.V0, Arm64.W0), "scvtf s0, w0")
        assertEnc(0x9e620000.toInt(), Arm64.scvtfD(Arm64.V0, Arm64.X0), "scvtf d0, x0")
        assertEnc(0x1e220041.toInt(), Arm64.scvtfS(Arm64.V1, Arm64.W2), "scvtf s1, w2")
    }

    @Test fun fpConvertVector() {
        assertEnc(0x4ea1b820.toInt(), Arm64.fcvtzsVec(Arm64.V0, Arm64.V1, Arm64.VArr.S4),
            "fcvtzs v0.4s, v1.4s")
        assertEnc(0x4ee1b820.toInt(), Arm64.fcvtzsVec(Arm64.V0, Arm64.V1, Arm64.VArr.D2),
            "fcvtzs v0.2d, v1.2d")
        assertEnc(0x4e21d820.toInt(), Arm64.scvtfVec(Arm64.V0, Arm64.V1, Arm64.VArr.S4),
            "scvtf v0.4s, v1.4s")
        assertEnc(0x4e61d820.toInt(), Arm64.scvtfVec(Arm64.V0, Arm64.V1, Arm64.VArr.D2),
            "scvtf v0.2d, v1.2d")
    }

    @Test fun fpCompareScalar() {
        assertEnc(0x1e212000.toInt(), Arm64.fcmpS(Arm64.V0, Arm64.V1), "fcmp s0, s1")
        assertEnc(0x1e612000.toInt(), Arm64.fcmpD(Arm64.V0, Arm64.V1), "fcmp d0, d1")
        assertEnc(0x1e202008.toInt(), Arm64.fcmpSZero(Arm64.V0), "fcmp s0, #0.0")
        assertEnc(0x1e602008.toInt(), Arm64.fcmpDZero(Arm64.V0), "fcmp d0, #0.0")
    }

    @Test fun fpCompareVector() {
        assertEnc(0x6ea2e420.toInt(),
            Arm64.fcmgt(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fcmgt v0.4s, v1.4s, v2.4s")
        assertEnc(0x6e22e420.toInt(),
            Arm64.fcmge(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fcmge v0.4s, v1.4s, v2.4s")
        assertEnc(0x4e22e420.toInt(),
            Arm64.fcmeq(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fcmeq v0.4s, v1.4s, v2.4s")
        assertEnc(0x6ee2e420.toInt(),
            Arm64.fcmgt(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.D2), "fcmgt v0.2d, v1.2d, v2.2d")
    }

    @Test fun condSelect() {
        assertEnc(0x9a820020.toInt(),
            Arm64.csel(Arm64.X0, Arm64.X1, Arm64.X2, Arm64.Cond.EQ), "csel x0, x1, x2, eq")
        assertEnc(0x1a821020.toInt(),
            Arm64.csel(Arm64.W0, Arm64.W1, Arm64.W2, Arm64.Cond.NE), "csel w0, w1, w2, ne")
        assertEnc(0x9a82b420.toInt(),
            Arm64.csinc(Arm64.X0, Arm64.X1, Arm64.X2, Arm64.Cond.LT), "csinc x0, x1, x2, lt")
        assertEnc(0xda82c020.toInt(),
            Arm64.csinv(Arm64.X0, Arm64.X1, Arm64.X2, Arm64.Cond.GT), "csinv x0, x1, x2, gt")
        assertEnc(0xda82d420.toInt(),
            Arm64.csneg(Arm64.X0, Arm64.X1, Arm64.X2, Arm64.Cond.LE), "csneg x0, x1, x2, le")
        assertEnc(0x1e220c20.toInt(),
            Arm64.fcselS(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.Cond.EQ), "fcsel s0, s1, s2, eq")
        assertEnc(0x1e621c20.toInt(),
            Arm64.fcselD(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.Cond.NE), "fcsel d0, d1, d2, ne")
    }

    @Test fun reciprocalEstimates() {
        assertEnc(0x4ea1d820.toInt(),
            Arm64.frecpe(Arm64.V0, Arm64.V1, Arm64.VArr.S4), "frecpe v0.4s, v1.4s")
        assertEnc(0x4e22fc20.toInt(),
            Arm64.frecps(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "frecps v0.4s, v1.4s, v2.4s")
        assertEnc(0x6ea1d820.toInt(),
            Arm64.frsqrte(Arm64.V0, Arm64.V1, Arm64.VArr.S4), "frsqrte v0.4s, v1.4s")
        assertEnc(0x4ea2fc20.toInt(),
            Arm64.frsqrts(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "frsqrts v0.4s, v1.4s, v2.4s")
        assertEnc(0x5ea1d820.toInt(), Arm64.frecpeS(Arm64.V0, Arm64.V1), "frecpe s0, s1")
        assertEnc(0x7ea1d820.toInt(), Arm64.frsqrteS(Arm64.V0, Arm64.V1), "frsqrte s0, s1")
    }

    @Test fun bitmaskImm() {
        assertEnc(0x92401c20.toInt(), Arm64.andImm(Arm64.X0, Arm64.X1, 0xFFL), "and x0, x1, #0xff")
        assertEnc(0x92403c20.toInt(), Arm64.andImm(Arm64.X0, Arm64.X1, 0xFFFFL), "and x0, x1, #0xffff")
        assertEnc(0x12001c20.toInt(), Arm64.andImm(Arm64.W0, Arm64.W1, 0xFFL), "and w0, w1, #0xff")
        assertEnc(0xb204cc20.toInt(),
            Arm64.orrImm(Arm64.X0, Arm64.X1, 0xF0F0F0F0F0F0F0F0UL.toLong()),
            "orr x0, x1, #0xf0f0f0f0f0f0f0f0")
        assertEnc(0xd200f020.toInt(),
            Arm64.eorImm(Arm64.X0, Arm64.X1, 0x5555555555555555L),
            "eor x0, x1, #0x5555555555555555")
        assertEnc(0xf2401c1f.toInt(), Arm64.tstImm(Arm64.X0, 0xFFL), "tst x0, #0xff")
    }

    @Test fun bitmaskImmRejectsInvalid() {
        // 0 and all-ones aren't representable.
        assertEquals(null, Arm64.encodeBitmaskImm(0L, 64))
        assertEquals(null, Arm64.encodeBitmaskImm(-1L, 64))
        assertEquals(null, Arm64.encodeBitmaskImm(0xFFFFFFFFL, 32))
        // Non-replicating, non-rotated mask. 0b1010 has period 1 but isn't all-ones.
        // 0x101 (binary 1_0000_0001) — has 2 ones not adjacent, can't be a single rotated run.
        assertEquals(null, Arm64.encodeBitmaskImm(0x101L, 64))
    }

    @Test fun preIndexLoads() {
        assertEnc(0xa9bf7bfd.toInt(),
            Arm64.stpPre(Arm64.X29, Arm64.X30, Arm64.SP, -16), "stp x29,x30,[sp,#-16]!")
        assertEnc(0xa8c17bfd.toInt(),
            Arm64.ldpPost(Arm64.X29, Arm64.X30, Arm64.SP, 16), "ldp x29,x30,[sp],#16")
        assertEnc(0xf8408c20.toInt(),
            Arm64.ldrPre(Arm64.X0, Arm64.X1, 8), "ldr x0,[x1,#8]!")
        assertEnc(0xf8408420.toInt(),
            Arm64.ldrPost(Arm64.X0, Arm64.X1, 8), "ldr x0,[x1],#8")
        assertEnc(0xf81f8c20.toInt(),
            Arm64.strPre(Arm64.X0, Arm64.X1, -8), "str x0,[x1,#-8]!")
    }

    @Test fun registerOffsetLoads() {
        assertEnc(0xf8627820.toInt(),
            Arm64.ldrReg(Arm64.X0, Arm64.X1, Arm64.X2), "ldr x0,[x1,x2,lsl#3]")
        assertEnc(0xb8627820.toInt(),
            Arm64.ldrRegW(Arm64.W0, Arm64.X1, Arm64.X2), "ldr w0,[x1,x2,lsl#2]")
        assertEnc(0xf8227820.toInt(),
            Arm64.strReg(Arm64.X0, Arm64.X1, Arm64.X2), "str x0,[x1,x2,lsl#3]")
        assertEnc(0x38626820.toInt(),
            Arm64.ldrbReg(Arm64.W0, Arm64.X1, Arm64.X2), "ldrb w0,[x1,x2]")
        assertEnc(0xf8625820.toInt(),
            Arm64.ldrReg(Arm64.X0, Arm64.X1, Arm64.X2, Arm64.IndexExt.UXTW),
            "ldr x0,[x1,w2,uxtw#3]")
        assertEnc(0xf862d820.toInt(),
            Arm64.ldrReg(Arm64.X0, Arm64.X1, Arm64.X2, Arm64.IndexExt.SXTW),
            "ldr x0,[x1,w2,sxtw#3]")
    }

    // ---------------------------------------------------------------
    // Tier 3: saturating arith / dot product / FP16 / address-relative /
    //         min-max numeric.
    // ---------------------------------------------------------------

    @Test fun saturatingArith() {
        assertEnc(0x4e220c20.toInt(),
            Arm64.sqadd(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.B16), "sqadd v0.16b, v1.16b, v2.16b")
        assertEnc(0x4ea20c20.toInt(),
            Arm64.sqadd(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "sqadd v0.4s, v1.4s, v2.4s")
        assertEnc(0x6e220c20.toInt(),
            Arm64.uqadd(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.B16), "uqadd v0.16b, v1.16b, v2.16b")
        assertEnc(0x6ea20c20.toInt(),
            Arm64.uqadd(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "uqadd v0.4s, v1.4s, v2.4s")
        assertEnc(0x4e222c20.toInt(),
            Arm64.sqsub(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.B16), "sqsub v0.16b, v1.16b, v2.16b")
        assertEnc(0x4ea22c20.toInt(),
            Arm64.sqsub(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "sqsub v0.4s, v1.4s, v2.4s")
        assertEnc(0x6e222c20.toInt(),
            Arm64.uqsub(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.B16), "uqsub v0.16b, v1.16b, v2.16b")
        assertEnc(0x6ea22c20.toInt(),
            Arm64.uqsub(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "uqsub v0.4s, v1.4s, v2.4s")
    }

    @Test fun saturatingExtractNarrow() {
        assertEnc(0x0e214820.toInt(),
            Arm64.sqxtn(Arm64.V0, Arm64.V1, Arm64.VArr.B8), "sqxtn v0.8b, v1.8h")
        assertEnc(0x0e614820.toInt(),
            Arm64.sqxtn(Arm64.V0, Arm64.V1, Arm64.VArr.H4), "sqxtn v0.4h, v1.4s")
        assertEnc(0x0ea14820.toInt(),
            Arm64.sqxtn(Arm64.V0, Arm64.V1, Arm64.VArr.S2), "sqxtn v0.2s, v1.2d")
        assertEnc(0x4e214820.toInt(),
            Arm64.sqxtn(Arm64.V0, Arm64.V1, Arm64.VArr.B16), "sqxtn2 v0.16b, v1.8h")
        assertEnc(0x2e214820.toInt(),
            Arm64.uqxtn(Arm64.V0, Arm64.V1, Arm64.VArr.B8), "uqxtn v0.8b, v1.8h")
    }

    @Test fun saturatingDoublingMulHigh() {
        assertEnc(0x4ea2b420.toInt(),
            Arm64.sqdmulh(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "sqdmulh v0.4s, v1.4s, v2.4s")
        assertEnc(0x4e62b420.toInt(),
            Arm64.sqdmulh(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.H8), "sqdmulh v0.8h, v1.8h, v2.8h")
    }

    @Test fun dotProduct() {
        assertEnc(0x4e829420.toInt(),
            Arm64.sdot(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "sdot v0.4s, v1.16b, v2.16b")
        assertEnc(0x6e829420.toInt(),
            Arm64.udot(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "udot v0.4s, v1.16b, v2.16b")
        assertEnc(0x0e829420.toInt(),
            Arm64.sdot(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S2), "sdot v0.2s, v1.8b, v2.8b")
        assertEnc(0x2e829420.toInt(),
            Arm64.udot(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S2), "udot v0.2s, v1.8b, v2.8b")
    }

    @Test fun halfPrecisionVector() {
        assertEnc(0x0e421420.toInt(),
            Arm64.faddH(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.H4), "fadd v0.4h, v1.4h, v2.4h")
        assertEnc(0x4e421420.toInt(),
            Arm64.faddH(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.H8), "fadd v0.8h, v1.8h, v2.8h")
        assertEnc(0x4ec21420.toInt(),
            Arm64.fsubH(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.H8), "fsub v0.8h, v1.8h, v2.8h")
        assertEnc(0x6e421c20.toInt(),
            Arm64.fmulH(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.H8), "fmul v0.8h, v1.8h, v2.8h")
        assertEnc(0x4e420c20.toInt(),
            Arm64.fmlaH(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.H8), "fmla v0.8h, v1.8h, v2.8h")
        assertEnc(0x4ec20c20.toInt(),
            Arm64.fmlsH(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.H8), "fmls v0.8h, v1.8h, v2.8h")
    }

    @Test fun fpHalfConvert() {
        assertEnc(0x1e23c000.toInt(), Arm64.fcvtHFromS(Arm64.V0, Arm64.V0), "fcvt h0, s0")
        assertEnc(0x1ee24000.toInt(), Arm64.fcvtSFromH(Arm64.V0, Arm64.V0), "fcvt s0, h0")
        assertEnc(0x0e217820.toInt(), Arm64.fcvtl(Arm64.V0, Arm64.V1, q = 0), "fcvtl v0.4s, v1.4h")
        assertEnc(0x4e217820.toInt(), Arm64.fcvtl(Arm64.V0, Arm64.V1, q = 1), "fcvtl2 v0.4s, v1.8h")
        assertEnc(0x0e216820.toInt(), Arm64.fcvtn(Arm64.V0, Arm64.V1, q = 0), "fcvtn v0.4h, v1.4s")
        assertEnc(0x4e216820.toInt(), Arm64.fcvtn(Arm64.V0, Arm64.V1, q = 1), "fcvtn2 v0.8h, v1.4s")
    }

    @Test fun addressRelative() {
        assertEnc(0x10000040.toInt(), Arm64.adr(Arm64.X0, 8), "adr x0, .+8")
        assertEnc(0x90000000.toInt(), Arm64.adrp(Arm64.X0, 0), "adrp x0, page+0")
        // Negative offset: adr x0, .-4 → byteOffset = -4 → imm21 = 0x1FFFFC.
        // Resulting bits: immlo=0, immhi = 0x7FFFF, encoded = 0x10FFFFE0.
        assertEnc(0x10ffffe0.toInt(), Arm64.adr(Arm64.X0, -4), "adr x0, .-4")
    }

    @Test fun fpMinMaxNumeric() {
        assertEnc(0x4ea2c420.toInt(),
            Arm64.fminnm(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fminnm v0.4s, v1.4s, v2.4s")
        assertEnc(0x4e22c420.toInt(),
            Arm64.fmaxnm(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4), "fmaxnm v0.4s, v1.4s, v2.4s")
        assertEnc(0x4ee2c420.toInt(),
            Arm64.fminnm(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.D2), "fminnm v0.2d, v1.2d, v2.2d")
        assertEnc(0x4e62c420.toInt(),
            Arm64.fmaxnm(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.D2), "fmaxnm v0.2d, v1.2d, v2.2d")
        assertEnc(0x1e227820.toInt(),
            Arm64.fminnmS(Arm64.V0, Arm64.V1, Arm64.V2), "fminnm s0, s1, s2")
        assertEnc(0x1e226820.toInt(),
            Arm64.fmaxnmS(Arm64.V0, Arm64.V1, Arm64.V2), "fmaxnm s0, s1, s2")
        assertEnc(0x1e627820.toInt(),
            Arm64.fminnmD(Arm64.V0, Arm64.V1, Arm64.V2), "fminnm d0, d1, d2")
        assertEnc(0x1e626820.toInt(),
            Arm64.fmaxnmD(Arm64.V0, Arm64.V1, Arm64.V2), "fmaxnm d0, d1, d2")
    }

    @Test fun assemblePacksLittleEndian() {
        val bytes = Arm64.assemble(Arm64.movz(Arm64.X0, 0xCAFE), Arm64.ret())
        assertEquals(8, bytes.size)
        // movz x0, #0xCAFE = 0xd2995fc0 little-endian → c0 5f 99 d2
        assertEquals(0xc0.toByte(), bytes[0])
        assertEquals(0x5f.toByte(), bytes[1])
        assertEquals(0x99.toByte(), bytes[2])
        assertEquals(0xd2.toByte(), bytes[3])
        // ret = 0xd65f03c0 → c0 03 5f d6
        assertEquals(0xc0.toByte(), bytes[4])
        assertEquals(0x03.toByte(), bytes[5])
        assertEquals(0x5f.toByte(), bytes[6])
        assertEquals(0xd6.toByte(), bytes[7])
    }
}
