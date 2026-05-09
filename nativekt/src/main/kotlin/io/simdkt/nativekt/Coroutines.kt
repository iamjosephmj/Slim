package io.simdkt.nativekt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-friendly suspending overloads for the lower-level [NativeKt]
 * dispatch entry points.
 *
 * For most users the high-level [io.simdkt.slim.slim] API is the entry
 * point. These extensions exist for callers who want fine-grained
 * control over the [KernelHandle] lifecycle but still want to dispatch
 * via a coroutine-friendly suspend function.
 *
 * ## Behavior
 *
 * The underlying ART EP-hijack dispatch is **blocking** — once control
 * jumps through the patched entry point, the kernel runs to its `ret`
 * with no yield point. These extensions wrap the blocking call in
 * [withContext] so it runs on the [CoroutineContext] of your choice
 * (defaulting to [Dispatchers.Default], the CPU-bound thread pool).
 *
 * The wrap is a thin shim — three coroutine state-machine transitions
 * per call (suspend, dispatch, resume). At nanosecond-grained kernels
 * this overhead can be measurable; for kernels in the µs range and
 * above it's invisible.
 *
 * ## Custom dispatchers
 *
 * Pass any [CoroutineContext] to route the dispatch onto your own
 * executor:
 *
 * ```
 * val customPool = Executors.newFixedThreadPool(2)
 *     .asCoroutineDispatcher()
 *
 * coroutineScope {
 *     frames.map { frame ->
 *         async { handle.runOn(customPool, frame) }
 *     }.awaitAll()
 * }
 * ```
 *
 * The default `Dispatchers.Default` is sized to CPU count and is the
 * conventional choice for compute-bound kernel dispatch. Avoid
 * `Dispatchers.Main` — kernels running on the UI thread will jank.
 *
 * ## Cancellation
 *
 * Coroutine cancellation is checked before the dispatch (via
 * [withContext]'s standard cooperation) but **cannot interrupt a kernel
 * mid-execution**. If the caller's coroutine is cancelled while a
 * kernel is running, the kernel runs to completion; the cancellation
 * takes effect when control returns to the suspending wrapper.
 *
 * Practically this only matters for long-running kernels (>10 ms). For
 * typical NEON kernels (sub-millisecond), cancellation latency is
 * indistinguishable from the dispatch latency itself.
 *
 * ## Concurrency
 *
 * Same rules as the blocking API:
 *
 *   - The engine's probe pool serves up to 8 in-flight dispatches.
 *     Beyond that, the wrapper blocks on probe-slot acquisition.
 *   - [KernelHandle] is single-writer per its KDoc — concurrent calls
 *     on the same handle would race the data-pointer slot patches. Give
 *     each parallel worker its own handle (compiled from the same
 *     [KernelTemplate]).
 *   - The high-level [io.simdkt.slim.slim] API does this synchronization
 *     for you via per-handle [kotlinx.coroutines.sync.Mutex].
 *
 * @see io.simdkt.slim.slim — the high-level API; covers most use cases
 *   without these extensions.
 */

/** Run [code] under [dataPtr] on [context]. */
suspend fun NativeKt.executeOn(
    context: CoroutineContext = Dispatchers.Default,
    code: ByteArray,
    dataPtr: Long,
): Boolean = withContext(context) { executeDirect(code, dataPtr) }

/** Run [template] against a direct [data] buffer on [context]. */
suspend fun NativeKt.executeTemplateOn(
    context: CoroutineContext = Dispatchers.Default,
    template: KernelTemplate,
    data: ByteBuffer,
): Boolean = withContext(context) { executeTemplate(template, data) }

/** Run [template] against a raw [dataPtr] on [context]. */
suspend fun NativeKt.executeTemplateOn(
    context: CoroutineContext = Dispatchers.Default,
    template: KernelTemplate,
    dataPtr: Long,
): Boolean = withContext(context) { executeTemplate(template, dataPtr) }

/**
 * Compile a kernel on [context] (touches `mmap`/`memfd_create`, so worth
 * keeping off the main thread for cold compiles).
 */
suspend fun NativeKt.compileKernelOn(
    context: CoroutineContext = Dispatchers.Default,
    template: KernelTemplate,
): KernelHandle = withContext(context) { compileKernel(template) }

/** Dispatch this handle on [context]. */
suspend fun KernelHandle.runOn(
    context: CoroutineContext = Dispatchers.Default,
    dataPtr: Long,
): Boolean = withContext(context) { run(dataPtr) }

/** Dispatch this handle on [context] with a direct ByteBuffer. */
suspend fun KernelHandle.runOn(
    context: CoroutineContext = Dispatchers.Default,
    data: ByteBuffer,
): Boolean = withContext(context) { run(data) }
