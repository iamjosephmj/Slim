# Slim Cookbook

A long read, not a recipe list. This document explains how Slim
integrates ARM64 NEON kernels into ordinary Kotlin — closure semantics,
`suspend` composition, bidirectional value passing, structured
concurrency — and uses worked examples to show what that integration
unlocks. Recipes are the second half; the first half is about the
machinery that makes them feel native.

---

## Table of contents

1. [The integration thesis](#1-the-integration-thesis)
2. [Fundamentals: how the DSL meets Kotlin](#2-fundamentals-how-the-dsl-meets-kotlin)
3. [Closure capture: passing Kotlin context into NEON](#3-closure-capture-passing-kotlin-context-into-neon)
4. [Value extraction: getting results back out](#4-value-extraction-getting-results-back-out)
5. [Coroutine patterns: SIMD as a `suspend` citizen](#5-coroutine-patterns-simd-as-a-suspend-citizen)
6. [Reactive pipelines: SIMD inside `Flow`](#6-reactive-pipelines-simd-inside-flow)
7. [Conditional dispatch: Kotlin chooses, NEON runs](#7-conditional-dispatch-kotlin-chooses-neon-runs)
8. [Recipes](#8-recipes)
9. [Debugging your kernel](#9-debugging-your-kernel)
10. [Linker — shared subroutines (advanced)](#10-linker-shared-subroutines-advanced)
11. [Production patterns](#11-production-patterns)

---

## 1. The integration thesis

Most "write SIMD in a high-level language" projects feel like calling
out to a separate world. You declare a kernel, you invoke it, you get
a result, and the kernel is otherwise a black box that lives somewhere
else — JNI, a `.so`, a GPU shader, a separate compilation unit.

Slim does not feel like that.

In Slim, a `slim { ... }` block is a **Kotlin lambda**. It captures
your enclosing scope. It runs inside the same coroutine you're already
running in. It composes with `Flow`, `Channel`, `coroutineScope`,
custom `CoroutineDispatcher`s, and `Job` cancellation the same way any
other `suspend` function does. Your Kotlin variables flow into it as
immediates. Buffer contents flow back out. The kernel is *load-bearing
infrastructure* in your coroutine graph, not an island.

That's the harness this cookbook is about. Once the integration model
clicks, the recipes become obvious — most of them are just five lines
of NEON wrapped in the closure that gives you everything else.

The four mechanics you need to internalize:

| Mechanic | What it means | Where you'll meet it |
|---|---|---|
| **Closure capture** | The DSL block is a Kotlin lambda — it sees enclosing-scope variables, computed values, captured `Floats`, anything | Section 3 |
| **Encode-time evaluation** | Captured values become bytes in the compiled kernel; they're "burnt in" at encode time, not runtime | Section 3 |
| **Suspend composition** | `slim()` is a `suspend` function — it lives inside structured concurrency like any other suspending op | Section 5 |
| **Buffer-as-channel** | The data buffer is the value channel — pre-fill from Kotlin, mutate in NEON, read back in Kotlin | Section 4 |

The rest of this document is variations on those four mechanics.

---

## 2. Fundamentals: how the DSL meets Kotlin

### Setup

```kotlin
import io.simdkt.slim.Slim
import io.simdkt.slim.slim
import io.simdkt.slim.Floats
import io.simdkt.slim.Ints
import io.simdkt.slim.Bytes

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val ready = Slim.initialize(this)         // once per process
        if (!ready) Log.w("Slim", "fell back: ${Slim.lastError}")
    }
}
```

Always check `Slim.initialize()`'s return — see
[Production patterns](#11-production-patterns) for the kill-switch
pattern.

### The shape of a kernel

```kotlin
suspend fun example(buf: Floats) {
    slim(buf) {
        // Body is ARM64 NEON. The block is a Kotlin lambda — it can
        // reference any variable from the enclosing scope.
        loadImm32(W3, buf.size)              // captures buf.size
        mov(X1, X0)                          // X0 = data pointer (auto-prologue)
        val loop = bindLabel()
        ld1(V0, X1, S4)
        fmul(V0, V0, V0, S4)
        st1(V0, X1, S4)
        add(X1, X1, 16)
        sub(W3, W3, 4)
        cbnz(W3, loop)
        // auto-epilogue: ret
    }
}
```

Three things to notice:

1. **`slim` is `suspend`.** You're already in a coroutine to call it.
2. **The block runs at *encode time*** — every line emits 4 bytes into
   a kernel buffer. The kernel body itself executes natively when ART
   dispatches it. The Kotlin you write isn't run on every call; it's
   run *once*, the bytes are cached, and from then on it's pure
   shellcode.
3. **`X0` is the data pointer.** Slim auto-injects a prologue that
   sets `X0` to the buffer's native address. You don't write
   `prologue` / `epilogue` explicitly.

### What runs when

Understanding the two execution times is essential:

```
┌──────────────────────────────────────────────────────────────────────┐
│  ENCODE TIME (Kotlin)              RUN TIME (NEON shellcode)         │
│  ─────────────────────              ────────────────────────         │
│                                                                      │
│  • Lambda body runs                 • Encoded bytes execute          │
│  • Each `mov`, `ld1`, etc. emits      directly on CPU via ART        │
│    4 bytes into the kernel            quick-dispatch                 │
│  • Captured Kotlin values get       • No Kotlin involved             │
│    baked into immediates            • Pure ARM64 instructions        │
│  • Kotlin if/for/etc. control         hitting registers + memory     │
│    flow runs here                   • Returns via `ret`              │
│  • Cache lookup keyed on body                                        │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
                            ↓ cached →
              First call: encode, then dispatch.
              Subsequent calls with the same captures: dispatch only.
```

This dual-time model is the reason captures feel transparent and
performance still hits 20+ GB/s. Everything you do at encode time costs
~5 µs *once*; everything in the body runs at native NEON throughput.

---

## 3. Closure capture: passing Kotlin context into NEON

Because the DSL body is a Kotlin lambda, it captures the enclosing
scope. This is how you pass values from your Kotlin world into the
SIMD kernel.

### 3.1 Capture local values as immediates

```kotlin
suspend fun scaleBy(buf: Floats, factor: Float) {
    val factorBits = java.lang.Float.floatToRawIntBits(factor)

    slim(buf) {
        loadImm32(W4, factorBits)        // captures `factorBits` from outer scope
        dup(V0, X4, S4)                  // V0 = factor broadcast across 4 lanes
        loadImm32(W3, buf.size)          // captures `buf.size`
        mov(X1, X0)
        val loop = bindLabel()
        ld1(V1, X1, S4)
        fmul(V1, V1, V0, S4)
        st1(V1, X1, S4)
        add(X1, X1, 16)
        sub(W3, W3, 4)
        cbnz(W3, loop)
    }
}
```

`factorBits` and `buf.size` are evaluated when the lambda runs (encode
time) and become 32-bit immediates baked into the encoded bytes. By
the time the kernel actually executes, those values are *part of the
shellcode*. There is no per-call lookup of `buf.size`; it's a constant
in the instruction stream.

### 3.2 Capture computed values

The captured value can be the result of any Kotlin expression:

```kotlin
suspend fun thresholdContrastSlider(pixels: Bytes, sliderValue: Float) {
    // Map a 0..1 slider to a 0..255 byte threshold via Kotlin math.
    val byteThreshold = (sliderValue.coerceIn(0f, 1f) * 255).toInt() and 0xFF

    slim(pixels) {
        movz(W4, byteThreshold)           // captures the computed byte value
        dup(V1, X4, B16)
        loadImm32(W3, pixels.size)
        mov(X1, X0)
        val loop = bindLabel()
        ld1(V0, X1, B16)
        // ... compare-and-mask logic ...
        st1(V0, X1, B16)
        add(X1, X1, 16)
        sub(W3, W3, 16)
        cbnz(W3, loop)
    }
}
```

Whatever Kotlin can compute — a network response, a database lookup, a
sensor reading — can flow into the kernel as a constant. The
expression runs in your coroutine context (with all the suspending you
need), and the *result* gets baked into the kernel.

### 3.3 Capture data buffers (lookup tables, weights, etc.)

For larger captures (a 1024-entry lookup table, a convolution kernel's
weights), don't try to bake them as immediates — they'd require
hundreds of `movz`/`movk` pairs. Instead, allocate a side buffer and
reference it via a second pointer.

The current ABI passes one pointer in `X0`. To pass a second region,
**stash a layout** in the buffer:

```kotlin
suspend fun gammaCorrect(pixels: Bytes, gammaTable: ByteArray) {
    require(gammaTable.size == 256) { "gamma table must be 256 entries" }

    // Layout: [0 .. pixels.size)               — pixels (RGBA bytes)
    //         [pixels.size .. pixels.size+256) — gamma LUT
    val combined = Bytes(pixels.size + 256)
    for (i in 0 until pixels.size) combined[i] = pixels[i]
    for (i in 0 until 256) combined[pixels.size + i] = gammaTable[i]

    slim(combined) {
        loadImm32(W3, pixels.size)
        mov(X1, X0)                           // X1 = pixels pointer
        loadImm32(W5, pixels.size)
        add(X2, X0, X5)                       // X2 = gamma LUT pointer

        val loop = bindLabel()
        ldrb(W6, X1, 0)                       // load 1 pixel byte
        ldrb(W6, X2, X6)                      // LUT indexed lookup
        strb(W6, X1, 0)                       // store back
        add(X1, X1, 1)
        sub(W3, W3, 1)
        cbnz(W3, loop)
    }

    // Read back. The first pixels.size bytes are now gamma-corrected.
    for (i in 0 until pixels.size) pixels[i] = combined[i]
}
```

The `ldrb (register, register)` form does an indexed load — `X6 =
mem[X2 + W6]` — letting you index the LUT by a pixel value in one
instruction. This is the canonical pattern for any kernel that wants
to consult precomputed Kotlin data at runtime.

### 3.4 Cache implications

Slim caches compiled kernels keyed on the encoded byte sequence. Two
calls that produce the same bytes share a kernel; two calls with
different captured values produce different bytes and miss the cache.

```kotlin
slim(buf) { /* uses factor=0.5 baked in */ }   // miss; encode + dispatch
slim(buf) { /* uses factor=0.5 baked in */ }   // hit; just dispatch
slim(buf) { /* uses factor=0.7 baked in */ }   // miss; encode + dispatch
```

For a UI slider that produces continuous values, this would churn the
cache. The fix: don't bake the slider value into the kernel; pass it
via the buffer. Section 3.3's pattern works for scalars too.

### 3.5 What you can't capture

Anything that isn't a number can't be a kernel immediate. You can
*read* arbitrary Kotlin objects in the encode-time block, but only
numeric results can flow into instructions. If you need to dispatch on
an enum, do that **outside** the `slim {}` block (Section 7).

---

## 4. Value extraction: getting results back out

NEON can't return values to Kotlin directly — there's no `return 42`
in the kernel. Results flow back via the **buffer**. Three patterns:

### 4.1 In-place mutation (the default)

The most common pattern. The kernel reads the buffer, transforms it,
and writes back. After `slim()` returns, the buffer holds the result.

```kotlin
suspend fun normalize(values: Floats) {
    val maxAbs = computeMaxAbs(values)                  // Kotlin scan
    val scale = 1f / maxAbs.coerceAtLeast(1e-9f)
    val scaleBits = java.lang.Float.floatToRawIntBits(scale)

    slim(values) {
        loadImm32(W4, scaleBits)
        dup(V0, X4, S4)
        loadImm32(W3, values.size)
        mov(X1, X0)
        val loop = bindLabel()
        ld1(V1, X1, S4)
        fmul(V1, V1, V0, S4)
        st1(V1, X1, S4)
        add(X1, X1, 16)
        sub(W3, W3, 4)
        cbnz(W3, loop)
    }

    // `values` is now normalized in place. No copy.
}
```

### 4.2 Scalar result via a stash slot

Reserve space at the end of the buffer for a scalar output. The kernel
writes its accumulator there before `ret`; Kotlin reads that slot.

```kotlin
suspend fun sumOfSquares(input: Floats): Float {
    // Layout: [0 .. n)   — input data (floats)
    //         [n .. n+1) — output accumulator (float)
    val buf = Floats(input.size + 4)        // +4 for one .4s lane reservation
    for (i in input.indices) buf[i] = input[i]

    slim(buf) {
        loadImm32(W3, input.size)
        mov(X1, X0)
        // V_acc holds running sum across .4s lanes
        movz(W5, 0)
        dup(V_ACC, X5, S4)                  // V_ACC = 0,0,0,0

        val loop = bindLabel()
        ld1(V1, X1, S4)
        fmla(V_ACC, V1, V1, S4)             // V_ACC += V1 * V1
        add(X1, X1, 16)
        sub(W3, W3, 4)
        cbnz(W3, loop)

        // Store V_ACC into the stash slot (offset = input.size * 4 bytes)
        loadImm32(W5, input.size)
        add(X2, X0, X5)                     // X2 = stash slot pointer
        st1(V_ACC, X2, S4)
    }

    // Horizontal sum of the four .4s lanes happens in Kotlin.
    return buf[input.size] + buf[input.size + 1] +
           buf[input.size + 2] + buf[input.size + 3]
}
```

Pattern: do the SIMD-friendly part (lane-parallel multiply-add) in the
kernel; do the horizontal-reduce / single-value part in Kotlin. Slim
gives you a clean handoff at the buffer boundary.

> Note: `V_ACC` is a placeholder — substitute any unused vector
> register (e.g., `V8`). The encoder doesn't have register names like
> "ACC"; use `V0..V31`.

### 4.3 Status code or sentinel

For kernels that can fail (e.g., overflow detection), reserve a
status slot and write it from the kernel:

```kotlin
data class Result(val ok: Boolean, val data: FloatArray)

suspend fun saturatingAdd(a: Floats, b: Floats): Result {
    require(a.size == b.size)
    // Layout: [0 .. n)        — a (float)
    //         [n .. 2n)       — b (float)
    //         [2n .. 2n+1)    — output count
    //         [2n+1 .. 2n+5)  — saturation flags
    val buf = Floats(2 * a.size + 8)
    for (i in a.indices) buf[i] = a[i]
    for (i in b.indices) buf[a.size + i] = b[i]

    slim(buf) {
        loadImm32(W3, a.size)
        mov(X1, X0)                          // X1 = a pointer
        loadImm32(W5, a.size)
        add(X2, X0, X5)                      // X2 = b pointer
        // ... do the add ... track saturation in V_SAT ...
        // Write V_SAT to the flag slot before ret.
    }

    val saturationFlag = buf[2 * a.size + 4].toInt()
    return Result(ok = saturationFlag == 0, data = buf.toFloatArray().copyOf(a.size))
}
```

The buffer layout is your protocol. Document it in a comment near the
allocation; treat it like any other ABI.

### 4.4 Multi-output

Same pattern, more slots. Pack multiple results in named regions of
the same buffer:

```kotlin
suspend fun statistics(input: Floats): Stats {
    val buf = Floats(input.size + 16)        // 4 .4s output slots
    for (i in input.indices) buf[i] = input[i]

    slim(buf) {
        // ... compute min, max, sum, sum-of-squares in vector accumulators
        // ... store each at slots 0, 4, 8, 12 (relative to input end)
    }

    val base = input.size
    return Stats(
        min = buf[base],
        max = buf[base + 4],
        sum = buf[base + 8],
        sumSq = buf[base + 12],
    )
}
```

---

## 5. Coroutine patterns: SIMD as a `suspend` citizen

`slim()` is a `suspend` function. Anywhere you'd put a network call,
a `delay`, a `withContext` — that's where `slim()` fits, syntactically
and semantically.

### 5.1 Just-another-suspend-function composition

A real-world flow: fetch data, run a SIMD kernel, post the result.
The whole thing is one `suspend` call site:

```kotlin
suspend fun fetchAndProcess(url: String): Float {
    val raw: FloatArray = httpClient.getFloats(url)         // suspend (network)
    val buf = Floats(raw)                                   // wrap heap → native

    val whitebalance = computeWhiteBalance()                // suspend (heavy compute)

    slim(buf) {                                             // suspend (SIMD)
        val wbBits = java.lang.Float.floatToRawIntBits(whitebalance)
        loadImm32(W4, wbBits); dup(V0, X4, S4)
        loadImm32(W3, buf.size); mov(X1, X0)
        val loop = bindLabel()
        ld1(V1, X1, S4)
        fmul(V1, V1, V0, S4)
        st1(V1, X1, S4)
        add(X1, X1, 16)
        sub(W3, W3, 4)
        cbnz(W3, loop)
    }

    return logToServer(buf.toFloatArray())                  // suspend (network)
}
```

This is one continuous suspend chain. The SIMD step is a peer of the
network steps, not a special case. If `fetchAndProcess` is cancelled
mid-flight, cancellation propagates through every suspend point —
including `slim()`.

### 5.2 Cancellation semantics

A kernel runs to its `ret`. Coroutine cancellation cannot interrupt a
running kernel — but it *can* prevent the next one from starting:

```kotlin
val job = launch {
    repeat(100) { i ->
        slim(frames[i]) { /* ~5 ms kernel */ }   // suspend point
    }
}

delay(50.milliseconds)
job.cancel()
// In-flight kernel completes its ~5 ms work, then loop sees cancellation
// at the next suspend (the next slim() call) and unwinds.
```

A typical kernel runs in microseconds-to-milliseconds, so the
"granularity is too coarse" concern almost never matters in practice.
For long-running batch loops where you *do* want sub-kernel
cancellation, slice the work: process N elements per kernel, then
`yield()` between batches.

```kotlin
suspend fun cancellableLargeBatch(buf: Floats, batchSize: Int = 4096) {
    var offset = 0
    while (offset < buf.size) {
        val end = (offset + batchSize).coerceAtMost(buf.size)
        slim(buf.slice(offset, end)) { /* batch kernel */ }
        offset = end
        yield()              // checks cancellation here
    }
}
```

### 5.3 Structured concurrency: parallel kernels

```kotlin
suspend fun processFrames(frames: List<Floats>) = coroutineScope {
    frames.map { frame ->
        async(Dispatchers.Default) {
            slim(frame) {
                /* per-frame kernel body */
                loadImm32(W3, frame.size); mov(X1, X0)
                val loop = bindLabel()
                ld1(V0, X1, S4)
                fmul(V0, V0, V0, S4)
                st1(V0, X1, S4)
                add(X1, X1, 16)
                sub(W3, W3, 4)
                cbnz(W3, loop)
            }
        }
    }.awaitAll()
}
```

The probe pool serves up to **8 in-flight** kernels concurrently. With
4–8 frames in flight you get near-linear scaling on multi-core CPUs.
Beyond 8, threads block on slot acquisition — a soft cap, not a
deadlock. For 16-way parallelism, batch into pairs of 8.

### 5.4 Custom dispatchers

By default `slim()` runs on whatever dispatcher you call it from.
Route work to a specific pool with the `dispatcher` parameter:

```kotlin
val computePool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

suspend fun compute(buf: Floats) {
    slim(buf, dispatcher = computePool) {
        /* kernel runs on computePool's threads */
    }
}
```

**Don't use `Dispatchers.Main`.** Kernels run synchronously between
encode and `ret`; on the UI thread that's a frame drop waiting to
happen. Use `Default`, `IO`, or a custom compute pool.

### 5.5 Backpressure via `Channel`

Producer-consumer kernel pipelines benefit from `Channel`:

```kotlin
fun CoroutineScope.imageProcessor(
    incoming: ReceiveChannel<Bytes>
): ReceiveChannel<Bytes> = produce {
    for (frame in incoming) {
        slim(frame) { /* invertRgb or similar */ }
        send(frame)
    }
}
```

Backpressure works the standard `Channel` way: `send` suspends if the
downstream is slow, kernels stop running until the consumer catches
up. No special integration — it's just suspend semantics.

---

## 6. Reactive pipelines: SIMD inside `Flow`

`Flow` is the most ergonomic way to build SIMD pipelines on Android.
Each operator is a transformation; SIMD kernels slot in as `.map { }`
or `.transform { }` steps.

### 6.1 Camera filter

```kotlin
fun Flow<Bitmap>.invertColors(): Flow<Bitmap> = map { bitmap ->
    val pixels = Bytes(bitmap.byteCount)
    bitmap.copyPixelsToBuffer(pixels.directBuffer)        // zero-copy

    slim(pixels) {
        loadImm32(W3, pixels.size); mov(X1, X0)
        movz(W4, 0xFF); dup(V1, X4, B16)
        val loop = bindLabel()
        ld1(V0, X1, B16)
        sub(V0, V1, V0, B16)
        st1(V0, X1, B16)
        add(X1, X1, 16)
        sub(W3, W3, 16)
        cbnz(W3, loop)
    }

    Bitmap.createBitmap(pixels.toByteArray(), bitmap.width, bitmap.height, bitmap.config)
}.flowOn(Dispatchers.Default)
```

```kotlin
cameraX.imageFlow
    .invertColors()
    .onEach { renderToSurface(it) }
    .launchIn(viewScope)
```

The kernel runs once per frame; `flowOn(Dispatchers.Default)` puts it
on a worker thread; backpressure / cancellation / lifecycle scoping
all work via Flow's standard semantics.

### 6.2 Multi-stage pipeline

Each `.map` is one kernel. Chain them:

```kotlin
fun Flow<Bytes>.pipeline(): Flow<Bytes> = this
    .map { it.also { f -> slim(f) { /* normalize */  } } }
    .map { it.also { f -> slim(f) { /* contrast */   } } }
    .map { it.also { f -> slim(f) { /* gamma */      } } }
    .flowOn(Dispatchers.Default)
```

Each stage caches its own kernel handle. The buffer flows through
in-place; no allocation per stage. For a 4K frame, this is three
~1 ms kernels = one frame budget.

### 6.3 Fan-out / fan-in

Run the same buffer through multiple kernels in parallel and merge:

```kotlin
suspend fun analyze(frame: Bytes): Analysis = coroutineScope {
    val histogramBuf = Ints(256)
    val edgeMap = Bytes(frame.size)

    val histJob = async { slim(histogramBuf, frame) { /* kernel 1 */ } }
    val edgeJob = async { slim(edgeMap, frame) { /* kernel 2 */ } }
    listOf(histJob, edgeJob).awaitAll()

    Analysis(histogram = histogramBuf, edges = edgeMap)
}
```

The two kernels run on different probe slots; the merge happens via
ordinary `coroutineScope` semantics.

---

## 7. Conditional dispatch: Kotlin chooses, NEON runs

Sometimes you want different kernel behavior based on runtime state.
Decide in Kotlin; execute in NEON:

```kotlin
enum class FilterMode { IDENTITY, INVERT, GRAYSCALE, THRESHOLD }

suspend fun applyFilter(pixels: Bytes, mode: FilterMode, param: Int = 0) {
    when (mode) {
        FilterMode.IDENTITY -> {
            // No-op — don't even dispatch.
        }
        FilterMode.INVERT -> slim(pixels) {
            // ... invert kernel (constant body, single cache entry)
        }
        FilterMode.GRAYSCALE -> slim(pixels) {
            // ... grayscale kernel
        }
        FilterMode.THRESHOLD -> slim(pixels) {
            movz(W4, param and 0xFF)         // captures param
            dup(V1, X4, B16)
            // ... threshold kernel
        }
    }
}
```

Each branch is a separate kernel in the cache. Switching between modes
hits one cache line per mode — no cold-encode cost after warm-up.

### 7.1 Strategy pattern

For more elaborate dispatch:

```kotlin
fun interface KernelStrategy {
    suspend fun apply(buf: Bytes)
}

object Invert : KernelStrategy {
    override suspend fun apply(buf: Bytes) = slim(buf) { /* ... */ }
}

object Grayscale : KernelStrategy {
    override suspend fun apply(buf: Bytes) = slim(buf) { /* ... */ }
}

class Threshold(private val t: Int) : KernelStrategy {
    override suspend fun apply(buf: Bytes) = slim(buf) {
        movz(W4, t and 0xFF)
        dup(V1, X4, B16)
        // ... threshold body
    }
}
```

Each strategy carries its captures. `Threshold(128)` and
`Threshold(200)` produce two kernel cache entries; same `Threshold(t)`
called twice with the same `t` shares one.

### 7.2 Hybrid: scalar fallback when Slim isn't available

This is the production pattern. Decide once at startup, route forever:

```kotlin
object FastPath {
    val brighten: (Floats, Float) -> Unit = if (Slim.ready) {
        ::slimBrighten
    } else {
        ::scalarBrighten
    }
}

private suspend fun slimBrighten(buf: Floats, factor: Float) {
    val bits = java.lang.Float.floatToRawIntBits(factor)
    slim(buf) { /* NEON kernel */ }
}

private fun scalarBrighten(buf: Floats, factor: Float) {
    for (i in 0 until buf.size) buf[i] *= factor
}
```

Worst case (Slim unavailable): degraded perf, no crash. Best case:
6.95×. See [Production patterns](#11-production-patterns).

---

## 8. Recipes

The integration patterns above are reusable. The kernels below are the
NEON bodies you compose with them.

### 8.1 SAXPY: `y[i] = a·x[i] + b`

The "hello world" of SIMD. Brightness adjustment for floats.

```kotlin
suspend fun saxpy(data: Floats, a: Float, b: Float) {
    val aBits = java.lang.Float.floatToRawIntBits(a)
    val bBits = java.lang.Float.floatToRawIntBits(b)

    slim(data) {
        loadImm32(W4, aBits); dup(V0, X4, S4)         // V0 = a × 4
        loadImm32(W4, bBits); dup(V1, X4, S4)         // V1 = b × 4
        loadImm32(W3, data.size)
        mov(X1, X0)

        val loop = bindLabel()
        ld1(V2, X1, S4)
        fmul(V2, V2, V0, S4)                          // *= a
        fadd(V2, V2, V1, S4)                          // += b
        st1(V2, X1, S4)
        add(X1, X1, 16)
        sub(W3, W3, 4)
        cbnz(W3, loop)
    }
}
```

**Performance** (1M floats, S24, Cortex-X4): ~0.5 ms vs ~5 ms for
hot-path Kotlin scalar — **10× speedup**, throughput-bound at
~24 GB/s.

### 8.2 Color invert (RGBA bytes)

Operates on byte lanes. `y = 255 - x` per channel.

```kotlin
suspend fun invertRgba(pixels: Bytes) {
    require(pixels.size % 16 == 0)

    slim(pixels) {
        loadImm32(W3, pixels.size)
        mov(X1, X0)
        movz(W4, 0xFF)
        dup(V1, X4, B16)                              // V1 = 0xFF × 16

        val loop = bindLabel()
        ld1(V0, X1, B16)
        sub(V0, V1, V0, B16)                          // V0 = 255 − V0
        st1(V0, X1, B16)
        add(X1, X1, 16)
        sub(W3, W3, 16)
        cbnz(W3, loop)
    }
}
```

This inverts alpha too. To preserve alpha, mask with `bic` against an
alternating 0xFF/0x00 byte pattern before the subtract.

### 8.3 Threshold (binary mask)

Compare each pixel to a threshold; output 0xFF where greater, 0x00
where not. The pattern uses the **register-form unsigned compare**
(`cmhi`):

```kotlin
suspend fun threshold(gray: Bytes, thresholdByte: Int) {
    require(gray.size % 16 == 0)
    require(thresholdByte in 0..255)

    slim(gray) {
        loadImm32(W3, gray.size)
        mov(X1, X0)
        movz(W4, thresholdByte)
        dup(V1, X4, B16)                              // V1 = threshold × 16

        val loop = bindLabel()
        ld1(V0, X1, B16)
        // cmhi: V0 > V1 ? 0xFF : 0x00 per byte
        raw(io.simdkt.nativekt.engine.Arm64.cmhi(V0, V0, V1, B16))
        st1(V0, X1, B16)
        add(X1, X1, 16)
        sub(W3, W3, 16)
        cbnz(W3, loop)
    }
}
```

`cmhi` isn't yet a top-level helper on `Arm64Emitter` — use `raw()`
plus the underlying encoder until it's bound.

### 8.4 Vector dot product (sum of element-wise products)

Pack inputs interleaved or as a [a, b] layout in the buffer:

```kotlin
suspend fun dot(x: Floats, y: Floats): Float {
    require(x.size == y.size && x.size % 4 == 0)

    // Layout: [0 .. n)        — x
    //         [n .. 2n)       — y
    //         [2n .. 2n+4)    — accumulator (.4s)
    val buf = Floats(2 * x.size + 4)
    for (i in x.indices) buf[i] = x[i]
    for (i in y.indices) buf[x.size + i] = y[i]

    slim(buf) {
        loadImm32(W3, x.size)
        mov(X1, X0)
        loadImm32(W5, x.size)
        add(X2, X0, X5)                               // X2 = y pointer
        // Accumulator V8 = 0,0,0,0
        movz(W6, 0)
        dup(V8, X6, S4)

        val loop = bindLabel()
        ld1(V0, X1, S4)
        ld1(V1, X2, S4)
        fmla(V8, V0, V1, S4)                          // V8 += V0 * V1
        add(X1, X1, 16)
        add(X2, X2, 16)
        sub(W3, W3, 4)
        cbnz(W3, loop)

        // Store V8 into stash slot
        loadImm32(W6, 2 * x.size)
        add(X3, X0, X6)
        st1(V8, X3, S4)
    }

    val stash = 2 * x.size
    return buf[stash] + buf[stash + 1] + buf[stash + 2] + buf[stash + 3]
}
```

**Performance**: with `fmla` on `.4s` lanes, 4 multiply-adds per cycle
on Cortex-X4 — sustains ~16 GFLOPs.

### 8.5 More recipes

- **Brightness/contrast on uint8 RGBA (saturating)** — uses `uxtl` to
  promote to halfwords, `mul` + `sshr` for the contrast scale, `sqxtn`
  to narrow + saturate. ~25 instructions.
- **RGB→Grayscale (float)** — `faddp` (pairwise add) horizontal-sums
  the R/G/B lanes after weighting. SOA layout (separate planes) is
  much cleaner than RGBA-interleaved.
- **Box blur (3×3)** — ~80 instructions for a clean implementation
  with neighbor reuse via register rotation. See the demo app's
  `kernels/` directory.

If you've built a kernel that's general-purpose, see
[CONTRIBUTING.md](CONTRIBUTING.md) for the contribution path.

---

## 9. Debugging your kernel

Every kernel you write becomes 4 bytes per instruction in a memfd
page. The disassembler decodes those bytes back to canonical assembly,
optionally annotated with the originating Kotlin source line.

### 9.1 The `Slim.preview { ... }` workflow

```kotlin
Slim.debug = true                              // opt-in source capture

val asm: String = Slim.preview {
    mov(X1, X0)
    val loop = bindLabel("loop")
    ld1(V0, X1, S4)
    fmul(V0, V0, V0, S4)
    st1(V0, X1, S4)
    add(X1, X1, 16)
    sub(W3, W3, 4)
    cbnz(W3, loop)
}

println(asm)
```

Output:

```
  0000  aa0003e1  mov    x1, x0               // MyKernel.kt:42
loop:
  0004  4cc07c20  ld1    {v0.4s}, [x1]        // MyKernel.kt:44
  0008  6e20dc00  fmul   v0.4s, v0.4s, v0.4s  // MyKernel.kt:45
  000c  4c007c20  st1    {v0.4s}, [x1]        // MyKernel.kt:46
  0010  91004021  add    x1, x1, #0x10        // MyKernel.kt:47
  0014  51001063  sub    w3, w3, #4           // MyKernel.kt:48
  0018  35ffff83  cbnz   w3, loop             // MyKernel.kt:49
```

Each line: byte offset · hex opcode · mnemonic · operands · source
ref. Branch targets are resolved to label names (`cbnz w3, loop`) when
you bound them with a name; anonymous labels render as `L0`, `L1`, …

### 9.2 What the disassembler covers

- **All 207 encoder helpers** — branches, data-processing (immediate
  + register), GP and SIMD load/store, NEON FP and integer, system /
  hint.
- **Canonical alias rewriting** — `mov xN, xM` instead of
  `orr xN, xzr, xM`, `cmp` instead of `subs xzr`, `lsl #N` instead of
  `ubfm`, `mul` / `mneg` instead of `madd` / `msub` with `xzr`. Output
  matches `llvm-objdump` defaults.
- **Resolved label names** — when bound via `bindLabel("name")`.
- **Source `file:line` annotation** — when `Slim.debug == true`.
  Overhead is ~1–3 µs per emitted instruction (stack-walk to identify
  the user frame), so leave it off in production.

### 9.3 Disassembling a compiled kernel

For an already-compiled kernel (e.g., one that misbehaved in
production), call `disassemble()` on the handle:

```kotlin
val handle = compileMyKernel(...)
println(handle.disassemble())
```

If `Slim.debug` was on when the handle was compiled, source frames are
included; otherwise you get bytes + asm + labels only.

### 9.4 Tested round-trip guarantee

The decoder is cross-validated against the encoder:

- **150+ paired golden-byte tests** — every encoder `assertEnc` has a
  paired `assertDec`, cross-checked against `clang+llvm-objdump`.
- **14 property-based round-trip tests** — random valid inputs per
  family, encode → decode → assert.
- **1000-opcode negative test** — random 32-bit ints never throw;
  unknown encodings return `Operand.Unknown` and a `?` mnemonic.

If a kernel disassembles to something surprising, the bytes and the
canonical assembly *agree*. The bug is in your DSL, not in the
disassembler.

---

## 10. Linker — shared subroutines (advanced)

For libraries of kernels that share helpers, `compileLinkable` + `link`
let one kernel call another via `bl`:

```kotlin
import io.simdkt.nativekt.compileLinkable
import io.simdkt.nativekt.link
import io.simdkt.nativekt.NativeKt

val main = compileLinkable("main") {
    placeholderDataPtr()                  // X0 = data
    ldrW(W0, X0, 0)
    bl("square")                          // call helper, W0 = W0 * W0
    strW(W0, X0, 0)
    add(Arm64.ret())
}
val helper = compileLinkable("square") {
    export("square")
    mul(W0, W0, W0)
    add(Arm64.ret())
}

val template = link(listOf(main, helper))
NativeKt.executeTemplate(template, buffer)
```

The linker resolves `bl("square")` to the byte offset between the
call site and the export. See `LinkerTest.kt` for full examples
including forward + backward branches.

This is the lower-level API — the high-level `slim {}` doesn't yet
expose linking. Migration to the high-level API is V3 work.

---

## 11. Production patterns

### 11.1 Kill-switch + scalar fallback

Slim takes liberties with the runtime to deliver native-throughput
SIMD without JNI. The engineering bet is that the runtime is allowed
to refuse, and you handle it. Wire a fallback once at startup:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val ready = Slim.initialize(this)
        FastPath.brighten = if (ready) ::slimBrighten else ::scalarBrighten
        if (!ready) Log.i("Slim", "fell back: ${Slim.lastError}")
    }
}
```

For any code path that might invoke a SIMD kernel, gate it on
`Slim.initialize()`'s return value. If the dispatch path fails on a
user's device, you want a scalar fallback, not a 1-star review.

### 11.2 Cache-aware kernel design

The kernel cache holds up to 32 compiled handles before evicting LRU.
If your kernel takes a "user slider" parameter that bakes a continuous
value as an immediate, every slider tick is a cache miss. Don't do
that. Pass continuous parameters via the **buffer** (Section 3.3) and
keep immediates for parameters that take 4–8 distinct values.

### 11.3 Memory layout

- Use `Floats` / `Ints` / `Bytes` for hot paths. They're zero-copy.
  Going through `FloatArray` costs 2 heap↔native copies per call (~2
  ms for 16 MB).
- For very large buffers, allocate once and reuse. `Floats(n)` uses a
  direct `ByteBuffer` underneath; allocation is bounded by GC pressure.
- Buffer alignment: NEON likes 16-byte aligned data. `Floats` is
  16-byte aligned by construction.

### 11.4 Anti-tamper compatibility

If your app embeds DexProtector, Promon SHIELD, AppDome, or any RASP
SDK, run a smoke test on your build pipeline before shipping. Slim's
reflection is exactly the kind these SDKs flag. The kill-switch above
means worst case is the scalar fallback path — not a crash — but
you'll want to verify on your specific build.

See the [Production readiness](https://github.com/iamjosephmj/Slim#%EF%B8%8F-production-readiness)
section in the main README for the full anti-tamper compatibility
table and the four-tier graceful-bypass cascade behavior.

---

## Adding more recipes

If you've built a NEON kernel that's general-purpose enough to be
useful to others, PRs are welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md) for guidelines and the
golden-byte test pattern.

The cookbook is intentionally a long read. The point isn't to give
you a recipe for every kernel you might want — it's to make the
mechanics so explicit that you can write your own kernel for whatever
your real problem is, with confidence that it'll integrate into your
coroutine graph cleanly.
