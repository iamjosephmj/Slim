package io.simdkt.nativekt.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Locks the [Asm] label/fixup pass against hand-encoded byte streams. The
 * underlying instruction encoding is already covered by [Arm64Test]; what
 * we're proving here is that branch immediates land at the right offsets
 * for forward, backward, and conditional branches.
 */
class AsmTest {

    @Test fun backwardCbnzMatchesHandRolledOffset() {
        // 8 instructions, cbnz on the 8th branching back to the 1st:
        //   distance = -7 instructions = -28 bytes.
        // Mirrors the SAXPY loop body — same encoding the demo uses.
        val asm = Asm()
        val loop = asm.bindLabel()
        asm.add(Arm64.ld1(Arm64.V1, Arm64.X1, Arm64.VArr.S4))
        asm.add(Arm64.ld1(Arm64.V2, Arm64.X2, Arm64.VArr.S4))
        asm.add(Arm64.fmla(Arm64.V1, Arm64.V2, Arm64.V0, Arm64.VArr.S4))
        asm.add(Arm64.st1(Arm64.V1, Arm64.X1, Arm64.VArr.S4))
        asm.add(Arm64.addImm(Arm64.X1, Arm64.X1, 16))
        asm.add(Arm64.addImm(Arm64.X2, Arm64.X2, 16))
        asm.add(Arm64.subImm(Arm64.W3, Arm64.W3, 4))
        asm.cbnz(Arm64.W3, loop)
        val bytes = asm.assemble()

        val expected = Arm64.assemble(
            Arm64.ld1(Arm64.V1, Arm64.X1, Arm64.VArr.S4),
            Arm64.ld1(Arm64.V2, Arm64.X2, Arm64.VArr.S4),
            Arm64.fmla(Arm64.V1, Arm64.V2, Arm64.V0, Arm64.VArr.S4),
            Arm64.st1(Arm64.V1, Arm64.X1, Arm64.VArr.S4),
            Arm64.addImm(Arm64.X1, Arm64.X1, 16),
            Arm64.addImm(Arm64.X2, Arm64.X2, 16),
            Arm64.subImm(Arm64.W3, Arm64.W3, 4),
            Arm64.cbnz(Arm64.W3, -28),
        )
        assertArrayEquals(expected, bytes)
    }

    @Test fun forwardBranchPatchesAfterBind() {
        // b skip; nop; nop; skip:
        // Distance = 3 instructions = +12 bytes.
        val asm = Asm()
        val skip = asm.label()
        asm.b(skip)
        asm.add(Arm64.nop())
        asm.add(Arm64.nop())
        asm.bind(skip)
        asm.add(Arm64.ret())
        val bytes = asm.assemble()

        val expected = Arm64.assemble(
            Arm64.b(12),
            Arm64.nop(),
            Arm64.nop(),
            Arm64.ret(),
        )
        assertArrayEquals(expected, bytes)
    }

    @Test fun forwardCbzAndBackwardBInSameStream() {
        // loop: ... cbz x0, end ... b loop ... end: ret
        val asm = Asm()
        val loop = asm.bindLabel()
        val end = asm.label()
        asm.add(Arm64.subImm(Arm64.X0, Arm64.X0, 1))     // x0 -= 1
        asm.cbz(Arm64.X0, end)                            // if zero, branch to end
        asm.b(loop)                                       // else loop
        asm.bind(end)
        asm.add(Arm64.ret())
        val bytes = asm.assemble()

        // sub @ 0; cbz @ 4 → end @ 12, dist=+8; b @ 8 → loop @ 0, dist=-8; ret @ 12
        val expected = Arm64.assemble(
            Arm64.subImm(Arm64.X0, Arm64.X0, 1),
            Arm64.cbz(Arm64.X0, 8),
            Arm64.b(-8),
            Arm64.ret(),
        )
        assertArrayEquals(expected, bytes)
    }

    @Test fun bCondForwardBranch() {
        val asm = Asm()
        val skip = asm.label()
        asm.add(Arm64.cmpImm(Arm64.X0, 0))
        asm.bCond(Arm64.Cond.EQ, skip)
        asm.add(Arm64.movz(Arm64.W1, 1))
        asm.bind(skip)
        asm.add(Arm64.ret())
        val bytes = asm.assemble()

        // cmp @ 0; b.eq @ 4 → skip @ 12, dist=+8; movz @ 8; ret @ 12
        val expected = Arm64.assemble(
            Arm64.cmpImm(Arm64.X0, 0),
            Arm64.bCond(Arm64.Cond.EQ, 8),
            Arm64.movz(Arm64.W1, 1),
            Arm64.ret(),
        )
        assertArrayEquals(expected, bytes)
    }

    @Test fun tbnzForwardBranch() {
        val asm = Asm()
        val end = asm.label()
        asm.tbnz(Arm64.X0, 5, end)
        asm.add(Arm64.nop())
        asm.bind(end)
        asm.add(Arm64.ret())
        val bytes = asm.assemble()

        val expected = Arm64.assemble(
            Arm64.tbnz(Arm64.X0, 5, 8),
            Arm64.nop(),
            Arm64.ret(),
        )
        assertArrayEquals(expected, bytes)
    }

    @Test fun unboundLabelThrowsAtAssemble() {
        val asm = Asm()
        val never = asm.label()
        asm.b(never)
        asm.add(Arm64.ret())
        try {
            asm.assemble()
            fail("expected unbound-label error")
        } catch (e: IllegalStateException) {
            assertEquals(true, e.message?.contains("unbound"))
        }
    }

    @Test fun bindingTwiceThrows() {
        val asm = Asm()
        val l = asm.bindLabel()
        try {
            asm.bind(l)
            fail("expected double-bind error")
        } catch (e: IllegalStateException) {
            assertEquals(true, e.message?.contains("already bound"))
        }
    }

    @Test fun mixedAddSignaturesProduceSameBytes() {
        val a = Asm().apply {
            add(Arm64.movz(Arm64.X0, 1))
            add(Arm64.loadImm64(Arm64.X1, 0xCAFEL))   // List<Int>
            add(Arm64.ret())
        }
        val b = Asm().apply {
            add(Arm64.movz(Arm64.X0, 1), Arm64.movz(Arm64.X1, 0xCAFE), Arm64.ret())
        }
        assertArrayEquals(a.assemble(), b.assemble())
    }
}
