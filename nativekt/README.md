# Slim

Write ARM64 NEON code in Kotlin. Run it from Android, no JNI per call, no
boilerplate.

```kotlin
val pixels = Floats(myFloatArray)

slim(pixels) {
    loadImm32(W4, java.lang.Float.floatToRawIntBits(0.5f))
    dup(V0, X4, S4)              // v0 = 0.5 × 4 (broadcast)
    loadImm32(W3, pixels.size)
    mov(X1, X0)

    val loop = bindLabel()
    ld1(V2, X1, S4)              // v2 = pixels[i..i+3]
    fmul(V2, V2, V0, S4)         // v2 *= 0.5
    st1(V2, X1, S4)
    add(X1, X1, 16)
    sub(W3, W3, 4)
    cbnz(W3, loop)
}

println(pixels[0])               // result, zero-copy read
```

That's the whole API. No `mmap`, no `KernelHandle`, no `placeholderDataPtr`,
no `Arm64.X0` qualifiers. The runtime handles ART entry-point hijacking,
hidden-API bypass, JIT memory mapping, and dispatcher routing under the
hood.

## What you get

- **Pure Kotlin ARM64 encoder** — ~150 instruction helpers covering moves,
  GP/SIMD memory, integer/FP arithmetic, NEON FP/integer/misc/logical,
  saturating arith, dot product, half-precision FP, conditional select,
  bitmask immediates, PC-relative addressing, PAC/BTI, and system ops.
  Each helper is locked against `clang+llvm-objdump` golden bytes.
- **ART entry-point hijack dispatch** — kernels run with **no JNI per
  call**. The runtime patches a probe method's
  `entry_point_from_quick_compiled_code`, calls the method via reflection,
  and ART jumps directly into the JIT'd shellcode.
- **Hidden-API bypass for API 28-36** — works on stock Android 16
  (Pixel 8, Samsung One UI). Four-tier cascade: meta-reflection, direct
  call, Java-side `targetSdkVersion` Unsafe poke, and finally a native
  `art::Runtime::hidden_api_policy_` flip via ELF parsing of libart.so.
  Per-device offsets cached for fast cold start.
- **memfd dual-map JIT memory** — RW + RX mappings of the same memfd page,
  no `mprotect` traffic, no I-cache flush.
- **Coroutine-friendly** — `slim()` is suspending; pass any
  `CoroutineContext` for custom dispatchers, or accept `Dispatchers.Default`.
- **Multi-threaded** — `BlockingQueue<ProbeSlot>` pool of 8 probe methods
  serves concurrent `slim {}` calls without a global lock.

## Setup

Maven coordinates:
```kotlin
implementation("io.simdkt:nativekt:0.1.0")
```

Once at app startup:
```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Slim.initialize(this)
    }
}
```

## The two data types

| Type | Construction | When to use |
|---|---|---|
| `FloatArray`, `IntArray`, `ByteArray` | Plain Kotlin arrays | One-shot kernels. The runtime copies in/out via a pooled direct buffer per call. Convenient but pays ~2 ms / 16 MB / call in copy overhead. |
| `Floats`, `Ints`, `Bytes` | `Floats(myArray)` or `Floats(size) { i -> ... }` | Hot paths, repeated kernels on the same buffer. Backed by a direct buffer; `slim()` is **zero-copy**. Same `data[i]` array-like API. |

```kotlin
// Convenient
slim(myFloatArray) { /* kernel */ }

// Performance
val data = Floats(myFloatArray)
repeat(1000) {
    slim(data) { /* kernel — zero copies */ }
}
val result = data.toFloatArray()
```

## Inside the `slim {}` block

Every ARM64 register, vector arrangement, condition code, and instruction
helper is in scope:

```kotlin
slim(data) {
    // Registers — all 32 X, W, V regs plus XZR/SP/WZR/WSP
    mov(X1, X0)
    movz(W3, 1024)

    // Vector arrangements — B8/B16/H4/H8/S2/S4/D1/D2
    ld1(V0, X1, S4)
    fmla(V1, V0, V2, S4)

    // Conditions — EQ/NE/CS/CC/MI/PL/VS/VC/HI/LS/GE/LT/GT/LE
    csel(X4, X5, X6, GT)

    // Labels and branches
    val loop = bindLabel()
    sub(W3, W3, 1)
    cbnz(W3, loop)
}
```

If a helper isn't bound to the scope (rare specialized instruction),
escape via `raw(opcode)` or `raw(opcodes)` taking the underlying
`Arm64.foo(...)` Int / List<Int>.

## Coroutines

`slim()` is `suspend`. Default dispatcher is `Dispatchers.Default` (the
CPU-bound thread pool); pass a custom context to override:

```kotlin
val customPool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

coroutineScope {
    frames.map { f ->
        async {
            slim(f, dispatcher = customPool) { /* kernel */ }
        }
    }.awaitAll()
}
```

A kernel mid-flight can't be cancelled — it runs to its `ret`. Cancellation
takes effect on coroutine resumption.

## Supported devices

- **API 26+** (Android 8.0 Oreo and up)
- **arm64-v8a** only
- Confirmed on AOSP-derived Android 12-16 ROMs (Pixel, Samsung One UI);
  earlier API levels (26-30) exercise the same engine path with the
  hidden-API bypass falling through to a no-op (reflection isn't gated
  until API 28).

The hidden-API bypass falls through gracefully on devices where one
technique fails; `Slim.lastError` reports the failure if `initialize`
returns `false`.

## Building & testing

```bash
# 49 unit tests covering encoder, asm pass, and linker
./gradlew :nativekt:testDebugUnitTest

# Build the AAR
./gradlew :nativekt:assembleRelease

# Publish to local maven
./gradlew :nativekt:publishToMavenLocal
```

---

## Advanced — bypassing the high-level API

The `slim` package wraps a lower-level core that's still public for
specialized cases. Listed here for completeness:

- **`io.simdkt.nativekt.NativeKt`** — bare `init` / `execute` /
  `executeDirect` / `executeTemplate` / `compileKernel`. Operates on raw
  `ByteBuffer` and `Long` data pointers.
- **`io.simdkt.nativekt.KernelTemplate`**, **`KernelHandle`** — manual
  compile-once / dispatch-many lifecycle. `compileTemplate { ... }` and
  `compileLinkable(name) { ... }` build templates; `link(parts)` resolves
  cross-kernel `bl`s; `NativeKt.compileKernel(template)` returns a handle
  whose `.run(buffer)` dispatches without internal caching or
  marshalling.
- **`io.simdkt.nativekt.engine.Arm64`** — the pure encoder. Each helper
  returns an `Int` opcode (or `List<Int>` for `loadImm64` / `loadImm32`).
  Use directly when you want `Int` opcodes for inspection or testing.
- **`io.simdkt.nativekt.engine.Asm`** — the label/fixup pass standalone.
  Useful if you're building your own DSL on top.
- **`io.simdkt.nativekt.engine.Trampoline`** — JNI helper. Exposes
  `callAndCheck` (fallback dispatch), `clearCache`, and `artRuntimeAddr`
  (used by the hidden-API bypass). Don't call directly unless you know
  why.

These layers are stable but not the recommended path. If you find
yourself reaching for them, file an issue — the high-level API likely
needs an addition.

## License

Apache 2.0.
