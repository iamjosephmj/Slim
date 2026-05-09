# Contributing to Slim

Thanks for considering a contribution. This document covers the most
common patterns and the testing/validation discipline used across the
codebase.

## Setup

```bash
git clone https://github.com/iamjosephmj/Slim.git
cd Slim
./gradlew :nativekt:assembleDebug :nativekt:testDebugUnitTest
```

You'll need:
- JDK 17+ (Android Studio's bundled JBR works).
- Android SDK + NDK 27 (API 36 platform).
- For on-device validation: an arm64-v8a Android 12+ device.

## What we welcome

In rough priority order:

1. **New encoder helpers** in `Arm64.kt` covering ARMv8.2-A or
   ARMv8.4-A instructions we don't yet have. The pattern is mechanical
   and self-contained (see §1 below).
2. **Per-vendor bypass tweaks** when one of the four bypass tiers fails
   on a device we haven't tested. The cascade gracefully falls through;
   PRs that add new fallback paths or per-OEM fixes are great.
3. **`Slim` cookbook recipes** — interesting NEON kernels worth sharing.
4. **Bug fixes** with a regression test.
5. **Documentation improvements** — clearer KDoc, better examples,
   typo fixes.

For larger structural work (encoder restructuring, the V3 compile-time
plugin, ARMv7 support), open an issue first to discuss design.

## 1. Adding an encoder helper

This is the most common contribution. The pattern is identical for
every instruction:

### Step 1: Find the encoding

Look up the instruction in the
[ARM Architecture Reference Manual (DDI 0487)](https://developer.arm.com/documentation/ddi0487/latest/),
or just disassemble a reference:

```bash
$ cat > /tmp/t.s <<'EOF'
.text
.global _start
_start:
    fmla v0.4s, v1.4s, v2.4s
EOF
$ clang --target=aarch64-linux-android -c /tmp/t.s -o /tmp/t.o
$ llvm-objdump -d /tmp/t.o
       0: 4e22cc20     fmla   v0.4s, v1.4s, v2.4s
```

So `fmla v0.4s, v1.4s, v2.4s` encodes to `0x4e22cc20`.

### Step 2: Add the helper

In `nativekt/src/main/kotlin/io/simdkt/nativekt/engine/Arm64.kt`, add
to the appropriate section. For NEON FP 3-register ops, find the
existing `fpVec3` template and add:

```kotlin
fun fmla(rd: V, rn: V, rm: V, arr: VArr): Int {
    val sz = arr.size and 0b1
    return fpVec3(arr.q, 0, sz, 0b11001, rd.n, rn.n, rm.n)
}
```

For unfamiliar encoding shapes, add a new private bit-pack helper.
Look at the existing helpers (`addSubReg`, `logicalImm`, etc.) for
patterns.

### Step 3: Write a golden-byte test

In `nativekt/src/test/kotlin/io/simdkt/nativekt/engine/Arm64Test.kt`:

```kotlin
@Test fun fpVector() {
    assertEnc(0x4e22d420.toInt(),
        Arm64.fadd(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4),
        "fadd v0.4s, v1.4s, v2.4s")
    // ... your new instruction here
    assertEnc(0x4e22cc20.toInt(),
        Arm64.fmla(Arm64.V0, Arm64.V1, Arm64.V2, Arm64.VArr.S4),
        "fmla v0.4s, v1.4s, v2.4s")
}
```

For a fresh instruction group, add a new `@Test fun ...` with all the
relevant variants.

### Step 4: Forward to `Arm64Emitter`

In `slim/Arm64Emitter.kt`, add the auto-emit forwarder:

```kotlin
fun fmla(rd: Arm64.V, rn: Arm64.V, rm: Arm64.V, arr: Arm64.VArr) {
    emit(Arm64.fmla(rd, rn, rm, arr))
}
```

This makes it usable inside `slim {}` blocks as `fmla(V0, V1, V2, S4)`.

### Step 5: Verify

```bash
./gradlew :nativekt:testDebugUnitTest
```

If the test fails, the message tells you exactly which bits are wrong:

```
expected=0x4e22cc20 actual=0x4e22dc20
```

XOR'ing those gives the bit difference (`0x1000` = bit 12), which
points at the field you mis-encoded.

### Naming conventions

- Match ARM assembly mnemonics. `add`, `sub`, `fmla`, etc.
- For instructions that overload by operand type (register vs.
  immediate vs. vector), use Kotlin overload resolution: same name,
  different parameter types. The `add` family in `Arm64Emitter` is
  the reference example.
- Vector-specific names: keep the architectural name (`fmla`, not
  `vfmla`).
- Convention `*W` / `*X` suffix when the same op exists on 32 vs.
  64-bit registers and overload resolution can't disambiguate
  (`scvtfS`, `scvtfD`).

## 2. Validating a bypass tweak on a new device

If `Slim.initialize` fails on a device we haven't tested:

1. Reproduce on the device with logcat tag filters `nk` and `nk-jni`:
   ```bash
   adb logcat -s nk:V nk-jni:V
   ```
2. The cascade reports which tier failed. Common patterns:
   - `bypass: meta-reflection failed` — tier 1 always fails on API 31+.
     Expected.
   - `bypass: direct failed (NoSuchMethodException)` — tier 2 expected
     to fail on API 36+.
   - `bypass: no targetSdk slot took effect` — tier 3 failed.
     Investigate VMRuntime field layout for that ROM.
   - `bypass: art::Runtime probe failed` — tier 4 failed. Most likely
     `art::Runtime::instance_` isn't exported, or the policy field is
     past the 8 KB probe window.
3. For tier 4 failures, dump libart.so's dynsym:
   ```bash
   adb shell 'cp /apex/com.android.art/lib64/libart.so /data/local/tmp/'
   adb pull /data/local/tmp/libart.so
   llvm-nm -D libart.so | grep -i runtime | grep instance
   ```
   If `instance_` is missing, we need a different anchor.
4. Open an issue with the device model, Android version, and the
   `nk` logcat output.

## 3. Adding a `slim` cookbook recipe

Add to `docs/COOKBOOK.md` with:

- A clear use case description.
- The kernel code in a `kotlin` fenced block.
- Notes on assumptions (data alignment, size constraints).
- Performance numbers if you have them.

Don't worry about polishing every recipe to perfection — even a sketch
with notes is useful as a starting point for someone else.

## 4. Code style

- **Formatting**: standard `ktfmt` defaults. Run
  `./gradlew :nativekt:ktlintFormat` (when configured) before pushing.
- **Imports**: prefer fully qualified names over wildcards in the
  library; wildcards are fine in tests.
- **Naming**: PascalCase classes, camelCase functions/properties,
  SCREAMING_SNAKE for compile-time constants.
- **Visibility**: `internal` aggressively for anything that's not part
  of the public API. The high-level (`slim` package) and low-level
  (`nativekt` package) surfaces are both public; everything in `engine`
  is internal except where explicitly noted.

## 5. Commits and PRs

- **Commit messages**: imperative mood ("Add fmla helper", not "Added
  fmla helper"). One concept per commit.
- **PRs**: target `main`. Include:
  - What the change does.
  - Why (link to issue if applicable).
  - Test results: `./gradlew :nativekt:testDebugUnitTest` output.
  - On-device verification if the change touches dispatch / bypass.
- **Test the demo**: even pure encoder PRs benefit from running the
  app and verifying the benchmark numbers don't regress.

## 6. Things to avoid

- **Don't add public types lightly.** The "user only writes `slim {}`"
  design philosophy means each new public class costs mindshare.
  Prefer internal helpers; only promote to public after a real consumer
  needs it.
- **Don't bypass the encoder's golden-byte tests.** Every helper has
  one; "trivial" instructions are exactly where mistakes hide.
- **Don't break source compatibility on the high-level API.** The
  `Slim` / `slim()` / `Floats`/`Ints`/`Bytes` surface is contractual.
  Changes there require a version bump and migration notes.
- **Don't add per-vendor #ifdefs to encoder helpers.** ARM64 encoding
  is universal; there's no Samsung-vs-Pixel difference at the
  instruction level. If you need vendor-specific behavior, it belongs
  in the runtime layer (bypass, EP probe), not the encoder.
- **Don't reach into ART internals from user code.** The public API
  intentionally hides `KernelHandle`, `KernelTemplate`, etc. behind
  the `slim` package. If you find yourself needing them, file an issue
  — likely the high-level API needs a new affordance.

## 7. Project structure reference

```
Slim/
├── README.md                       — top-level (GitHub landing page)
├── LICENSE                          — Apache 2.0
├── SDK_PLAN.md                      — historical V1 build plan
├── ENCODER_V2_PLAN.md               — V2 roadmap
├── docs/
│   ├── ARCHITECTURE.md              — runtime internals
│   ├── COOKBOOK.md                  — kernel recipes
│   └── CONTRIBUTING.md              — this file
├── nativekt/                        — the library AAR module
│   ├── README.md                    — module-specific reference
│   └── src/
│       ├── main/
│       │   ├── kotlin/io/simdkt/
│       │   │   ├── slim/            — high-level public API
│       │   │   │   ├── Slim.kt
│       │   │   │   ├── Arm64Emitter.kt
│       │   │   │   ├── Floats.kt
│       │   │   │   ├── Ints.kt
│       │   │   │   └── Bytes.kt
│       │   │   └── nativekt/        — lower-level public API
│       │   │       ├── NativeKt.kt
│       │   │       ├── KernelTemplate.kt
│       │   │       ├── KernelHandle.kt
│       │   │       ├── Linker.kt
│       │   │       ├── Coroutines.kt
│       │   │       └── engine/      — internal
│       │   │           ├── Arm64.kt
│       │   │           ├── Asm.kt
│       │   │           ├── MemoryExecutor.kt
│       │   │           └── Trampoline.kt
│       │   └── cpp/
│       │       ├── trampoline.cpp   — JNI helpers (libnktrampoline.so)
│       │       └── CMakeLists.txt
│       └── test/                    — unit tests
└── app/                              — demo app
    └── src/main/kotlin/com/example/slim/MainActivity.kt
```

## 8. Testing matrix

The CI ideal — even if not yet automated — is:

| Layer | What's tested | How |
|---|---|---|
| Encoder | Every helper produces correct bytes | Golden bytes from `clang+llvm-objdump`, 49 test methods |
| Asm | Forward/backward/conditional branches resolve | Byte-equivalence vs. hand-rolled |
| Linker | Symbol resolution, error cases | Byte-equivalence + error-path tests |
| Bypass | Tier cascade lands on API 36 | On-device, `Slim.isReady` after init |
| Dispatch | EP hijack returns correct results | On-device, SAXPY/brightness against scalar reference |
| Concurrency | 4 threads × 50 calls, no races | On-device, comparing per-element output |
| Coroutine API | suspend dispatch, cancellation propagation | Unit tests + on-device |

Per-PR, run:
1. `./gradlew :nativekt:testDebugUnitTest` (encoder, asm, linker)
2. `./gradlew :app:assembleDebug && adb install` (on a real device)
3. Run the demo, verify the benchmark numbers and concurrency status

If a change touches the bypass cascade, validate on at least one
Pixel and one Samsung device if available.

## Questions

Open an issue or start a discussion. The maintainers are happy to
clarify design decisions or scope changes before you spend time on
something that won't merge.
