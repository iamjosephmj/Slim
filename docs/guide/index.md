# The Slim guide

Welcome. This is the teaching guide for ARM64 NEON via Slim — written
for Android developers who haven't written assembly before but have
felt a Kotlin loop go slow on real workloads.

The guide is meant as a **read in order**. Each chapter builds on the
previous, with worked examples you can run and measure as you go. By
the end you'll have written, debugged, and benchmarked NEON kernels
on your own.

---

## Why this guide exists

Most ARM64 / NEON tutorials assume C++ + intrinsics + a build
pipeline. They're written for systems people who've already written
assembly. They start with mode bits, ABI conventions, and a 60-page
reference dump.

That's not how a working Android developer learns SIMD. You learn it
because your image filter is too slow, you tried writing it in C++,
the JNI overhead ate your savings, and the build matrix gave you a
headache. You want to write the same Kotlin you've always written —
with one block in the middle that runs at native NEON throughput.

Slim gives you that. This guide teaches you how to use it, building
from "what is SIMD?" to "I just shipped a real kernel" without
dropping into intrinsics or C++ once.

---

## What's in scope

- The instruction subset that covers ~80% of image / audio / ML
  preprocessing kernels. Loads, stores, arithmetic, compare, branch,
  the half-dozen NEON instructions you reach for over and over.
- The DSL — how to write it, how to read disassembly, how to
  iterate.
- Performance tuning at the kernel level — when to use which
  arrangement, when SIMD wins, when scalar is just as fast.
- Integration patterns — covered in depth in the
  [Cookbook](../COOKBOOK.md) but referenced from the guide.

---

## What's not in scope

- A comprehensive ARM64 reference. ARM's
  [official docs](https://developer.arm.com/documentation/ddi0596/latest/Base-Instructions/)
  are exhaustive and well-written; if you need to look up an
  instruction not covered here, go there.
- SVE2, SME, crypto extensions, or atomic operations. All useful,
  none in scope.
- Auto-vectorization theory or compiler internals. We're writing the
  vector code by hand — that's the point.

---

## Chapter index

### [1. Introduction — your first kernel](introduction.md)
*Available now.* Brighten a 16 MB float buffer **7× faster** than the
JIT-compiled Kotlin scalar version, in 9 lines of inline DSL. Walks
through the kernel line by line, decodes the disassembly, runs a
benchmark, and demystifies what ART is doing under the hood. No
prior assembly experience required.

### 2. Registers and arrangements *(coming)*
The 96 registers in scope inside `slim { }`. What `B16`/`H8`/`S4`/
`D2` actually mean bit by bit, when to choose which, the difference
between scalar and vector use of the V registers, signed vs unsigned
byte semantics.

### 3. Branches, labels, and control flow *(coming)*
`cbz`, `cbnz`, `tbz`, `tbnz`, conditional branches across all 16
condition codes, structured loops, early exits, the forward-reference
pattern for if/else.

### 4. Memory access patterns *(coming)*
`ld1`/`st1` variants, `ldp`/`stp` for paired loads, `ld1r` for
replicate-load, addressing modes (offset / pre-indexed / post-
indexed), alignment requirements, prefetch hints.

### 5. Common kernel shapes *(coming)*
The repeating structures across image / audio / ML kernels: the
streaming SAXPY, the horizontal reduce, the lookup-table indirection,
the saturating-narrow output stage. With worked examples for each.

### 6. Debugging + iterating *(coming)*
The disassembler in detail, common bugs, how to bisect a misbehaving
kernel, how to read perf counter output, when to use the `raw()`
escape hatch for unbound instructions.

---

## Who this guide is for

Android developers who:

- Have written `for` loops over `IntArray` / `FloatArray` and felt
  them get slow on real workloads (image filters, audio buffers,
  ML preprocessing).
- Want to make tight loops faster without dropping into C++ + NDK.
- Have never written assembly and don't want to start a build
  pipeline just to learn.

You don't need prior NEON experience. You don't need to know what a
"Q register" is. By the end of [Chapter 1](introduction.md) you'll
have written a kernel and inspected its disassembly side-by-side
with the originating Kotlin.

---

## How to read it

- **Follow chapters in order** if you're new to SIMD. Each builds on
  the previous.
- **Skip ahead** if you already know NEON and just want the Slim
  syntax — the Cookbook is probably the better entry point in that
  case.
- **Run every example.** Copy them into a fresh Android project that
  depends on `com.github.iamjosephmj:Slim:0.1.0` and run them as you
  read. The disassembler-in-the-loop feedback is the point — it's
  what makes Slim a teaching tool, not just a runtime.

---

## A note on the disassembler

Throughout this guide you'll see kernels followed by their decoded
output:

```
  0000  aa0003e1  mov    x1, x0               // example.kt:42
loop:
  0004  4cc07c20  ld1    {v0.4s}, [x1]        // example.kt:44
  0008  6e20dc00  fmul   v0.4s, v0.4s, v0.4s  // example.kt:45
```

That's not a screenshot. It's the actual output of `Slim.preview { }`
with `Slim.debug = true`, copy-pasted. You can reproduce every
disassembly block in this guide by setting `Slim.debug = true` and
calling `Slim.preview` on the kernel.

The disassembly is the fastest way to build correct mental models for
what NEON does — it tells you exactly what bytes the CPU will execute
when your kernel runs.

---

[**Start with Chapter 1 →**](introduction.md){ .md-button .md-button--primary }
[Cookbook](../COOKBOOK.md){ .md-button }
[GitHub](https://github.com/iamjosephmj/Slim){ .md-button }
