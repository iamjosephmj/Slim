package io.simdkt.nativekt.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class Arm64DecoderPropertyTest {

    private val rng = Random(0xC0FFEEL)

    // Exclude r31 to avoid xzr/sp/wzr aliasing that triggers decoder aliases (cmp, neg, mov, etc.)
    private fun xnz() = Arm64.X(rng.nextInt(0, 31))
    private fun wnz() = Arm64.W(rng.nextInt(0, 31))
    private fun v()   = Arm64.V(rng.nextInt(0, 32))

    // FP vector ops only support S2/S4/D2
    private fun fpVecArr() = listOf(Arm64.VArr.S2, Arm64.VArr.S4, Arm64.VArr.D2).random(rng)

    // Integer vector ops support all arrangements
    private fun intVecArr() = Arm64.VArr.values().random(rng)

    // --- Round-trip helper ---

    private fun assertRoundTrip(opcode: Int, expectedMnemonic: String, expectedArity: Int) {
        val insn = Arm64Decoder.decode(opcode)
        assertEquals(
            "mnemonic for opcode 0x${"${"%08x".format(opcode)}"}",
            expectedMnemonic,
            insn.mnemonic,
        )
        assertEquals(
            "arity for opcode 0x${"${"%08x".format(opcode)}"}",
            expectedArity,
            insn.operands.size,
        )
        assertEquals(
            "raw for opcode 0x${"${"%08x".format(opcode)}"}",
            opcode,
            insn.raw,
        )
    }

    // --- NEON FP family ---

    @Test fun fmla_roundtrips() {
        repeat(50) {
            val opcode = Arm64.fmla(v(), v(), v(), fpVecArr())
            assertRoundTrip(opcode, "fmla", 3)
        }
    }

    @Test fun fadd_vec_roundtrips() {
        repeat(50) {
            val opcode = Arm64.fadd(v(), v(), v(), fpVecArr())
            assertRoundTrip(opcode, "fadd", 3)
        }
    }

    @Test fun fmul_vec_roundtrips() {
        repeat(50) {
            val opcode = Arm64.fmul(v(), v(), v(), fpVecArr())
            assertRoundTrip(opcode, "fmul", 3)
        }
    }

    // --- Data-proc immediate (add/sub) ---

    @Test fun add_imm_roundtrips() {
        repeat(50) {
            // addImm encodes imm12 from 0..4095; decoder returns [rd, rn, imm] — arity 3
            val imm = rng.nextInt(0, 4096)
            val opcode = Arm64.addImm(xnz(), xnz(), imm)
            assertRoundTrip(opcode, "add", 3)
        }
    }

    // --- Data-proc register ---

    @Test fun add_reg_roundtrips() {
        repeat(50) {
            // add(X, X, X) with rd/rn/rm all != 31 → plain "add", arity 3
            val opcode = Arm64.add(xnz(), xnz(), xnz())
            assertRoundTrip(opcode, "add", 3)
        }
    }

    @Test fun mov_reg_alias_roundtrips() {
        repeat(50) {
            // mov Xd, Xm = orr Xd, xzr, Xm — decoder emits "mov" with 2 operands [rd, rm]
            val rd = xnz()
            val rm = xnz()
            val opcode = Arm64.mov(rd, rm)
            assertRoundTrip(opcode, "mov", 2)
        }
    }

    // --- Branches ---

    @Test fun cbz_roundtrips() {
        repeat(50) {
            // byteOffset must be a multiple of 4 and fit in 19 signed bits shifted left 2
            // Valid range: -(1 shl 20)..(1 shl 20) - 4, in steps of 4
            val byteOffset = (rng.nextInt(-(1 shl 18), (1 shl 18))) shl 2
            val opcode = Arm64.cbz(xnz(), byteOffset)
            assertRoundTrip(opcode, "cbz", 2)
        }
    }

    @Test fun cbnz_roundtrips() {
        repeat(50) {
            val byteOffset = (rng.nextInt(-(1 shl 18), (1 shl 18))) shl 2
            val opcode = Arm64.cbnz(xnz(), byteOffset)
            assertRoundTrip(opcode, "cbnz", 2)
        }
    }

    // --- Load/store ---

    @Test fun ldr_roundtrips() {
        repeat(50) {
            // ldr(X, X, offset): offset is byte offset, must be multiple of 8, imm12 = offset/8 in 0..4095
            // So byte offset range: 0..32760 in steps of 8
            val byteOffset = rng.nextInt(0, 4096) shl 3  // 0..32760, multiple of 8
            val opcode = Arm64.ldr(xnz(), xnz(), byteOffset)
            // decoder emits [Reg(rt), MemAddr(rn, imm)] — arity 2
            assertRoundTrip(opcode, "ldr", 2)
        }
    }

    @Test fun ld1_roundtrips() {
        repeat(50) {
            // ld1(V, X, VArr) — decoder emits [VecReg(rt), MemAddr(rn)] — arity 2
            val opcode = Arm64.ld1(v(), xnz(), intVecArr())
            assertRoundTrip(opcode, "ld1", 2)
        }
    }

    // --- NEON integer family ---

    @Test fun add_vec_roundtrips() {
        repeat(50) {
            val opcode = Arm64.addVec(v(), v(), v(), intVecArr())
            assertRoundTrip(opcode, "add", 3)
        }
    }

    // --- System / hint ---

    @Test fun ret_roundtrips() {
        val opcode = Arm64.ret()
        assertRoundTrip(opcode, "ret", 0)
    }

    @Test fun nop_roundtrips() {
        val opcode = Arm64.nop()
        assertRoundTrip(opcode, "nop", 0)
    }

    // --- Negative test: 1000 random opcodes must never throw ---

    @Test fun random_bytes_never_throw() {
        repeat(1000) {
            val opcode = rng.nextInt()
            val insn = Arm64Decoder.decode(opcode)
            assertEquals("raw must echo back for opcode 0x${"${"%08x".format(opcode)}"}", opcode, insn.raw)
            // mnemonic can be anything including "?"; we only require no exception is thrown
        }
    }
}
