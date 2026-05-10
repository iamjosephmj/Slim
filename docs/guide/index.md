# The Slim guide

A teaching guide for writing ARM64 NEON kernels in Kotlin via Slim.

## Chapters

1. [Introduction](introduction.md) — what NEON is, why it matters, what
   Slim does for you, and your first kernel.

*(more chapters in progress — see the [tracking issue](https://github.com/iamjosephmj/Slim/issues) for what's planned)*

## Who this guide is for

Android developers who:

- Have written `for` loops over `IntArray` / `FloatArray` and felt
  them get slow on real workloads (image filters, audio buffers,
  ML preprocessing).
- Want to make tight loops faster without dropping into C++ + NDK.
- Have never written assembly and don't want to start a build
  pipeline just to learn.

You don't need prior NEON experience. You don't need to know what a
"Q register" is. By the end of Chapter 1 you'll have written a kernel
and inspected its disassembly side-by-side with the originating Kotlin.

## What this guide isn't

This isn't a comprehensive ARM64 reference. ARM's
[official docs](https://developer.arm.com/documentation/ddi0596/latest/Base-Instructions/)
are exhaustive and well-written; if you need to look up an instruction
that isn't covered here, go there.

This guide teaches **just enough NEON** to write meaningful kernels
for the workloads most Android devs actually have — image processing,
audio convolution, ML preprocessing — and leaves SVE2, SME, crypto,
and the corners of the architecture out of scope on purpose.

## How to read it

- Follow chapters in order if you're new to SIMD; each builds on the
  previous.
- Skip ahead if you already know NEON and just want the Slim syntax.
- Every chapter has runnable Kotlin examples. Copy them into a fresh
  Android project that depends on
  `com.github.iamjosephmj:Slim:0.1.0` and run them as you read — the
  feedback loop is the point.

## A note on the disassembler

Slim ships with a built-in disassembler. Throughout this guide you'll
see kernels followed by their decoded output:

```
  0000  aa0003e1  mov    x1, x0               // example.kt:42
loop:
  0004  4cc07c20  ld1    {v0.4s}, [x1]        // example.kt:44
  0008  6e20dc00  fmul   v0.4s, v0.4s, v0.4s  // example.kt:45
```

That's not a screenshot. It's the actual output of
`Slim.preview { ... }` with `Slim.debug = true`, copy-pasted. You can
reproduce every block in this guide by setting `Slim.debug = true` and
calling `Slim.preview` on the kernel — the disassembly tells you what
your Kotlin compiled to, which is the fastest way to build correct
mental models for what NEON does.
