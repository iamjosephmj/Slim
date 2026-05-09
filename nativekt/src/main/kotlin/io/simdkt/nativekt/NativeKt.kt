package io.simdkt.nativekt

import io.simdkt.nativekt.engine.MemoryExecutor
import java.io.File
import java.nio.ByteBuffer

/**
 * Lower-level entry point for the runtime. Most users should reach for
 * the high-level [io.simdkt.slim.Slim] / [io.simdkt.slim.slim] API
 * instead — this object exists for callers who want manual control over
 * the [KernelTemplate] / [KernelHandle] lifecycle.
 *
 * ## What this object does
 *
 * - **Initialization** ([init]): probe the ART entry-point offset,
 *   disable hidden-API enforcement, load the JNI helper, populate the
 *   probe-method pool. Idempotent.
 * - **Status** ([isReady], [lastError], [flags]): read-only view of the
 *   runtime's state. Useful for guarding optional NEON paths.
 * - **Direct dispatch** ([execute], [executeDirect]): run a raw
 *   `ByteArray` of ARM64 instructions against a buffer/pointer. Each
 *   call allocates a fresh memfd region.
 * - **Template dispatch** ([executeTemplate]): like [execute] but takes
 *   a [KernelTemplate] with reserved data-pointer slots; patches the
 *   slots before dispatch.
 * - **Compile-once dispatch** ([compileKernel]): compile a
 *   [KernelTemplate] into a long-lived [KernelHandle] whose subsequent
 *   `run()` calls reuse the memfd region and just patch the slots.
 *
 * ## Supported devices
 *
 * - **API**: 31+ (Android 12 and up).
 * - **ABI**: arm64-v8a only.
 * - **Confirmed on**: AOSP-derived Android 12-16 ROMs (Pixel, Samsung
 *   One UI). The hidden-API bypass cascade gracefully falls through if
 *   one technique no longer applies on a future Android.
 *
 * PAC/BTI-strict kernels on some Pixel 8+ branches may reject the
 * EP-hijack dispatch — in that case [Flags.trigger] is `false` and
 * [Flags.trampoline] is the fallback. The trampoline path uses JNI per
 * call (~150 ns overhead).
 *
 * ## Threading
 *
 * - [init] is `@Synchronized`; safe to call from any thread, but cold
 *   init blocks for a few ms.
 * - [execute], [executeTemplate], [executeDirect], [compileKernel] are
 *   thread-safe — the engine takes a probe slot from a pool of 8 per
 *   call. Beyond 8 in-flight dispatches, threads block on slot
 *   acquisition.
 * - [KernelHandle.run] is single-writer per the handle's KDoc.
 *
 * @see io.simdkt.slim.Slim — high-level facade; recommended.
 * @see io.simdkt.slim.slim — high-level dispatch entry point.
 * @see KernelHandle — long-lived compile-once dispatch.
 * @see KernelTemplate — compiled kernel image with placeholders.
 */
object NativeKt {
    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    val flags: Flags
        get() = MemoryExecutor.flags

    /**
     * Probe the ART entrypoint layout, load the JNI trampoline, and prepare the
     * memfd-backed code regions. Idempotent.
     *
     * @return true on success. On failure check [lastError].
     */
    @Synchronized
    fun init(cacheDir: File): Boolean {
        if (isReady) return true
        return try {
            MemoryExecutor.init(cacheDir)
            isReady = true
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = "${t::class.java.simpleName}: ${t.message ?: "(no message)"}"
            false
        }
    }

    /**
     * Run a kernel that operates on a direct [ByteBuffer]. The buffer's native
     * address is passed to the kernel in `x0` (AAPCS64 first arg).
     */
    fun execute(code: ByteArray, data: ByteBuffer): Boolean {
        check(isReady) { "NativeKt.init must succeed before execute" }
        require(data.isDirect) { "data must be a direct ByteBuffer" }
        return MemoryExecutor.execute(code, MemoryExecutor.directBufferAddress(data))
    }

    /**
     * Run a kernel against a raw native pointer. The caller is responsible for
     * keeping the pointed-to memory alive for the duration of the call.
     */
    fun executeDirect(code: ByteArray, dataPtr: Long): Boolean {
        check(isReady) { "NativeKt.init must succeed before executeDirect" }
        return MemoryExecutor.execute(code, dataPtr)
    }

    /**
     * Run a [KernelTemplate] against a direct [ByteBuffer]. The template's
     * data-pointer slot (if any) is patched with the buffer's native
     * address before dispatch, so the same compiled image can be reused
     * across many calls and many buffers.
     */
    fun executeTemplate(template: KernelTemplate, data: ByteBuffer): Boolean {
        check(isReady) { "NativeKt.init must succeed before executeTemplate" }
        require(data.isDirect) { "data must be a direct ByteBuffer" }
        return MemoryExecutor.executeTemplate(
            template, MemoryExecutor.directBufferAddress(data)
        )
    }

    /**
     * Run a [KernelTemplate] against a raw native pointer.
     */
    fun executeTemplate(template: KernelTemplate, dataPtr: Long): Boolean {
        check(isReady) { "NativeKt.init must succeed before executeTemplate" }
        return MemoryExecutor.executeTemplate(template, dataPtr)
    }

    /**
     * Compile [template] into a long-lived [KernelHandle]. The memfd
     * dual-map region is allocated once; subsequent [KernelHandle.run]
     * calls reuse it and only patch the data-pointer slots in place.
     *
     * For hot kernels invoked many times this is dramatically faster than
     * [executeTemplate] (which re-allocates per call). The caller is
     * responsible for [KernelHandle.close].
     */
    fun compileKernel(template: KernelTemplate): KernelHandle {
        check(isReady) { "NativeKt.init must succeed before compileKernel" }
        return MemoryExecutor.compileTemplate(template)
    }

    /**
     * Diagnostic snapshot of the runtime's discovered configuration.
     *
     * Read after [init] completes successfully. Stable for the rest of
     * the process lifetime.
     *
     * @property epIndex byte offset of `entry_point_from_quick_compiled_code`
     *   within `ArtMethod`, as discovered by the probe. Typically `0x18` on
     *   AOSP arm64. `-1` if discovery failed.
     * @property trigger `true` if EP-hijack dispatch works on this device.
     *   `false` means PAC/BTI strict, anti-tamper SDK, or vendor-patched
     *   ART rejected the probe; the [trampoline] path is the fallback.
     * @property unsafeEp `true` if `sun.misc.Unsafe` is the active raw-memory
     *   backend (preferred). `false` means the runtime fell back to
     *   `libcore.io.Memory`.
     * @property methodHandle `true` if `MethodHandles.lookup()` is available
     *   for an alternative dispatch path. Reserved for future use.
     * @property trampoline `true` if `libnktrampoline.so` loaded
     *   successfully. Required for the hidden-API bypass on API 36+;
     *   when [trigger] is `false`, the trampoline is the only working
     *   dispatch path.
     */
    data class Flags(
        val epIndex: Int,
        val trigger: Boolean,
        val unsafeEp: Boolean,
        val methodHandle: Boolean,
        val trampoline: Boolean,
    )
}
