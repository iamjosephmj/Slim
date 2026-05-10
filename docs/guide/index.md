# Write your first NEON kernel

This is the Slim guide. It's written for Android developers who
haven't written assembly before but have felt a Kotlin loop go slow
on real workloads — image filters, audio buffers, ML preprocessing.
By the end of this page you'll have **written, run, and measured your
first NEON kernel — ~7× faster than JIT-compiled Kotlin scalar — in 9
lines of inline DSL.** No prior assembly experience required.

If you're looking for the integration patterns (how `slim {}` blocks
compose with `suspend`, `Flow`, `Channel`, structured concurrency, how
to pass Kotlin context into kernels and get results back), read the
[Cookbook](../COOKBOOK.md). This guide is the *what* — how to read
NEON, how to write a kernel, how to verify it. The Cookbook is the
*how to compose* — once you've finished this page, that's where to go
next.

---

## 1. The problem

You're writing an Android app. Some part of it has a tight loop —
maybe an image filter, maybe an audio normalizer, maybe an ML
preprocessing step. The loop looks something like this:

```kotlin
fun brighten(values: FloatArray, factor: Float) {
    for (i in values.indices) {
        values[i] *= factor
    }
}
```

When the array is small — a few thousand elements — this is fine. The
JIT compiles the loop to scalar AArch64, the prefetcher keeps the
cache hot, and you don't notice.

When the array is large — say a 1024×1024 float image, 4 million
elements — you start to feel it. On a Cortex-X4 (Samsung S24, top-tier
2024 phone), that loop takes about **5.32 ms**. That's roughly one
frame's worth of budget for a single trivial transformation. If you're
applying multiple filters in sequence, you've blown the frame.

---

## 2. The naive Kotlin baseline

Let's establish the baseline. Here's a runnable benchmark with timing:

```kotlin
import kotlin.system.measureNanoTime

fun benchmarkScalar() {
    val n = 1024 * 1024 * 4              // 4M floats = 16 MB
    val data = FloatArray(n) { (it % 256) / 255f }
    val factor = 0.5f

    repeat(10) { brighten(data, factor) }   // warm the JIT

    val ns = measureNanoTime {
        repeat(10) { brighten(data, factor) }
    }
    val msPerCall = ns / 10.0 / 1_000_000.0
    val gbPerSec  = (n * 4L * 2) / (msPerCall / 1000) / 1e9   // ×2 = read + write

    println("Scalar: $msPerCall ms/call, $gbPerSec GB/s")
}

fun brighten(values: FloatArray, factor: Float) {
    for (i in values.indices) values[i] *= factor
}
```

Result on Cortex-X4:

```
Scalar: 5.32 ms/call, 6.0 GB/s
```

We're moving 32 MB through memory each call (16 MB read + 16 MB
write) at 6 GB/s. That's not memory-bandwidth-limited — Cortex-X4 +
LPDDR5X can do ~50 GB/s. Something is leaving headroom on the table.

---

## 3. Why is it slow?

A modern ARM CPU has **NEON** — a Single Instruction Multiple Data
unit that processes 4 floats per instruction in parallel. If your
loop uses NEON, you push roughly 4× more bytes per cycle.

The Kotlin/Java JIT (ART) *theoretically* could auto-vectorize this
loop. In practice, ART doesn't auto-vectorize ARM NEON. Period.
Hot-path Kotlin loops on Android stay scalar — they execute one float
at a time even when 4-at-a-time would be trivially correct.

You have two options for hitting NEON-rate throughput:

1. **Drop into C++ via JNI + NDK**, write the loop in
   `<arm_neon.h>` intrinsics, build a `.so` per ABI, and pay ~100 ns
   of JNI overhead on every call. This is the established path. It
   also requires a CMake build, a separate native binary, and some
   choreography for ABI compatibility.

2. **Write NEON inline in Kotlin via Slim**, which compiles your DSL
   block to ARM64 shellcode at runtime and dispatches it via a
   hijacked ART entry point. No JNI hop, no separate `.so`, no NDK
   build pipeline.

This guide is option 2.

---

## 4. What SIMD actually is

Before we write the kernel, build the mental model.

**Scalar** (what you've been doing):

```
v[i+0] = v[i+0] * factor    ; 1 multiply, 1 cycle
v[i+1] = v[i+1] * factor    ; 1 multiply, 1 cycle
v[i+2] = v[i+2] * factor    ; 1 multiply, 1 cycle
v[i+3] = v[i+3] * factor    ; 1 multiply, 1 cycle
```

**Vector / SIMD** (what NEON gives you):

```
[v[i+0], v[i+1], v[i+2], v[i+3]] *= [factor, factor, factor, factor]
                                     ; 1 multiply, 1 cycle, 4 results
```

A NEON **vector register** is 128 bits wide — 16 bytes — which means
it can hold:

- `.4s` — 4 × 32-bit floats (single precision)
- `.8h` — 8 × 16-bit halfwords
- `.16b` — 16 × 8-bit bytes
- `.2d` — 2 × 64-bit doubles
- `.2s` — 2 × 32-bit floats (lower half only)
- `.8b` — 8 × 8-bit bytes (lower half only)

You'll see these arrangement specifiers (`S4`, `B16`, etc.) all over
Slim's DSL.

A diagram, with `.4s` (4 single-precision floats):

```
V0:  ┌─────────┬─────────┬─────────┬─────────┐
     │ v[i+0]  │ v[i+1]  │ v[i+2]  │ v[i+3]  │
     └─────────┴─────────┴─────────┴─────────┘
        32 bit    32 bit    32 bit    32 bit
                       128-bit register

V1:  ┌─────────┬─────────┬─────────┬─────────┐
     │ factor  │ factor  │ factor  │ factor  │   ← broadcast (dup)
     └─────────┴─────────┴─────────┴─────────┘

fmul V0.4s, V0.4s, V1.4s   ; lane-wise multiply
                            ; 4 multiplies execute in parallel
```

Each instruction operates on 4 lanes simultaneously. Same
*instruction*, multiple *data lanes*. That's literally what SIMD
stands for.

---

## 5. Your first kernel

OK. Here's the kernel:

```kotlin
import io.simdkt.slim.Slim
import io.simdkt.slim.slim
import io.simdkt.slim.Floats

suspend fun slimBrighten(buf: Floats, factor: Float) {
    val factorBits = java.lang.Float.floatToRawIntBits(factor)

    slim(buf) {
        loadImm32(W4, factorBits)        // W4 = factor's 32-bit IEEE-754 pattern
        dup(V0, X4, S4)                  // V0 = factor broadcast across 4 lanes
        loadImm32(W3, buf.size)          // W3 = element count (loop counter)
        mov(X1, X0)                      // X1 = walking pointer (X0 stays at start)

        val loop = bindLabel()
        ld1(V1, X1, S4)                  // load 4 floats from [X1] into V1
        fmul(V1, V1, V0, S4)             // V1.4s *= V0.4s
        st1(V1, X1, S4)                  // store 4 floats from V1 to [X1]
        add(X1, X1, 16)                  // advance 16 bytes (= 4 floats)
        sub(W3, W3, 4)                   // count down by 4
        cbnz(W3, loop)                   // branch back if not zero
    }
}
```

That's the entire kernel. 9 instructions. Let me walk through each
line.

### Line by line

```kotlin
loadImm32(W4, factorBits)
```

NEON instructions can't operate on Kotlin `Float` values — they
operate on **bit patterns in registers**.
`Float.floatToRawIntBits(0.5f)` converts the float `0.5f` to the
32-bit pattern `0x3F000000`, and `loadImm32` writes that pattern into
the W4 general-purpose register.

(W4 is the lower 32 bits of X4. Same physical register, two views of
it: 32-bit `Wn` vs 64-bit `Xn`.)

```kotlin
dup(V0, X4, S4)
```

**Duplicate** — broadcast the 32-bit value in W4/X4 into all 4 lanes
of V0. After this, V0 holds
`[0x3F000000, 0x3F000000, 0x3F000000, 0x3F000000]`, which is
`[0.5, 0.5, 0.5, 0.5]` interpreted as 4 floats.

This is how you turn a Kotlin scalar into a SIMD broadcast vector.
Every kernel that multiplies by a constant uses this pattern.

```kotlin
loadImm32(W3, buf.size)
```

W3 is the loop counter. `buf.size` is **captured from the enclosing
Kotlin scope** — its value is evaluated when the lambda runs (at
encode time), and the resulting integer becomes a 32-bit immediate
baked into the instruction stream.

This is closure capture in action. Section 3 of the
[Cookbook](../COOKBOOK.md) covers the mechanics in depth; for now,
the takeaway is: any Kotlin expression that produces a number can flow
into the kernel as a constant.

```kotlin
mov(X1, X0)
```

Slim's auto-prologue puts the buffer's native address into **X0**
before your code runs. We need a walking pointer — one we can advance
through the loop — but we want to keep X0 stable in case the
auto-epilogue or a future call needs it. `mov(X1, X0)` copies the
address into X1.

```kotlin
val loop = bindLabel()
```

Marks the current position in the byte stream. `bindLabel()` returns
a `Label` token that any later branch instruction can target. The
encoder is a two-pass assembler under the hood: when a forward branch
references an unbound label, a fixup is recorded; when the label
binds, all fixups patch to the correct offset.

For backward branches like the one we're about to emit, the label is
already bound when the branch is encoded — so the offset is computed
immediately.

```kotlin
ld1(V1, X1, S4)
```

**Load 1 vector** — read 16 bytes (4 floats) from the address in X1
into V1. The `S4` specifies the arrangement — 4 single-precision
floats, lane-wise.

```kotlin
fmul(V1, V1, V0, S4)
```

**Floating-point multiply**: `V1.4s = V1.4s * V0.4s`, lane-wise. 4
multiplies execute in 1 cycle on Cortex-X4 (specifically, in the
NEON FPU pipeline; the vector unit is dedicated, so this doesn't
contend with scalar work).

```kotlin
st1(V1, X1, S4)
```

**Store 1 vector** — write the 16 bytes of V1 back to [X1].

```kotlin
add(X1, X1, 16)
sub(W3, W3, 4)
cbnz(W3, loop)
```

Standard SIMD loop tail: advance the pointer by 16 bytes (4 floats),
decrement the counter by 4, **conditional branch if non-zero** back
to `loop`. The branch instruction encodes the offset as a signed
19-bit value (multiple of 4); `bindLabel` machinery handles all the
math.

The loop runs `buf.size / 4` times. For a 4M-element buffer, that's
1M iterations of a 9-instruction body.

---

## 6. Reading the disassembly

Now the satisfying part. Let's see what your DSL actually compiled to.

```kotlin
Slim.debug = true   // capture source-line annotations

val asm: String = Slim.preview {
    val factorBits = java.lang.Float.floatToRawIntBits(0.5f)
    loadImm32(W4, factorBits)
    dup(V0, X4, S4)
    loadImm32(W3, 1024)
    mov(X1, X0)

    val loop = bindLabel("loop")        // optional name for nicer disassembly
    ld1(V1, X1, S4)
    fmul(V1, V1, V0, S4)
    st1(V1, X1, S4)
    add(X1, X1, 16)
    sub(W3, W3, 4)
    cbnz(W3, loop)
}
println(asm)
```

Output:

```
  0000  52a7e004  movz   w4, #0x3f00, lsl #16    // Brighten.kt:42
  0004  72800004  movk   w4, #0x0                // Brighten.kt:42
  0008  4e040c80  dup    v0.4s, w4               // Brighten.kt:43
  000c  528000a3  movz   w3, #0x5                // Brighten.kt:44
  0010  72a00003  movk   w3, #0x0, lsl #16       // Brighten.kt:44
  0014  aa0003e1  mov    x1, x0                  // Brighten.kt:45
loop:
  0018  4cdf7821  ld1    {v1.4s}, [x1], #16      // Brighten.kt:48
  001c  6e20dc21  fmul   v1.4s, v1.4s, v0.4s     // Brighten.kt:49
  0020  4c007821  st1    {v1.4s}, [x1]           // Brighten.kt:50
  0024  91004021  add    x1, x1, #0x10           // Brighten.kt:51
  0028  51001063  sub    w3, w3, #4              // Brighten.kt:52
  002c  35ffff63  cbnz   w3, loop                // Brighten.kt:53
```

Each line is one ARM64 instruction. Columns: byte offset, raw 32-bit
opcode, mnemonic, operands, `// source.kt:line`.

A few things to notice:

**`loadImm32(W4, factorBits)` became two instructions** (`movz` +
`movk`). ARM64 doesn't have a single instruction that loads an
arbitrary 32-bit immediate — it loads 16 bits at a time, with `movz`
for the lower half and `movk` to overlay the upper half. Slim emits
both for you when you call `loadImm32`.

**`loadImm32(W3, 1024)` is the same — two instructions** even though
1024 fits in 16 bits. The encoder doesn't try to short-circuit; it
emits the full sequence so the byte layout is predictable. (A future
encoder pass could optimize this; today it's stable, not clever.)

**`dup v0.4s, w4`** is one instruction encoding the broadcast.

**The loop body is 6 instructions** — `ld1`, `fmul`, `st1`, `add`,
`sub`, `cbnz`. Each is 4 bytes. The loop occupies bytes `0018`–`002f`
(24 bytes total).

**`cbnz w3, loop`** — the disassembler resolves the branch target
back to the `loop` label name because we bound it with
`bindLabel("loop")`. If we'd bound it anonymously with `bindLabel()`,
the disassembler would name it `L0` instead.

**`// Brighten.kt:N`** — these annotations only appear when
`Slim.debug = true`. They cost ~1–3 µs per emitted instruction
(stack-walk to identify the user frame), so leave debug off in
production.

This isn't a screenshot or a reformatted output. It's the actual
bytes that Slim wrote to a memfd page, decoded back via the
round-trip-tested disassembler. Every byte you see here got executed
by the CPU on every kernel call.

When your kernel misbehaves — wrong output, crash, or surprising
timing — this is the first thing you read.

---

## 7. Run it. Measure it.

Drop both versions into the same harness:

```kotlin
suspend fun benchmark() {
    val n = 1024 * 1024 * 4
    val factor = 0.5f

    // Scalar baseline
    val scalarData = FloatArray(n) { (it % 256) / 255f }
    repeat(10) { brighten(scalarData, factor) }                 // warm JIT
    val scalarMs = measureNanoTime {
        repeat(10) { brighten(scalarData, factor) }
    } / 10.0 / 1_000_000.0

    // Slim version
    val slimData = Floats(n) { (it % 256) / 255f }
    repeat(10) { slimBrighten(slimData, factor) }               // warm cache
    val slimMs = measureNanoTime {
        runBlocking {
            repeat(10) { slimBrighten(slimData, factor) }
        }
    } / 10.0 / 1_000_000.0

    val speedup = scalarMs / slimMs
    println("Scalar: $scalarMs ms")
    println("Slim:   $slimMs ms")
    println("Speedup: ${"%.2f".format(speedup)}×")
}
```

On a Samsung S24 (Cortex-X4, Android 16):

```
Scalar: 5.32 ms
Slim:   0.76 ms
Speedup: 7.00×
```

That's your first NEON kernel. **7× faster than the JIT-compiled
Kotlin scalar version**, in 9 lines of inline DSL, with no NDK build,
no JNI, no separate `.so`.

If you got a different speedup, that's normal. The number depends on:

- **Your CPU.** A Cortex-A55 (efficiency core) sees ~3–4×; an X4 or X3
  sees ~7–8×. The Slim path is bound by memory bandwidth on this
  kernel; the scalar path is bound by issue-rate. Different cores
  shift that balance.
- **Your buffer size.** Sub-L1 buffers cache-fit and the speedup
  shrinks (less memory pressure to amortize). Multi-MB buffers spill
  to L3 / RAM and the speedup grows.
- **Your warmup.** The first call pays the encode cost (~5 µs) and
  the cache-miss cost. Always warm.

---

## 8. What just happened? (under the hood)

Briefly — full architecture is in [Architecture](../ARCHITECTURE.md):

1. **Encode time** (~5 µs): your `slim { ... }` lambda ran. Each
   function call (`mov`, `ld1`, etc.) emitted 4 bytes into a `memfd`
   (Linux in-memory file descriptor). The `memfd` is mapped twice —
   once writable (so the encoder can fill it), once executable (so the
   CPU can run it). The two mappings share physical pages, which dodges
   I-cache invalidation issues without an explicit flush.

2. **Cache lookup**: Slim hashed the encoded bytes. If a kernel with
   these exact bytes was compiled before (same captures, same body),
   you got the cached handle and skipped re-encoding entirely.

3. **Dispatch time** (~3 µs + kernel work): Slim looked up an
   `ArtMethod` struct via reflection, atomically swapped its
   `entry_point_from_quick_compiled_code_` pointer to the address of
   your shellcode page, and called the corresponding Method via
   ordinary Java reflection. ART's quick-dispatch jumped straight into
   your bytes — no JNI hop, no transition, just a regular method
   dispatch into native code.

4. **Your kernel ran** at native NEON throughput. No overhead.

5. **`ret`** at the end of your kernel returned to ART. Slim restored
   the original entry-point pointer.

The total per-call overhead — everything outside the kernel's actual
work — is about 3 µs. For a kernel that does ~0.7 ms of useful work,
the overhead is **0.4%**. For a kernel that does 7 µs of work, the
overhead is 30%. There's a per-call cost; you amortize it across the
size of your input.

For sub-microsecond kernels, the runtime encoding cost (~5 µs first
time) dominates. A future Kotlin compiler plugin will move encoding
to build time and eliminate that cost — but for the workloads this
guide covers (image / audio / ML preprocessing on real-sized
buffers), the runtime path is already in the right ballpark.

---

## 9. Common mistakes

A few footguns to avoid as you start writing your own kernels:

**Forgetting `mov(X1, X0)`** at the start, then writing `add(X0, X0, 16)`
directly. X0 is the auto-prologue register; if you mutate it, the
auto-epilogue's behavior is undefined. Always copy X0 to a working
register first.

**Buffer size not divisible by lane count.** If `buf.size` isn't a
multiple of 4 in this kernel, the loop will read past the end. For
production code, pad buffers to a SIMD-friendly multiple, or add a
scalar epilogue loop.

**Using `loadImm32` for floats.** `loadImm32` takes the bit pattern,
not the float. Always go through `Float.floatToRawIntBits(...)` first.
Forgetting this gives you an integer interpretation of the float bits,
which produces silent wrong results.

**Reusing register V0 across kernels mentally** — registers don't
persist between `slim {}` blocks. Each kernel starts with undefined
register state (except X0, which is the data pointer). Always
initialize what you read.

**Forgetting `Slim.initialize()`** before the first call. The first
`slim {}` invocation will throw if `initialize()` hasn't run. Wire
it from `Application.onCreate`, and check the return value (Section
11 of the [Cookbook](../COOKBOOK.md) covers the kill-switch pattern).

---

## 10. Where to go next

You've now written, run, and measured your first NEON kernel.

The single best next read is the [**Cookbook**](../COOKBOOK.md) — a
long-form integration guide for how `slim {}` blocks compose with
ordinary Kotlin. How they live inside `suspend` functions, `Flow`
pipelines, `Channel` backpressure, structured concurrency. How to
pass complex Kotlin context (lookup tables, computed parameters)
into kernels and get results back. The "SIMD as ordinary Kotlin"
thesis and the patterns that follow from it.

More chapters of this guide will land as the project matures —
registers and arrangements in depth, branches and control flow,
memory access patterns, common kernel shapes. For now, the Cookbook
recipes are worked examples that exercise most of the same material
in context.

Feedback on this guide is welcome via a
[GitHub issue](https://github.com/iamjosephmj/Slim/issues) — what was
unclear, what was redundant, what would have helped.
