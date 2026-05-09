package io.simdkt.nativekt.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin AArch64 instruction encoder.
 *
 * Each helper returns a 32-bit opcode (`Int`). [assemble] packs a list of
 * opcodes into a little-endian `ByteArray` ready to be written into a
 * memfd-backed RW mapping.
 *
 * The encoding constants come straight from the ARM Architecture Reference
 * Manual (DDI 0487). The unit tests in `src/test` lock the encoder against
 * golden bytes captured from `aarch64-linux-gnu-as`.
 *
 * Coverage matches the SDK_PLAN Phase 3 table — moves, GP/SIMD memory,
 * integer arithmetic, logicals, compare/branch, NEON FP/integer/misc/logical,
 * PAC/BTI, and miscellaneous. Bitmask-immediate encoding (AND/ORR/EOR with
 * immediate operand) and the saturating/crypto subgroups are deferred to a
 * future pass.
 */
@Suppress(
    "FunctionName",
    "MemberVisibilityCanBePrivate",
    "unused"
)
object Arm64 {

    // -----------------------------------------------------------------
    // Register types
    // -----------------------------------------------------------------

    @JvmInline
    value class X(val n: Int) {
        init {
            require(n in 0..31) { "X register out of range: $n" }
        }
    }

    @JvmInline
    value class W(val n: Int) {
        init {
            require(n in 0..31) { "W register out of range: $n" }
        }
    }

    @JvmInline
    value class V(val n: Int) {
        init {
            require(n in 0..31) { "V register out of range: $n" }
        }
    }

    val X0 = X(0);
    val X1 = X(1);
    val X2 = X(2);
    val X3 = X(3)
    val X4 = X(4);
    val X5 = X(5);
    val X6 = X(6);
    val X7 = X(7)
    val X8 = X(8);
    val X9 = X(9);
    val X10 = X(10);
    val X11 = X(11)
    val X12 = X(12);
    val X13 = X(13);
    val X14 = X(14);
    val X15 = X(15)
    val X16 = X(16);
    val X17 = X(17);
    val X18 = X(18);
    val X19 = X(19)
    val X20 = X(20);
    val X21 = X(21);
    val X22 = X(22);
    val X23 = X(23)
    val X24 = X(24);
    val X25 = X(25);
    val X26 = X(26);
    val X27 = X(27)
    val X28 = X(28);
    val X29 = X(29);
    val X30 = X(30);
    val XZR = X(31)
    val SP = X(31)

    val W0 = W(0);
    val W1 = W(1);
    val W2 = W(2);
    val W3 = W(3)
    val W4 = W(4);
    val W5 = W(5);
    val W6 = W(6);
    val W7 = W(7)
    val W8 = W(8);
    val W9 = W(9);
    val W10 = W(10);
    val W11 = W(11)
    val W12 = W(12);
    val W13 = W(13);
    val W14 = W(14);
    val W15 = W(15)
    val W16 = W(16);
    val W17 = W(17);
    val W18 = W(18);
    val W19 = W(19)
    val W20 = W(20);
    val W21 = W(21);
    val W22 = W(22);
    val W23 = W(23)
    val W24 = W(24);
    val W25 = W(25);
    val W26 = W(26);
    val W27 = W(27)
    val W28 = W(28);
    val W29 = W(29);
    val W30 = W(30);
    val WZR = W(31)
    val WSP = W(31)

    val V0 = V(0);
    val V1 = V(1);
    val V2 = V(2);
    val V3 = V(3)
    val V4 = V(4);
    val V5 = V(5);
    val V6 = V(6);
    val V7 = V(7)
    val V8 = V(8);
    val V9 = V(9);
    val V10 = V(10);
    val V11 = V(11)
    val V12 = V(12);
    val V13 = V(13);
    val V14 = V(14);
    val V15 = V(15)
    val V16 = V(16);
    val V17 = V(17);
    val V18 = V(18);
    val V19 = V(19)
    val V20 = V(20);
    val V21 = V(21);
    val V22 = V(22);
    val V23 = V(23)
    val V24 = V(24);
    val V25 = V(25);
    val V26 = V(26);
    val V27 = V(27)
    val V28 = V(28);
    val V29 = V(29);
    val V30 = V(30);
    val V31 = V(31)

    /** Vector arrangement (Q, size, lanes). */
    enum class VArr(val q: Int, val size: Int) {
        B8(0, 0), B16(1, 0),
        H4(0, 1), H8(1, 1),
        S2(0, 2), S4(1, 2),
        D1(0, 3), D2(1, 3),
    }

    /** Floating-point scalar arrangement. */
    enum class FArr(val ftype: Int) { S(0), D(1), H(3) }

    /** Branch condition codes. */
    enum class Cond(val v: Int) {
        EQ(0), NE(1), CS(2), CC(3),
        MI(4), PL(5), VS(6), VC(7),
        HI(8), LS(9), GE(10), LT(11),
        GT(12), LE(13), AL(14), NV(15);

        val HS get() = CS
        val LO get() = CC
    }

    // -----------------------------------------------------------------
    // Bit-pack helpers
    // -----------------------------------------------------------------

    private fun mask(width: Int) = (1 shl width) - 1
    private fun bits(value: Int, width: Int): Int = value and mask(width)
    private fun bits(value: Long, width: Int): Long = value and ((1L shl width) - 1L)

    // -----------------------------------------------------------------
    // Move wide immediate
    //   sf [opc] 100101 hw imm16 Rd
    // -----------------------------------------------------------------

    private fun moveWide(sf: Int, opc: Int, rd: Int, imm16: Int, shift: Int): Int {
        val hw = when (shift) {
            0 -> 0; 16 -> 1; 32 -> 2; 48 -> 3
            else -> error("shift must be 0/16/32/48 (got $shift)")
        }
        require(sf == 1 || hw <= 1) { "shift $shift invalid for 32-bit register" }
        require(imm16 in 0..0xFFFF) { "imm16 out of range: $imm16" }
        return (sf shl 31) or (opc shl 29) or (0b100101 shl 23) or
                (hw shl 21) or (imm16 shl 5) or rd
    }

    fun movz(rd: X, imm16: Int, shift: Int = 0): Int = moveWide(1, 0b10, rd.n, imm16, shift)
    fun movz(rd: W, imm16: Int, shift: Int = 0): Int = moveWide(0, 0b10, rd.n, imm16, shift)
    fun movk(rd: X, imm16: Int, shift: Int = 0): Int = moveWide(1, 0b11, rd.n, imm16, shift)
    fun movk(rd: W, imm16: Int, shift: Int = 0): Int = moveWide(0, 0b11, rd.n, imm16, shift)
    fun movn(rd: X, imm16: Int, shift: Int = 0): Int = moveWide(1, 0b00, rd.n, imm16, shift)
    fun movn(rd: W, imm16: Int, shift: Int = 0): Int = moveWide(0, 0b00, rd.n, imm16, shift)

    /** MOV (register): alias for ORR Xd, XZR, Xm. */
    fun mov(rd: X, rm: X): Int = orr(rd, XZR, rm)
    fun mov(rd: W, rm: W): Int = orr(rd, WZR, rm)

    /**
     * Emit the smallest sequence of MOVZ/MOVK that loads [imm] into [rd].
     * Always emits at least 1 instruction (movz xd, #0).
     */
    fun loadImm64(rd: X, imm: Long): List<Int> {
        val out = ArrayList<Int>(4)
        var first = true
        for (shift in intArrayOf(0, 16, 32, 48)) {
            val chunk = ((imm ushr shift) and 0xFFFF).toInt()
            if (chunk == 0 && !first) continue
            if (first) {
                out += movz(rd, chunk, shift)
                first = false
            } else {
                out += movk(rd, chunk, shift)
            }
        }
        if (out.isEmpty()) out += movz(rd, 0, 0)
        return out
    }

    /** 32-bit variant. Emits 1 or 2 instructions. */
    fun loadImm32(rd: W, imm: Int): List<Int> {
        val out = ArrayList<Int>(2)
        val low = imm and 0xFFFF
        val high = (imm ushr 16) and 0xFFFF
        out += movz(rd, low, 0)
        if (high != 0) out += movk(rd, high, 16)
        return out
    }

    // -----------------------------------------------------------------
    // GP load/store (immediate, unsigned offset, scaled)
    //   size [111] 0 [01] opc imm12 Rn Rt
    // -----------------------------------------------------------------

    private fun ldStImm(size: Int, opc: Int, rt: Int, rn: Int, offset: Int, scale: Int): Int {
        require(offset >= 0) { "negative offset requires LDUR/STUR (not encoded here)" }
        require(offset and ((1 shl scale) - 1) == 0) {
            "offset $offset not aligned to $scale-byte boundary"
        }
        val imm12 = offset shr scale
        require(imm12 in 0..0xFFF) { "scaled offset $imm12 out of range" }
        return (size shl 30) or (0b111 shl 27) or (0 shl 26) or (0b01 shl 24) or
                (opc shl 22) or (imm12 shl 10) or (rn shl 5) or rt
    }

    fun ldr(rt: X, rn: X, offset: Int = 0): Int = ldStImm(0b11, 0b01, rt.n, rn.n, offset, 3)
    fun str(rt: X, rn: X, offset: Int = 0): Int = ldStImm(0b11, 0b00, rt.n, rn.n, offset, 3)
    fun ldrW(rt: W, rn: X, offset: Int = 0): Int = ldStImm(0b10, 0b01, rt.n, rn.n, offset, 2)
    fun strW(rt: W, rn: X, offset: Int = 0): Int = ldStImm(0b10, 0b00, rt.n, rn.n, offset, 2)
    fun ldrH(rt: W, rn: X, offset: Int = 0): Int = ldStImm(0b01, 0b01, rt.n, rn.n, offset, 1)
    fun strH(rt: W, rn: X, offset: Int = 0): Int = ldStImm(0b01, 0b00, rt.n, rn.n, offset, 1)
    fun ldrB(rt: W, rn: X, offset: Int = 0): Int = ldStImm(0b00, 0b01, rt.n, rn.n, offset, 0)
    fun strB(rt: W, rn: X, offset: Int = 0): Int = ldStImm(0b00, 0b00, rt.n, rn.n, offset, 0)

    // -----------------------------------------------------------------
    // LDP/STP (signed-offset, scaled)
    //   opc 101 V 010 L imm7 Rt2 Rn Rt
    // -----------------------------------------------------------------

    private fun ldpStp(
        opc: Int, v: Int, l: Int,
        rt: Int, rt2: Int, rn: Int, offset: Int, scale: Int,
    ): Int {
        require(offset and ((1 shl scale) - 1) == 0) {
            "offset $offset not aligned to ${1 shl scale}"
        }
        val imm7 = offset shr scale
        require(imm7 in -64..63) { "imm7 out of range: $imm7" }
        return (opc shl 30) or (0b101 shl 27) or (v shl 26) or (0b010 shl 23) or
                (l shl 22) or (bits(imm7, 7) shl 15) or (rt2 shl 10) or
                (rn shl 5) or rt
    }

    fun ldp(rt1: X, rt2: X, rn: X, offset: Int = 0): Int =
        ldpStp(0b10, 0, 1, rt1.n, rt2.n, rn.n, offset, 3)

    fun stp(rt1: X, rt2: X, rn: X, offset: Int = 0): Int =
        ldpStp(0b10, 0, 0, rt1.n, rt2.n, rn.n, offset, 3)

    fun ldpW(rt1: W, rt2: W, rn: X, offset: Int = 0): Int =
        ldpStp(0b00, 0, 1, rt1.n, rt2.n, rn.n, offset, 2)

    fun stpW(rt1: W, rt2: W, rn: X, offset: Int = 0): Int =
        ldpStp(0b00, 0, 0, rt1.n, rt2.n, rn.n, offset, 2)

    /** LDP Q, Q (128-bit SIMD pair) — opc=10 V=1. */
    fun ldp_q(rt1: V, rt2: V, rn: X, offset: Int = 0): Int =
        ldpStp(0b10, 1, 1, rt1.n, rt2.n, rn.n, offset, 4)

    fun stp_q(rt1: V, rt2: V, rn: X, offset: Int = 0): Int =
        ldpStp(0b10, 1, 0, rt1.n, rt2.n, rn.n, offset, 4)

    // -----------------------------------------------------------------
    // SIMD multi-structure load/store (LD1/ST1 single struct, no offset)
    //   0 Q 0011000 L 000000 opcode size Rn Rt
    // opcode = 0b0111 → "{Vt.<spec>}" (one register, all lanes)
    // -----------------------------------------------------------------

    private fun ld1St1(l: Int, q: Int, size: Int, rt: Int, rn: Int): Int {
        return (q shl 30) or (0b0011000 shl 23) or (l shl 22) or
                (0b000000 shl 16) or (0b0111 shl 12) or (size shl 10) or
                (rn shl 5) or rt
    }

    fun ld1(rt: V, rn: X, arr: VArr): Int = ld1St1(1, arr.q, arr.size, rt.n, rn.n)
    fun st1(rt: V, rn: X, arr: VArr): Int = ld1St1(0, arr.q, arr.size, rt.n, rn.n)

    /**
     * LD1R (replicate): broadcast a single element to all lanes.
     *   0 Q 0011010 1 000000 110 0 size Rn Rt
     */
    fun ld1r(rt: V, rn: X, arr: VArr): Int {
        return (arr.q shl 30) or (0b0011010 shl 23) or (1 shl 22) or
                (0b000000 shl 16) or (0b110 shl 13) or (0 shl 12) or
                (arr.size shl 10) or (rn.n shl 5) or rt.n
    }

    // -----------------------------------------------------------------
    // Add/sub immediate (12-bit, optionally shifted by 12)
    //   sf op S 100010 sh imm12 Rn Rd
    // -----------------------------------------------------------------

    private fun addSubImm(
        sf: Int, op: Int, s: Int,
        rd: Int, rn: Int, imm: Int, shift12: Boolean,
    ): Int {
        require(imm in 0..0xFFF) { "imm12 out of range: $imm" }
        val sh = if (shift12) 1 else 0
        return (sf shl 31) or (op shl 30) or (s shl 29) or (0b100010 shl 23) or
                (sh shl 22) or (imm shl 10) or (rn shl 5) or rd
    }

    fun addImm(rd: X, rn: X, imm: Int, shift12: Boolean = false): Int =
        addSubImm(1, 0, 0, rd.n, rn.n, imm, shift12)

    fun addImm(rd: W, rn: W, imm: Int, shift12: Boolean = false): Int =
        addSubImm(0, 0, 0, rd.n, rn.n, imm, shift12)

    fun subImm(rd: X, rn: X, imm: Int, shift12: Boolean = false): Int =
        addSubImm(1, 1, 0, rd.n, rn.n, imm, shift12)

    fun subImm(rd: W, rn: W, imm: Int, shift12: Boolean = false): Int =
        addSubImm(0, 1, 0, rd.n, rn.n, imm, shift12)

    /** CMP Xn, #imm = SUBS XZR, Xn, #imm */
    fun cmpImm(rn: X, imm: Int, shift12: Boolean = false): Int =
        addSubImm(1, 1, 1, XZR.n, rn.n, imm, shift12)

    fun cmpImm(rn: W, imm: Int, shift12: Boolean = false): Int =
        addSubImm(0, 1, 1, WZR.n, rn.n, imm, shift12)

    // -----------------------------------------------------------------
    // Add/sub shifted register
    //   sf op S 01011 shift 0 Rm imm6 Rn Rd
    // -----------------------------------------------------------------

    private fun addSubReg(
        sf: Int, op: Int, s: Int,
        rd: Int, rn: Int, rm: Int,
    ): Int {
        return (sf shl 31) or (op shl 30) or (s shl 29) or (0b01011 shl 24) or
                (0 shl 22) or (0 shl 21) or (rm shl 16) or (0 shl 10) or
                (rn shl 5) or rd
    }

    fun add(rd: X, rn: X, rm: X): Int = addSubReg(1, 0, 0, rd.n, rn.n, rm.n)
    fun add(rd: W, rn: W, rm: W): Int = addSubReg(0, 0, 0, rd.n, rn.n, rm.n)
    fun sub(rd: X, rn: X, rm: X): Int = addSubReg(1, 1, 0, rd.n, rn.n, rm.n)
    fun sub(rd: W, rn: W, rm: W): Int = addSubReg(0, 1, 0, rd.n, rn.n, rm.n)

    /** NEG Xd, Xm = SUB Xd, XZR, Xm */
    fun neg(rd: X, rm: X): Int = sub(rd, XZR, rm)
    fun neg(rd: W, rm: W): Int = sub(rd, WZR, rm)

    /** CMP Xn, Xm = SUBS XZR, Xn, Xm */
    fun cmp(rn: X, rm: X): Int = addSubReg(1, 1, 1, XZR.n, rn.n, rm.n)
    fun cmp(rn: W, rm: W): Int = addSubReg(0, 1, 1, WZR.n, rn.n, rm.n)

    // -----------------------------------------------------------------
    // Multiply / divide
    // -----------------------------------------------------------------

    /** MADD Xd, Xn, Xm, Xa: sf 00 11011 000 Rm 0 Ra Rn Rd */
    private fun madd(sf: Int, rd: Int, rn: Int, rm: Int, ra: Int): Int {
        return (sf shl 31) or (0b00 shl 29) or (0b11011 shl 24) or (0b000 shl 21) or
                (rm shl 16) or (0 shl 15) or (ra shl 10) or (rn shl 5) or rd
    }

    fun madd(rd: X, rn: X, rm: X, ra: X): Int = madd(1, rd.n, rn.n, rm.n, ra.n)
    fun madd(rd: W, rn: W, rm: W, ra: W): Int = madd(0, rd.n, rn.n, rm.n, ra.n)

    /** MUL is MADD with Ra=ZR. */
    fun mul(rd: X, rn: X, rm: X): Int = madd(1, rd.n, rn.n, rm.n, XZR.n)
    fun mul(rd: W, rn: W, rm: W): Int = madd(0, rd.n, rn.n, rm.n, WZR.n)

    private fun divide(sf: Int, signed: Int, rd: Int, rn: Int, rm: Int): Int {
        // sf 0 0 11010110 Rm 00001 [signed] Rn Rd
        return (sf shl 31) or (0b0011010110 shl 21) or (rm shl 16) or
                (0b00001 shl 11) or (signed shl 10) or (rn shl 5) or rd
    }

    fun udiv(rd: X, rn: X, rm: X): Int = divide(1, 0, rd.n, rn.n, rm.n)
    fun udiv(rd: W, rn: W, rm: W): Int = divide(0, 0, rd.n, rn.n, rm.n)
    fun sdiv(rd: X, rn: X, rm: X): Int = divide(1, 1, rd.n, rn.n, rm.n)
    fun sdiv(rd: W, rn: W, rm: W): Int = divide(0, 1, rd.n, rn.n, rm.n)

    // -----------------------------------------------------------------
    // Logical (shifted register, no shift)
    //   sf opc 01010 shift N Rm imm6 Rn Rd
    // -----------------------------------------------------------------

    private fun logicalReg(
        sf: Int, opc: Int, n: Int,
        rd: Int, rn: Int, rm: Int,
    ): Int {
        return (sf shl 31) or (opc shl 29) or (0b01010 shl 24) or (0 shl 22) or
                (n shl 21) or (rm shl 16) or (0 shl 10) or (rn shl 5) or rd
    }

    fun and(rd: X, rn: X, rm: X): Int = logicalReg(1, 0b00, 0, rd.n, rn.n, rm.n)
    fun and(rd: W, rn: W, rm: W): Int = logicalReg(0, 0b00, 0, rd.n, rn.n, rm.n)
    fun orr(rd: X, rn: X, rm: X): Int = logicalReg(1, 0b01, 0, rd.n, rn.n, rm.n)
    fun orr(rd: W, rn: W, rm: W): Int = logicalReg(0, 0b01, 0, rd.n, rn.n, rm.n)
    fun eor(rd: X, rn: X, rm: X): Int = logicalReg(1, 0b10, 0, rd.n, rn.n, rm.n)
    fun eor(rd: W, rn: W, rm: W): Int = logicalReg(0, 0b10, 0, rd.n, rn.n, rm.n)

    /** MVN Xd, Xm = ORN Xd, XZR, Xm (logical with N=1, opc=01). */
    fun mvn(rd: X, rm: X): Int = logicalReg(1, 0b01, 1, rd.n, XZR.n, rm.n)
    fun mvn(rd: W, rm: W): Int = logicalReg(0, 0b01, 1, rd.n, WZR.n, rm.n)

    // -----------------------------------------------------------------
    // Shift immediate (UBFM / SBFM aliases)
    // -----------------------------------------------------------------

    /** UBFM: sf 10 100110 N immr imms Rn Rd; for X regs N=1, for W regs N=0. */
    private fun ubfm(sf: Int, n: Int, rd: Int, rn: Int, immr: Int, imms: Int): Int {
        return (sf shl 31) or (0b10 shl 29) or (0b100110 shl 23) or (n shl 22) or
                (immr shl 16) or (imms shl 10) or (rn shl 5) or rd
    }

    private fun sbfm(sf: Int, n: Int, rd: Int, rn: Int, immr: Int, imms: Int): Int {
        return (sf shl 31) or (0b00 shl 29) or (0b100110 shl 23) or (n shl 22) or
                (immr shl 16) or (imms shl 10) or (rn shl 5) or rd
    }

    fun lsl(rd: X, rn: X, shift: Int): Int {
        require(shift in 0..63)
        return ubfm(1, 1, rd.n, rn.n, (-shift) and 63, 63 - shift)
    }

    fun lsl(rd: W, rn: W, shift: Int): Int {
        require(shift in 0..31)
        return ubfm(0, 0, rd.n, rn.n, (-shift) and 31, 31 - shift)
    }

    fun lsr(rd: X, rn: X, shift: Int): Int {
        require(shift in 0..63)
        return ubfm(1, 1, rd.n, rn.n, shift, 63)
    }

    fun lsr(rd: W, rn: W, shift: Int): Int {
        require(shift in 0..31)
        return ubfm(0, 0, rd.n, rn.n, shift, 31)
    }

    fun asr(rd: X, rn: X, shift: Int): Int {
        require(shift in 0..63)
        return sbfm(1, 1, rd.n, rn.n, shift, 63)
    }

    fun asr(rd: W, rn: W, shift: Int): Int {
        require(shift in 0..31)
        return sbfm(0, 0, rd.n, rn.n, shift, 31)
    }

    // -----------------------------------------------------------------
    // Compare-and-branch / test-bit-and-branch
    // -----------------------------------------------------------------

    /** CBZ/CBNZ: sf 011010 op imm19 Rt — offset in bytes from this insn. */
    private fun cb(sf: Int, op: Int, rt: Int, byteOffset: Int): Int {
        require(byteOffset and 0b11 == 0) { "branch offset must be multiple of 4" }
        val imm19 = byteOffset shr 2
        require(imm19 in -(1 shl 18)..((1 shl 18) - 1)) { "branch offset out of range" }
        return (sf shl 31) or (0b011010 shl 25) or (op shl 24) or
                (bits(imm19, 19) shl 5) or rt
    }

    fun cbz(rt: X, byteOffset: Int): Int = cb(1, 0, rt.n, byteOffset)
    fun cbz(rt: W, byteOffset: Int): Int = cb(0, 0, rt.n, byteOffset)
    fun cbnz(rt: X, byteOffset: Int): Int = cb(1, 1, rt.n, byteOffset)
    fun cbnz(rt: W, byteOffset: Int): Int = cb(0, 1, rt.n, byteOffset)

    /** TBZ/TBNZ: b5 011011 op b40 imm14 Rt */
    private fun tb(op: Int, rt: Int, bit: Int, byteOffset: Int): Int {
        require(bit in 0..63)
        require(byteOffset and 0b11 == 0)
        val imm14 = byteOffset shr 2
        require(imm14 in -(1 shl 13)..((1 shl 13) - 1))
        val b5 = (bit shr 5) and 1
        val b40 = bit and 0b11111
        return (b5 shl 31) or (0b011011 shl 25) or (op shl 24) or
                (b40 shl 19) or (bits(imm14, 14) shl 5) or rt
    }

    fun tbz(rt: X, bit: Int, byteOffset: Int): Int = tb(0, rt.n, bit, byteOffset)
    fun tbnz(rt: X, bit: Int, byteOffset: Int): Int = tb(1, rt.n, bit, byteOffset)

    // -----------------------------------------------------------------
    // Branches
    // -----------------------------------------------------------------

    fun b(byteOffset: Int): Int {
        require(byteOffset and 0b11 == 0)
        val imm26 = byteOffset shr 2
        require(imm26 in -(1 shl 25)..((1 shl 25) - 1))
        return (0b000101 shl 26) or bits(imm26, 26)
    }

    fun bl(byteOffset: Int): Int {
        require(byteOffset and 0b11 == 0)
        val imm26 = byteOffset shr 2
        require(imm26 in -(1 shl 25)..((1 shl 25) - 1))
        return (0b100101 shl 26) or bits(imm26, 26)
    }

    fun br(rn: X): Int = (0b1101011 shl 25) or (0 shl 24) or (0 shl 23) or
            (0b00 shl 21) or (0b11111 shl 16) or (0 shl 10) or (rn.n shl 5)

    fun blr(rn: X): Int = (0b1101011 shl 25) or (0 shl 24) or (1 shl 21) or
            (0b11111 shl 16) or (0 shl 10) or (rn.n shl 5)

    fun ret(rn: X = X30): Int = (0b1101011 shl 25) or (0b10 shl 21) or
            (0b11111 shl 16) or (0 shl 10) or (rn.n shl 5)

    fun bCond(cond: Cond, byteOffset: Int): Int {
        require(byteOffset and 0b11 == 0)
        val imm19 = byteOffset shr 2
        require(imm19 in -(1 shl 18)..((1 shl 18) - 1))
        return (0b01010100 shl 24) or (bits(imm19, 19) shl 5) or (0 shl 4) or cond.v
    }

    // -----------------------------------------------------------------
    // NEON FP arithmetic (vector & scalar)
    //   3-source vector: 0 Q U 01110 sz 1 Rm op Rn Rd
    // We expose the common ops: FADD/FSUB/FMUL/FDIV/FMIN/FMAX/FMLA/FMLS
    // -----------------------------------------------------------------

    private fun fpVec3(
        q: Int, u: Int, sz: Int, op: Int,
        rd: Int, rn: Int, rm: Int,
    ): Int {
        return (0 shl 31) or (q shl 30) or (u shl 29) or (0b01110 shl 24) or
                (sz shl 22) or (1 shl 21) or (rm shl 16) or (op shl 11) or
                (1 shl 10) or (rn shl 5) or rd
    }

    /** FADD vector. arr must be S2/S4/D2 (size derived). */
    fun fadd(rd: V, rn: V, rm: V, arr: VArr): Int {
        val sz = arr.size and 0b1
        return fpVec3(arr.q, 0, sz, 0b11010, rd.n, rn.n, rm.n)
    }

    fun fsub(rd: V, rn: V, rm: V, arr: VArr): Int {
        val sz = (arr.size and 0b1) or 0b10  // sz[1]=1 for sub
        return fpVec3(arr.q, 0, sz, 0b11010, rd.n, rn.n, rm.n)
    }

    fun fmul(rd: V, rn: V, rm: V, arr: VArr): Int {
        val sz = arr.size and 0b1
        return fpVec3(arr.q, 1, sz, 0b11011, rd.n, rn.n, rm.n)
    }

    fun fdiv(rd: V, rn: V, rm: V, arr: VArr): Int {
        val sz = arr.size and 0b1
        return fpVec3(arr.q, 1, sz, 0b11111, rd.n, rn.n, rm.n)
    }

    fun fmla(rd: V, rn: V, rm: V, arr: VArr): Int {
        val sz = arr.size and 0b1
        return fpVec3(arr.q, 0, sz, 0b11001, rd.n, rn.n, rm.n)
    }

    fun fmls(rd: V, rn: V, rm: V, arr: VArr): Int {
        val sz = (arr.size and 0b1) or 0b10
        return fpVec3(arr.q, 0, sz, 0b11001, rd.n, rn.n, rm.n)
    }

    fun fmin(rd: V, rn: V, rm: V, arr: VArr): Int {
        val sz = (arr.size and 0b1) or 0b10
        return fpVec3(arr.q, 0, sz, 0b11110, rd.n, rn.n, rm.n)
    }

    fun fmax(rd: V, rn: V, rm: V, arr: VArr): Int {
        val sz = arr.size and 0b1
        return fpVec3(arr.q, 0, sz, 0b11110, rd.n, rn.n, rm.n)
    }

    /** Scalar FP add: 0 0 0 11110 type 1 Rm 0010 10 Rn Rd */
    private fun fpScalar3(type: Int, op: Int, rd: Int, rn: Int, rm: Int): Int {
        return (0b00011110 shl 24) or (type shl 22) or (1 shl 21) or
                (rm shl 16) or (op shl 12) or (0b10 shl 10) or (rn shl 5) or rd
    }

    fun faddS(rd: V, rn: V, rm: V): Int = fpScalar3(0, 0b0010, rd.n, rn.n, rm.n)
    fun faddD(rd: V, rn: V, rm: V): Int = fpScalar3(1, 0b0010, rd.n, rn.n, rm.n)
    fun fsubS(rd: V, rn: V, rm: V): Int = fpScalar3(0, 0b0011, rd.n, rn.n, rm.n)
    fun fsubD(rd: V, rn: V, rm: V): Int = fpScalar3(1, 0b0011, rd.n, rn.n, rm.n)
    fun fmulS(rd: V, rn: V, rm: V): Int = fpScalar3(0, 0b0000, rd.n, rn.n, rm.n)
    fun fmulD(rd: V, rn: V, rm: V): Int = fpScalar3(1, 0b0000, rd.n, rn.n, rm.n)
    fun fdivS(rd: V, rn: V, rm: V): Int = fpScalar3(0, 0b0001, rd.n, rn.n, rm.n)
    fun fdivD(rd: V, rn: V, rm: V): Int = fpScalar3(1, 0b0001, rd.n, rn.n, rm.n)

    // -----------------------------------------------------------------
    // NEON integer arithmetic (vector)
    //   0 Q U 01110 size 1 Rm op 1 Rn Rd
    // -----------------------------------------------------------------

    private fun intVec3(
        q: Int, u: Int, size: Int, op: Int,
        rd: Int, rn: Int, rm: Int,
    ): Int {
        return (0 shl 31) or (q shl 30) or (u shl 29) or (0b01110 shl 24) or
                (size shl 22) or (1 shl 21) or (rm shl 16) or (op shl 11) or
                (1 shl 10) or (rn shl 5) or rd
    }

    fun addVec(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 0, arr.size, 0b10000, rd.n, rn.n, rm.n)

    fun subVec(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 1, arr.size, 0b10000, rd.n, rn.n, rm.n)

    fun mulVec(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 0, arr.size, 0b10011, rd.n, rn.n, rm.n)

    fun mlaVec(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 0, arr.size, 0b10010, rd.n, rn.n, rm.n)

    fun mlsVec(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 1, arr.size, 0b10010, rd.n, rn.n, rm.n)

    // -----------------------------------------------------------------
    // NEON misc: DUP, UXTL/SXTL, USHL/SSHL, XTN/XTN2
    // -----------------------------------------------------------------

    /** DUP Vd.<T>, Xn (general → vector). */
    fun dup(rd: V, rn: X, arr: VArr): Int {
        val imm5 = when (arr.size) {
            0 -> 0b00001  // 8B/16B
            1 -> 0b00010  // 4H/8H
            2 -> 0b00100  // 2S/4S
            3 -> 0b01000  // 1D/2D
            else -> error("invalid size")
        }
        return (0 shl 31) or (arr.q shl 30) or (0 shl 29) or (0b01110000 shl 21) or
                (imm5 shl 16) or (0b000011 shl 10) or (rn.n shl 5) or rd.n
    }

    /** XTN: 0 Q 0 01110 size 100001 0010 10 Rn Rd */
    fun xtn(rd: V, rn: V, arr: VArr): Int {
        require(arr.q == 0) { "XTN uses lower half (q=0); use xtn2 for upper" }
        return (0 shl 31) or (0 shl 30) or (0 shl 29) or (0b01110 shl 24) or
                (arr.size shl 22) or (0b100001 shl 16) or (0b00101 shl 11) or
                (0 shl 10) or (rn.n shl 5) or rd.n
    }

    fun xtn2(rd: V, rn: V, arr: VArr): Int {
        require(arr.q == 1) { "XTN2 writes upper half (q=1); use xtn for lower" }
        return (0 shl 31) or (1 shl 30) or (0 shl 29) or (0b01110 shl 24) or
                (arr.size shl 22) or (0b100001 shl 16) or (0b00101 shl 11) or
                (0 shl 10) or (rn.n shl 5) or rd.n
    }

    /** USHL/SSHL (vector). */
    fun ushl(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 1, arr.size, 0b01000, rd.n, rn.n, rm.n)

    fun sshl(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 0, arr.size, 0b01000, rd.n, rn.n, rm.n)

    /** UXTL Vd.<T>, Vn.<Tb> = USHLL Vd, Vn, #0. */
    fun uxtl(rd: V, rn: V, srcArr: VArr): Int {
        val immh = when (srcArr.size) {
            0 -> 0b0001
            1 -> 0b0010
            2 -> 0b0100
            else -> error("UXTL src size too wide")
        }
        return (0 shl 31) or (srcArr.q shl 30) or (1 shl 29) or (0b011110 shl 23) or
                (immh shl 19) or (0 shl 16) or (0b101001 shl 10) or
                (rn.n shl 5) or rd.n
    }

    fun sxtl(rd: V, rn: V, srcArr: VArr): Int {
        val immh = when (srcArr.size) {
            0 -> 0b0001
            1 -> 0b0010
            2 -> 0b0100
            else -> error("SXTL src size too wide")
        }
        return (0 shl 31) or (srcArr.q shl 30) or (0 shl 29) or (0b011110 shl 23) or
                (immh shl 19) or (0 shl 16) or (0b101001 shl 10) or
                (rn.n shl 5) or rd.n
    }

    // -----------------------------------------------------------------
    // NEON logical (vector AND/ORR/EOR/BIC)
    //   0 Q U 01110 size 1 Rm 00011 1 Rn Rd
    // size encodes the op:
    //   AND: U=0 size=00, ORR: U=0 size=10, EOR: U=1 size=00, BIC: U=0 size=01
    // -----------------------------------------------------------------

    private fun logicalVec(
        q: Int, u: Int, size: Int,
        rd: Int, rn: Int, rm: Int,
    ): Int {
        return (0 shl 31) or (q shl 30) or (u shl 29) or (0b01110 shl 24) or
                (size shl 22) or (1 shl 21) or (rm shl 16) or (0b00011 shl 11) or
                (1 shl 10) or (rn shl 5) or rd
    }

    fun andVec(rd: V, rn: V, rm: V, q: Int = 1): Int =
        logicalVec(q, 0, 0b00, rd.n, rn.n, rm.n)

    fun orrVec(rd: V, rn: V, rm: V, q: Int = 1): Int =
        logicalVec(q, 0, 0b10, rd.n, rn.n, rm.n)

    fun eorVec(rd: V, rn: V, rm: V, q: Int = 1): Int =
        logicalVec(q, 1, 0b00, rd.n, rn.n, rm.n)

    fun bicVec(rd: V, rn: V, rm: V, q: Int = 1): Int =
        logicalVec(q, 0, 0b01, rd.n, rn.n, rm.n)

    // -----------------------------------------------------------------
    // PAC / BTI / NOP / DMB / ISB
    // -----------------------------------------------------------------

    fun paciasp(): Int = 0xD503233F.toInt()
    fun autiasp(): Int = 0xD50323BF.toInt()
    fun btiC(): Int = 0xD503245F.toInt()
    fun btiJ(): Int = 0xD503249F.toInt()
    fun btiJC(): Int = 0xD50324DF.toInt()

    fun nop(): Int = 0xD503201F.toInt()
    fun isb(): Int = 0xD5033FDF.toInt()

    /** DMB <option>: option=0xF (SY), 0xE (ST), 0xD (LD), 0xB (ISH), etc. */
    fun dmb(option: Int = 0xF): Int {
        require(option in 0..0xF)
        return 0xD50330BF.toInt() or (option shl 8)
    }

    // -----------------------------------------------------------------
    // FP <-> int conversions (FCVTZS / SCVTF)
    // -----------------------------------------------------------------

    /** Common encoder for scalar FP conversion: sf 0 0 11110 type 1 rmode opcode 000000 Rn Rd. */
    private fun fpScalarConv(
        sf: Int, type: Int, rmode: Int, opcode: Int, rd: Int, rn: Int,
    ): Int {
        return (sf shl 31) or (0 shl 30) or (0 shl 29) or (0b11110 shl 24) or
                (type shl 22) or (1 shl 21) or (rmode shl 19) or (opcode shl 16) or
                (rn shl 5) or rd
    }

    /** FCVTZS Wd, Sn — convert single-precision to signed 32-bit int (toward zero). */
    fun fcvtzsW(rd: W, sn: V): Int = fpScalarConv(0, 0b00, 0b11, 0b000, rd.n, sn.n)
    /** FCVTZS Xd, Dn — double to signed 64-bit int. */
    fun fcvtzsX(rd: X, dn: V): Int = fpScalarConv(1, 0b01, 0b11, 0b000, rd.n, dn.n)
    /** FCVTZS Xd, Sn — single to signed 64-bit int. */
    fun fcvtzsXFromS(rd: X, sn: V): Int = fpScalarConv(1, 0b00, 0b11, 0b000, rd.n, sn.n)
    /** FCVTZS Wd, Dn — double to signed 32-bit int. */
    fun fcvtzsWFromD(rd: W, dn: V): Int = fpScalarConv(0, 0b01, 0b11, 0b000, rd.n, dn.n)

    /** SCVTF Sd, Wn — signed 32-bit int to single-precision float. */
    fun scvtfS(rd: V, wn: W): Int = fpScalarConv(0, 0b00, 0b00, 0b010, rd.n, wn.n)
    /** SCVTF Dd, Xn — signed 64-bit int to double. */
    fun scvtfD(rd: V, xn: X): Int = fpScalarConv(1, 0b01, 0b00, 0b010, rd.n, xn.n)
    /** SCVTF Sd, Xn — signed 64-bit int to single. */
    fun scvtfSFromX(rd: V, xn: X): Int = fpScalarConv(1, 0b00, 0b00, 0b010, rd.n, xn.n)
    /** SCVTF Dd, Wn — signed 32-bit int to double. */
    fun scvtfDFromW(rd: V, wn: W): Int = fpScalarConv(0, 0b01, 0b00, 0b010, rd.n, wn.n)

    /** Common encoder for vector FP unary misc: 0Q U 01110 a sz 1 0000 1 opcode 10 Rn Rd. */
    private fun fpVecUnary(
        q: Int, u: Int, a: Int, sz: Int, opcode: Int, rd: Int, rn: Int,
    ): Int {
        return (0 shl 31) or (q shl 30) or (u shl 29) or (0b01110 shl 24) or
                (a shl 23) or (sz shl 22) or (1 shl 21) or (0 shl 17) or
                (1 shl 16) or (opcode shl 12) or (0b10 shl 10) or (rn shl 5) or rd
    }

    /** FCVTZS vector: Vd.<T> ← Vn.<T> toward zero. arr ∈ {S2, S4, D2}. */
    fun fcvtzsVec(rd: V, rn: V, arr: VArr): Int {
        require(arr.size in 2..3) { "FCVTZS only on .2s/.4s/.2d" }
        val sz = arr.size and 1
        return fpVecUnary(arr.q, 0, 1, sz, 0b1011, rd.n, rn.n)
    }

    /** SCVTF vector: Vd.<T> ← Vn.<T> signed-int → float. arr ∈ {S2, S4, D2}. */
    fun scvtfVec(rd: V, rn: V, arr: VArr): Int {
        require(arr.size in 2..3) { "SCVTF only on .2s/.4s/.2d" }
        val sz = arr.size and 1
        return fpVecUnary(arr.q, 0, 0, sz, 0b1101, rd.n, rn.n)
    }

    // -----------------------------------------------------------------
    // FP compare (scalar — sets NZCV; vector — produces lane masks)
    // -----------------------------------------------------------------

    private fun fpCmpScalar(type: Int, rm: Int, rn: Int, opc2: Int): Int {
        return (0b00011110 shl 24) or (type shl 22) or (1 shl 21) or
                (rm shl 16) or (0b001000 shl 10) or (rn shl 5) or opc2
    }

    /** FCMP Sn, Sm. Sets PSTATE.NZCV. */
    fun fcmpS(rn: V, rm: V): Int = fpCmpScalar(0, rm.n, rn.n, 0b00000)
    /** FCMP Dn, Dm. */
    fun fcmpD(rn: V, rm: V): Int = fpCmpScalar(1, rm.n, rn.n, 0b00000)
    /** FCMP Sn, #0.0. */
    fun fcmpSZero(rn: V): Int = fpCmpScalar(0, 0, rn.n, 0b01000)
    /** FCMP Dn, #0.0. */
    fun fcmpDZero(rn: V): Int = fpCmpScalar(1, 0, rn.n, 0b01000)

    /** FCMEQ vector — per-lane equality, result is 0xFF...F or 0. arr ∈ {S2,S4,D2}. */
    fun fcmeq(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = arr.size and 1
        return fpVec3(arr.q, 0, sz, 0b11100, rd.n, rn.n, rm.n)
    }

    /** FCMGE vector — per-lane greater-or-equal. */
    fun fcmge(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = arr.size and 1
        return fpVec3(arr.q, 1, sz, 0b11100, rd.n, rn.n, rm.n)
    }

    /** FCMGT vector — per-lane greater-than. */
    fun fcmgt(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = (arr.size and 1) or 0b10
        return fpVec3(arr.q, 1, sz, 0b11100, rd.n, rn.n, rm.n)
    }

    // -----------------------------------------------------------------
    // Conditional select (CSEL / CSINC / CSINV / CSNEG / FCSEL)
    // -----------------------------------------------------------------

    /** Encoder: sf op S 11010100 Rm cond 0 op2 Rn Rd. */
    private fun condSelect(
        sf: Int, op: Int, op2: Int, rd: Int, rn: Int, rm: Int, cond: Cond,
    ): Int {
        return (sf shl 31) or (op shl 30) or (0 shl 29) or (0b11010100 shl 21) or
                (rm shl 16) or (cond.v shl 12) or (0 shl 11) or (op2 shl 10) or
                (rn shl 5) or rd
    }

    fun csel(rd: X, rn: X, rm: X, cond: Cond): Int = condSelect(1, 0, 0, rd.n, rn.n, rm.n, cond)
    fun csel(rd: W, rn: W, rm: W, cond: Cond): Int = condSelect(0, 0, 0, rd.n, rn.n, rm.n, cond)
    fun csinc(rd: X, rn: X, rm: X, cond: Cond): Int = condSelect(1, 0, 1, rd.n, rn.n, rm.n, cond)
    fun csinc(rd: W, rn: W, rm: W, cond: Cond): Int = condSelect(0, 0, 1, rd.n, rn.n, rm.n, cond)
    fun csinv(rd: X, rn: X, rm: X, cond: Cond): Int = condSelect(1, 1, 0, rd.n, rn.n, rm.n, cond)
    fun csinv(rd: W, rn: W, rm: W, cond: Cond): Int = condSelect(0, 1, 0, rd.n, rn.n, rm.n, cond)
    fun csneg(rd: X, rn: X, rm: X, cond: Cond): Int = condSelect(1, 1, 1, rd.n, rn.n, rm.n, cond)
    fun csneg(rd: W, rn: W, rm: W, cond: Cond): Int = condSelect(0, 1, 1, rd.n, rn.n, rm.n, cond)

    /** FCSEL: 0 0 0 11110 type 1 Rm cond 11 Rn Rd. */
    private fun fcsel(type: Int, rd: Int, rn: Int, rm: Int, cond: Cond): Int {
        return (0b00011110 shl 24) or (type shl 22) or (1 shl 21) or
                (rm shl 16) or (cond.v shl 12) or (0b11 shl 10) or
                (rn shl 5) or rd
    }
    fun fcselS(rd: V, rn: V, rm: V, cond: Cond): Int = fcsel(0, rd.n, rn.n, rm.n, cond)
    fun fcselD(rd: V, rn: V, rm: V, cond: Cond): Int = fcsel(1, rd.n, rn.n, rm.n, cond)

    // -----------------------------------------------------------------
    // Reciprocal estimates (FRECPE / FRECPS / FRSQRTE / FRSQRTS)
    // -----------------------------------------------------------------

    /** FRECPE Vd.<T>, Vn.<T>. arr ∈ {S2,S4,D2}. */
    fun frecpe(rd: V, rn: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = arr.size and 1
        return fpVecUnary(arr.q, 0, 1, sz, 0b1101, rd.n, rn.n)
    }

    /** FRSQRTE Vd.<T>, Vn.<T>. */
    fun frsqrte(rd: V, rn: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = arr.size and 1
        return fpVecUnary(arr.q, 1, 1, sz, 0b1101, rd.n, rn.n)
    }

    /** FRECPS Vd, Vn, Vm — Newton-Raphson reciprocal step. */
    fun frecps(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = arr.size and 1
        return fpVec3(arr.q, 0, sz, 0b11111, rd.n, rn.n, rm.n)
    }

    /** FRSQRTS Vd, Vn, Vm — Newton-Raphson rsqrt step. */
    fun frsqrts(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = (arr.size and 1) or 0b10
        return fpVec3(arr.q, 0, sz, 0b11111, rd.n, rn.n, rm.n)
    }

    /** Scalar 2-reg misc: 01 U 11110 a sz 1 0000 1 opcode 10 Rn Rd. */
    private fun fpScalarUnary(
        u: Int, a: Int, sz: Int, opcode: Int, rd: Int, rn: Int,
    ): Int {
        return (0b01 shl 30) or (u shl 29) or (0b11110 shl 24) or (a shl 23) or
                (sz shl 22) or (1 shl 21) or (0b00001 shl 16) or
                (opcode shl 12) or (0b10 shl 10) or (rn shl 5) or rd
    }
    fun frecpeS(rd: V, rn: V): Int = fpScalarUnary(0, 1, 0, 0b1101, rd.n, rn.n)
    fun frecpeD(rd: V, rn: V): Int = fpScalarUnary(0, 1, 1, 0b1101, rd.n, rn.n)
    fun frsqrteS(rd: V, rn: V): Int = fpScalarUnary(1, 1, 0, 0b1101, rd.n, rn.n)
    fun frsqrteD(rd: V, rn: V): Int = fpScalarUnary(1, 1, 1, 0b1101, rd.n, rn.n)

    // -----------------------------------------------------------------
    // Logical immediate (AND / ORR / EOR / TST with bitmask immediate)
    // -----------------------------------------------------------------

    /**
     * Encode [imm] as an AArch64 logical-immediate bitmask. Returns the
     * `(N, immr, imms)` triple, or null if the value isn't representable.
     *
     * Algorithm (per ARM ARM DecodeBitMasks, ported from LLVM's
     * AArch64AddressingModes.h):
     *   1. Reject 0 and all-ones (within [regSize]).
     *   2. Find the smallest power-of-two element size such that the value
     *      is a replicated copy of one element.
     *   3. Within that element, find a rotation that turns the pattern into
     *      a contiguous run of 1s at the LSB.
     *   4. Pack `(N, immr, imms)` from the discovered (esize, rotation, S+1).
     */
    fun encodeBitmaskImm(imm: Long, regSize: Int): Triple<Int, Int, Int>? {
        require(regSize == 32 || regSize == 64) { "regSize must be 32 or 64" }
        if (regSize == 32 && (imm ushr 32) != 0L) return null
        val mask: Long = if (regSize == 64) -1L else (1L shl regSize) - 1
        val v = imm and mask
        if (v == 0L || v == mask) return null

        // Step 1: reduce to smallest replicating element size.
        var size = regSize
        var element = v
        while (size > 2) {
            val half = size / 2
            val halfMask = (1L shl half) - 1
            val low = element and halfMask
            val high = (element ushr half) and halfMask
            if (low != high) break
            size = half
            element = low
        }

        val popcount = java.lang.Long.bitCount(element)
        if (popcount == 0 || popcount == size) return null

        // Step 2: find rotation that brings ones to the LSB contiguously.
        val sizeMask = if (size == 64) -1L else (1L shl size) - 1
        var rotation = -1
        var ones = 0
        for (r in 0 until size) {
            val rotated = if (r == 0) element
                          else ((element ushr r) or (element shl (size - r))) and sizeMask
            if (rotated != 0L && (rotated and (rotated + 1)) == 0L) {
                rotation = (size - r) % size
                ones = java.lang.Long.bitCount(rotated)
                break
            }
        }
        if (rotation < 0) return null

        // Step 3: pack (N, immr, imms).
        val n: Int
        val immsHigh: Int
        when (size) {
            64 -> { n = 1; immsHigh = 0 }
            32 -> { n = 0; immsHigh = 0 }
            16 -> { n = 0; immsHigh = 0b100000 }
            8  -> { n = 0; immsHigh = 0b110000 }
            4  -> { n = 0; immsHigh = 0b111000 }
            2  -> { n = 0; immsHigh = 0b111100 }
            else -> return null
        }
        return Triple(n, rotation, immsHigh or (ones - 1))
    }

    private fun logicalImm(
        sf: Int, opc: Int, rd: Int, rn: Int, imm: Long,
    ): Int {
        val regSize = if (sf == 1) 64 else 32
        val (n, immr, imms) = encodeBitmaskImm(imm, regSize)
            ?: error("not encodable as logical immediate at $regSize-bit: 0x${imm.toString(16)}")
        return (sf shl 31) or (opc shl 29) or (0b100100 shl 23) or (n shl 22) or
                (immr shl 16) or (imms shl 10) or (rn shl 5) or rd
    }

    fun andImm(rd: X, rn: X, imm: Long): Int = logicalImm(1, 0b00, rd.n, rn.n, imm)
    fun andImm(rd: W, rn: W, imm: Long): Int = logicalImm(0, 0b00, rd.n, rn.n, imm)
    fun orrImm(rd: X, rn: X, imm: Long): Int = logicalImm(1, 0b01, rd.n, rn.n, imm)
    fun orrImm(rd: W, rn: W, imm: Long): Int = logicalImm(0, 0b01, rd.n, rn.n, imm)
    fun eorImm(rd: X, rn: X, imm: Long): Int = logicalImm(1, 0b10, rd.n, rn.n, imm)
    fun eorImm(rd: W, rn: W, imm: Long): Int = logicalImm(0, 0b10, rd.n, rn.n, imm)
    /** TST = ANDS Rd=ZR; sets flags, discards result. */
    fun tstImm(rn: X, imm: Long): Int = logicalImm(1, 0b11, XZR.n, rn.n, imm)
    fun tstImm(rn: W, imm: Long): Int = logicalImm(0, 0b11, WZR.n, rn.n, imm)

    // -----------------------------------------------------------------
    // Pre/post-index loads & stores
    //   size 111 V 00 opc 0 imm9 idx_2 Rn Rt
    //   idx: 01 = post-index, 11 = pre-index, 00 = unscaled, 10 = unprivileged
    // -----------------------------------------------------------------

    private fun ldStImmIndex(
        size: Int, opc: Int, rt: Int, rn: Int, offset: Int, idx: Int,
    ): Int {
        require(offset in -256..255) { "imm9 offset out of range: $offset" }
        return (size shl 30) or (0b111 shl 27) or (0 shl 26) or (0b00 shl 24) or
                (opc shl 22) or (0 shl 21) or (bits(offset, 9) shl 12) or
                (idx shl 10) or (rn shl 5) or rt
    }

    fun ldrPre(rt: X, rn: X, offset: Int): Int = ldStImmIndex(0b11, 0b01, rt.n, rn.n, offset, 0b11)
    fun ldrPost(rt: X, rn: X, offset: Int): Int = ldStImmIndex(0b11, 0b01, rt.n, rn.n, offset, 0b01)
    fun strPre(rt: X, rn: X, offset: Int): Int = ldStImmIndex(0b11, 0b00, rt.n, rn.n, offset, 0b11)
    fun strPost(rt: X, rn: X, offset: Int): Int = ldStImmIndex(0b11, 0b00, rt.n, rn.n, offset, 0b01)

    fun ldrPreW(rt: W, rn: X, offset: Int): Int = ldStImmIndex(0b10, 0b01, rt.n, rn.n, offset, 0b11)
    fun ldrPostW(rt: W, rn: X, offset: Int): Int = ldStImmIndex(0b10, 0b01, rt.n, rn.n, offset, 0b01)
    fun strPreW(rt: W, rn: X, offset: Int): Int = ldStImmIndex(0b10, 0b00, rt.n, rn.n, offset, 0b11)
    fun strPostW(rt: W, rn: X, offset: Int): Int = ldStImmIndex(0b10, 0b00, rt.n, rn.n, offset, 0b01)

    /** Pre/post-indexed LDP/STP. mode: 0b011 = pre-index, 0b001 = post-index. */
    private fun ldpStpIndex(
        opc: Int, v: Int, l: Int, mode: Int,
        rt: Int, rt2: Int, rn: Int, offset: Int, scale: Int,
    ): Int {
        require(offset and ((1 shl scale) - 1) == 0) {
            "offset $offset not aligned to ${1 shl scale}"
        }
        val imm7 = offset shr scale
        require(imm7 in -64..63) { "imm7 out of range: $imm7" }
        return (opc shl 30) or (0b101 shl 27) or (v shl 26) or (mode shl 23) or
                (l shl 22) or (bits(imm7, 7) shl 15) or (rt2 shl 10) or
                (rn shl 5) or rt
    }

    fun ldpPre(rt1: X, rt2: X, rn: X, offset: Int): Int =
        ldpStpIndex(0b10, 0, 1, 0b011, rt1.n, rt2.n, rn.n, offset, 3)
    fun ldpPost(rt1: X, rt2: X, rn: X, offset: Int): Int =
        ldpStpIndex(0b10, 0, 1, 0b001, rt1.n, rt2.n, rn.n, offset, 3)
    fun stpPre(rt1: X, rt2: X, rn: X, offset: Int): Int =
        ldpStpIndex(0b10, 0, 0, 0b011, rt1.n, rt2.n, rn.n, offset, 3)
    fun stpPost(rt1: X, rt2: X, rn: X, offset: Int): Int =
        ldpStpIndex(0b10, 0, 0, 0b001, rt1.n, rt2.n, rn.n, offset, 3)

    // -----------------------------------------------------------------
    // Register-offset loads & stores
    //   size 111 V 00 opc 1 Rm option S 10 Rn Rt
    //   option: 010 UXTW, 011 LSL, 110 SXTW, 111 SXTX
    //   S: 1 → shift by access-size log2 (e.g. 3 for X), 0 → no shift
    // -----------------------------------------------------------------

    enum class IndexExt(val v: Int) { UXTW(0b010), LSL(0b011), SXTW(0b110), SXTX(0b111) }

    private fun ldStRegOff(
        size: Int, opc: Int,
        rt: Int, rn: Int, rm: Int, option: Int, shift: Boolean,
    ): Int {
        return (size shl 30) or (0b111 shl 27) or (0 shl 26) or (0b00 shl 24) or
                (opc shl 22) or (1 shl 21) or (rm shl 16) or
                (option shl 13) or ((if (shift) 1 else 0) shl 12) or
                (0b10 shl 10) or (rn shl 5) or rt
    }

    fun ldrReg(rt: X, rn: X, rm: X, ext: IndexExt = IndexExt.LSL, shift: Boolean = true): Int =
        ldStRegOff(0b11, 0b01, rt.n, rn.n, rm.n, ext.v, shift)
    fun strReg(rt: X, rn: X, rm: X, ext: IndexExt = IndexExt.LSL, shift: Boolean = true): Int =
        ldStRegOff(0b11, 0b00, rt.n, rn.n, rm.n, ext.v, shift)
    fun ldrRegW(rt: W, rn: X, rm: X, ext: IndexExt = IndexExt.LSL, shift: Boolean = true): Int =
        ldStRegOff(0b10, 0b01, rt.n, rn.n, rm.n, ext.v, shift)
    fun strRegW(rt: W, rn: X, rm: X, ext: IndexExt = IndexExt.LSL, shift: Boolean = true): Int =
        ldStRegOff(0b10, 0b00, rt.n, rn.n, rm.n, ext.v, shift)
    fun ldrbReg(rt: W, rn: X, rm: X, ext: IndexExt = IndexExt.LSL, shift: Boolean = false): Int =
        ldStRegOff(0b00, 0b01, rt.n, rn.n, rm.n, ext.v, shift)
    fun strbReg(rt: W, rn: X, rm: X, ext: IndexExt = IndexExt.LSL, shift: Boolean = false): Int =
        ldStRegOff(0b00, 0b00, rt.n, rn.n, rm.n, ext.v, shift)

    // -----------------------------------------------------------------
    // Saturating arithmetic (vector)
    //   sqadd / uqadd / sqsub / uqsub use the standard intVec3 template.
    // -----------------------------------------------------------------

    fun sqadd(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 0, arr.size, 0b00001, rd.n, rn.n, rm.n)
    fun uqadd(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 1, arr.size, 0b00001, rd.n, rn.n, rm.n)
    fun sqsub(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 0, arr.size, 0b00101, rd.n, rn.n, rm.n)
    fun uqsub(rd: V, rn: V, rm: V, arr: VArr): Int =
        intVec3(arr.q, 1, arr.size, 0b00101, rd.n, rn.n, rm.n)

    /** SQDMULH — saturating doubling multiply (returning the high half). */
    fun sqdmulh(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size in 1..2) { "SQDMULH only on .H or .S element sizes" }
        return intVec3(arr.q, 0, arr.size, 0b10110, rd.n, rn.n, rm.n)
    }

    /**
     * Saturating extract narrow: `Vd.<Tb> ← Vn.<Ta>`. The destination
     * arrangement [destArr] specifies the narrow lane size (.8b/.4h/.2s);
     * the source is implicitly the same number of elements at twice the
     * width (.8h/.4s/.2d).
     *
     * Use `q=1` (.16b/.8h/.4s) to write to the upper half ("xtn2" form).
     */
    fun sqxtn(rd: V, rn: V, destArr: VArr): Int {
        require(destArr.size in 0..2) { "SQXTN dest must be .8b/.16b/.4h/.8h/.2s/.4s" }
        return fpVecUnary(destArr.q, 0, 0, destArr.size, 0b0100, rd.n, rn.n)
    }
    fun uqxtn(rd: V, rn: V, destArr: VArr): Int {
        require(destArr.size in 0..2)
        return fpVecUnary(destArr.q, 1, 0, destArr.size, 0b0100, rd.n, rn.n)
    }

    // -----------------------------------------------------------------
    // Dot product (int8 → int32 accumulating)
    //   sdot/udot Vd.4s, Vn.16b, Vm.16b   — accumulates Vd
    //   (.2s + .8b form processes 8 bytes only)
    // -----------------------------------------------------------------

    private fun dotProd(q: Int, u: Int, rd: Int, rn: Int, rm: Int): Int {
        return (0 shl 31) or (q shl 30) or (u shl 29) or (0b01110 shl 24) or
                (1 shl 23) or (0 shl 22) or (0 shl 21) or (rm shl 16) or
                (0b10010 shl 11) or (1 shl 10) or (rn shl 5) or rd
    }
    /** SDOT Vd.<Ts>, Vn.<Tb>, Vm.<Tb>. arr ∈ {S2, S4} (selects Q). */
    fun sdot(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size == 2) { "SDOT writes to .2s or .4s" }
        return dotProd(arr.q, 0, rd.n, rn.n, rm.n)
    }
    fun udot(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size == 2)
        return dotProd(arr.q, 1, rd.n, rn.n, rm.n)
    }

    // -----------------------------------------------------------------
    // Half-precision (FP16) vector arithmetic
    //   Different opcode encoding from .4s/.2d: bit 22 selects the FP16
    //   class, bit 23 distinguishes the {fadd/fmul/fmla} ↔ {fsub/fmls}
    //   pairs.
    // -----------------------------------------------------------------

    private fun fpVec3H(q: Int, u: Int, hi: Int, op: Int, rd: Int, rn: Int, rm: Int): Int {
        return (0 shl 31) or (q shl 30) or (u shl 29) or (0b01110 shl 24) or
                (hi shl 23) or (1 shl 22) or (0 shl 21) or (rm shl 16) or
                (op shl 11) or (1 shl 10) or (rn shl 5) or rd
    }

    fun faddH(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size == 1) { "FP16 needs .4h or .8h" }
        return fpVec3H(arr.q, 0, 0, 0b00010, rd.n, rn.n, rm.n)
    }
    fun fsubH(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size == 1)
        return fpVec3H(arr.q, 0, 1, 0b00010, rd.n, rn.n, rm.n)
    }
    fun fmulH(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size == 1)
        return fpVec3H(arr.q, 1, 0, 0b00011, rd.n, rn.n, rm.n)
    }
    fun fmlaH(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size == 1)
        return fpVec3H(arr.q, 0, 0, 0b00001, rd.n, rn.n, rm.n)
    }
    fun fmlsH(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size == 1)
        return fpVec3H(arr.q, 0, 1, 0b00001, rd.n, rn.n, rm.n)
    }

    // -----------------------------------------------------------------
    // FP convert between half ↔ single ↔ double (scalar + widening/narrowing)
    // -----------------------------------------------------------------

    /** Scalar `fcvt`: `dst ← src` between FP types. type/opcode2 select widths. */
    private fun fcvtScalar(type: Int, opcode2: Int, rd: Int, rn: Int): Int {
        return (0b00011110 shl 24) or (type shl 22) or (1 shl 21) or
                (0b0001 shl 17) or (opcode2 shl 15) or (0b10000 shl 10) or
                (rn shl 5) or rd
    }
    /** FCVT Hd, Sn — narrow single → half. */
    fun fcvtHFromS(rd: V, rn: V): Int = fcvtScalar(0b00, 0b11, rd.n, rn.n)
    /** FCVT Sd, Hn — widen half → single. */
    fun fcvtSFromH(rd: V, rn: V): Int = fcvtScalar(0b11, 0b00, rd.n, rn.n)
    /** FCVT Dd, Sn — widen single → double. */
    fun fcvtDFromS(rd: V, rn: V): Int = fcvtScalar(0b00, 0b01, rd.n, rn.n)
    /** FCVT Sd, Dn — narrow double → single. */
    fun fcvtSFromD(rd: V, rn: V): Int = fcvtScalar(0b01, 0b00, rd.n, rn.n)
    /** FCVT Hd, Dn — narrow double → half. */
    fun fcvtHFromD(rd: V, rn: V): Int = fcvtScalar(0b01, 0b11, rd.n, rn.n)
    /** FCVT Dd, Hn — widen half → double. */
    fun fcvtDFromH(rd: V, rn: V): Int = fcvtScalar(0b11, 0b01, rd.n, rn.n)

    /**
     * FCVTL widens a half-vector to a single-vector. Source is `.4h` (q=0)
     * or `.8h` (q=1, the "fcvtl2" form using the upper half). Dest is
     * always `.4s`.
     */
    fun fcvtl(rd: V, rn: V, q: Int = 0): Int =
        fpVecUnary(q, 0, 0, 0, 0b0111, rd.n, rn.n)

    /** FCVTN narrows a single-vector to a half-vector. */
    fun fcvtn(rd: V, rn: V, q: Int = 0): Int =
        fpVecUnary(q, 0, 0, 0, 0b0110, rd.n, rn.n)

    // -----------------------------------------------------------------
    // PC-relative address (ADR / ADRP)
    //   Format: op immlo[1:0] 10000 immhi[18:0] Rd
    //   ADR  : op=0, byte offset (21-bit signed, ±1 MiB)
    //   ADRP : op=1, page offset (21-bit signed × 4096, ±4 GiB)
    // -----------------------------------------------------------------

    fun adr(rd: X, byteOffset: Int): Int {
        require(byteOffset in -(1 shl 20)..((1 shl 20) - 1)) {
            "adr offset $byteOffset out of 21-bit signed range"
        }
        val imm21 = byteOffset and ((1 shl 21) - 1)
        val immlo = imm21 and 0b11
        val immhi = (imm21 ushr 2) and ((1 shl 19) - 1)
        return (0 shl 31) or (immlo shl 29) or (0b10000 shl 24) or
                (immhi shl 5) or rd.n
    }

    fun adrp(rd: X, pageOffset: Int): Int {
        require(pageOffset in -(1 shl 20)..((1 shl 20) - 1)) {
            "adrp page offset $pageOffset out of 21-bit signed range"
        }
        val imm21 = pageOffset and ((1 shl 21) - 1)
        val immlo = imm21 and 0b11
        val immhi = (imm21 ushr 2) and ((1 shl 19) - 1)
        return (1 shl 31) or (immlo shl 29) or (0b10000 shl 24) or
                (immhi shl 5) or rd.n
    }

    // -----------------------------------------------------------------
    // FP min/max numeric (NaN-aware: returns the non-NaN operand)
    // -----------------------------------------------------------------

    fun fminnm(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = (arr.size and 1) or 0b10
        return fpVec3(arr.q, 0, sz, 0b11000, rd.n, rn.n, rm.n)
    }
    fun fmaxnm(rd: V, rn: V, rm: V, arr: VArr): Int {
        require(arr.size in 2..3)
        val sz = arr.size and 1
        return fpVec3(arr.q, 0, sz, 0b11000, rd.n, rn.n, rm.n)
    }
    /** Scalar FMINNM Sd, Sn, Sm. */
    fun fminnmS(rd: V, rn: V, rm: V): Int = fpScalar3(0, 0b0111, rd.n, rn.n, rm.n)
    fun fmaxnmS(rd: V, rn: V, rm: V): Int = fpScalar3(0, 0b0110, rd.n, rn.n, rm.n)
    fun fminnmD(rd: V, rn: V, rm: V): Int = fpScalar3(1, 0b0111, rd.n, rn.n, rm.n)
    fun fmaxnmD(rd: V, rn: V, rm: V): Int = fpScalar3(1, 0b0110, rd.n, rn.n, rm.n)

    // -----------------------------------------------------------------
    // Assembly
    // -----------------------------------------------------------------

    fun assemble(instrs: List<Int>): ByteArray {
        val bb = ByteBuffer.allocate(instrs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in instrs) bb.putInt(i)
        return bb.array()
    }

    fun assemble(vararg instrs: Int): ByteArray = assemble(instrs.toList())
}
