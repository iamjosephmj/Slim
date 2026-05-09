# Slim — Architecture

How the runtime actually works. Read this if you want to extend Slim,
debug a bypass failure on a new device, or just understand why ARM64
shellcode dispatched from Kotlin doesn't need JNI.

## TL;DR

```
                ┌─────────────────────────────┐
                │   slim(data) { ... }        │  ← user code (Kotlin DSL)
                └──────────────┬──────────────┘
                               │
                ┌──────────────▼──────────────┐
                │   Arm64Emitter (DSL → bytes) │  ← register/instr surface
                └──────────────┬──────────────┘
                               │
                ┌──────────────▼──────────────┐
                │   Asm (label fixup pass)    │
                └──────────────┬──────────────┘
                               │
                ┌──────────────▼──────────────┐
                │   Arm64 (pure encoder)      │  ← bit-pack → Int opcode
                └──────────────┬──────────────┘
                               │ ByteArray
                ┌──────────────▼──────────────┐
                │   KernelTemplate cache      │  ← LRU keyed by bytes
                └──────────────┬──────────────┘
                               │ on miss
                ┌──────────────▼──────────────┐
                │   memfd_create + mmap RW+RX │  ← JIT memory
                └──────────────┬──────────────┘
                               │
                ┌──────────────▼──────────────┐
                │   ART entry-point hijack    │  ← dispatch (no JNI)
                └─────────────────────────────┘
```

## 1. Memfd dual-map JIT memory

Android's SELinux policy on app domains forbids `mprotect` from W to X
on anonymous memory (`execmem` denial). The classic JIT pattern of
"allocate RW page, write code, mprotect to RX, execute" is dead.

The workaround: **two mappings of the same memfd page**, one R/W and one
R/X. The kernel sees these as file-backed mappings (memfd is a file in
shared memory), and SELinux allows file-backed RX even when it forbids
anonymous-memory RX.

The allocation order matters:

1. `memfd_create("nk-region", 0)` → fd
2. `ftruncate(fd, page_aligned_size)`
3. `mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0)` → rwAddr
4. **Write the encoded instruction bytes via rwAddr**
5. `mmap(NULL, size, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0)` → rxAddr
6. **Execute via rxAddr** (jump in via the EP hijack, see §3)

Step 5 happens *after* step 4. The fresh RX virtual address has never
been speculatively fetched by the CPU, so the I-cache contains nothing
stale for it; the bytes the CPU reads on first execution are exactly
what we wrote in step 4. No `clear_cache` syscall needed.

If a region is reused (RX address re-executed after RW writes), the
caller MUST issue an I-cache flush — `Trampoline.clearCache(addr, len)`
emits the canonical ARM64 sequence (`dc cvau` / `dsb ish` / `ic ivau` /
`dsb ish` / `isb`).

The allocation goes through `libcore.io.Os.mmap` (not the public
`android.system.Os.mmap`, which doesn't exist), reflectively. This is
what triggers the hidden-API check on API 28+ and motivates §4.

**Code**: `engine/MemoryExecutor.kt::Region` — the dual-map allocator.

## 2. The ARM64 encoder (`Arm64.kt`)

Pure Kotlin. ~150 helper functions, each returning a 32-bit `Int`
opcode. No state, no side effects. The encoding constants come straight
from the [ARM Architecture Reference Manual (DDI 0487)](https://developer.arm.com/documentation/ddi0487/latest/).

For example, `MOVZ Xd, #imm16, lsl #shift`:

```
ARM ARM Format:  sf  opc  100101  hw  imm16   Rd
                 1   10                       (xd)
```

Encoded as:

```kotlin
fun movz(rd: X, imm16: Int, shift: Int = 0): Int {
    val hw = when (shift) {
        0 -> 0; 16 -> 1; 32 -> 2; 48 -> 3
        else -> error("shift must be 0/16/32/48")
    }
    return (1 shl 31) or (0b10 shl 29) or (0b100101 shl 23) or
            (hw shl 21) or (imm16 shl 5) or rd.n
}
```

### Validation: golden bytes from clang

Every helper has a unit test that compares the encoded `Int` against
output from `clang --target=aarch64-linux-android -c | llvm-objdump -d`:

```kotlin
@Test fun movWide() {
    assertEnc(0xd2995fc0.toInt(), Arm64.movz(Arm64.X0, 0xCAFE), "movz x0, #0xCAFE")
    assertEnc(0xf2b81bc0.toInt(), Arm64.movk(Arm64.X0, 0xC0DE, shift = 16),
        "movk x0, #0xC0DE, lsl 16")
    // ...
}
```

49 test methods cover every shipped helper. Adding a new instruction is
a 4-step ritual: find the encoding in the ARM ARM, write the helper,
run `clang+llvm-objdump` for a reference, add the assertion. New
instructions land cleanly in ~5 minutes.

**Coverage**: ~150 helpers across moves, GP/SIMD memory, integer/FP
arithmetic, NEON FP/integer/misc/logical, saturating arithmetic, dot
product, half-precision FP, conditional select, bitmask immediates,
PC-relative addressing, PAC/BTI, and system ops. See the comprehensive
list in the class KDoc on `Arm64`.

## 3. ART entry-point hijack dispatch

The actually novel part. ART runs every Java/Kotlin method via a
function pointer stored in `ArtMethod`:

```cpp
class ArtMethod {
    GcRoot<Class> declaring_class_;     // offset 0x00
    uint16_t access_flags_;             // offset 0x04
    uint16_t method_index_;             // offset 0x06
    uint16_t hotness_count_;            // offset 0x08
    uint16_t imt_index_;                // offset 0x0A
    PtrSizedField data_;                // offset 0x10
    void* entry_point_from_quick_compiled_code_;  // offset 0x18 ← THIS
};
```

ART's "quick" dispatch path — used for normal Java method calls
including `Method.invoke` — sets up the calling convention, then
`bl entry_point_from_quick_compiled_code_`. Whatever address we put
there is what gets executed.

So Slim's dispatch loop is:

1. Pick a probe `Method` from the pool (we pre-create 8
   `Probes.probe0..probe7` static no-arg methods).
2. Read the probe's `ArtMethod*` via reflection on the
   `java.lang.reflect.Method.artMethod` long field.
3. Read the saved EP pointer at `artMethod + 0x18` (offset discovered
   per device, cached at `<cacheDir>/nk_ep.bin`).
4. **Patch** `artMethod + 0x18` with the address of our shellcode (the
   memfd region's RX mapping).
5. Call `method.invoke(null)` reflectively. ART's quick dispatch jumps
   into our shellcode.
6. Shellcode executes, `ret`s.
7. **Restore** `artMethod + 0x18` to the saved EP.
8. Return the probe to the pool.

Steps 3, 4, 7 are `Unsafe.peekLong` / `pokeLong` calls — ~50 ns each.
Step 5 is a normal Kotlin reflective call (~1 µs of `Method.invoke`
overhead, mostly unavoidable).

**Total dispatch overhead**: ~3 µs per `slim {}` call. For NEON kernels
in the µs–ms range that's noise; for sub-µs kernels (a single ALU
operation), it's the dominant cost.

### The kernel ABI

ART's quick ABI puts `ArtMethod*` in `x0` on entry. Slim's auto-
prologue replaces this with the user's data pointer via a 4-instruction
`movz`/`movk`/`movk`/`movk` sequence. The runtime patches the four
`imm16` fields in those instructions per dispatch with the actual
buffer address — that's the "data pointer slot" mechanism.

The kernel ends with `ret` (auto-epilogue). ART expects `x30` to hold
the return address; we don't touch `x30` so the epilogue's `ret`
returns into ART's caller-side bookkeeping cleanly.

**Code**: `engine/MemoryExecutor.kt::dispatchViaEpHijack`,
`Probes` object (static probe methods), `KernelHandle.run` (the
slot-patch + dispatch loop for compile-once kernels).

## 4. Hidden-API bypass

ART since API 28 enforces "hidden API" restrictions on reflective
access to internal classes/methods/fields — including the
`libcore.io.Os.mmap` we need for the dual-map allocator and the
`Method.artMethod` field we need to read for the EP hijack.

Slim defeats this with a four-tier cascade. Each tier is tried in
order; the first one that works wins. On API 36 only tier 4 succeeds;
on older APIs tier 1 or 2 typically suffice.

### Tier 1: Meta-reflection

Get `Class.getDeclaredMethod` reflectively, then use it to obtain
`VMRuntime.setHiddenApiExemptions("L")`. The trick: ART's caller
attribution for the hidden-API check looks at the immediate calling
class on the stack. Going through `Method.invoke` (boot classpath)
makes that frame a system frame, exempt.

Worked through API 30. API 31+ closed the loophole — the stack walker
now skips reflection frames properly and the check sees the real
caller (Slim's code, app domain) → denied.

### Tier 2: Direct call

`VMRuntime.class.getDeclaredMethod("setHiddenApiExemptions", ...)`.
Works on ROMs that haven't applied the latest enforcement patches.
Increasingly rare.

### Tier 3: `targetSdkVersion` poke (Java side)

ART consults `runtime.targetSdkVersion < 28` for some hidden-API
exemptions (the `max-target-*`-flagged ones). If we can flip that
field on the JVM-side `VMRuntime` instance to `27`, those gates open.

Use `sun.misc.Unsafe.theUnsafe` (still reachable on modern Android via
`getDeclaredFields()` plural, which returns hidden fields too without
firing the per-field check) to get an Unsafe instance, then probe the
`VMRuntime` instance's int fields for the `targetSdkVersion` slot
(typically at offset 0x14), write `27`, verify by attempting to resolve
a previously-blocked hidden method.

This works for **`max-target-*`-flagged APIs only**. The truly
blocklisted ones (`mmap`, `setHiddenApiExemptions`) are unconditional —
they're denied regardless of `targetSdkVersion`. So tier 3 is
incomplete on its own.

### Tier 4: `art::Runtime::hidden_api_policy_` poke (native side)

The actual lever. ART's hidden-API check uses
`Runtime::Current()->GetHiddenApiPolicy()` — an enum stored as a 4-byte
field somewhere in the C++ `Runtime` struct. If we can write `0`
(`kDisabled`) to that field, **all** hidden-API checks are short-
circuited to allow.

Three steps:

1. **Find `art::Runtime*`**: ELF-parse `libart.so` from disk to locate
   the symbol `_ZN3art7Runtime9instance_E` (`art::Runtime::instance_`).
   Add the libart base address from `/proc/self/maps` to get the
   absolute address of the `instance_` slot. Dereference: that's the
   live `art::Runtime*`. The JNI helper in `libnktrampoline.so`
   (`Trampoline.artRuntimeAddr()`) does this.
2. **Probe for the policy field**: the `Runtime` struct is large
   (~4-8 KB on Android 14+). Walk its int slots looking for value `1`
   (`kJustWarn`) or `2` (`kEnabled`) — the default values of the policy
   enum. For each candidate, write `0`, verify by trying to resolve
   `libcore.io.ForwardingOs.mmap` reflectively. If verification
   succeeds, you found the field. If not, restore the original value
   and continue.
3. **Cache the offset**: stash the offset in
   `<cacheDir>/nk_policy.bin` so subsequent cold starts skip the probe.

Why probe rather than hard-code: the offset varies across Android
versions and minor ART updates. On Samsung S24 / Android 16 it's
`+0x43c`. The probe finds it once and caches forever (or until the
device updates ART, at which point it re-probes).

**Code**: `MemoryExecutor.bypassHiddenApi` (the cascade),
`cpp/trampoline.cpp::Java_..._artRuntimeAddr` (the libart locator).

## 5. The label / fixup pass (`Asm.kt`)

The pure encoder takes byte offsets directly: `Arm64.cbnz(W3, -28)`.
For trivial loops fine; for anything more complex the byte-counting is
brittle. `Asm` adds named labels.

Two-pass:

1. **Forward pass** (during `add` / `cbnz(rt, label)` calls): emit
   instructions; for branches with labels, emit a placeholder zero and
   record a `Fixup(byteOffset, label, patcher)` where `patcher` is a
   closure that takes the resolved relative byte offset and produces
   the patched 32-bit opcode.
2. **Backward pass** (in `assemble()`): walk the fixup list, look up
   each target label's bound byte offset, compute `relative = target -
   site`, call `patcher(relative)`, write the result into the
   instruction stream at `site`. Throw if any label is unbound or any
   offset is out of range.

The label resolution is per-encoding: `b/bl` accept ±128 MB
(26-bit signed × 4); `b.cond/cbz/cbnz` accept ±1 MB; `tbz/tbnz` accept
±32 KB. Out-of-range offsets fail clearly at `assemble()` time, not at
runtime with a corrupt branch.

**Code**: `engine/Asm.kt`.

## 6. Kernel cache

`slim(data) { body }` hashes the body's assembled bytes and looks up a
`KernelHandle` in an LRU cache (32 entries, content-keyed, per-process).

- **Hit**: reuse the existing handle's memfd region; just patch the
  data pointer and dispatch. ~3 µs.
- **Miss**: assemble the bytes, allocate a new memfd region, write
  bytes, build a `KernelHandle`, insert into cache (evicting the LRU
  entry, whose handle is closed). First call for a given body is the
  slow one (~20 µs); subsequent calls hit.

The cache is keyed on byte content — kernels parameterized by *runtime*
values (e.g., `loadImm32(W3, count)` where `count` varies at the call
site) recompile on every unique value and churn the cache. The fix is
to bake compile-time constants into the body via the closure capturing
`val n = ...`, and reserve runtime variation for parameters loaded
*from* memory inside the kernel.

Per-handle concurrency control via `Mutex` — concurrent calls on the
same kernel serialize (the slot-patch step is shared mutable state),
different kernels parallelize via the probe pool.

**Code**: `slim/Slim.kt::SlimCache`.

## 7. Probe pool & multi-threaded dispatch

The dispatch loop in §3 needs a `Method` object whose EP it can patch.
A single shared method would force serialization across all `slim {}`
calls process-wide.

The pool: 8 static methods (`Probes.probe0` through `probe7`), each
with its own `(Method, ArtMethod*)` pair, in a
`BlockingQueue<ProbeSlot>`. Each dispatch:

1. `pool.take()` — blocks if all 8 are in flight.
2. Patch this slot's EP, call, restore.
3. `pool.put(slot)` — releases.

8 concurrent kernels run without contention; beyond that, threads
queue. The pool is populated lazily on first dispatch.

`KernelHandle` is single-writer per its KDoc — different handles can
dispatch in parallel, the same handle serializes (because the
data-pointer slot patch is per-handle state). The high-level `slim {}`
API wraps each cached handle in a `Mutex` to make this safe.

**Code**: `engine/MemoryExecutor.kt::probePool`,
`engine/MemoryExecutor.kt::dispatchViaEpHijack`.

## 8. Coroutine integration

`slim()` is a `suspend` function. Internally it calls `withContext`
on the user's `CoroutineContext` (defaults to `Dispatchers.Default`)
to route the blocking dispatch off the calling coroutine's thread.

The dispatch itself is blocking — once ART jumps into the kernel,
control returns only on `ret`. Coroutine cancellation cannot interrupt
a kernel mid-flight; the cancellation honors at the next suspension
point after the kernel returns.

**Code**: `slim/Slim.kt` (top-level `slim` overloads),
`nativekt/Coroutines.kt` (lower-level extensions).

## 9. The trampoline (`libnktrampoline.so`)

A small JNI helper (~5 KB stripped) with three native functions:

- **`artRuntimeAddr()`** — locates `art::Runtime*` via ELF parsing of
  `libart.so`. Used by tier 4 of the bypass cascade.
- **`callAndCheck(codePtr, dataPtr, magic)`** — fallback dispatch via
  plain JNI for devices where the EP hijack is rejected (PAC/BTI
  strict, anti-tamper SDK, vendor-patched ART). Adds ~150 ns of JNI
  overhead per call.
- **`clearCache(addr, length)`** — `__builtin___clear_cache` wrapper.
  Not used on the normal path (the memfd dual-map order avoids cache
  staleness) but exposed for callers that reuse RX regions.

The library is `arm64-v8a` only and ships in the AAR's `jni/arm64-v8a/`.

**Code**: `cpp/trampoline.cpp`, `cpp/CMakeLists.txt`.

## 10. Putting it all together

A single `slim(data) { mov(X1, X0); ... }` call:

1. **DSL evaluation** — Kotlin invokes the lambda with a `SlimScope`
   receiver. Each instruction call (`mov`, `ld1`, etc.) emits a 32-bit
   opcode via the underlying `Arm64.foo(...)` encoder, fed into an
   `Asm` instance.
2. **Auto-prologue/epilogue** — the runtime prepends 4 placeholder
   `movz/movk` instructions and appends a `ret`.
3. **Two-pass assemble** — `Asm.assemble()` resolves any labels,
   produces a final `ByteArray`.
4. **Cache lookup** — hash the bytes, look up in the LRU cache.
5. **(On miss)** allocate a memfd region (RW + RX), write bytes to RW,
   create a `KernelHandle`, insert into cache.
6. **Acquire mutex** — per-handle, serialize against concurrent calls
   on the same kernel.
7. **Acquire probe slot** — `take()` from the pool of 8.
8. **Patch data pointer slots** — write the buffer's address into the
   4 `imm16` fields of the prologue.
9. **Save EP, patch EP** — read the probe's `entry_point_from_quick_
   compiled_code_`, replace with the kernel's RX address.
10. **Reflective invoke** — call `probe.invoke(null)`. ART jumps into
    the kernel.
11. **Kernel runs** — your NEON code reads/writes the buffer at `x0`.
12. **`ret`** — control returns to ART, which returns to the
    `Method.invoke` plumbing.
13. **Restore EP, release probe** — un-patch and return to pool.
14. **Release mutex**.
15. **Return** — control returns to the caller's coroutine, possibly
    on a different thread depending on the dispatcher.

End-to-end: ~3 µs of Slim overhead (steps 6, 7, 8, 9, 13, 14) plus
whatever your kernel takes.

## Where to read further

- **`engine/Arm64.kt`** — the encoder. Class-level KDoc enumerates the
  instruction surface.
- **`engine/MemoryExecutor.kt`** — the dispatch core. `init`, `bypass-
  HiddenApi`, `probeEpIndex`, `dispatchViaEpHijack`, `Region.allocate`.
- **`engine/Asm.kt`** — the label/fixup pass.
- **`slim/Slim.kt`** — the user-facing top-level API + cache.
- **`slim/Arm64Emitter.kt`** — the auto-emit DSL surface.
- **`KernelHandle.kt`** — long-lived dispatch handles.
- **`Linker.kt`** — multi-kernel `bl <symbol>` resolution.
- **`cpp/trampoline.cpp`** — JNI helpers.

For practical examples of writing kernels, see
[`COOKBOOK.md`](COOKBOOK.md). For contributing, see
[`CONTRIBUTING.md`](CONTRIBUTING.md).
