package io.simdkt.nativekt.engine

object Arm64Decoder {
    fun decode(op: Int): DecodedInsn = when {
        isUnconditionalBranch(op) -> decodeUnconditionalBranch(op)
        isConditionalBranch(op)   -> decodeConditionalBranch(op)
        isCompareBranch(op)       -> decodeCompareBranch(op)
        isTestBranch(op)          -> decodeTestBranch(op)
        isRegisterBranch(op)      -> decodeRegisterBranch(op)
        else -> DecodedInsn("?", listOf(Operand.Unknown(op)), op)
    }

    // bits 30..26 = 0b00101 (b/bl): bit 31 = link
    private fun isUnconditionalBranch(op: Int): Boolean =
        (op ushr 26) and 0b011111 == 0b000101

    private fun decodeUnconditionalBranch(op: Int): DecodedInsn {
        val link = (op ushr 31) and 1
        val imm26 = signExtend(op and 0x03FFFFFF, 26)
        val byteOffset = imm26 shl 2
        val mnem = if (link == 1) "bl" else "b"
        return DecodedInsn(mnem, listOf(Operand.BranchOffset(byteOffset)), op)
    }

    // bits 31..24 = 0b01010100 (b.cond)
    private fun isConditionalBranch(op: Int): Boolean =
        (op ushr 24) and 0xFF == 0b01010100

    private fun decodeConditionalBranch(op: Int): DecodedInsn {
        val cond = op and 0xF
        val imm19 = signExtend((op ushr 5) and 0x7FFFF, 19)
        val byteOffset = imm19 shl 2
        return DecodedInsn(
            "b.${condName(cond)}",
            listOf(Operand.BranchOffset(byteOffset)),
            op,
        )
    }

    // bits 30..25 = 0b011010, bit 24 = op (0=cbz, 1=cbnz)
    private fun isCompareBranch(op: Int): Boolean =
        (op ushr 25) and 0b0111111 == 0b0011010

    private fun decodeCompareBranch(op: Int): DecodedInsn {
        val sf = (op ushr 31) and 1
        val isCbnz = ((op ushr 24) and 1) == 1
        val imm19 = signExtend((op ushr 5) and 0x7FFFF, 19)
        val byteOffset = imm19 shl 2
        val rt = op and 0x1F
        val regName = if (sf == 1) "x$rt" else "w$rt"
        val mnem = if (isCbnz) "cbnz" else "cbz"
        return DecodedInsn(
            mnem,
            listOf(Operand.Reg(regName), Operand.BranchOffset(byteOffset)),
            op,
        )
    }

    // bits 30..25 = 0b011011, bit 24 = op (0=tbz, 1=tbnz)
    private fun isTestBranch(op: Int): Boolean =
        (op ushr 25) and 0b0111111 == 0b0011011

    private fun decodeTestBranch(op: Int): DecodedInsn {
        val b5 = (op ushr 31) and 1
        val isTbnz = ((op ushr 24) and 1) == 1
        val b40 = (op ushr 19) and 0x1F
        val bit = (b5 shl 5) or b40
        val imm14 = signExtend((op ushr 5) and 0x3FFF, 14)
        val byteOffset = imm14 shl 2
        val rt = op and 0x1F
        val regName = if (b5 == 1) "x$rt" else "w$rt"
        val mnem = if (isTbnz) "tbnz" else "tbz"
        return DecodedInsn(
            mnem,
            listOf(
                Operand.Reg(regName),
                Operand.Imm(bit.toLong(), ImmFormat.DEC),
                Operand.BranchOffset(byteOffset),
            ),
            op,
        )
    }

    // bits 31..25 = 0b1101011 (br/blr/ret)
    private fun isRegisterBranch(op: Int): Boolean =
        (op ushr 25) and 0b1111111 == 0b1101011

    private fun decodeRegisterBranch(op: Int): DecodedInsn {
        val opc = (op ushr 21) and 0b11   // 00=br, 01=blr, 10=ret
        val rn = (op ushr 5) and 0x1F
        val regName = "x$rn"
        return when (opc) {
            0 -> DecodedInsn("br",  listOf(Operand.Reg(regName)), op)
            1 -> DecodedInsn("blr", listOf(Operand.Reg(regName)), op)
            2 -> if (rn == 30) DecodedInsn("ret", emptyList(), op)
                 else          DecodedInsn("ret", listOf(Operand.Reg(regName)), op)
            else -> DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
    }

    private fun signExtend(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }

    private fun condName(cond: Int): String = when (cond) {
        0 -> "eq"; 1 -> "ne"; 2 -> "cs"; 3 -> "cc"
        4 -> "mi"; 5 -> "pl"; 6 -> "vs"; 7 -> "vc"
        8 -> "hi"; 9 -> "ls"; 10 -> "ge"; 11 -> "lt"
        12 -> "gt"; 13 -> "le"; 14 -> "al"; 15 -> "nv"
        else -> error("invalid cond $cond")
    }
}
