package io.simdkt.nativekt

import io.simdkt.nativekt.engine.Arm64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the [link] function's branch-fixup math. The linked image is
 * compared byte-by-byte against a hand-rolled equivalent using direct
 * [Arm64] encoding with manually-counted offsets.
 */
class LinkerTest {

    @Test fun blFromEntryToSubroutineGetsCorrectRelativeOffset() {
        // Entry: 3 instructions then `bl helper`, then ret.
        //   nop                         ; 0x00
        //   nop                         ; 0x04
        //   nop                         ; 0x08
        //   bl helper                   ; 0x0c
        //   ret                         ; 0x10
        // Helper at byte 0x14:
        //   helper:
        //   mul w0, w0, w0              ; 0x14
        //   ret                         ; 0x18
        //
        // bl distance: 0x14 - 0x0c = 8 bytes.
        val entry = compileLinkable("entry") {
            add(Arm64.nop())
            add(Arm64.nop())
            add(Arm64.nop())
            bl("helper")
            add(Arm64.ret())
        }
        val helper = compileLinkable("helper") {
            export("helper")
            add(Arm64.mul(Arm64.W0, Arm64.W0, Arm64.W0))
            add(Arm64.ret())
        }

        val template = link(listOf(entry, helper))
        val expected = Arm64.assemble(
            Arm64.nop(), Arm64.nop(), Arm64.nop(),
            Arm64.bl(8),                                   // distance to helper
            Arm64.ret(),
            Arm64.mul(Arm64.W0, Arm64.W0, Arm64.W0),
            Arm64.ret(),
        )
        assertTrue("linked size", template.size == expected.size)
        for (i in expected.indices) {
            assertEquals("byte $i", expected[i], template.bytes[i])
        }
    }

    @Test fun forwardAndBackwardSymbolBranchesBothLink() {
        // `tail` calls back into `head` — link must resolve a NEGATIVE offset.
        val head = compileLinkable("head") {
            export("hot")
            add(Arm64.ret())     // 0x00
            add(Arm64.nop())     // 0x04
        }
        val tail = compileLinkable("tail") {
            // Lives at byte 0x08. `bl hot` should resolve to offset = 0x00 - 0x08 = -8.
            bl("hot")            // 0x08
            add(Arm64.ret())     // 0x0c
        }

        val template = link(listOf(head, tail))
        val expected = Arm64.assemble(
            Arm64.ret(),                  // hot symbol target at 0x00
            Arm64.nop(),
            Arm64.bl(-8),                 // backward bl
            Arm64.ret(),
        )
        assertEquals(expected.size, template.size)
        for (i in expected.indices) assertEquals("byte $i", expected[i], template.bytes[i])
    }

    @Test fun unresolvedSymbolThrows() {
        val orphan = compileLinkable("orphan") {
            bl("nowhere")
            add(Arm64.ret())
        }
        try {
            link(listOf(orphan))
            org.junit.Assert.fail("expected unresolved symbol error")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message mentions the missing symbol",
                e.message?.contains("nowhere") == true
            )
        }
    }

    @Test fun entryDataPtrSlotsArePreservedAtImageStart() {
        val entry = compileLinkable("entry") {
            placeholderDataPtr()      // 4 instructions = 16 bytes at offset 0
            add(Arm64.ret())
        }
        val helper = compileLinkable("helper") {
            export("h")
            add(Arm64.ret())
        }
        val template = link(listOf(entry, helper))
        // Entry's slots are byte offsets 0, 4, 8, 12 — should pass through.
        assertEquals(4, template.dataPtrSlots.size)
        assertEquals(0, template.dataPtrSlots[0])
        assertEquals(4, template.dataPtrSlots[1])
        assertEquals(8, template.dataPtrSlots[2])
        assertEquals(12, template.dataPtrSlots[3])
    }
}
