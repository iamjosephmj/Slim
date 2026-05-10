package io.simdkt.slim

import io.simdkt.nativekt.engine.AddrMode
import io.simdkt.nativekt.engine.Arm64
import io.simdkt.nativekt.engine.Arm64Decoder
import io.simdkt.nativekt.engine.DecodedInsn
import io.simdkt.nativekt.engine.ImmFormat
import io.simdkt.nativekt.engine.KernelMetadata
import io.simdkt.nativekt.engine.Operand
import io.simdkt.nativekt.engine.SourceFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Disassembler {

    fun format(bytes: ByteArray, metadata: KernelMetadata): String {
        require(bytes.size % 4 == 0) { "kernel byte length must be multiple of 4" }
        val sb = StringBuilder()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var offset = 0
        while (offset < bytes.size) {
            metadata.labelNames[offset]?.let { sb.append(it).append(":\n") }
            val opcode = buf.getInt(offset)
            val insn = Arm64Decoder.decode(opcode)
            sb.append(formatLine(offset, opcode, insn, metadata.sourceFrames[offset], metadata.labelNames))
            sb.append('\n')
            offset += 4
        }
        return sb.toString()
    }

    private fun formatLine(
        offset: Int,
        opcode: Int,
        insn: DecodedInsn,
        source: SourceFrame?,
        labels: Map<Int, String>,
    ): String {
        val operandStr = renderOperands(insn, offset, labels)
        val sourceStr = source?.let { "  // ${it.file}:${it.line}" } ?: ""
        return "  %04x  %08x  %-6s %-30s%s".format(offset, opcode, insn.mnemonic, operandStr, sourceStr)
    }

    private fun renderOperands(insn: DecodedInsn, siteOffset: Int, labels: Map<Int, String>): String =
        insn.operands.joinToString(", ") { renderOperand(it, siteOffset, labels) }

    private fun renderOperand(op: Operand, siteOffset: Int, labels: Map<Int, String>): String = when (op) {
        is Operand.Reg          -> op.name
        is Operand.VecReg       -> if (op.arr != null) "${op.name}.${op.arr.lowerSuffix()}" else op.name
        is Operand.FpReg        -> op.name
        is Operand.Imm          -> formatImm(op)
        is Operand.BranchOffset -> {
            val target = siteOffset + op.byteOffset
            labels[target] ?: signedHex(op.byteOffset)
        }
        is Operand.CondCode     -> op.name
        is Operand.Label        -> op.name
        is Operand.MemAddr      -> formatMemAddr(op, siteOffset, labels)
        is Operand.Unknown      -> "0x%08x".format(op.raw)
    }

    private fun formatMemAddr(op: Operand.MemAddr, siteOffset: Int, labels: Map<Int, String>): String {
        val baseStr = renderOperand(op.base, siteOffset, labels)
        val offStr = op.offset?.let { ", " + formatImm(it) } ?: ""
        return when (op.mode) {
            AddrMode.OFFSET        -> "[$baseStr$offStr]"
            AddrMode.PRE_INDEXED   -> "[$baseStr$offStr]!"
            AddrMode.POST_INDEXED  -> "[$baseStr]" + (op.offset?.let { ", " + formatImm(it) } ?: "")
        }
    }

    private fun formatImm(imm: Operand.Imm): String = when (imm.format) {
        ImmFormat.HEX           -> "#0x%x".format(imm.value)
        ImmFormat.DEC           -> "#${imm.value}"
        ImmFormat.SHIFT_AMOUNT  -> "lsl #${imm.value}"
        ImmFormat.FP32_BITS     -> "#0x%x".format(imm.value)
        ImmFormat.FP64_BITS     -> "#0x%x".format(imm.value)
    }

    private fun signedHex(b: Int): String = if (b < 0) ".-0x%x".format(-b) else ".+0x%x".format(b)

    private fun Arm64.VArr.lowerSuffix(): String = name.lowercase()
}
