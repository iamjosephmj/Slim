package io.simdkt.nativekt.engine

data class DecodedInsn(
    val mnemonic: String,
    val operands: List<Operand>,
    val raw: Int,
)

sealed interface Operand {
    data class Reg(val name: String) : Operand                          // "x1", "w3", "xzr", "wzr", "sp"
    data class VecReg(val name: String, val arr: Arm64.VArr?) : Operand // "v0.4s" or "v0" (no arrangement)
    data class FpReg(val name: String) : Operand                        // "s0", "d0", "h0"
    data class Imm(val value: Long, val format: ImmFormat) : Operand
    data class BranchOffset(val byteOffset: Int) : Operand              // resolved by Disassembler
    data class CondCode(val name: String) : Operand                     // "eq", "ne", ...
    data class Label(val name: String) : Operand                        // populated post-resolution
    data class MemAddr(val base: Reg, val offset: Imm?) : Operand       // [x1, #16]
    data class Unknown(val raw: Int) : Operand
}

enum class ImmFormat { DEC, HEX, FP32_BITS, FP64_BITS, SHIFT_AMOUNT }

data class SourceFrame(val file: String, val line: Int)
