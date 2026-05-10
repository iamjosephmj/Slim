package io.simdkt.nativekt.engine

data class DecodedInsn(
    val mnemonic: String,
    val operands: List<Operand>,
    val raw: Int,
)

/** Addressing mode for memory operands. */
enum class AddrMode {
    /** [base, #offset]  — unsigned-offset or signed-offset (no writeback). */
    OFFSET,
    /** [base, #offset]!  — pre-indexed with writeback. */
    PRE_INDEXED,
    /** [base], #offset  — post-indexed with writeback. */
    POST_INDEXED,
}

sealed interface Operand {
    data class Reg(val name: String) : Operand                          // "x1", "w3", "xzr", "wzr", "sp"
    data class VecReg(val name: String, val arr: Arm64.VArr?) : Operand // "v0.4s" or "v0" (no arrangement)
    data class FpReg(val name: String) : Operand                        // "s0", "d0", "h0"
    data class Imm(val value: Long, val format: ImmFormat) : Operand
    data class BranchOffset(val byteOffset: Int) : Operand              // resolved by Disassembler
    data class CondCode(val name: String) : Operand                     // "eq", "ne", ...
    data class Label(val name: String) : Operand                        // populated post-resolution
    data class MemAddr(                                                  // [x1, #16] / [x1, #16]! / [x1], #16
        val base: Reg,
        val offset: Imm?,
        val mode: AddrMode = AddrMode.OFFSET,
    ) : Operand
    data class Unknown(val raw: Int) : Operand
}

enum class ImmFormat { DEC, HEX, FP32_BITS, FP64_BITS, SHIFT_AMOUNT }

data class SourceFrame(val file: String, val line: Int)
