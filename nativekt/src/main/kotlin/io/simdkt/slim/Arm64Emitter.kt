package io.simdkt.slim

import io.simdkt.nativekt.engine.Arm64

/**
 * The instruction-emitting DSL backbone for [SlimScope] blocks.
 *
 * Every ARM64 register, vector arrangement, condition code, and
 * instruction helper is exposed as a property or method on this
 * abstract class. Subclasses override [emit] to route the resulting
 * 32-bit opcodes to a backing buffer. [SlimScope] is the primary
 * subclass — it routes opcodes through an [io.simdkt.nativekt.engine.Asm]
 * for label/branch fixup.
 *
 * ## What's exposed
 *
 * ### Registers (96)
 *
 *   - `X0..X30`, plus `XZR` (= zero register) and `SP` (= stack pointer).
 *     Both `XZR` and `SP` map to register-31; the encoding picks based on
 *     instruction context.
 *   - `W0..W30`, plus `WZR` and `WSP`. The 32-bit views.
 *   - `V0..V31`. SIMD/FP vector registers.
 *
 * ### Vector arrangements (8)
 *
 *   - **8-bit**: `B8` (.8b, 8 lanes × 8-bit = 64-bit total), `B16` (.16b).
 *   - **16-bit**: `H4` (.4h), `H8` (.8h).
 *   - **32-bit**: `S2` (.2s), `S4` (.4s).
 *   - **64-bit**: `D1` (.1d), `D2` (.2d).
 *
 * Pass these as the last parameter of vector ops (`fadd(V0, V1, V2, S4)`).
 *
 * ### Condition codes (16)
 *
 * `EQ`, `NE`, `CS`, `CC`, `MI`, `PL`, `VS`, `VC`, `HI`, `LS`, `GE`, `LT`,
 * `GT`, `LE`, `AL`, plus `NV`. `HS` and `LO` are aliases (for `CS` and
 * `CC` respectively, per ARM convention).
 *
 * ### Index extensions (4)
 *
 * `UXTW`, `LSL`, `SXTW`, `SXTX` for register-offset loads
 * (`ldrReg(rt, rn, rm, ext)`).
 *
 * ## Instruction naming
 *
 * Where ARM assembly uses one mnemonic for register / immediate / vector
 * variants of the same op (`add x0, x1, x2` / `add x0, x1, #4` / `add
 * v0.4s, v1.4s, v2.4s`), this class unifies them under one Kotlin
 * function name via overload resolution:
 *
 *   - `add(X0, X1, X2)` → register add (X reg)
 *   - `add(X0, X1, 4)` → immediate add (12-bit imm)
 *   - `add(V0, V1, V2, S4)` → vector int add (`.4s` lanes)
 *
 * Same pattern for `sub`, `cmp`, `and`, `orr`, `eor`, `mul`. FP
 * instructions keep their `f`-prefixed names (`fadd`, `fmul`, `fmla`, ...)
 * since they're architecturally distinct ops.
 *
 * ## Coverage
 *
 * Approximately 150 helpers across:
 *
 *   - Move / load-immediate (movz/movk/movn/mov, loadImm32/64)
 *   - GP load/store (ldr/str/ldp/stp + size variants, pre/post-index,
 *     register-offset)
 *   - SIMD load/store (ld1/st1/ld1r, ldp_q/stp_q)
 *   - Integer arithmetic (add/sub/neg/cmp/mul/madd/udiv/sdiv)
 *   - Logical (and/orr/eor/mvn/bic/tst, shifts lsl/lsr/asr)
 *   - Compare-and-branch (cbz/cbnz/tbz/tbnz)
 *   - Direct branches (br/blr/ret) — label-driven b/bl/bCond live on
 *     [SlimScope]
 *   - Conditional select (csel/csinc/csinv/csneg, fcselS/fcselD)
 *   - NEON FP — vector and scalar (fadd/fsub/fmul/fdiv/fmla/fmls/
 *     fmin/fmax/fminnm/fmaxnm)
 *   - FP convert/compare/reciprocal (fcvtzs/scvtf/fcmp/fcmgt/fcmge/fcmeq/
 *     frecpe/frsqrte/frecps/frsqrts)
 *   - NEON misc (dup/xtn/xtn2/uxtl/sxtl/ushl/sshl)
 *   - Saturating arithmetic (sqadd/uqadd/sqsub/uqsub/sqdmulh/sqxtn/uqxtn)
 *   - Dot product (sdot/udot)
 *   - Half-precision FP (faddH/fsubH/fmulH/fmlaH/fmlsH/fcvtl/fcvtn/
 *     fcvtHFromS/fcvtSFromH)
 *   - PC-relative addressing (adr/adrp)
 *   - System (nop/isb/dmb/paciasp/autiasp/btiC/btiJ/btiJC)
 *
 * For instructions not yet bound (specialized SVE, SME, crypto, etc.),
 * use the [raw] escape hatch:
 *
 * ```
 * raw(Arm64.someUncommonInstruction(args))
 * ```
 *
 * ## Why abstract
 *
 * The same DSL serves both `SlimScope` (which routes opcodes through an
 * `Asm` for label fixup) and any future subclass that wants to capture
 * instructions differently — e.g., a future "raw bytes" emitter for
 * benchmarking or a "disassembly print" emitter for debugging. Override
 * [emit] to wire up the storage.
 *
 * @see SlimScope — the concrete subclass used by [slim] blocks.
 * @see io.simdkt.nativekt.engine.Arm64 — the underlying pure encoder
 *   (returns `Int` opcodes; no emit side-effect).
 */
abstract class Arm64Emitter internal constructor() {

    // -----------------------------------------------------------------
    // Emit primitives — concrete subclasses route to their own buffer.
    // -----------------------------------------------------------------

    /**
     * Emit a single 32-bit ARM64 opcode. Concrete subclasses route this
     * to whatever storage they're using (an `Asm` for [SlimScope], etc.).
     *
     * @param opcode a fully-encoded 32-bit instruction word.
     */
    protected abstract fun emit(opcode: Int)

    /**
     * Emit a sequence of 32-bit opcodes — used by helpers like
     * `loadImm64` that produce up to 4 instructions.
     *
     * @param opcodes the encoded instruction words, in emission order.
     */
    protected abstract fun emit(opcodes: List<Int>)

    // -----------------------------------------------------------------
    // Register constants (re-exported)
    // -----------------------------------------------------------------

    val X0 = Arm64.X0; val X1 = Arm64.X1; val X2 = Arm64.X2; val X3 = Arm64.X3
    val X4 = Arm64.X4; val X5 = Arm64.X5; val X6 = Arm64.X6; val X7 = Arm64.X7
    val X8 = Arm64.X8; val X9 = Arm64.X9; val X10 = Arm64.X10; val X11 = Arm64.X11
    val X12 = Arm64.X12; val X13 = Arm64.X13; val X14 = Arm64.X14; val X15 = Arm64.X15
    val X16 = Arm64.X16; val X17 = Arm64.X17; val X18 = Arm64.X18; val X19 = Arm64.X19
    val X20 = Arm64.X20; val X21 = Arm64.X21; val X22 = Arm64.X22; val X23 = Arm64.X23
    val X24 = Arm64.X24; val X25 = Arm64.X25; val X26 = Arm64.X26; val X27 = Arm64.X27
    val X28 = Arm64.X28; val X29 = Arm64.X29; val X30 = Arm64.X30
    val XZR = Arm64.XZR; val SP = Arm64.SP

    val W0 = Arm64.W0; val W1 = Arm64.W1; val W2 = Arm64.W2; val W3 = Arm64.W3
    val W4 = Arm64.W4; val W5 = Arm64.W5; val W6 = Arm64.W6; val W7 = Arm64.W7
    val W8 = Arm64.W8; val W9 = Arm64.W9; val W10 = Arm64.W10; val W11 = Arm64.W11
    val W12 = Arm64.W12; val W13 = Arm64.W13; val W14 = Arm64.W14; val W15 = Arm64.W15
    val W16 = Arm64.W16; val W17 = Arm64.W17; val W18 = Arm64.W18; val W19 = Arm64.W19
    val W20 = Arm64.W20; val W21 = Arm64.W21; val W22 = Arm64.W22; val W23 = Arm64.W23
    val W24 = Arm64.W24; val W25 = Arm64.W25; val W26 = Arm64.W26; val W27 = Arm64.W27
    val W28 = Arm64.W28; val W29 = Arm64.W29; val W30 = Arm64.W30
    val WZR = Arm64.WZR; val WSP = Arm64.WSP

    val V0 = Arm64.V0; val V1 = Arm64.V1; val V2 = Arm64.V2; val V3 = Arm64.V3
    val V4 = Arm64.V4; val V5 = Arm64.V5; val V6 = Arm64.V6; val V7 = Arm64.V7
    val V8 = Arm64.V8; val V9 = Arm64.V9; val V10 = Arm64.V10; val V11 = Arm64.V11
    val V12 = Arm64.V12; val V13 = Arm64.V13; val V14 = Arm64.V14; val V15 = Arm64.V15
    val V16 = Arm64.V16; val V17 = Arm64.V17; val V18 = Arm64.V18; val V19 = Arm64.V19
    val V20 = Arm64.V20; val V21 = Arm64.V21; val V22 = Arm64.V22; val V23 = Arm64.V23
    val V24 = Arm64.V24; val V25 = Arm64.V25; val V26 = Arm64.V26; val V27 = Arm64.V27
    val V28 = Arm64.V28; val V29 = Arm64.V29; val V30 = Arm64.V30; val V31 = Arm64.V31

    val B8 = Arm64.VArr.B8; val B16 = Arm64.VArr.B16
    val H4 = Arm64.VArr.H4; val H8 = Arm64.VArr.H8
    val S2 = Arm64.VArr.S2; val S4 = Arm64.VArr.S4
    val D1 = Arm64.VArr.D1; val D2 = Arm64.VArr.D2

    val EQ = Arm64.Cond.EQ; val NE = Arm64.Cond.NE
    val CS = Arm64.Cond.CS; val CC = Arm64.Cond.CC
    val MI = Arm64.Cond.MI; val PL = Arm64.Cond.PL
    val VS = Arm64.Cond.VS; val VC = Arm64.Cond.VC
    val HI = Arm64.Cond.HI; val LS = Arm64.Cond.LS
    val GE = Arm64.Cond.GE; val LT = Arm64.Cond.LT
    val GT = Arm64.Cond.GT; val LE = Arm64.Cond.LE
    val AL = Arm64.Cond.AL

    val UXTW = Arm64.IndexExt.UXTW; val LSL = Arm64.IndexExt.LSL
    val SXTW = Arm64.IndexExt.SXTW; val SXTX = Arm64.IndexExt.SXTX

    // -----------------------------------------------------------------
    // Move / load-immediate
    // -----------------------------------------------------------------

    fun movz(rd: Arm64.X, imm16: Int, shift: Int = 0) { emit(Arm64.movz(rd, imm16, shift)) }
    fun movz(rd: Arm64.W, imm16: Int, shift: Int = 0) { emit(Arm64.movz(rd, imm16, shift)) }
    fun movk(rd: Arm64.X, imm16: Int, shift: Int = 0) { emit(Arm64.movk(rd, imm16, shift)) }
    fun movk(rd: Arm64.W, imm16: Int, shift: Int = 0) { emit(Arm64.movk(rd, imm16, shift)) }
    fun movn(rd: Arm64.X, imm16: Int, shift: Int = 0) { emit(Arm64.movn(rd, imm16, shift)) }
    fun movn(rd: Arm64.W, imm16: Int, shift: Int = 0) { emit(Arm64.movn(rd, imm16, shift)) }
    fun mov(rd: Arm64.X, rm: Arm64.X) { emit(Arm64.mov(rd, rm)) }
    fun mov(rd: Arm64.W, rm: Arm64.W) { emit(Arm64.mov(rd, rm)) }
    fun loadImm64(rd: Arm64.X, imm: Long) { emit(Arm64.loadImm64(rd, imm)) }
    fun loadImm32(rd: Arm64.W, imm: Int) { emit(Arm64.loadImm32(rd, imm)) }

    // -----------------------------------------------------------------
    // GP load/store (unsigned-offset)
    // -----------------------------------------------------------------

    fun ldr(rt: Arm64.X, rn: Arm64.X, offset: Int = 0) { emit(Arm64.ldr(rt, rn, offset)) }
    fun str(rt: Arm64.X, rn: Arm64.X, offset: Int = 0) { emit(Arm64.str(rt, rn, offset)) }
    fun ldrW(rt: Arm64.W, rn: Arm64.X, offset: Int = 0) { emit(Arm64.ldrW(rt, rn, offset)) }
    fun strW(rt: Arm64.W, rn: Arm64.X, offset: Int = 0) { emit(Arm64.strW(rt, rn, offset)) }
    fun ldrH(rt: Arm64.W, rn: Arm64.X, offset: Int = 0) { emit(Arm64.ldrH(rt, rn, offset)) }
    fun strH(rt: Arm64.W, rn: Arm64.X, offset: Int = 0) { emit(Arm64.strH(rt, rn, offset)) }
    fun ldrB(rt: Arm64.W, rn: Arm64.X, offset: Int = 0) { emit(Arm64.ldrB(rt, rn, offset)) }
    fun strB(rt: Arm64.W, rn: Arm64.X, offset: Int = 0) { emit(Arm64.strB(rt, rn, offset)) }

    fun ldp(rt1: Arm64.X, rt2: Arm64.X, rn: Arm64.X, offset: Int = 0) { emit(Arm64.ldp(rt1, rt2, rn, offset)) }
    fun stp(rt1: Arm64.X, rt2: Arm64.X, rn: Arm64.X, offset: Int = 0) { emit(Arm64.stp(rt1, rt2, rn, offset)) }
    fun ldpW(rt1: Arm64.W, rt2: Arm64.W, rn: Arm64.X, offset: Int = 0) { emit(Arm64.ldpW(rt1, rt2, rn, offset)) }
    fun stpW(rt1: Arm64.W, rt2: Arm64.W, rn: Arm64.X, offset: Int = 0) { emit(Arm64.stpW(rt1, rt2, rn, offset)) }
    fun ldpQ(rt1: Arm64.V, rt2: Arm64.V, rn: Arm64.X, offset: Int = 0) { emit(Arm64.ldp_q(rt1, rt2, rn, offset)) }
    fun stpQ(rt1: Arm64.V, rt2: Arm64.V, rn: Arm64.X, offset: Int = 0) { emit(Arm64.stp_q(rt1, rt2, rn, offset)) }

    // Pre / post-index
    fun ldrPre(rt: Arm64.X, rn: Arm64.X, offset: Int) { emit(Arm64.ldrPre(rt, rn, offset)) }
    fun ldrPost(rt: Arm64.X, rn: Arm64.X, offset: Int) { emit(Arm64.ldrPost(rt, rn, offset)) }
    fun strPre(rt: Arm64.X, rn: Arm64.X, offset: Int) { emit(Arm64.strPre(rt, rn, offset)) }
    fun strPost(rt: Arm64.X, rn: Arm64.X, offset: Int) { emit(Arm64.strPost(rt, rn, offset)) }
    fun ldpPre(rt1: Arm64.X, rt2: Arm64.X, rn: Arm64.X, offset: Int) { emit(Arm64.ldpPre(rt1, rt2, rn, offset)) }
    fun ldpPost(rt1: Arm64.X, rt2: Arm64.X, rn: Arm64.X, offset: Int) { emit(Arm64.ldpPost(rt1, rt2, rn, offset)) }
    fun stpPre(rt1: Arm64.X, rt2: Arm64.X, rn: Arm64.X, offset: Int) { emit(Arm64.stpPre(rt1, rt2, rn, offset)) }
    fun stpPost(rt1: Arm64.X, rt2: Arm64.X, rn: Arm64.X, offset: Int) { emit(Arm64.stpPost(rt1, rt2, rn, offset)) }

    // Register-offset
    fun ldrReg(rt: Arm64.X, rn: Arm64.X, rm: Arm64.X, ext: Arm64.IndexExt = LSL, shift: Boolean = true) =
        emit(Arm64.ldrReg(rt, rn, rm, ext, shift))
    fun strReg(rt: Arm64.X, rn: Arm64.X, rm: Arm64.X, ext: Arm64.IndexExt = LSL, shift: Boolean = true) =
        emit(Arm64.strReg(rt, rn, rm, ext, shift))

    // SIMD memory
    fun ld1(rt: Arm64.V, rn: Arm64.X, arr: Arm64.VArr) { emit(Arm64.ld1(rt, rn, arr)) }
    fun st1(rt: Arm64.V, rn: Arm64.X, arr: Arm64.VArr) { emit(Arm64.st1(rt, rn, arr)) }
    fun ld1r(rt: Arm64.V, rn: Arm64.X, arr: Arm64.VArr) { emit(Arm64.ld1r(rt, rn, arr)) }

    // -----------------------------------------------------------------
    // Integer arithmetic (unified: register / immediate / vector overloads)
    // -----------------------------------------------------------------

    fun add(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X) { emit(Arm64.add(rd, rn, rm)) }
    fun add(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W) { emit(Arm64.add(rd, rn, rm)) }
    fun add(rd: Arm64.X, rn: Arm64.X, imm: Int, shift12: Boolean = false) { emit(Arm64.addImm(rd, rn, imm, shift12)) }
    fun add(rd: Arm64.W, rn: Arm64.W, imm: Int, shift12: Boolean = false) { emit(Arm64.addImm(rd, rn, imm, shift12)) }
    fun add(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.addVec(rd, rn, rm, arr)) }

    fun sub(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X) { emit(Arm64.sub(rd, rn, rm)) }
    fun sub(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W) { emit(Arm64.sub(rd, rn, rm)) }
    fun sub(rd: Arm64.X, rn: Arm64.X, imm: Int, shift12: Boolean = false) { emit(Arm64.subImm(rd, rn, imm, shift12)) }
    fun sub(rd: Arm64.W, rn: Arm64.W, imm: Int, shift12: Boolean = false) { emit(Arm64.subImm(rd, rn, imm, shift12)) }
    fun sub(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.subVec(rd, rn, rm, arr)) }

    fun neg(rd: Arm64.X, rm: Arm64.X) { emit(Arm64.neg(rd, rm)) }
    fun neg(rd: Arm64.W, rm: Arm64.W) { emit(Arm64.neg(rd, rm)) }

    fun cmp(rn: Arm64.X, rm: Arm64.X) { emit(Arm64.cmp(rn, rm)) }
    fun cmp(rn: Arm64.W, rm: Arm64.W) { emit(Arm64.cmp(rn, rm)) }
    fun cmp(rn: Arm64.X, imm: Int, shift12: Boolean = false) { emit(Arm64.cmpImm(rn, imm, shift12)) }
    fun cmp(rn: Arm64.W, imm: Int, shift12: Boolean = false) { emit(Arm64.cmpImm(rn, imm, shift12)) }

    fun mul(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X) { emit(Arm64.mul(rd, rn, rm)) }
    fun mul(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W) { emit(Arm64.mul(rd, rn, rm)) }
    fun mul(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.mulVec(rd, rn, rm, arr)) }
    fun madd(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X, ra: Arm64.X) { emit(Arm64.madd(rd, rn, rm, ra)) }
    fun madd(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W, ra: Arm64.W) { emit(Arm64.madd(rd, rn, rm, ra)) }
    fun mla(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.mlaVec(rd, rn, rm, arr)) }
    fun mls(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.mlsVec(rd, rn, rm, arr)) }

    fun udiv(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X) { emit(Arm64.udiv(rd, rn, rm)) }
    fun udiv(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W) { emit(Arm64.udiv(rd, rn, rm)) }
    fun sdiv(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X) { emit(Arm64.sdiv(rd, rn, rm)) }
    fun sdiv(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W) { emit(Arm64.sdiv(rd, rn, rm)) }

    // -----------------------------------------------------------------
    // Logical
    // -----------------------------------------------------------------

    fun and(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X) { emit(Arm64.and(rd, rn, rm)) }
    fun and(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W) { emit(Arm64.and(rd, rn, rm)) }
    fun and(rd: Arm64.X, rn: Arm64.X, imm: Long) { emit(Arm64.andImm(rd, rn, imm)) }
    fun and(rd: Arm64.W, rn: Arm64.W, imm: Long) { emit(Arm64.andImm(rd, rn, imm)) }
    fun and(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.andVec(rd, rn, rm)) }

    fun orr(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X) { emit(Arm64.orr(rd, rn, rm)) }
    fun orr(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W) { emit(Arm64.orr(rd, rn, rm)) }
    fun orr(rd: Arm64.X, rn: Arm64.X, imm: Long) { emit(Arm64.orrImm(rd, rn, imm)) }
    fun orr(rd: Arm64.W, rn: Arm64.W, imm: Long) { emit(Arm64.orrImm(rd, rn, imm)) }
    fun orr(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.orrVec(rd, rn, rm)) }

    fun eor(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X) { emit(Arm64.eor(rd, rn, rm)) }
    fun eor(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W) { emit(Arm64.eor(rd, rn, rm)) }
    fun eor(rd: Arm64.X, rn: Arm64.X, imm: Long) { emit(Arm64.eorImm(rd, rn, imm)) }
    fun eor(rd: Arm64.W, rn: Arm64.W, imm: Long) { emit(Arm64.eorImm(rd, rn, imm)) }
    fun eor(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.eorVec(rd, rn, rm)) }

    fun bic(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.bicVec(rd, rn, rm)) }

    fun mvn(rd: Arm64.X, rm: Arm64.X) { emit(Arm64.mvn(rd, rm)) }
    fun mvn(rd: Arm64.W, rm: Arm64.W) { emit(Arm64.mvn(rd, rm)) }

    fun tst(rn: Arm64.X, imm: Long) { emit(Arm64.tstImm(rn, imm)) }
    fun tst(rn: Arm64.W, imm: Long) { emit(Arm64.tstImm(rn, imm)) }

    // -----------------------------------------------------------------
    // Shifts
    // -----------------------------------------------------------------

    fun lsl(rd: Arm64.X, rn: Arm64.X, shift: Int) { emit(Arm64.lsl(rd, rn, shift)) }
    fun lsl(rd: Arm64.W, rn: Arm64.W, shift: Int) { emit(Arm64.lsl(rd, rn, shift)) }
    fun lsr(rd: Arm64.X, rn: Arm64.X, shift: Int) { emit(Arm64.lsr(rd, rn, shift)) }
    fun lsr(rd: Arm64.W, rn: Arm64.W, shift: Int) { emit(Arm64.lsr(rd, rn, shift)) }
    fun asr(rd: Arm64.X, rn: Arm64.X, shift: Int) { emit(Arm64.asr(rd, rn, shift)) }
    fun asr(rd: Arm64.W, rn: Arm64.W, shift: Int) { emit(Arm64.asr(rd, rn, shift)) }

    fun ushl(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.ushl(rd, rn, rm, arr)) }
    fun sshl(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.sshl(rd, rn, rm, arr)) }

    // -----------------------------------------------------------------
    // Branches (byte-offset versions; label-driven versions live on SlimScope)
    // -----------------------------------------------------------------

    fun br(rn: Arm64.X) { emit(Arm64.br(rn)) }
    fun blr(rn: Arm64.X) { emit(Arm64.blr(rn)) }
    fun ret(rn: Arm64.X = X30) { emit(Arm64.ret(rn)) }

    // -----------------------------------------------------------------
    // Conditional select
    // -----------------------------------------------------------------

    fun csel(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X, cond: Arm64.Cond) { emit(Arm64.csel(rd, rn, rm, cond)) }
    fun csel(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W, cond: Arm64.Cond) { emit(Arm64.csel(rd, rn, rm, cond)) }
    fun csinc(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X, cond: Arm64.Cond) { emit(Arm64.csinc(rd, rn, rm, cond)) }
    fun csinc(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W, cond: Arm64.Cond) { emit(Arm64.csinc(rd, rn, rm, cond)) }
    fun csinv(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X, cond: Arm64.Cond) { emit(Arm64.csinv(rd, rn, rm, cond)) }
    fun csinv(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W, cond: Arm64.Cond) { emit(Arm64.csinv(rd, rn, rm, cond)) }
    fun csneg(rd: Arm64.X, rn: Arm64.X, rm: Arm64.X, cond: Arm64.Cond) { emit(Arm64.csneg(rd, rn, rm, cond)) }
    fun csneg(rd: Arm64.W, rn: Arm64.W, rm: Arm64.W, cond: Arm64.Cond) { emit(Arm64.csneg(rd, rn, rm, cond)) }
    fun fcselS(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, cond: Arm64.Cond) { emit(Arm64.fcselS(rd, rn, rm, cond)) }
    fun fcselD(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, cond: Arm64.Cond) { emit(Arm64.fcselD(rd, rn, rm, cond)) }

    // -----------------------------------------------------------------
    // NEON FP (vector + scalar)
    // -----------------------------------------------------------------

    fun fadd(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fadd(rd, rn, rm, arr)) }
    fun fsub(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fsub(rd, rn, rm, arr)) }
    fun fmul(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmul(rd, rn, rm, arr)) }
    fun fdiv(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fdiv(rd, rn, rm, arr)) }
    fun fmla(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmla(rd, rn, rm, arr)) }
    fun fmls(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmls(rd, rn, rm, arr)) }
    fun fmin(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmin(rd, rn, rm, arr)) }
    fun fmax(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmax(rd, rn, rm, arr)) }
    fun fminnm(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fminnm(rd, rn, rm, arr)) }
    fun fmaxnm(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmaxnm(rd, rn, rm, arr)) }

    fun faddS(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.faddS(rd, rn, rm)) }
    fun faddD(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.faddD(rd, rn, rm)) }
    fun fsubS(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fsubS(rd, rn, rm)) }
    fun fsubD(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fsubD(rd, rn, rm)) }
    fun fmulS(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fmulS(rd, rn, rm)) }
    fun fmulD(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fmulD(rd, rn, rm)) }
    fun fdivS(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fdivS(rd, rn, rm)) }
    fun fdivD(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fdivD(rd, rn, rm)) }
    fun fminnmS(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fminnmS(rd, rn, rm)) }
    fun fmaxnmS(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fmaxnmS(rd, rn, rm)) }
    fun fminnmD(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fminnmD(rd, rn, rm)) }
    fun fmaxnmD(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fmaxnmD(rd, rn, rm)) }

    // -----------------------------------------------------------------
    // FP convert / compare / reciprocal
    // -----------------------------------------------------------------

    fun fcvtzs(rd: Arm64.W, sn: Arm64.V) { emit(Arm64.fcvtzsW(rd, sn)) }
    fun fcvtzs(rd: Arm64.X, dn: Arm64.V) { emit(Arm64.fcvtzsX(rd, dn)) }
    fun fcvtzs(rd: Arm64.V, rn: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fcvtzsVec(rd, rn, arr)) }
    fun scvtfS(rd: Arm64.V, wn: Arm64.W) { emit(Arm64.scvtfS(rd, wn)) }
    fun scvtfD(rd: Arm64.V, xn: Arm64.X) { emit(Arm64.scvtfD(rd, xn)) }
    fun scvtf(rd: Arm64.V, rn: Arm64.V, arr: Arm64.VArr) { emit(Arm64.scvtfVec(rd, rn, arr)) }

    fun fcmpS(rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fcmpS(rn, rm)) }
    fun fcmpD(rn: Arm64.V, rm: Arm64.V) { emit(Arm64.fcmpD(rn, rm)) }
    fun fcmeq(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fcmeq(rd, rn, rm, arr)) }
    fun fcmge(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fcmge(rd, rn, rm, arr)) }
    fun fcmgt(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fcmgt(rd, rn, rm, arr)) }

    fun frecpe(rd: Arm64.V, rn: Arm64.V, arr: Arm64.VArr) { emit(Arm64.frecpe(rd, rn, arr)) }
    fun frecps(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.frecps(rd, rn, rm, arr)) }
    fun frsqrte(rd: Arm64.V, rn: Arm64.V, arr: Arm64.VArr) { emit(Arm64.frsqrte(rd, rn, arr)) }
    fun frsqrts(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.frsqrts(rd, rn, rm, arr)) }

    // -----------------------------------------------------------------
    // NEON misc / saturating / dot product / FP16
    // -----------------------------------------------------------------

    fun dup(rd: Arm64.V, rn: Arm64.X, arr: Arm64.VArr) { emit(Arm64.dup(rd, rn, arr)) }
    fun xtn(rd: Arm64.V, rn: Arm64.V, arr: Arm64.VArr) { emit(Arm64.xtn(rd, rn, arr)) }
    fun xtn2(rd: Arm64.V, rn: Arm64.V, arr: Arm64.VArr) { emit(Arm64.xtn2(rd, rn, arr)) }
    fun uxtl(rd: Arm64.V, rn: Arm64.V, srcArr: Arm64.VArr) { emit(Arm64.uxtl(rd, rn, srcArr)) }
    fun sxtl(rd: Arm64.V, rn: Arm64.V, srcArr: Arm64.VArr) { emit(Arm64.sxtl(rd, rn, srcArr)) }

    fun sqadd(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.sqadd(rd, rn, rm, arr)) }
    fun uqadd(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.uqadd(rd, rn, rm, arr)) }
    fun sqsub(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.sqsub(rd, rn, rm, arr)) }
    fun uqsub(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.uqsub(rd, rn, rm, arr)) }
    fun sqdmulh(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.sqdmulh(rd, rn, rm, arr)) }
    fun sqxtn(rd: Arm64.V, rn: Arm64.V, destArr: Arm64.VArr) { emit(Arm64.sqxtn(rd, rn, destArr)) }
    fun uqxtn(rd: Arm64.V, rn: Arm64.V, destArr: Arm64.VArr) { emit(Arm64.uqxtn(rd, rn, destArr)) }
    fun sdot(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.sdot(rd, rn, rm, arr)) }
    fun udot(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.udot(rd, rn, rm, arr)) }

    fun faddH(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.faddH(rd, rn, rm, arr)) }
    fun fsubH(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fsubH(rd, rn, rm, arr)) }
    fun fmulH(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmulH(rd, rn, rm, arr)) }
    fun fmlaH(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmlaH(rd, rn, rm, arr)) }
    fun fmlsH(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) { emit(Arm64.fmlsH(rd, rn, rm, arr)) }
    fun fcvtl(rd: Arm64.V, rn: Arm64.V, q: Int = 0) { emit(Arm64.fcvtl(rd, rn, q)) }
    fun fcvtn(rd: Arm64.V, rn: Arm64.V, q: Int = 0) { emit(Arm64.fcvtn(rd, rn, q)) }

    // -----------------------------------------------------------------
    // PC-relative / system
    // -----------------------------------------------------------------

    fun adr(rd: Arm64.X, byteOffset: Int) { emit(Arm64.adr(rd, byteOffset)) }
    fun adrp(rd: Arm64.X, pageOffset: Int) { emit(Arm64.adrp(rd, pageOffset)) }

    fun nop() { emit(Arm64.nop()) }
    fun isb() { emit(Arm64.isb()) }
    fun dmb(option: Int = 0xF) { emit(Arm64.dmb(option)) }
    fun paciasp() { emit(Arm64.paciasp()) }
    fun autiasp() { emit(Arm64.autiasp()) }
    fun btiC() { emit(Arm64.btiC()) }
    fun btiJ() { emit(Arm64.btiJ()) }
    fun btiJC() { emit(Arm64.btiJC()) }

    // -----------------------------------------------------------------
    // Escape hatch — drop down to raw opcodes if a helper is missing.
    // -----------------------------------------------------------------

    /**
     * Emit a raw 32-bit opcode bypassing the DSL surface. Use when an
     * instruction isn't yet bound on this class — pass the result of an
     * `Arm64.foo(...)` encoder helper:
     *
     * ```
     * raw(io.simdkt.nativekt.engine.Arm64.someExoticInstruction(X0, X1))
     * ```
     *
     * @param opcode an encoded 32-bit ARM64 instruction word.
     */
    fun raw(opcode: Int) { emit(opcode) }

    /**
     * Emit a list of raw opcodes. Convenient for helpers that return
     * [List]<[Int]> (like `loadImm64` returning up to 4 instructions).
     *
     * @param opcodes encoded ARM64 instruction words in order.
     */
    fun raw(opcodes: List<Int>) { emit(opcodes) }
}
