package io.simdkt.nativekt.engine

object Arm64Decoder {
    fun decode(op: Int): DecodedInsn = when {
        isUnconditionalBranch(op) -> decodeUnconditionalBranch(op)
        isConditionalBranch(op)   -> decodeConditionalBranch(op)
        isCompareBranch(op)       -> decodeCompareBranch(op)
        isTestBranch(op)          -> decodeTestBranch(op)
        isRegisterBranch(op)      -> decodeRegisterBranch(op)
        isDataProcImm(op)         -> decodeDataProcImm(op)
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

    // -----------------------------------------------------------------
    // Data-processing — immediate  (bits 28..26 = 0b100)
    // -----------------------------------------------------------------

    // bits 28..26 of the instruction = 0b100
    private fun isDataProcImm(op: Int): Boolean = (op ushr 26) and 0b111 == 0b100

    private fun decodeDataProcImm(op: Int): DecodedInsn {
        val op0 = (op ushr 23) and 0b111   // bits 25..23
        return when (op0) {
            0b101        -> decodeMoveWide(op)     // 100101 fixed = move-wide imm
            0b010, 0b011 -> decodeAddSubImm(op)    // 100010/100011 = add/sub imm
            0b100        -> decodeLogicalImm(op)   // 100100 = logical imm
            0b110        -> decodeBitfield(op)     // 100110 = bitfield
            else -> DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
    }

    // -----------------------------------------------------------------
    // Move-wide immediate  (sf opc 100101 hw imm16 Rd)
    // opc: 00=movn, 10=movz, 11=movk
    // -----------------------------------------------------------------

    private fun decodeMoveWide(op: Int): DecodedInsn {
        val sf    = (op ushr 31) and 1
        val opc   = (op ushr 29) and 0b11
        val hw    = (op ushr 21) and 0b11    // shift = hw * 16
        val imm16 = (op ushr  5) and 0xFFFF
        val rd    = op and 0x1F
        val mnem   = when (opc) { 0b00 -> "movn"; 0b10 -> "movz"; 0b11 -> "movk"; else -> "?" }
        val reg    = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        val regName = if (rd == 31) zrName else "$reg$rd"
        val ops = mutableListOf<Operand>(
            Operand.Reg(regName),
            Operand.Imm(imm16.toLong(), ImmFormat.HEX),
        )
        // shift operand omitted when hw=0
        if (hw != 0) ops += Operand.Imm((hw * 16).toLong(), ImmFormat.SHIFT_AMOUNT)
        return DecodedInsn(mnem, ops, op)
    }

    // -----------------------------------------------------------------
    // Add/sub immediate  (sf op S 100010 sh imm12 Rn Rd)
    // op: 0=add, 1=sub; S: set flags
    // Alias: subs xzr → cmp; adds xzr → cmn (not in encoder, so skip cmn alias)
    // -----------------------------------------------------------------

    private fun decodeAddSubImm(op: Int): DecodedInsn {
        val sf    = (op ushr 31) and 1
        val opc   = (op ushr 30) and 1    // 0=add, 1=sub
        val s     = (op ushr 29) and 1    // set flags
        val sh    = (op ushr 22) and 1    // 0=no shift, 1=shift imm by 12
        val imm12 = (op ushr 10) and 0xFFF
        val rn    = (op ushr  5) and 0x1F
        val rd    = op and 0x1F
        val reg    = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        val spName = if (sf == 1) "sp" else "wsp"
        val rdName = when {
            rd == 31 && s == 1 -> zrName   // ADDS/SUBS — result discarded, use ZR
            rd == 31           -> spName   // ADD/SUB  — write result to SP
            else               -> "$reg$rd"
        }
        val rnName = if (rn == 31) spName else "$reg$rn"

        // cmp alias: subs xzr, Rn, #imm  (op=1, s=1, rd=31)
        if (opc == 1 && s == 1 && rd == 31) {
            val imOps = mutableListOf<Operand>(Operand.Reg(rnName))
            imOps += Operand.Imm(imm12.toLong(), ImmFormat.DEC)
            if (sh == 1) imOps += Operand.Imm(12L, ImmFormat.SHIFT_AMOUNT)
            return DecodedInsn("cmp", imOps, op)
        }

        val mnem = when {
            opc == 0 && s == 0 -> "add"
            opc == 0 && s == 1 -> "adds"
            opc == 1 && s == 0 -> "sub"
            else               -> "subs"
        }
        val ops = mutableListOf<Operand>(
            Operand.Reg(rdName),
            Operand.Reg(rnName),
            Operand.Imm(imm12.toLong(), ImmFormat.DEC),
        )
        if (sh == 1) ops += Operand.Imm(12L, ImmFormat.SHIFT_AMOUNT)
        return DecodedInsn(mnem, ops, op)
    }

    // -----------------------------------------------------------------
    // Logical immediate  (sf opc 100100 N immr imms Rn Rd)
    // opc: 00=and, 01=orr, 10=eor, 11=ands
    // N:immr:imms encode the bitmask (ARM ARM C4.1.2 / J1.2.4)
    // Aliases: ands xzr → tst; orr Rd, xzr, #imm → mov
    // -----------------------------------------------------------------

    private fun decodeLogicalImm(op: Int): DecodedInsn {
        val sf   = (op ushr 31) and 1
        val opc  = (op ushr 29) and 0b11
        val n    = (op ushr 22) and 1
        val immr = (op ushr 16) and 0x3F
        val imms = (op ushr 10) and 0x3F
        val rn   = (op ushr  5) and 0x1F
        val rd   = op and 0x1F
        val reg  = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        val rdName = if (rd == 31) zrName else "$reg$rd"
        val rnName = if (rn == 31) zrName else "$reg$rn"

        val immRaw = decodeBitmaskImm(n, immr, imms)
        // For 32-bit instructions the logical mask only spans the lower 32 bits.
        val imm = if (sf == 0) immRaw and 0xFFFFFFFFL else immRaw
        val immOp = Operand.Imm(imm, ImmFormat.HEX)

        // tst alias: ands xzr, Rn, #imm  (opc=11, rd=31)
        if (opc == 0b11 && rd == 31) {
            return DecodedInsn("tst", listOf(Operand.Reg(rnName), immOp), op)
        }
        // mov alias: orr Rd, xzr/wzr, #imm  (opc=01, rn=31)
        if (opc == 0b01 && rn == 31) {
            return DecodedInsn("mov", listOf(Operand.Reg(rdName), immOp), op)
        }

        val mnem = when (opc) { 0b00 -> "and"; 0b01 -> "orr"; 0b10 -> "eor"; else -> "ands" }
        return DecodedInsn(mnem, listOf(Operand.Reg(rdName), Operand.Reg(rnName), immOp), op)
    }

    /**
     * Decode the bitmask immediate defined by [n], [immr], [imms] into its 64-bit value.
     *
     * ARM ARM C4.1.2 / J1.2.4 algorithm:
     *  1. len = HighestSetBit(N : NOT(imms[5:0]))  — determines element size = 2^len
     *  2. S = imms[len-1:0], R = immr[len-1:0]
     *  3. pattern = ROR((S+1 ones), R, esize)
     *  4. replicate pattern to fill 64 bits
     */
    private fun decodeBitmaskImm(n: Int, immr: Int, imms: Int): Long {
        // combined = N:NOT(imms[5:0]) as 7-bit value (bit6=N, bits5..0=~imms)
        val combined = (n shl 6) or ((imms.inv()) and 0x3F)
        // Find position of highest set bit (len must be >= 1)
        var len = -1
        for (i in 6 downTo 0) {
            if ((combined ushr i) and 1 != 0) { len = i; break }
        }
        if (len < 1) return 0L  // invalid encoding — return 0 as best effort

        val esize = 1 shl len                  // element size: 2, 4, 8, 16, 32, or 64
        val s     = imms and (esize - 1)       // number of set bits minus 1
        val r     = immr and (esize - 1)       // rotate-right amount
        val emask = if (esize == 64) -1L else ((1L shl esize) - 1L)

        // (s+1) ones, rotated right by r within esize-bit element
        val ones    = (1L shl (s + 1)) - 1L
        val pattern = if (r == 0) ones
                      else ((ones ushr r) or (ones shl (esize - r))) and emask

        // Replicate esize-bit pattern to fill 64 bits
        var rep   = pattern
        var rsize = esize
        while (rsize < 64) { rep = rep or (rep shl rsize); rsize = rsize shl 1 }
        return rep
    }

    // -----------------------------------------------------------------
    // Bitfield  (sf opc 100110 N immr imms Rn Rd)
    // opc: 00=sbfm, 01=bfm, 10=ubfm
    // Aliases: lsl/lsr/asr, sxtb/sxth/sxtw, uxtb/uxth
    // -----------------------------------------------------------------

    private fun decodeBitfield(op: Int): DecodedInsn {
        val sf   = (op ushr 31) and 1
        val opc  = (op ushr 29) and 0b11
        val immr = (op ushr 16) and 0x3F
        val imms = (op ushr 10) and 0x3F
        val rn   = (op ushr  5) and 0x1F
        val rd   = op and 0x1F
        val reg    = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        val regBits = if (sf == 1) 64 else 32
        val rdName = if (rd == 31) zrName else "$reg$rd"
        val rnName = if (rn == 31) zrName else "$reg$rn"

        return when (opc) {
            0b10 -> decodeUbfm(sf, regBits, immr, imms, rdName, rnName, op)
            0b00 -> decodeSbfm(sf, regBits, immr, imms, rdName, rnName, op)
            0b01 -> DecodedInsn(
                "bfm",
                listOf(
                    Operand.Reg(rdName),
                    Operand.Reg(rnName),
                    Operand.Imm(immr.toLong(), ImmFormat.DEC),
                    Operand.Imm(imms.toLong(), ImmFormat.DEC),
                ),
                op,
            )
            else -> DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
    }

    /** UBFM aliases: lsl, lsr, uxtb, uxth; fallback to ubfm. */
    private fun decodeUbfm(
        sf: Int, regBits: Int, immr: Int, imms: Int,
        rdName: String, rnName: String, op: Int,
    ): DecodedInsn {
        val top = regBits - 1
        return when {
            // lsr  Rd, Rn, #shift  — imms == regBits-1
            imms == top -> DecodedInsn(
                "lsr",
                listOf(Operand.Reg(rdName), Operand.Reg(rnName), Operand.Imm(immr.toLong(), ImmFormat.DEC)),
                op,
            )
            // lsl  Rd, Rn, #shift  — (imms+1) % regBits == immr
            (imms + 1) % regBits == immr -> {
                val shift = top - imms
                DecodedInsn(
                    "lsl",
                    listOf(Operand.Reg(rdName), Operand.Reg(rnName), Operand.Imm(shift.toLong(), ImmFormat.DEC)),
                    op,
                )
            }
            // uxtb — ubfm Wd, Wn, #0, #7  (only makes sense for 32-bit)
            sf == 0 && immr == 0 && imms == 7 -> DecodedInsn(
                "uxtb",
                listOf(Operand.Reg(rdName), Operand.Reg(rnName)),
                op,
            )
            // uxth — ubfm Wd, Wn, #0, #15
            sf == 0 && immr == 0 && imms == 15 -> DecodedInsn(
                "uxth",
                listOf(Operand.Reg(rdName), Operand.Reg(rnName)),
                op,
            )
            else -> DecodedInsn(
                "ubfm",
                listOf(
                    Operand.Reg(rdName),
                    Operand.Reg(rnName),
                    Operand.Imm(immr.toLong(), ImmFormat.DEC),
                    Operand.Imm(imms.toLong(), ImmFormat.DEC),
                ),
                op,
            )
        }
    }

    /** SBFM aliases: asr, sxtb, sxth, sxtw; fallback to sbfm. */
    private fun decodeSbfm(
        sf: Int, regBits: Int, immr: Int, imms: Int,
        rdName: String, rnName: String, op: Int,
    ): DecodedInsn {
        val top = regBits - 1
        return when {
            // asr  Rd, Rn, #shift  — imms == regBits-1
            imms == top -> DecodedInsn(
                "asr",
                listOf(Operand.Reg(rdName), Operand.Reg(rnName), Operand.Imm(immr.toLong(), ImmFormat.DEC)),
                op,
            )
            // sxtb — sbfm Rd, Rn, #0, #7
            immr == 0 && imms == 7 -> DecodedInsn(
                "sxtb",
                listOf(Operand.Reg(rdName), Operand.Reg(rnName)),
                op,
            )
            // sxth — sbfm Rd, Rn, #0, #15
            immr == 0 && imms == 15 -> DecodedInsn(
                "sxth",
                listOf(Operand.Reg(rdName), Operand.Reg(rnName)),
                op,
            )
            // sxtw — sbfm Xd, Wn, #0, #31  (only for 64-bit form)
            sf == 1 && immr == 0 && imms == 31 -> DecodedInsn(
                "sxtw",
                listOf(Operand.Reg(rdName), Operand.Reg(rnName.replace("x", "w"))),
                op,
            )
            else -> DecodedInsn(
                "sbfm",
                listOf(
                    Operand.Reg(rdName),
                    Operand.Reg(rnName),
                    Operand.Imm(immr.toLong(), ImmFormat.DEC),
                    Operand.Imm(imms.toLong(), ImmFormat.DEC),
                ),
                op,
            )
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
