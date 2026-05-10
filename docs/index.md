---
title: Slim — ARM64 NEON in Kotlin
hide:
  - navigation
---

# Slim

**Pure-Kotlin ARM64 NEON runtime for Android.** Write SIMD instructions
inline in Kotlin and have them executed by ART as if they were
JIT-compiled Kotlin — no JNI per call, no NDK build, no separate
`.so`.

[Get started :material-arrow-right:](guide/index.md){ .md-button .md-button--primary }
[GitHub :fontawesome-brands-github:](https://github.com/iamjosephmj/Slim){ .md-button }
[JitPack :fontawesome-solid-cube:](https://jitpack.io/#iamjosephmj/Slim){ .md-button }

---

!!! abstract "Start here"
    The [**Guide**](guide/index.md) walks you through writing your
    first NEON kernel — brightening a 16 MB float buffer **~7× faster
    than JIT-compiled Kotlin** in 9 lines of inline DSL. Line by line,
    with the disassembled output, a runnable benchmark, and a "what
    just happened?" tour of the ART entry-point hijack. No prior
    assembly experience required.

---

## What it looks like

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
```

That's the whole API. Two functions — `Slim.initialize(context)` once
at startup, then `slim(data) { ... }` anywhere. Inside the block, raw
ARM64 NEON: registers, instructions, vector arrangements, condition
codes. The runtime handles JIT memory, ART internals, and dispatch.

---

## How fast?

Slim sits between scalar Kotlin (the floor — JIT-compiled, no SIMD) and
hand-tuned native C++ with NEON intrinsics (the ceiling — what you'd
otherwise ship in a `.so`). Two numbers worth knowing: how close to the
native ceiling, and how much over the scalar floor.

### vs hand-tuned native NEON — multi-device

How close does Slim's runtime-emitted code get to **hand-written native
NEON** compiled with `clang -O3`? The
[`:bench`](https://github.com/iamjosephmj/Slim/tree/main/bench) module
answers fair-and-square: same fused 8-stage NEON pipeline both sides,
same direct `ByteBuffer`, same thread, byte-identical output enforced
by a correctness gate — only the dispatch mechanism differs.

Ran on 7 real devices via a cloud test farm:

<table>
<tr>
<td align="center" width="33%"><a href="https://github.com/iamjosephmj/Slim/blob/main/bench/bench-results/pixel-10-pro-xl-android17.png"><img src="https://raw.githubusercontent.com/iamjosephmj/Slim/main/bench/bench-results/pixel-10-pro-xl-android17.png" alt="Pixel 10 Pro XL — Android 17" width="200"/></a><br/><sub>Pixel 10 Pro XL · Android 17</sub></td>
<td align="center" width="33%"><a href="https://github.com/iamjosephmj/Slim/blob/main/bench/bench-results/galaxy-note20-android13.png"><img src="https://raw.githubusercontent.com/iamjosephmj/Slim/main/bench/bench-results/galaxy-note20-android13.png" alt="Galaxy Note20 — Android 13" width="200"/></a><br/><sub>Galaxy Note20 · Android 13</sub></td>
<td align="center" width="33%"><a href="https://github.com/iamjosephmj/Slim/blob/main/bench/bench-results/oppo-a94-5g-android11.png"><img src="https://raw.githubusercontent.com/iamjosephmj/Slim/main/bench/bench-results/oppo-a94-5g-android11.png" alt="Oppo A94 5G — Android 11" width="200"/></a><br/><sub>Oppo A94 5G · Android 11</sub></td>
</tr>
</table>

| Device | Android | 1080p (2 MB) | 4K (8 MB) |
|---|:-:|:-:|:-:|
| Pixel 10 Pro XL | 17 | 11% slower | 6% slower |
| Galaxy A54 5G | 16 | 13% slower | 7% slower |
| Oppo Reno13 F | 15 | 12% slower | **TIE** |
| Galaxy A23 5G | 14 | 9% slower | **TIE** |
| Galaxy Note20 | 13 | **TIE** | **TIE** |
| Galaxy S20 FE 2022 | 12 | 13% slower | **TIE** |
| Oppo A94 5G | 11 | 7% slower | **TIE** |

**TIE = Slim within 5% of JNI on that cell.** Across 7 devices, three
vendors (Google, Samsung, Oppo), and six Android versions (11 → 17),
Slim never loses by more than 13% at 1080p, and matches JNI on 5 of 7
devices at 4K. Full screenshots and the bench module live in the
[`bench/`](https://github.com/iamjosephmj/Slim/tree/main/bench) directory.

### vs scalar Kotlin

The reason a SIMD runtime exists at all. On a Samsung S24 (Cortex-X4,
Android 16), a 16 MB SAXPY kernel:

| Path | Time | Throughput | Speedup |
|:--|---:|---:|---:|
| Hot-path Kotlin scalar (JIT-compiled) | 5.32 ms | 3.0 GB/s | 1.0× |
| Slim with `FloatArray` (eager copy) | 2.22 ms | 7.2 GB/s | 2.4× |
| Slim with `Floats` (zero-copy) | **0.76 ms** | **23.4 GB/s** | **6.95×** |

### Operational characteristics

Per-call dispatch overhead: **~3 µs**. Concurrent dispatch:
**~3 K calls/sec** across 4 coroutines. Cold start: ~3 ms warm /
~10 ms uncached. Probe pool serves up to **8 in-flight** kernels
before blocking.

---

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.iamjosephmj:Slim:0.1.2")
}
```

!!! warning "0.1.2 — V1 internal release"
    Public API surface (`Slim` / `slim {}`) is stable and won't change
    incompatibly. The underlying engine is still validating against new
    Android releases. See **[Production readiness](https://github.com/iamjosephmj/Slim#%EF%B8%8F-production-readiness)**
    for the kill-switch pattern + anti-tamper compatibility checklist
    before shipping.

---

## Where to next

<div class="grid cards" markdown>

-   :material-school: __[Guide](guide/index.md)__

    ---

    Learn ARM64 NEON via Slim. Start from `for` loops and end with
    real kernels. No prior assembly experience required.

-   :material-book-open-variant: __[Cookbook](COOKBOOK.md)__

    ---

    Recipes for common kernels: SAXPY, dot product, color filters,
    blur, threshold, and debugging your own kernels.

-   :material-cog-outline: __[Architecture](ARCHITECTURE.md)__

    ---

    How the runtime works: memfd dual-map, ART entry-point hijack,
    four-tier hidden-API bypass, encoder, label assembler.

-   :material-account-multiple: __[Contributing](CONTRIBUTING.md)__

    ---

    Adding encoder helpers, the testing pattern, ART-internals work,
    and per-vendor bypass tweaks.

</div>
