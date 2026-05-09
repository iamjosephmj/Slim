package io.simdkt.nativekt.engine

/**
 * JNI helper library. Wraps `libnktrampoline.so` with three native
 * entry points used at setup time and (rarely) on the hot path.
 *
 * ## What it provides
 *
 * - **[artRuntimeAddr]** (setup) — locates the `art::Runtime` singleton
 *   in `libart.so` by walking `/proc/self/maps` and ELF-parsing the
 *   `.dynsym` for `_ZN3art7Runtime9instance_E`. Used by the hidden-API
 *   bypass cascade in [MemoryExecutor.bypassHiddenApi].
 * - **[clearCache]** (setup) — emits the ARM64 cache-flush sequence
 *   (`dc cvau` / `dsb ish` / `ic ivau` / `dsb ish` / `isb`) over a
 *   memory range. The memfd dual-map allocator avoids needing this on
 *   the normal path; it's exposed for callers that reuse RX regions
 *   across kernels.
 * - **[callAndCheck]** (rare hot-path fallback) — invokes a kernel via
 *   plain JNI dispatch. Used only on devices where EP hijack fails
 *   (PAC/BTI strict, etc.); adds ~150 ns of JNI overhead per call.
 *
 * ## Loading
 *
 * [init] is best-effort. If `libnktrampoline.so` is missing (e.g. the
 * AAR was repackaged without the native lib) or the linker rejects it
 * (rare; typically a corrupted APK), [loaded] stays `false` and
 * subsequent native calls would throw `UnsatisfiedLinkError`.
 *
 * Callers should branch on [loaded] before using any external method.
 *
 * On API 36 the hidden-API bypass requires this library — without it,
 * the cascade can't reach its last-resort `art::Runtime` poke and
 * [MemoryExecutor.init] will fail.
 *
 * ## Internal-only
 *
 * Marked `internal` because direct callers should go through
 * [io.simdkt.nativekt.NativeKt] / [io.simdkt.slim.Slim].
 */
internal object Trampoline {

    @Volatile
    var loaded: Boolean = false
        private set

    @Synchronized
    fun init() {
        if (loaded) return
        try {
            System.loadLibrary("nktrampoline")
            loaded = true
        } catch (_: Throwable) {
            loaded = false
        }
    }

    /**
     * Call the kernel at [codePtr] with [dataPtr] in `x0`. Before the call we
     * write 0 to a private magic slot; after the call we read it back. The
     * kernel is expected to write [magic] to that slot if it ran. Returns
     * `true` if the slot matches.
     *
     * The C side allocates the magic slot once at JNI_OnLoad and exposes its
     * address via a small "kernel preamble" the caller is responsible for
     * embedding — see [trampolinePreamble]. For most uses the EP-hijack path
     * is preferred and this method is a fallback.
     */
    external fun callAndCheck(codePtr: Long, dataPtr: Long, magic: Int): Boolean

    /**
     * Issue an ARM64 instruction-cache flush over `[addr, addr+length)`. The
     * memfd dual-map allocator in [MemoryExecutor] is designed to avoid
     * needing this (the RX VA is fresh on every kernel), but if a caller
     * reuses an RX region across kernels they MUST flush before re-running.
     */
    external fun clearCache(addr: Long, length: Long)

    /**
     * Returns the native address of the `art::Runtime` singleton (the value
     * stored at `art::Runtime::instance_`). 0 if the symbol can't be located
     * — typically only on stripped/repackaged ROMs where libart's `.dynsym`
     * doesn't expose the global. Used by [MemoryExecutor]'s hidden-API
     * bypass to probe the policy field via Unsafe.
     */
    external fun artRuntimeAddr(): Long
}
