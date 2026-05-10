package io.simdkt.nativekt.engine

object Arm64Decoder {
    fun decode(op: Int): DecodedInsn = when {
        isUnconditionalBranch(op) -> decodeUnconditionalBranch(op)
        isConditionalBranch(op)   -> decodeConditionalBranch(op)
        isCompareBranch(op)       -> decodeCompareBranch(op)
        isTestBranch(op)          -> decodeTestBranch(op)
        isSystem(op)              -> decodeSystem(op)
        isRegisterBranch(op)      -> decodeRegisterBranch(op)
        isDataProcImm(op)         -> decodeDataProcImm(op)
        isDataProcReg(op)         -> decodeDataProcReg(op)
        isLoadStore(op)           -> decodeLoadStore(op)
        isDataProcSimd(op)        -> decodeDataProcSimd(op)
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

    // -----------------------------------------------------------------
    // System / hint / barrier instructions
    // All share bits 31..24 = 0b11010101 (0xD5) AND bit23=0 (non-MRS/MSR).
    // Specifically: hint (NOP/PAC/BTI) and barriers (ISB/DMB) have
    //   bits 31..24 = 0xD5, bit23=0, bit22=0, bit21=0, bit20=0, bit19=0,
    //   bits 18..16 = 011 (CRn=0011), bit12=1 (CRm subtype)
    // Rather than full field decode, use exact-match for fixed encodings
    // and a small mask for parameterised ones (DMB/ISB).
    // -----------------------------------------------------------------

    // Hint/barrier group: bits 31..12 cover all the fixed encodings we handle.
    // The shared prefix for the hint/barrier group is bits 31..22 = 0b1101010100_11
    // i.e. 0xD503_xxxx with bits 31..22 = 0b11_0101_0100 (0x354 >> ... let's just
    // match on the MSB byte and one more nybble for clarity).
    //
    // Exact encodings we need:
    //   NOP      = 0xD503201F   hints CRm=0010, op2=111
    //   ISB      = 0xD5033FDF   barriers CRm=1111, op2=110
    //   DMB      = 0xD503xxBF   barriers op2=101, option in bits[11:8]
    //   PACIASP  = 0xD503233F   hints CRm=0011, op2=111  (actually key = 0xFF)
    //   AUTIASP  = 0xD50323BF   hints CRm=0011, op2=101
    //   BTI C    = 0xD503245F   hints CRm=0010, op2=010 (BTI targets)
    //   BTI J    = 0xD503249F
    //   BTI JC   = 0xD50324DF
    //
    // All share bits[31:24]=0xD5 and bit[23]=0 and bit[22]=0 and bits[21:20]=00 and bits[19:16]=0011.
    // That is: top 20 bits (31..12) always = 0xD5030xx.  Use bits[31:20] = 0xD503 as discriminant.
    // All hint/barrier instructions share bits[31:16] = 0xD503
    // (bits[31:24]=0xD5 = MSR/hint/barrier encoding; bits[23:16]=0x03 = op1=0, CRn=3).
    private fun isSystem(op: Int): Boolean =
        (op ushr 16) and 0xFFFF == 0xD503

    private fun decodeSystem(op: Int): DecodedInsn {
        // Full exact matches first.
        return when (op) {
            0xD503201F.toInt() -> DecodedInsn("nop",     emptyList(), op)
            0xD503233F.toInt() -> DecodedInsn("paciasp", emptyList(), op)
            0xD50323BF.toInt() -> DecodedInsn("autiasp", emptyList(), op)
            0xD503245F.toInt() -> DecodedInsn("btiC",    emptyList(), op)
            0xD503249F.toInt() -> DecodedInsn("btiJ",    emptyList(), op)
            0xD50324DF.toInt() -> DecodedInsn("btiJC",   emptyList(), op)
            0xD5033FDF.toInt() -> DecodedInsn("isb",     emptyList(), op)
            else -> {
                // DMB: bits[31:12] = 0xD5033, bits[7:0] = 0xBF, option in bits[11:8].
                // Zero out the option field and compare against the DMB base template.
                val dmbBase = op and 0xFFFFF0FF.toInt()
                if (dmbBase == 0xD50330BF.toInt()) {
                    val option = (op ushr 8) and 0xF
                    val optName = when (option) {
                        0xF -> "sy"; 0xE -> "st"; 0xD -> "ld"
                        0xB -> "ish"; 0xA -> "ishst"; 0x9 -> "ishld"
                        0x7 -> "nsh"; 0x6 -> "nshst"; 0x5 -> "nshld"
                        0x3 -> "osh"; 0x2 -> "oshst"; 0x1 -> "oshld"
                        else -> "#$option"
                    }
                    DecodedInsn("dmb", listOf(Operand.Reg(optName)), op)
                } else {
                    DecodedInsn("?", listOf(Operand.Unknown(op)), op)
                }
            }
        }
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

    // -----------------------------------------------------------------
    // Data-processing — register  (bits 27..25 = 0b101)
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // Data-processing — SIMD/FP  (bits 27..25 = 0b111)
    // Covers: FP vector three-same, FP vector unary, scalar FP arithmetic,
    //         scalar FP conversions, scalar FP compare, scalar FP unary.
    // -----------------------------------------------------------------

    // bits 27..25 = 0b111 (data-processing — SIMD/FP)
    private fun isDataProcSimd(op: Int): Boolean = (op ushr 25) and 0b111 == 0b111

    private fun decodeDataProcSimd(op: Int): DecodedInsn {
        // --- Scalar FP (bits 28..24 = 11110) ---
        if ((op ushr 24) and 0x1F == 0b11110) return decodeScalarFp(op)

        // --- UXTL/SXTL (bits 28..24 = 01111) — shift-by-immediate class ---
        if ((op ushr 24) and 0x1F == 0b01111) return decodeUxtlSxtl(op)

        // Now everything below has bits 28..24 = 01110.

        // --- Dot product: bit23=1, bit22=0, bit21=0 ---
        //   0 Q U 01110 1 0 0 Rm 10010 1 Rn Rd
        //   Mask: bit31 | bits28..24 | bit23 | bit22 | bit21
        val dotMask = 0x9FE00000.toInt()
        val dotVal  = 0x0E800000.toInt()
        if ((op and dotMask) == dotVal) return decodeSimdDotProduct(op)

        // --- XTN / XTN2: bit21=1, bits20..16=00001, bits15..11=00101, bit10=0 ---
        //   0 Q 0 01110 size 1 00001 00101 0 Rn Rd
        //   Mask: bit31 | bit29 | bits28..24 | bit21 | bits20..16 | bits15..11 | bit10
        val xtnMask = 0xBF3FFE00.toInt()
        val xtnVal  = 0x0E212800.toInt()
        if ((op and xtnMask) == xtnVal) return decodeXtn(op)

        // --- DUP (general→vector): bit23=0, bit22=0, bit21=0, bits15..10=000011 ---
        //   0 Q 0 01110 0 0 imm5 000011 Rn Rd
        //   Mask: bit31 | bit29 | bits28..24 | bit23 | bit22 | bit21 | bits15..10
        val dupMask = 0xBFE0FC00.toInt()
        val dupVal  = 0x0E000C00.toInt()
        if ((op and dupMask) == dupVal) return decodeDup(op)

        // --- Vector FP unary / two-reg-misc (bits 28..24 = 01110, bit21=1,
        //     bits 17..16 = 01, bits 11..10 = 10) ---
        // Mask: bit31 | bits28..24 | bit21 | bit17 | bit16 | bits11..10
        val fpVecUnaryMask = 0x9F230C00.toInt()
        val fpVecUnaryVal  = 0x0E210800.toInt()
        if ((op and fpVecUnaryMask) == fpVecUnaryVal) return decodeFpVecUnary(op)

        // --- Three-same (bits 28..24 = 01110, bit21 = 1, bit10 = 1) ---
        // Discriminate integer vs FP three-same by the opcode field bits[15..11]:
        //   FP opcodes have opcode[4:3] = 0b11 (i.e. opcode >= 24)
        //   Integer opcodes (add/sub/mul/mla/mls/sshl/ushl/sqadd/sqsub/logical) are < 24
        val threeSameMask = 0x9F200400.toInt()
        val threeSameVal  = 0x0E200400.toInt()
        if ((op and threeSameMask) == threeSameVal) {
            val opc15_11 = (op ushr 11) and 0x1F
            return if ((opc15_11 ushr 3) and 0b11 == 0b11) decodeFpVec3(op)
                   else decodeIntVec3(op)
        }

        return DecodedInsn("?", listOf(Operand.Unknown(op)), op)
    }

    // -----------------------------------------------------------------
    // Scalar FP dispatch: bits 28..24 = 11110
    // Sub-families distinguished by bits 31..29, 21, 15..10.
    // -----------------------------------------------------------------

    private fun decodeScalarFp(op: Int): DecodedInsn {
        // Scalar FP unary two-reg-misc (frecpe/frsqrte scalar):
        //   bits 31..30 = 01, bits 20..16 = 00001, bits 11..10 = 10
        //   Mask: 0xDF3F0C00, Val: 0x5E210800
        val scUnaryMask = 0xDF3F0C00.toInt()
        val scUnaryVal  = 0x5E210800.toInt()
        if ((op and scUnaryMask) == scUnaryVal) return decodeFpScalarUnary(op)

        // FP scalar conversion (fcvtzs/scvtf GP↔FP):
        //   bits 30..29 = 00, bit21 = 1, bits 15..10 = 000000
        //   Mask: 0x7F20FC00, Val: 0x1E200000
        val scConvMask = 0x7F20FC00.toInt()
        val scConvVal  = 0x1E200000.toInt()
        if ((op and scConvMask) == scConvVal) return decodeFpScalarConv(op)

        // FP scalar compare (fcmp):
        //   bits 31..24 = 00011110, bit21 = 1, bits 15..10 = 001000
        //   Mask: 0xFF20FC00, Val: 0x1E202000
        val scCmpMask = 0xFF20FC00.toInt()
        val scCmpVal  = 0x1E202000.toInt()
        if ((op and scCmpMask) == scCmpVal) return decodeFpCmpScalar(op)

        // FP scalar conditional select (fcsel):
        //   bits 31..24 = 00011110, bit21 = 1, bits 11..10 = 11
        //   Mask: 0xFF200C00, Val: 0x1E200C00
        val scCselMask = 0xFF200C00.toInt()
        val scCselVal  = 0x1E200C00.toInt()
        if ((op and scCselMask) == scCselVal) return decodeFpCsel(op)

        // FP scalar three-register (fadd/fsub/fmul/fdiv/fminnm/fmaxnm scalar):
        //   bits 31..24 = 00011110, bit21 = 1, bits 11..10 = 10
        //   Mask: 0xFF200C00, Val: 0x1E200800
        val scThreeMask = 0xFF200C00.toInt()
        val scThreeVal  = 0x1E200800.toInt()
        if ((op and scThreeMask) == scThreeVal) return decodeFpScalar3(op)

        return DecodedInsn("?", listOf(Operand.Unknown(op)), op)
    }

    // FP scalar three-register: 00 0 11110 type 1 Rm op[15:12] 10 Rn Rd
    private fun decodeFpScalar3(op: Int): DecodedInsn {
        val type  = (op ushr 22) and 0x3   // 00=S, 01=D
        val rm    = (op ushr 16) and 0x1F
        val op4   = (op ushr 12) and 0xF   // bits 15..12
        val rn    = (op ushr  5) and 0x1F
        val rd    = op and 0x1F
        val ftype = fpTypeName(type)
        val mnem = when (op4) {
            0b0000 -> "fmul"
            0b0001 -> "fdiv"
            0b0010 -> "fadd"
            0b0011 -> "fsub"
            0b0110 -> "fmaxnm"
            0b0111 -> "fminnm"
            else   -> "?"
        }
        return DecodedInsn(
            mnem,
            listOf(
                Operand.FpReg("$ftype$rd"),
                Operand.FpReg("$ftype$rn"),
                Operand.FpReg("$ftype$rm"),
            ),
            op,
        )
    }

    // FP scalar conversion (GP↔FP): sf 0 0 11110 type 1 rmode opcode 000000 Rn Rd
    private fun decodeFpScalarConv(op: Int): DecodedInsn {
        val sf    = (op ushr 31) and 1
        val type  = (op ushr 22) and 0x3
        val rmode = (op ushr 19) and 0x3
        val opc   = (op ushr 16) and 0x7
        val rn    = (op ushr  5) and 0x1F
        val rd    = op and 0x1F
        val gpPrefix = if (sf == 1) "x" else "w"
        val fpType   = fpTypeName(type)
        return when {
            // fcvtzs: rmode=11, opcode=000 → GP(rd) = int(FP(rn))
            rmode == 0b11 && opc == 0b000 -> DecodedInsn(
                "fcvtzs",
                listOf(Operand.Reg("$gpPrefix$rd"), Operand.FpReg("$fpType$rn")),
                op,
            )
            // scvtf: rmode=00, opcode=010 → FP(rd) = float(GP(rn))
            rmode == 0b00 && opc == 0b010 -> DecodedInsn(
                "scvtf",
                listOf(Operand.FpReg("$fpType$rd"), Operand.Reg("$gpPrefix$rn")),
                op,
            )
            else -> DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
    }

    // FP scalar compare: 00 0 11110 type 1 Rm 001000 Rn opc2[4:0]
    private fun decodeFpCmpScalar(op: Int): DecodedInsn {
        val type  = (op ushr 22) and 0x3
        val rm    = (op ushr 16) and 0x1F
        val rn    = (op ushr  5) and 0x1F
        val opc2  = op and 0x1F   // bit3=1 → compare with #0.0
        val fpType = fpTypeName(type)
        return if ((opc2 and 0b01000) != 0) {
            DecodedInsn("fcmp", listOf(Operand.FpReg("$fpType$rn")), op)
        } else {
            DecodedInsn("fcmp", listOf(Operand.FpReg("$fpType$rn"), Operand.FpReg("$fpType$rm")), op)
        }
    }

    // FP conditional select: 00 0 11110 type 1 Rm cond 11 Rn Rd
    private fun decodeFpCsel(op: Int): DecodedInsn {
        val type = (op ushr 22) and 0x3
        val rm   = (op ushr 16) and 0x1F
        val cond = (op ushr 12) and 0xF
        val rn   = (op ushr  5) and 0x1F
        val rd   = op and 0x1F
        val fpType = fpTypeName(type)
        return DecodedInsn(
            "fcsel",
            listOf(
                Operand.FpReg("$fpType$rd"),
                Operand.FpReg("$fpType$rn"),
                Operand.FpReg("$fpType$rm"),
                Operand.CondCode(condName(cond)),
            ),
            op,
        )
    }

    // Scalar FP unary (frecpe/frsqrte scalar):
    //   01 U 11110 a sz 1 00001 opcode 10 Rn Rd
    private fun decodeFpScalarUnary(op: Int): DecodedInsn {
        val u      = (op ushr 29) and 1
        val sz     = (op ushr 22) and 1   // 0=S, 1=D
        val opcode = (op ushr 12) and 0xF
        val rn     = (op ushr  5) and 0x1F
        val rd     = op and 0x1F
        val fpType = if (sz == 0) "s" else "d"
        val mnem = when (Pair(opcode, u)) {
            0b1101 to 0 -> "frecpe"
            0b1101 to 1 -> "frsqrte"
            else -> "?"
        }
        return DecodedInsn(mnem, listOf(Operand.FpReg("$fpType$rd"), Operand.FpReg("$fpType$rn")), op)
    }

    // -----------------------------------------------------------------
    // Vector FP three-same: 0 Q U 01110 sz 1 Rm opcode 1 Rn Rd
    //   bits 28..24 = 01110, bit21 = 1, bit10 = 1
    //   FP opcodes always have opcode[4:3] = 0b11 (i.e. opcode >= 24 = 0b11000)
    // -----------------------------------------------------------------

    private fun decodeFpVec3(op: Int): DecodedInsn {
        val q      = (op ushr 30) and 1
        val u      = (op ushr 29) and 1
        val sz     = (op ushr 22) and 0x3   // bits 23..22
        val opcode = (op ushr 11) and 0x1F  // bits 15..11
        val rm     = (op ushr 16) and 0x1F
        val rn     = (op ushr  5) and 0x1F
        val rd     = op and 0x1F

        // Non-FP opcodes (opcode bits[4:3] != 11) pass through as Unknown.
        if ((opcode ushr 3) and 0b11 != 0b11) {
            return DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }

        // sz[0] selects S vs D; sz[1] selects the "neg" variant (sub/mls/min/minnm etc.)
        val szLow  = sz and 1        // 0 = single, 1 = double
        val szHigh = (sz ushr 1) and 1

        val arr = if (szLow == 0) (if (q == 1) Arm64.VArr.S4 else Arm64.VArr.S2)
                  else Arm64.VArr.D2

        val mnem = when (Triple(opcode, u, szHigh)) {
            // FADD: u=0, szH=0; FSUB: u=0, szH=1
            Triple(0b11010, 0, 0) -> "fadd"
            Triple(0b11010, 0, 1) -> "fsub"
            // FMUL: u=1, szH=0
            Triple(0b11011, 1, 0) -> "fmul"
            // FDIV: u=1, szH=0
            Triple(0b11111, 1, 0) -> "fdiv"
            // FMLA: u=0, szH=0; FMLS: u=0, szH=1
            Triple(0b11001, 0, 0) -> "fmla"
            Triple(0b11001, 0, 1) -> "fmls"
            // FMAX: u=0, szH=0; FMIN: u=0, szH=1
            Triple(0b11110, 0, 0) -> "fmax"
            Triple(0b11110, 0, 1) -> "fmin"
            // FMAXNM: u=0, szH=0; FMINNM: u=0, szH=1
            Triple(0b11000, 0, 0) -> "fmaxnm"
            Triple(0b11000, 0, 1) -> "fminnm"
            // FRECPS: u=0, szH=0; FRSQRTS: u=0, szH=1
            Triple(0b11111, 0, 0) -> "frecps"
            Triple(0b11111, 0, 1) -> "frsqrts"
            // FCMEQ: u=0, szH=0; FCMGE: u=1, szH=0; FCMGT: u=1, szH=1
            Triple(0b11100, 0, 0) -> "fcmeq"
            Triple(0b11100, 1, 0) -> "fcmge"
            Triple(0b11100, 1, 1) -> "fcmgt"
            else -> "?"
        }
        return DecodedInsn(
            mnem,
            listOf(
                Operand.VecReg("v$rd", arr),
                Operand.VecReg("v$rn", arr),
                Operand.VecReg("v$rm", arr),
            ),
            op,
        )
    }

    // -----------------------------------------------------------------
    // Vector FP unary two-reg misc: 0 Q U 01110 a sz 1 00001 opcode 10 Rn Rd
    //   bits 28..24 = 01110, bit21 = 1, bits 17..16 = 01, bits 11..10 = 10
    // -----------------------------------------------------------------

    private fun decodeFpVecUnary(op: Int): DecodedInsn {
        val q      = (op ushr 30) and 1
        val u      = (op ushr 29) and 1
        val a      = (op ushr 23) and 1   // bit23
        val sz     = (op ushr 22) and 1   // bit22: 0=S, 1=D
        val opcode = (op ushr 12) and 0xF // bits 15..12
        val rn     = (op ushr  5) and 0x1F
        val rd     = op and 0x1F
        val arr = if (sz == 0) (if (q == 1) Arm64.VArr.S4 else Arm64.VArr.S2)
                  else Arm64.VArr.D2
        val mnem = when (Triple(opcode, u, a)) {
            // FRECPE: u=0, a=1, opcode=1101
            Triple(0b1101, 0, 1) -> "frecpe"
            // FRSQRTE: u=1, a=1, opcode=1101
            Triple(0b1101, 1, 1) -> "frsqrte"
            // SCVTF: u=0, a=0, opcode=1101
            Triple(0b1101, 0, 0) -> "scvtf"
            // FCVTZS: u=0, a=1, opcode=1011
            Triple(0b1011, 0, 1) -> "fcvtzs"
            else -> "?"
        }
        return DecodedInsn(
            mnem,
            listOf(
                Operand.VecReg("v$rd", arr),
                Operand.VecReg("v$rn", arr),
            ),
            op,
        )
    }

    // -----------------------------------------------------------------
    // Integer three-same: 0 Q U 01110 size 1 Rm opcode 1 Rn Rd
    //   Covers: add/sub/mul/mla/mls/sshl/ushl/sqadd/sqsub + SIMD logical
    //   Dispatched here when opcode[4:3] != 0b11 (non-FP opcodes).
    // -----------------------------------------------------------------

    private fun decodeIntVec3(op: Int): DecodedInsn {
        val q      = (op ushr 30) and 1
        val u      = (op ushr 29) and 1
        val size   = (op ushr 22) and 0x3   // bits 23..22
        val opcode = (op ushr 11) and 0x1F  // bits 15..11
        val rm     = (op ushr 16) and 0x1F
        val rn     = (op ushr  5) and 0x1F
        val rd     = op and 0x1F
        val arr    = vArrFor(q, size)

        // SIMD logical: opcode=00011, size encodes op variant
        //   AND: U=0, size=00; ORR: U=0, size=10; EOR: U=1, size=00; BIC: U=0, size=01
        if (opcode == 0b00011) {
            val logArr = Arm64.VArr.B16  // always .16b / .8b (q selects which)
            val logArrFull = if (q == 1) Arm64.VArr.B16 else Arm64.VArr.B8
            val mnem = when {
                u == 0 && size == 0b00 -> "and"
                u == 0 && size == 0b10 -> "orr"
                u == 1 && size == 0b00 -> "eor"
                u == 0 && size == 0b01 -> "bic"
                else -> "?"
            }
            return DecodedInsn(
                mnem,
                listOf(
                    Operand.VecReg("v$rd", logArrFull),
                    Operand.VecReg("v$rn", logArrFull),
                    Operand.VecReg("v$rm", logArrFull),
                ),
                op,
            )
        }

        val mnem = when (Triple(opcode, u, 0)) {
            Triple(0b10000, 0, 0) -> "add"
            Triple(0b10000, 1, 0) -> "sub"
            Triple(0b10011, 0, 0) -> "mul"
            Triple(0b10010, 0, 0) -> "mla"
            Triple(0b10010, 1, 0) -> "mls"
            Triple(0b01000, 0, 0) -> "sshl"
            Triple(0b01000, 1, 0) -> "ushl"
            Triple(0b00001, 0, 0) -> "sqadd"
            Triple(0b00001, 1, 0) -> "uqadd"
            Triple(0b00101, 0, 0) -> "sqsub"
            Triple(0b00101, 1, 0) -> "uqsub"
            Triple(0b10110, 0, 0) -> "sqdmulh"
            else -> return DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
        return DecodedInsn(
            mnem,
            listOf(
                Operand.VecReg("v$rd", arr),
                Operand.VecReg("v$rn", arr),
                Operand.VecReg("v$rm", arr),
            ),
            op,
        )
    }

    // -----------------------------------------------------------------
    // Dot product: 0 Q U 01110 1 0 0 Rm 10010 1 Rn Rd
    //   SDOT: U=0; UDOT: U=1
    //   arr (S2 or S4) selects q; source lanes are B8/B16
    // -----------------------------------------------------------------

    private fun decodeSimdDotProduct(op: Int): DecodedInsn {
        val q  = (op ushr 30) and 1
        val u  = (op ushr 29) and 1
        val rm = (op ushr 16) and 0x1F
        val rn = (op ushr  5) and 0x1F
        val rd = op and 0x1F
        val mnem   = if (u == 0) "sdot" else "udot"
        val dstArr = if (q == 1) Arm64.VArr.S4 else Arm64.VArr.S2    // dest: .2s/.4s
        val srcArr = if (q == 1) Arm64.VArr.B16 else Arm64.VArr.B8   // src: .8b/.16b
        return DecodedInsn(
            mnem,
            listOf(
                Operand.VecReg("v$rd", dstArr),
                Operand.VecReg("v$rn", srcArr),
                Operand.VecReg("v$rm", srcArr),
            ),
            op,
        )
    }

    // -----------------------------------------------------------------
    // DUP (general-register → vector): 0 Q 0 01110 0 0 imm5 000011 Rn Rd
    //   imm5 lowest set bit encodes element size: bit0=B, bit1=H, bit2=S, bit3=D
    // -----------------------------------------------------------------

    private fun decodeDup(op: Int): DecodedInsn {
        val q    = (op ushr 30) and 1
        val imm5 = (op ushr 16) and 0x1F
        val rn   = (op ushr  5) and 0x1F
        val rd   = op and 0x1F
        // size = number of trailing zeros in imm5
        val size = Integer.numberOfTrailingZeros(imm5)
        val arr  = vArrFor(q, size)
        // source is always a W register (the general-purpose scalar)
        val rnName = "w$rn"
        return DecodedInsn(
            "dup",
            listOf(Operand.VecReg("v$rd", arr), Operand.Reg(rnName)),
            op,
        )
    }

    // -----------------------------------------------------------------
    // XTN / XTN2: 0 Q 0 01110 size 100001 00101 0 Rn Rd
    //   destArr is the narrow result; source is implicitly dest doubled.
    //   XTN (q=0) writes lower half; XTN2 (q=1) writes upper half.
    //   size field encodes the destination element size:
    //     0 -> .8b/.16b;  1 -> .4h/.8h;  2 -> .2s/.4s
    // -----------------------------------------------------------------

    private fun decodeXtn(op: Int): DecodedInsn {
        val q    = (op ushr 30) and 1
        val size = (op ushr 22) and 0x3
        val rn   = (op ushr  5) and 0x1F
        val rd   = op and 0x1F
        // destination arrangement (narrow result)
        val destArr = vArrFor(q, size)
        // source arrangement: XTN/XTN2 source is always 128-bit (Q=1), element size doubled.
        // ARM ARM C7.2.382: source operand is always the full 128-bit vector register.
        val srcArr  = vArrFor(1, size + 1)
        val mnem = if (q == 0) "xtn" else "xtn2"
        return DecodedInsn(
            mnem,
            listOf(Operand.VecReg("v$rd", destArr), Operand.VecReg("v$rn", srcArr)),
            op,
        )
    }

    // -----------------------------------------------------------------
    // UXTL / SXTL: 0 Q U 01111 immh imml 101001 Rn Rd
    //   U=1 → UXTL; U=0 → SXTL
    //   immh encodes the source arrangement size:
    //     0001 → srcSize=0 (.8b/.16b); 0010 → srcSize=1 (.4h/.8h);
    //     0100 → srcSize=2 (.2s/.4s)
    //   destination arrangement is one size wider than source, q determines lane count.
    // -----------------------------------------------------------------

    private fun decodeUxtlSxtl(op: Int): DecodedInsn {
        val q    = (op ushr 30) and 1
        val u    = (op ushr 29) and 1
        val immh = (op ushr 19) and 0xF   // bits 22..19
        val rn   = (op ushr  5) and 0x1F
        val rd   = op and 0x1F
        // immh highest set bit determines source element size
        val srcSize = when {
            immh and 0b1000 != 0 -> 3   // D — shouldn't occur for uxtl
            immh and 0b0100 != 0 -> 2   // S
            immh and 0b0010 != 0 -> 1   // H
            else                 -> 0   // B
        }
        val mnem   = if (u == 1) "uxtl" else "sxtl"
        val srcArr = vArrFor(q, srcSize)          // source narrow arrangement
        val dstArr = vArrFor(1, srcSize + 1)      // dest is one size wider; q=1 (full 128b)
        return DecodedInsn(
            mnem,
            listOf(Operand.VecReg("v$rd", dstArr), Operand.VecReg("v$rn", srcArr)),
            op,
        )
    }

    // -----------------------------------------------------------------
    // Data-processing — register  (bits 27..25 = 0b101)
    // -----------------------------------------------------------------

    // bits 27..25 = 0b101 (data-processing — register)
    private fun isDataProcReg(op: Int): Boolean = (op ushr 25) and 0b111 == 0b101

    private fun decodeDataProcReg(op: Int): DecodedInsn {
        val op1 = (op ushr 28) and 1
        val op2 = (op ushr 21) and 0xF
        return when {
            op1 == 0 && (op2 and 0b1000) == 0      -> decodeLogicalShiftedReg(op)
            op1 == 0 && (op2 and 0b1001) == 0b1000 -> decodeAddSubShiftedReg(op)
            op1 == 1 && op2 == 0b0110              -> decodeDataProc2Source(op)
            op1 == 1 && (op2 and 0b1000) == 0b1000 -> decodeDataProc3Source(op)
            op1 == 1 && op2 == 0b0100              -> decodeCondSelect(op)
            else -> DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
    }

    // Logical shifted register: sf opc 01010 shift N Rm imm6 Rn Rd
    // opc: 00=and 01=orr 10=eor 11=ands; N inverts to bic/orn/eon/bics
    private fun decodeLogicalShiftedReg(op: Int): DecodedInsn {
        val sf    = (op ushr 31) and 1
        val opc   = (op ushr 29) and 0b11
        val shift = (op ushr 22) and 0b11
        val n     = (op ushr 21) and 1
        val rm    = (op ushr 16) and 0x1F
        val imm6  = (op ushr 10) and 0x3F
        val rn    = (op ushr  5) and 0x1F
        val rd    = op and 0x1F
        val reg    = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        fun regName(n: Int) = if (n == 31) zrName else "$reg$n"

        val baseMnem = when (opc) { 0b00 -> "and"; 0b01 -> "orr"; 0b10 -> "eor"; else -> "ands" }
        val mnem = if (n == 1) when (baseMnem) {
            "and" -> "bic"; "orr" -> "orn"; "eor" -> "eon"; else -> "bics"
        } else baseMnem

        // mov Rd, Rm = orr Rd, xzr, Rm (opc=01, n=0, rn=31, no shift)
        if (mnem == "orr" && rn == 31 && shift == 0 && imm6 == 0) {
            return DecodedInsn("mov", listOf(Operand.Reg(regName(rd)), Operand.Reg(regName(rm))), op)
        }
        // tst Rn, Rm = ands xzr, Rn, Rm (opc=11, n=0, rd=31)
        if (mnem == "ands" && rd == 31) {
            return DecodedInsn("tst", listOf(Operand.Reg(regName(rn)), Operand.Reg(regName(rm))), op)
        }
        // mvn Rd, Rm = orn Rd, xzr, Rm (opc=01, n=1, rn=31, no shift)
        if (mnem == "orn" && rn == 31 && shift == 0 && imm6 == 0) {
            return DecodedInsn("mvn", listOf(Operand.Reg(regName(rd)), Operand.Reg(regName(rm))), op)
        }

        val ops = mutableListOf<Operand>(
            Operand.Reg(regName(rd)),
            Operand.Reg(regName(rn)),
            Operand.Reg(regName(rm)),
        )
        if (imm6 != 0) ops += Operand.Imm(imm6.toLong(), ImmFormat.SHIFT_AMOUNT)
        return DecodedInsn(mnem, ops, op)
    }

    // Add/sub shifted register: sf op S 01011 shift 0 Rm imm6 Rn Rd
    // op: 0=add, 1=sub; S: set flags
    // Aliases: subs xzr → cmp; sub Rd, xzr, Rm → neg
    private fun decodeAddSubShiftedReg(op: Int): DecodedInsn {
        val sf    = (op ushr 31) and 1
        val opc   = (op ushr 30) and 1   // 0=add, 1=sub
        val s     = (op ushr 29) and 1   // set flags
        val shift = (op ushr 22) and 0b11
        val rm    = (op ushr 16) and 0x1F
        val imm6  = (op ushr 10) and 0x3F
        val rn    = (op ushr  5) and 0x1F
        val rd    = op and 0x1F
        val reg    = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        fun regName(n: Int) = if (n == 31) zrName else "$reg$n"

        // cmp Rn, Rm = subs xzr, Rn, Rm (op=1, s=1, rd=31)
        if (opc == 1 && s == 1 && rd == 31) {
            return DecodedInsn("cmp", listOf(Operand.Reg(regName(rn)), Operand.Reg(regName(rm))), op)
        }
        // neg Rd, Rm = sub Rd, xzr, Rm (op=1, s=0, rn=31)
        if (opc == 1 && s == 0 && rn == 31) {
            return DecodedInsn("neg", listOf(Operand.Reg(regName(rd)), Operand.Reg(regName(rm))), op)
        }

        val mnem = when {
            opc == 0 && s == 0 -> "add"
            opc == 0 && s == 1 -> "adds"
            opc == 1 && s == 0 -> "sub"
            else               -> "subs"
        }
        val ops = mutableListOf<Operand>(
            Operand.Reg(regName(rd)),
            Operand.Reg(regName(rn)),
            Operand.Reg(regName(rm)),
        )
        if (imm6 != 0) ops += Operand.Imm(imm6.toLong(), ImmFormat.SHIFT_AMOUNT)
        return DecodedInsn(mnem, ops, op)
    }

    // Data-processing 2-source: sf 0 0 11010110 Rm opcode Rn Rd
    // Covers: udiv/sdiv (opcode 00001[01]), lsl-r/lsr-r/asr-r (opcode 00100[8/9/10])
    private fun decodeDataProc2Source(op: Int): DecodedInsn {
        val sf     = (op ushr 31) and 1
        val rm     = (op ushr 16) and 0x1F
        val opcode = (op ushr 10) and 0x3F   // bits 15..10
        val rn     = (op ushr  5) and 0x1F
        val rd     = op and 0x1F
        val reg    = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        fun regName(n: Int) = if (n == 31) zrName else "$reg$n"

        val mnem = when (opcode) {
            0b000010 -> "udiv"
            0b000011 -> "sdiv"
            0b001000 -> "lsl"
            0b001001 -> "lsr"
            0b001010 -> "asr"
            else     -> return DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
        return DecodedInsn(
            mnem,
            listOf(Operand.Reg(regName(rd)), Operand.Reg(regName(rn)), Operand.Reg(regName(rm))),
            op,
        )
    }

    // Data-processing 3-source: sf 00 11011 opc Rm o0 Ra Rn Rd
    // madd: opc=000, o0=0; msub: opc=000, o0=1
    // mul = madd with Ra=xzr
    private fun decodeDataProc3Source(op: Int): DecodedInsn {
        val sf  = (op ushr 31) and 1
        val opc = (op ushr 21) and 0b111   // bits 23..21
        val rm  = (op ushr 16) and 0x1F
        val o0  = (op ushr 15) and 1
        val ra  = (op ushr 10) and 0x1F
        val rn  = (op ushr  5) and 0x1F
        val rd  = op and 0x1F
        val reg    = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        fun regName(n: Int) = if (n == 31) zrName else "$reg$n"

        return when {
            opc == 0b000 && o0 == 0 && ra == 31 ->
                // mul Rd, Rn, Rm = madd Rd, Rn, Rm, xzr
                DecodedInsn("mul", listOf(Operand.Reg(regName(rd)), Operand.Reg(regName(rn)), Operand.Reg(regName(rm))), op)
            opc == 0b000 && o0 == 0 ->
                // madd Rd, Rn, Rm, Ra
                DecodedInsn("madd", listOf(Operand.Reg(regName(rd)), Operand.Reg(regName(rn)), Operand.Reg(regName(rm)), Operand.Reg(regName(ra))), op)
            opc == 0b000 && o0 == 1 && ra == 31 ->
                // mneg Rd, Rn, Rm = msub Rd, Rn, Rm, xzr
                DecodedInsn("mneg", listOf(Operand.Reg(regName(rd)), Operand.Reg(regName(rn)), Operand.Reg(regName(rm))), op)
            opc == 0b000 && o0 == 1 ->
                // msub Rd, Rn, Rm, Ra
                DecodedInsn("msub", listOf(Operand.Reg(regName(rd)), Operand.Reg(regName(rn)), Operand.Reg(regName(rm)), Operand.Reg(regName(ra))), op)
            else -> DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
    }

    // Conditional select: sf op S 11010100 Rm cond 0 op2 Rn Rd
    // op=0/op2=0: csel; op=0/op2=1: csinc; op=1/op2=0: csinv; op=1/op2=1: csneg
    // Alias: cset Rd, cond = csinc Rd, xzr, xzr, invert(cond)
    private fun decodeCondSelect(op: Int): DecodedInsn {
        val sf   = (op ushr 31) and 1
        val opc  = (op ushr 30) and 1   // op bit
        val rm   = (op ushr 16) and 0x1F
        val cond = (op ushr 12) and 0xF
        val op2  = (op ushr 10) and 1
        val rn   = (op ushr  5) and 0x1F
        val rd   = op and 0x1F
        val reg    = if (sf == 1) "x" else "w"
        val zrName = if (sf == 1) "xzr" else "wzr"
        fun regName(n: Int) = if (n == 31) zrName else "$reg$n"

        val mnem = when {
            opc == 0 && op2 == 0 -> "csel"
            opc == 0 && op2 == 1 -> "csinc"
            opc == 1 && op2 == 0 -> "csinv"
            else                 -> "csneg"
        }

        // cset Rd, cond = csinc Rd, xzr, xzr, invert(cond)
        if (mnem == "csinc" && rn == 31 && rm == 31) {
            val invertedCond = cond xor 1   // invert LSB to get opposite condition
            return DecodedInsn(
                "cset",
                listOf(Operand.Reg(regName(rd)), Operand.CondCode(condName(invertedCond))),
                op,
            )
        }

        return DecodedInsn(
            mnem,
            listOf(
                Operand.Reg(regName(rd)),
                Operand.Reg(regName(rn)),
                Operand.Reg(regName(rm)),
                Operand.CondCode(condName(cond)),
            ),
            op,
        )
    }

    // -----------------------------------------------------------------
    // Load/Store family
    // Three structurally distinct sub-encodings dispatched here.
    // -----------------------------------------------------------------

    private fun isLoadStore(op: Int): Boolean =
        isAdvSimdLoadStoreMulti(op) || isLoadStoreRegImm(op) || isLoadStorePair(op)

    private fun decodeLoadStore(op: Int): DecodedInsn = when {
        isAdvSimdLoadStoreMulti(op) -> decodeAdvSimdLoadStoreMulti(op)
        isLoadStorePair(op)         -> decodeLoadStorePair(op)
        isLoadStoreRegImm(op)       -> decodeLoadStoreRegImm(op)
        else -> DecodedInsn("?", listOf(Operand.Unknown(op)), op)
    }

    // -----------------------------------------------------------------
    // SIMD multi-structure load/store (LD1/ST1 one register, all lanes)
    //   0 Q 0011000 L 000000 opcode size Rn Rt   (bits[29:23] = 0011000)
    //
    // LD1R (replicate one element to all lanes):
    //   0 Q 0011010 1 000000 110 0 size Rn Rt     (bits[29:23] = 0011010)
    // -----------------------------------------------------------------

    // bits[29:23] = 0011000 (0x18)  — ld1/st1 multi-struct
    private fun isAdvSimdLoadStoreMultiOpc(op: Int): Boolean =
        (op ushr 23) and 0x7F == 0b0011000

    // bits[29:23] = 0011010 (0x1A)  — ld1r replicate
    private fun isAdvSimdLoadStoreSingleReplicate(op: Int): Boolean =
        (op ushr 23) and 0x7F == 0b0011010

    private fun isAdvSimdLoadStoreMulti(op: Int): Boolean =
        isAdvSimdLoadStoreMultiOpc(op) || isAdvSimdLoadStoreSingleReplicate(op)

    private fun decodeAdvSimdLoadStoreMulti(op: Int): DecodedInsn {
        val q    = (op ushr 30) and 1
        val l    = (op ushr 22) and 1
        val size = (op ushr 10) and 0b11
        val rn   = (op ushr  5) and 0x1F
        val rt   = op and 0x1F
        val arr  = vArrFor(q, size)
        val rnName = if (rn == 31) "sp" else "x$rn"
        val memAddr = Operand.MemAddr(Operand.Reg(rnName), null)
        return if (isAdvSimdLoadStoreSingleReplicate(op)) {
            // LD1R — load and replicate
            DecodedInsn(
                "ld1r",
                listOf(Operand.VecReg("v$rt", arr), memAddr),
                op,
            )
        } else {
            val mnem = if (l == 1) "ld1" else "st1"
            DecodedInsn(
                mnem,
                listOf(Operand.VecReg("v$rt", arr), memAddr),
                op,
            )
        }
    }

    private fun vArrFor(q: Int, size: Int): Arm64.VArr = when {
        q == 0 && size == 0 -> Arm64.VArr.B8;  q == 1 && size == 0 -> Arm64.VArr.B16
        q == 0 && size == 1 -> Arm64.VArr.H4;  q == 1 && size == 1 -> Arm64.VArr.H8
        q == 0 && size == 2 -> Arm64.VArr.S2;  q == 1 && size == 2 -> Arm64.VArr.S4
        q == 0 && size == 3 -> Arm64.VArr.D1;  else                 -> Arm64.VArr.D2
    }

    // -----------------------------------------------------------------
    // GP load/store — register immediate
    // Unsigned-offset:   size 111 0 01 opc imm12 Rn Rt   (bit24=1)
    // Pre/post-indexed:  size 111 0 00 opc 0 imm9 idx Rn Rt  (bit24=0, bit21=0)
    //
    // bit[24]=1 → unsigned-offset (imm12 in bits[21:10])
    // bit[24]=0, bit[21]=0 → pre/post-index (imm9 in bits[20:12], idx in bits[11:10])
    //   idx=0b11 = pre-indexed, idx=0b01 = post-indexed
    // -----------------------------------------------------------------

    // bits[29:27] = 111, bit[26] = 0 → GP load/store register
    private fun isLoadStoreRegImm(op: Int): Boolean =
        (op ushr 27) and 0b111 == 0b111 && (op ushr 26) and 1 == 0

    private fun decodeLoadStoreRegImm(op: Int): DecodedInsn {
        val size  = (op ushr 30) and 0b11   // 00=byte, 01=half, 10=word, 11=double
        val opc   = (op ushr 22) and 0b11   // 01=load, 00=store
        val bit24 = (op ushr 24) and 1
        val bit21 = (op ushr 21) and 1
        val rn    = (op ushr  5) and 0x1F
        val rt    = op and 0x1F

        val isLoad = opc == 0b01

        // Register name: for memory base, reg 31 = SP
        val rnName = if (rn == 31) "sp" else "x$rn"

        // Destination/source register naming depends on size
        val rtName = when (size) {
            0b11 -> "x$rt"  // double-word → X register
            else -> "w$rt"  // byte/half/word → W register
        }

        val mnem = when {
            size == 0b11 && isLoad  -> "ldr"
            size == 0b11 && !isLoad -> "str"
            size == 0b10 && isLoad  -> "ldr"
            size == 0b10 && !isLoad -> "str"
            size == 0b01 && isLoad  -> "ldrh"
            size == 0b01 && !isLoad -> "strh"
            size == 0b00 && isLoad  -> "ldrb"
            else                    -> "strb"
        }

        return if (bit24 == 1) {
            // Unsigned-offset: imm12 in bits[21:10], scaled by access size
            val imm12 = (op ushr 10) and 0xFFF
            val scale = size     // byte=0, half=1, word=2, double=3
            val byteOffset = imm12 shl scale
            val offsetOp = if (byteOffset == 0) null
                           else Operand.Imm(byteOffset.toLong(), ImmFormat.DEC)
            DecodedInsn(
                mnem,
                listOf(Operand.Reg(rtName), Operand.MemAddr(Operand.Reg(rnName), offsetOp)),
                op,
            )
        } else if (bit21 == 0) {
            // Pre/post-indexed: imm9 in bits[20:12] (signed), idx in bits[11:10]
            val imm9raw = (op ushr 12) and 0x1FF
            val imm9    = signExtend(imm9raw, 9)
            val idx     = (op ushr 10) and 0b11
            val mode = when (idx) {
                0b11 -> AddrMode.PRE_INDEXED
                0b01 -> AddrMode.POST_INDEXED
                else -> AddrMode.OFFSET   // unscaled (LDUR/STUR) — treat as offset
            }
            val offsetOp = if (imm9 == 0) null else Operand.Imm(imm9.toLong(), ImmFormat.DEC)
            DecodedInsn(
                mnem,
                listOf(Operand.Reg(rtName), Operand.MemAddr(Operand.Reg(rnName), offsetOp, mode)),
                op,
            )
        } else {
            // Register-offset (bit21=1) — not in scope for this task
            DecodedInsn("?", listOf(Operand.Unknown(op)), op)
        }
    }

    // -----------------------------------------------------------------
    // Load/store pair (LDP/STP)
    //   opc 101 V mode L imm7 Rt2 Rn Rt
    //   bits[29:27] = 101
    //   mode (bits[25:23]): 010=signed-offset, 011=pre-indexed, 001=post-indexed
    //
    // opc[31:30] + V[26]:
    //   00 V=0 → W register pair, scale=2
    //   10 V=0 → X register pair, scale=3
    //   10 V=1 → Q register pair (SIMD 128-bit), scale=4
    // -----------------------------------------------------------------

    // Load/store pair: bits 29..27 = 101 AND bit25 = 0 (mode bits 25..23 ∈ {001,010,011}).
    // Bit25 distinguishes from SIMD data-processing (fpVec3 uses bit25=1).
    private fun isLoadStorePair(op: Int): Boolean =
        (op ushr 27) and 0b111 == 0b101 && (op ushr 25) and 1 == 0

    private fun decodeLoadStorePair(op: Int): DecodedInsn {
        val opc  = (op ushr 30) and 0b11
        val v    = (op ushr 26) and 1
        val mode3 = (op ushr 23) and 0b111  // bits[25:23]
        val l    = (op ushr 22) and 1       // 1=load, 0=store
        val imm7raw = (op ushr 15) and 0x7F
        val imm7 = signExtend(imm7raw, 7)
        val rt2  = (op ushr 10) and 0x1F
        val rn   = (op ushr  5) and 0x1F
        val rt   = op and 0x1F

        val mnem = if (l == 1) "ldp" else "stp"

        // Address mode from bits[25:23]
        val addrMode = when (mode3) {
            0b010 -> AddrMode.OFFSET
            0b011 -> AddrMode.PRE_INDEXED
            0b001 -> AddrMode.POST_INDEXED
            else  -> AddrMode.OFFSET   // fallback
        }

        val rnName = if (rn == 31) "sp" else "x$rn"

        // Determine scale, register names, and operand kind from opc + V.
        //   V=0, opc=00 → W pair, scale=2
        //   V=0, opc=10 → X pair, scale=3
        //   V=1, opc=00 → S pair (32-bit SIMD), scale=2
        //   V=1, opc=01 → D pair (64-bit SIMD), scale=3
        //   V=1, opc=10 → Q pair (128-bit SIMD), scale=4
        val scale = when {
            v == 1 && opc == 0b10 -> 4
            v == 1 && opc == 0b01 -> 3
            v == 1              -> 2   // opc=00 → S
            opc == 0b10         -> 3   // V=0, X regs
            else                -> 2   // V=0, W regs
        }

        val byteOffset = imm7 shl scale
        val offsetOp = if (byteOffset == 0 && addrMode == AddrMode.OFFSET) null
                       else Operand.Imm(byteOffset.toLong(), ImmFormat.DEC)

        val rtOperands: List<Operand> = when {
            v == 1 -> {
                val simdPrefix = when (opc) {
                    0b10 -> "q"
                    0b01 -> "d"
                    else -> "s"   // opc=00
                }
                listOf(
                    Operand.VecReg("$simdPrefix$rt",  null),
                    Operand.VecReg("$simdPrefix$rt2", null),
                )
            }
            opc == 0b10 -> listOf(Operand.Reg("x$rt"), Operand.Reg("x$rt2"))
            else        -> listOf(Operand.Reg("w$rt"), Operand.Reg("w$rt2"))
        }

        return DecodedInsn(
            mnem,
            rtOperands + Operand.MemAddr(Operand.Reg(rnName), offsetOp, addrMode),
            op,
        )
    }

    private fun signExtend(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }

    /** Map ARM-ARM `type` field (00=S, 01=D, 1x=H) to FP register prefix. */
    private fun fpTypeName(type: Int): String = when (type) {
        0 -> "s"
        1 -> "d"
        else -> "h"
    }

    private fun condName(cond: Int): String = when (cond) {
        0 -> "eq"; 1 -> "ne"; 2 -> "cs"; 3 -> "cc"
        4 -> "mi"; 5 -> "pl"; 6 -> "vs"; 7 -> "vc"
        8 -> "hi"; 9 -> "ls"; 10 -> "ge"; 11 -> "lt"
        12 -> "gt"; 13 -> "le"; 14 -> "al"; 15 -> "nv"
        else -> error("invalid cond $cond")
    }
}
