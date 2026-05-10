package io.simdkt.nativekt.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AsmLabelNameTest {

    @Test fun named_label_is_recorded() {
        val asm = Asm()
        asm.add(Arm64.nop())                    // offset 0
        val loop = asm.bindLabel("loop")        // offset 4
        asm.add(Arm64.nop())
        asm.assemble()
        assertEquals("loop", asm.labelNames[4])
    }

    @Test fun anonymous_label_gets_auto_name() {
        val asm = Asm()
        val l = asm.bindLabel()
        asm.assemble()
        assertEquals("L0", asm.labelNames[0])
    }

    @Test fun multiple_anonymous_labels_increment() {
        val asm = Asm()
        asm.bindLabel()                          // L0 at offset 0
        asm.add(Arm64.nop())
        asm.bindLabel()                          // L1 at offset 4
        asm.assemble()
        assertEquals("L0", asm.labelNames[0])
        assertEquals("L1", asm.labelNames[4])
    }
}
