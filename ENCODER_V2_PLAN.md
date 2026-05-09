# NativeKt — Encoder & Workflow V2 Plan

Picks up where `SDK_PLAN.md` (V1) left off. V1 shipped a working AAR, ART
EP-hijack dispatch, memfd dual-map, hidden-API bypass, and a 60%-complete
ARM64 encoder validated end-to-end with a NEON SAXPY kernel on Android 16.
This plan closes the remaining gaps so the runtime becomes a credible
general-purpose NEON JIT.

> Prerequisites: V1 in place. All work in this plan is additive — no breaking
> changes to existing public API (`NativeKt.execute`, `NativeKt.executeDirect`,
> `Arm64.*` helpers).

---

## Tiers

Tiered by **leverage per hour**: Tier 1 unblocks every kernel anyone will
write; Tier 5 is for specific consumers and worth deferring.

---

## Tier 1 — Workflow (the biggest QoL wins) (~1 day)

### A1. Label / fixup pass

**Problem.** Branch offsets are raw byte distances today. The SAXPY loop
hand-counted `-28` for the `cbnz` back-edge; anything more complex is fragile.

**Plan.** Add a thin assembler layer over the existing `Arm64.assemble`:

```kotlin
val asm = Asm()
val loop = asm.label()
asm.add(Arm64.ld1(...))
asm.add(Arm64.ld1(...))
asm.add(Arm64.fmla(...))
asm.add(Arm64.st1(...))
asm.add(Arm64.subImm(W3, W3, 4))
asm.cbnz(W3, loop)         // emits a placeholder, fixup at finalize()
val bytes = asm.finalize()  // resolves all labels to byte offsets
```

Two-pass: collect placeholders + label sites, patch immediates at finalize.
Handles `b`, `bl`, `b.cond`, `cbz/cbnz`, `tbz/tbnz`. Out-of-range branches
throw at finalize, not silently miscalculate.

**Files written:**
- `nativekt/src/main/kotlin/io/simdkt/nativekt/engine/Asm.kt` (~120 lines)
- `nativekt/src/test/kotlin/io/simdkt/nativekt/engine/AsmTest.kt` (~80 lines)

**Exit criterion:** SAXPY rewritten with named labels, golden-byte equivalent
to the hand-rolled version.

### A2. `KernelTemplate` dataPtr abstraction

**Problem.** Every kernel must bake its data pointer in via
`loadImm64 x0, #ptr` because ART puts ArtMethod* in x0 at dispatch entry. The
demo's `buildSaxpyKernel(dataPtr)` rebuilds the entire byte array per call.

**Plan.** Compile once, patch immediates per call:

```kotlin
val template = KernelTemplate.compile {
    placeholder(DATA_PTR)              // emits 4 movz/movk slots
    add(Arm64.mov(X1, X0))
    // ... rest of saxpy
}
NativeKt.executeTemplate(template, buf)  // engine patches slots from buf addr
```

Engine cost per call: 4 × `pokeInt` to overwrite the immediate-encoded
movz/movk fields. Kernel byte array allocated once.

**Files written:**
- Add `KernelTemplate` + `executeTemplate` to `MemoryExecutor` / `NativeKt` (~100 lines)
- Demo updated to use template path.

**Exit criterion:** SAXPY runs from a single pre-compiled template across
multiple invocations on different buffers, output still `3*i`.

---

## Tier 2 — Everyday encoder gaps (~0.5 day)

Each helper is mechanical: bit-pack the spec, add a golden-byte test against
`clang+llvm-objdump`. Pattern matches every existing helper in `Arm64.kt`.

| Group | Instructions | Lines | Unlocks |
|---|---|---|---|
| **B1. FP↔int convert** | `fcvtzs`, `scvtf` (S/D, vector & scalar) | ~30 | Audio/video codec int↔float pipelines |
| **B2. FP compare** | `fcmp`, `fcmgt`, `fcmge`, `fcmeq` (S/D + vector) | ~40 | Predicate-driven branchless code |
| **B3. Conditional select** | `csel`, `fcsel`, `csinc`, `csinv`, `csneg` | ~30 | Branchless math (`max`, `clamp`, etc.) |
| **B4. Reciprocal estimates** | `frecpe`, `frecps`, `frsqrte`, `frsqrts` | ~25 | Newton-Raphson divide / sqrt iterations |
| **B5. Bitmask immediates** | `and`/`orr`/`eor`/`tst` with logical-imm | ~120 | `and x0, x1, #0xff` without a scratch reg. **Note: encoding requires the (N, immr, imms) → bitmask algorithm — well-documented but ~80 lines on its own.** |
| **B6. Pre/post-index loads** | `ldr/str/ldp/stp` with `[xn, #imm]!` and `[xn], #imm` | ~50 | Stack frames, `push/pop`-style code |
| **B7. Register-offset loads** | `ldr x0, [x1, x2, lsl #3]` and friends | ~40 | Indexed array access without scratch reg |

**Files written:** All helpers go into `Arm64.kt`; tests appended to
`Arm64Test.kt`. ~315 encoder lines, ~150 test lines.

**Exit criterion:** every new helper has at least one golden-byte assertion
against an LLVM-assembled reference; full test suite passes.

---

## Tier 3 — Specialized encoder (~0.5 day)

| Group | Instructions | Lines | Why |
|---|---|---|---|
| **C1. Saturating arith** | `sqadd`, `uqadd`, `sqsub`, `uqsub`, `sqxtn`, `sqdmulh` | ~50 | Audio limiters, fixed-point DSP |
| **C2. Dot product** | `sdot`, `udot` | ~15 | int8 quantized AI inference |
| **C3. Half-precision FP** | `.4h`/`.8h` arrangement on `fadd/fsub/fmul/fmla/fcvt` | ~40 | FP16 inference paths (Pixel 7+) |
| **C4. Address-relative** | `adr`, `adrp` | ~25 | Constant pools embedded in the kernel |
| **C5. FP min-max numeric** | `fminnm`, `fmaxnm` (NaN-aware variants) | ~15 | IEEE-correct reductions |

**Exit criterion:** test coverage parity with Tier 2.

---

## Tier 4 — Architectural (~3 days)

### D1. Multi-threaded dispatch (~1 day)

**Problem.** `MemoryExecutor.execute` is `@Synchronized` because the EP patch
on the single shared probe method is global state. Concurrent kernels would
race the patch/unpatch.

**Plan.** Per-thread probe-method allocation:

- `Probes` already has 8 static methods (`probe0..probe7`).
- Hand each `execute()` call a fresh probe via a thread-local cursor or a
  `BlockingQueue<Method>` pool.
- Each thread patches its own probe, calls, restores — no contention.
- Pool of N probes allows up to N concurrent dispatches; auto-grow if
  exhausted (block or generate more probes via dynamic class generation —
  the latter is V3 work).

**Exit criterion:** stress test runs 4 threads × 1000 kernels concurrently,
all complete with correct output, no crashes.

### D2. Kernel linking (`bl` to other kernels) (~1 day)

**Problem.** Each kernel is a flat blob; can't call shared subroutines.
Useful for libraries of small helpers (e.g., a `softmax` that calls into a
`exp_approx`).

**Plan.** Two-stage assemble + link:

- Each kernel compiled to bytes + a relocation list (`KernelObj`).
- Linker takes a list of `KernelObj`, places them sequentially in one
  memfd region, patches the `bl` immediates with computed offsets.
- Engine's `execute` accepts a linked image instead of a single kernel.

**Exit criterion:** demo calls a small `square` subroutine from a `sumsq`
kernel; output matches direct calculation.

### D3. Multi-region / kernel reuse (~1 day)

**Problem.** Today each `execute()` allocates a fresh memfd region. For
hot kernels invoked thousands of times, that's wasted setup.

**Plan.** Cache compiled regions:

- `KernelHandle` returned at compile time (memfd + RX address held alive).
- `executeHandle(handle, dataPtr)` — no allocation, just dispatch.
- Region freed when handle closed.

**Exit criterion:** SAXPY benchmark runs 10K invocations with one allocation;
average call cost drops to <2 µs (EP patch + invoke + unpatch only).

---

## Tier 5 — Deferred (~1-2 weeks each, only when needed)

Add when a specific consumer demands them. Speculative addition is bloat.

### E1. Crypto (AES, SHA-1, SHA-2, SHA-3, PMULL)
~80 lines, but each instruction has a unique encoding shape. Wait for an
actual TLS or hash kernel to need it.

### E2. SVE / SVE2
Variable-length vectors, predicate registers, completely different
instruction shapes. ~500 line addition. Pixel 9 / SD 8 Gen 4+ only — most
target devices today don't expose SVE to userspace anyway.

### E3. PAC strict-mode kernels
Pixel 8+ on certain branches reject the EP hijack because `ret` from a
non-PAC'd address fails sealed-return checks. Workaround: kernel prologue
issues `paciasp`, epilogue issues `autiasp`. Already encoded; needs the
template helper to inject. Niche.

### E4. Floating-point exceptions
No way to enable IEEE-754 trap signaling from Kotlin. Could expose via JNI
helper that toggles `FPCR`. Niche.

### E5. SME (Scalable Matrix Extension)
Apple silicon and ARMv9.2-A SoCs have it; Android devices mostly don't
yet. Defer until 2027+ class hardware ships in volume.

---

## Sequencing

The order matters because Tier 1 makes Tiers 2-4 pleasant to write:

1. **A1 Label pass** — used by every test in subsequent tiers
2. **A2 KernelTemplate** — used by demo + benchmarks in Tier 4
3. **B1-B7 Tier 2** — flat list, do in any order
4. **C1-C5 Tier 3** — flat list
5. **D1 Multi-thread** — independent
6. **D2 Linking** — depends on A1 (uses labels for inter-kernel branches)
7. **D3 Region reuse** — independent

Total Tier 1+2+3+4 ≈ **5 focused days**. Doubles the encoder's reach + the
runtime's ergonomics without adding ART internals risk.

---

## Open questions before starting

1. **Should `Asm` be the only public API for kernel building, or stay alongside the raw `Arm64.assemble(List<Int>)`?** I'd keep both: low-level for one-shot kernels, `Asm` for anything with branches.
2. **`KernelTemplate` slot model:** named placeholders (`DATA_PTR`, `LEN`, ...) or positional? Named is friendlier; ~10 extra lines.
3. **Multi-threaded dispatch — how big should the probe pool be?** 8 today is plenty for V2; revisit when a real workload needs more.
4. **Linking:** flat namespace or scoped (per-package)? Flat is simpler; scoped is what real linkers do. Pick flat for V2.
5. **Should Tier 5 items be split into a separate `ENCODER_V3_PLAN.md`?** Probably — V2 is what's worth committing to a dated plan; V3 is "when someone asks."

---

## What this does NOT cover

- Hexagon DSP, QNN, NPU backends — out of scope per project memory.
- Vulkan compute path — separate workstream.
- Building a full debugger/disassembler — `llvm-objdump` is fine for now.
- Auto-vectorization / a higher-level kernel DSL — would belong in a
  separate `kernels/` module on top of the encoder.
