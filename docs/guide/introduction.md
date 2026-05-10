# 1. Introduction

!!! info "Status"
    Chapter 1 placeholder — full content in progress. The outline below
    is the planned chapter structure.

## What you'll learn

By the end of this chapter you'll be able to:

- Explain what SIMD is in plain language (do 4 floats at once instead
  of one at a time).
- Read a NEON instruction and predict roughly what it does.
- Write your first `slim { }` kernel, run it, and verify the output.
- Use the disassembler to see what your Kotlin compiled to.
- Compare against a Kotlin scalar baseline and measure the speedup.

You don't need to know any assembly going in.

## Outline

### 1.1 The problem
- The "tight loop is slow" pattern in image / audio / ML preprocessing.
- Why the Kotlin/Java JIT can't fix it on ARM.

### 1.2 What SIMD actually is
- One operation, multiple data lanes.
- Floats vs ints, scalar vs vector.
- The mental model: `v0.4s` = a 128-bit register interpreted as 4 floats.

### 1.3 Your first kernel
- Setting up Slim.
- The simplest possible kernel: `y[i] = x[i] * 0.5` over a buffer.
- The DSL line by line.

### 1.4 Reading the disassembly
- Setting `Slim.debug = true` and calling `Slim.preview`.
- What each column means.
- The shape of a NEON loop: load, compute, store, advance, branch.

### 1.5 Benchmarking
- The Kotlin scalar baseline.
- The Slim version.
- How much speedup you should expect (and why it might be less than
  6.95× on your kernel).

### 1.6 Where to go next
- Chapter 2: The register set and arrangements.
- Chapter 3: Branches, labels, and loops.

---

*This chapter is a stub while the full guide is written. The structure
above is the planned shape — feedback welcome via a GitHub issue.*
