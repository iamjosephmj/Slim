package io.simdkt.nativekt.engine

data class KernelMetadata(
    val sourceFrames: Map<Int, SourceFrame>,
    val labelNames: Map<Int, String>,
) {
    companion object {
        val EMPTY = KernelMetadata(emptyMap(), emptyMap())
    }
}
