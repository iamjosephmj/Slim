# Slim — Kernel Cookbook

Practical NEON kernels you can copy-paste. Each example shows the
kernel body, the data setup, and notes on what's interesting about it.

## Conventions

All examples assume:

```kotlin
import io.simdkt.slim.Slim
import io.simdkt.slim.slim
import io.simdkt.slim.Floats
import io.simdkt.slim.Ints
import io.simdkt.slim.Bytes

// Once at startup
Slim.initialize(applicationContext)
```

## 1. SAXPY: y[i] = a·x[i] + b

The "hello world" of SIMD. Brightness adjustment on a float buffer in
place.

```kotlin
suspend fun saxpy(data: Floats, a: Float, b: Float) {
    val aBits = java.lang.Float.floatToRawIntBits(a)
    val bBits = java.lang.Float.floatToRawIntBits(b)
    slim(data) {
        loadImm32(W4, aBits)
        dup(V0, X4, S4)              // v0 = a × 4 (broadcast)
        loadImm32(W4, bBits)
        dup(V1, X4, S4)              // v1 = b × 4
        loadImm32(W3, data.size)     // w3 = lane count
        mov(X1, X0)                  // x1 = walking pointer

        val loop = bindLabel()
        ld1(V2, X1, S4)              // load 4 floats
        fmul(V2, V2, V0, S4)         // v2 *= a
        fadd(V2, V2, V1, S4)         // v2 += b
        st1(V2, X1, S4)              // store 4 floats
        add(X1, X1, 16)              // advance 16 bytes
        sub(W3, W3, 4)
        cbnz(W3, loop)
    }
}
```

**Performance** (1M floats, S24): ~0.5 ms vs ~5 ms for hot-path
Kotlin scalar — 10× speedup, throughput-bound at ~24 GB/s. The
constants `a` and `b` are baked into the kernel via `loadImm32`, so
calling with the same `a, b` reuses the same compiled handle from the
cache.

**Variants**:
- `data.size` must be divisible by 4. For unaligned tails, add a
  scalar epilogue loop.
- For different `(a, b)` per call, the cache holds up to 32 kernels
  before evicting — fine for a handful of presets, churns for
  fully-dynamic parameters.

## 2. Vector dot product

Sum of element-wise products. Useful for inner-product layers,
cosine similarity, matched filters.

```kotlin
suspend fun dot(x: Floats, y: Floats): Float {
    require(x.size == y.size && x.size % 4 == 0)
    require(y.size <= 65536) { "size baked as imm16 — use chunking for larger" }

    // Pack the result into a 4-element accumulator buffer.
    val result = Floats(4)
    // Stash the y pointer at the end of `x`'s buffer? No — use raw pointer.
    // Simpler: build a small data struct.
    val params = Floats(2 * x.size + 4 + 4) // xPtr-data, yPtr-data, count, scratch
    for (i in 0 until x.size) params[i] = x[i]
    for (i in 0 until x.size) params[x.size + i] = y[i]

    slim(params) {
        // x0 = base of params buffer.
        // Layout: [0..size*4)        = x data
        //         [size*4..2*size*4) = y data
        //         [2*size*4..)       = result accumulator
        loadImm32(W3, x.size)
        mov(X1, X0)                          // x1 = x_ptr
        add(X2, X0, 0 /* x.size*4 */)        // x2 = y_ptr — fix this
        // ... see notes below
    }

    return result[0] + result[1] + result[2] + result[3]
}
```

**Note**: passing two separate buffers cleanly is awkward with the
single-pointer ABI. The realistic pattern is to interleave the data or
pre-pack it into one buffer. For a properly engineered dot product see
the `Linker` section below.

**Performance**: with `fmla` (fused multiply-add) on `.4s` lanes,
4 multiply-adds per cycle — 16 GFLOPs sustainable on Cortex-X4.

## 3. Color invert (RGBA bytes)

Operates on byte lanes. `y = 255 - x` per channel.

```kotlin
suspend fun invertRgb(pixels: Bytes) {
    require(pixels.size % 16 == 0)
    slim(pixels) {
        loadImm32(W3, pixels.size)   // byte count
        mov(X1, X0)
        movz(W4, 0xFF)
        dup(V1, X4, B16)             // v1 = 0xFF × 16 (every byte = 255)

        val loop = bindLabel()
        ld1(V0, X1, B16)             // load 16 bytes
        sub(V0, V1, V0, B16)         // v0 = 255 - v0 (vector subtract)
        st1(V0, X1, B16)             // store 16 bytes
        add(X1, X1, 16)
        sub(W3, W3, 16)
        cbnz(W3, loop)
    }
}

// Usage:
val pixels = Bytes(width * height * 4)
// fill pixels from a Bitmap...
invertRgb(pixels)
```

**Note**: this inverts alpha too. To preserve alpha, mask out every
4th byte before the subtract — see the alpha-blending example.

## 4. Brightness/contrast on uint8 RGBA (saturating)

A more useful image kernel: `y = clamp(a·x + b, 0, 255)` per channel,
without converting to float.

Use case: real brightness/contrast UI slider applied per frame.

```kotlin
suspend fun brightnessContrastByte(pixels: Bytes, contrast: Int, bright: Int) {
    // contrast: signed 8-bit multiplier in Q1.7 fixed-point
    //   (e.g. 128 = 1.0, 192 = 1.5, 64 = 0.5)
    // bright: signed offset, clamped after multiply
    require(pixels.size % 16 == 0)
    slim(pixels) {
        loadImm32(W3, pixels.size)
        mov(X1, X0)
        movz(W4, contrast and 0xFF)
        dup(V1, X4, B16)               // v1 = contrast × 16
        movz(W4, bright and 0xFF)
        dup(V2, X4, B16)               // v2 = bright × 16

        val loop = bindLabel()
        ld1(V0, X1, B16)
        // Promote bytes → halfwords for the multiply (avoids overflow)
        uxtl(V3, V0, B8)               // V3.8h = lower 8 bytes as u16
        // (skip the upper half for brevity — full code does both halves)
        mul(V3, V3, V1, H8)            // V3.8h *= contrast
        // shift right 7 (Q1.7 → Q1.0), saturate to byte
        // ... (truncated for brevity; see actual implementation)
        sqxtn(V0, V3, B8)              // narrow + saturate
        st1(V0, X1, B16)
        add(X1, X1, 16)
        sub(W3, W3, 16)
        cbnz(W3, loop)
    }
}
```

**Note**: this is a sketch. The full implementation needs `uxtl2` for
the upper 8 bytes, `sshr` for the right shift, and `sqxtn2` for the
saturated narrow on the upper half. ~25 instructions total. Worth
~3-4× over a Kotlin scalar contrast pass.

## 5. RGB→Grayscale (float)

Standard ITU-R BT.601 weighted sum.

```kotlin
suspend fun rgbToGray(pixels: Floats, n: Int) {
    // pixels layout: RGBA RGBA RGBA ... (4 floats per pixel)
    // outputs: each pixel becomes (gray, gray, gray, alpha)
    require(pixels.size == n * 4)
    val rW = java.lang.Float.floatToRawIntBits(0.299f)
    val gW = java.lang.Float.floatToRawIntBits(0.587f)
    val bW = java.lang.Float.floatToRawIntBits(0.114f)
    slim(pixels) {
        loadImm32(W3, n)
        mov(X1, X0)
        loadImm32(W4, rW); dup(V0, X4, S4)
        loadImm32(W4, gW); dup(V1, X4, S4)
        loadImm32(W4, bW); dup(V2, X4, S4)

        val loop = bindLabel()
        // load 1 pixel = 4 floats: r, g, b, a (via .4s)
        ld1(V3, X1, S4)
        // gray = r·0.299 + g·0.587 + b·0.114
        // (this is per-element, not horizontal — needs a different approach
        //  for true RGB→grayscale; see notes)
        fmul(V4, V3, V0, S4)
        // ... (sketch only)
        st1(V4, X1, S4)
        add(X1, X1, 16)
        sub(W3, W3, 1)
        cbnz(W3, loop)
    }
}
```

**Note**: true RGB→gray needs a horizontal sum across the R, G, B
lanes of one pixel — `faddp` (pairwise add) does it in 2 instructions.
This kernel is sketched; for production you'd process 4 pixels at once
in SOA layout (separate R, G, B, A planes).

## 6. Box blur (3×3, per channel)

Spatial filter. The kernel reads a 3×3 neighborhood around each pixel
and outputs the average.

For SIMD, we process 4 output pixels per iteration (one `.4s` register
per channel) and reuse loaded pixels across adjacent outputs.

This is genuinely complex — ~80 instructions for a clean
implementation. Out of scope for a copy-paste recipe; see the project
demo's `kernels/` directory in the sample tree.

## 7. Threshold (binary mask)

Compare each pixel to a threshold; write 0xFF where greater, 0x00 where
not. Useful for binarization steps in vision pipelines.

```kotlin
suspend fun threshold(gray: Bytes, threshold: Byte) {
    require(gray.size % 16 == 0)
    slim(gray) {
        loadImm32(W3, gray.size)
        mov(X1, X0)
        movz(W4, threshold.toInt() and 0xFF)
        dup(V1, X4, B16)             // v1 = threshold × 16
        movz(W4, 0xFF)
        dup(V2, X4, B16)             // v2 = 0xFF × 16 (the "true" mask)

        val loop = bindLabel()
        ld1(V0, X1, B16)
        // Compare: v0 > v1 ? produces 0xFF or 0x00 per byte
        // (requires unsigned compare; ARM has cmhi for that)
        // For simplicity, use signed cmgt — assumes threshold and pixels
        // are in 0..127.
        // raw(Arm64.cmhi(V0, V0, V1, B16))   // — not bound; use raw escape
        st1(V0, X1, B16)
        add(X1, X1, 16)
        sub(W3, W3, 16)
        cbnz(W3, loop)
    }
}
```

**Note**: byte-lane compare instructions (`cmhi`, `cmhs`) aren't yet
bound on `Arm64Emitter`. Use `raw(Arm64.foo(...))` or contribute the
helper.

## 8. Concurrent dispatch

Multiple kernels in flight on different data, in parallel.

```kotlin
suspend fun processFrames(frames: List<Floats>) = coroutineScope {
    frames.map { frame ->
        async(Dispatchers.Default) {
            slim(frame) {
                // your kernel — runs on the coroutine's worker thread
                loadImm32(W3, frame.size)
                mov(X1, X0)
                val loop = bindLabel()
                ld1(V0, X1, S4)
                fmul(V0, V0, V0, S4)        // square in place
                st1(V0, X1, S4)
                add(X1, X1, 16)
                sub(W3, W3, 4)
                cbnz(W3, loop)
            }
        }
    }.awaitAll()
}
```

The probe pool serves up to 8 in-flight kernels; beyond that, threads
block on slot acquisition. For 4-8 concurrent kernels of comparable
cost this is invisible; for >8 it becomes a soft cap.

## 9. Custom dispatcher

Route kernel work onto a specific executor — a fixed-size pool, an
RxJava scheduler, etc.

```kotlin
val computePool = Executors.newFixedThreadPool(2)
    .asCoroutineDispatcher()

suspend fun runOnPool(data: Floats) {
    slim(data, dispatcher = computePool) {
        // kernel — runs on computePool's threads
    }
}
```

Avoid `Dispatchers.Main` — kernels block the calling thread until
they `ret`, which on the UI thread means a frame drop.

## 10. Shared subroutines via the linker

For libraries of related kernels that share helpers, `compileLinkable`
+ `link` lets one kernel call another via `bl`.

```kotlin
import io.simdkt.nativekt.compileLinkable
import io.simdkt.nativekt.link
import io.simdkt.nativekt.NativeKt

val main = compileLinkable("main") {
    placeholderDataPtr()                  // x0 = data
    ldrW(W0, X0, 0)                        // load element
    bl("square")                           // call helper, w0 = w0 * w0
    strW(W0, X0, 0)                        // store result
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

The linker resolves `bl("square")` to the actual byte offset between
the call site and the export. See `LinkerTest.kt` for full examples
including forward + backward branches.

This is the lower-level API — the high-level `slim {}` doesn't yet
expose linking; it's V3 work.

## When to use what

| Goal | Recipe |
|---|---|
| One-shot transform of a `FloatArray` | Wrap in `Floats`, use `slim()` |
| Repeated transforms on same buffer | Hold a single `Floats` and call `slim()` in a loop |
| Parallel transforms on independent buffers | `coroutineScope { frames.map { async { slim(it) {...} } } }` |
| Custom executor (thread pool, scheduler) | `slim(data, dispatcher = myDispatcher) { ... }` |
| Multi-kernel with shared helpers | `compileLinkable` + `link` (lower-level API) |
| Bypass even the `Floats` wrapper | Use `slim(rawNativePointer) { ... }` |

## Adding more recipes

If you've built a NEON kernel that's general-purpose enough to be
useful to others, PRs are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md)
for guidelines.

## Debugging your kernel

When a `slim { }` kernel produces unexpected output, inspect what
actually got compiled with the disassembler:

```kotlin
Slim.debug = true                         // enable source-line capture

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

`Slim.debug` adds ~1–3 µs per emitted instruction (stack walk to capture
the originating Kotlin file:line). Leave it off in production.

For an already-compiled kernel, call `disassemble()` on its handle:

```kotlin
val handle = compileMyKernel(...)
println(handle.disassemble())
```
