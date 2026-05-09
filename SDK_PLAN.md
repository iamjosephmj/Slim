# NativeKt SDK — Build Plan

Plan for packaging the existing `MemoryExecutor` + `NativeKt` code into a publishable Android library AAR with a complete ARM64 encoder, JNI trampoline, demo app, tests, and Maven publishing.

> Prerequisites: option **C** chosen — write full real versions of `Trampoline` and `Arm64`, not stubs.

---

## Module name

`nativekt` — matches the existing `io.simdkt.nativekt` package.

Maven coordinates: `io.simdkt:nativekt:0.1.0`.

---

## Project shape

```
Slim/
├── settings.gradle.kts           # add :nativekt
├── build.gradle.kts              # add maven-publish to plugin classpath
├── nativekt/                     # NEW — the library AAR
│   ├── build.gradle.kts
│   ├── consumer-rules.pro
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── kotlin/io/simdkt/nativekt/
│       │   │   ├── NativeKt.kt
│       │   │   └── engine/
│       │   │       ├── MemoryExecutor.kt
│       │   │       ├── Trampoline.kt
│       │   │       └── Arm64.kt
│       │   └── cpp/
│       │       ├── CMakeLists.txt
│       │       └── trampoline.cpp
│       ├── test/                 # unit tests (Arm64 encoder golden bytes)
│       └── androidTest/          # instrumented (real-device EP hijack)
└── app/                          # existing — becomes demo + bench harness
    └── src/main/kotlin/...       # MainActivity that runs a probe + small kernel
```

---

## Phase 1 — Gradle plumbing (~30 min)

**Files written:**
- `settings.gradle.kts` (modified) — add `include(":nativekt")`
- `nativekt/build.gradle.kts` — `com.android.library` plugin, `minSdk = 31`, ABI filter `arm64-v8a`, NDK 27+, `externalNativeBuild { cmake { ... } }`, `maven-publish` plugin, publication config
- `nativekt/src/main/AndroidManifest.xml` — bare manifest with package
- `nativekt/consumer-rules.pro`, `nativekt/proguard-rules.pro` — empty placeholders
- `app/build.gradle.kts` (modified) — add `implementation(project(":nativekt"))`, raise `minSdk` to 31

**Exit criterion:** `./gradlew :nativekt:assembleRelease` produces an empty AAR.

---

## Phase 2 — Place existing code (~10 min)

Copy the two existing files into the module unchanged:
- `nativekt/src/main/kotlin/io/simdkt/nativekt/NativeKt.kt`
- `nativekt/src/main/kotlin/io/simdkt/nativekt/engine/MemoryExecutor.kt`

**Exit criterion:** files in place; compilation will fail at this point because `Trampoline` and `Arm64` references don't exist yet — that's expected.

---

## Phase 3 — `Arm64.kt` encoder (~3–4 hours)

A real ARM64 instruction encoder, scoped pragmatically to what Slim's kernels will actually emit. Single Kotlin object with helper methods returning `Int` opcodes; final `assemble(List<Int>): ByteArray` packs little-endian.

### Coverage in V1

| Category | Instructions |
|---|---|
| Register types | `X`, `W`, `V` enums + `Reg` sealed type |
| Move | `movz`, `movk`, `movn`, `mov` (reg-reg), `loadImm64` (helper, emits ≤4 instructions) |
| Memory (GP) | `ldr`, `str`, `ldrW`, `strW`, `ldrB`, `strB`, `ldp`, `stp` with immediate offset |
| Memory (SIMD) | `ld1` / `st1` for `.16B`, `.8H`, `.4S`, `.2D`; `ldp_q` / `stp_q`; `ld1r` (broadcast) |
| Integer arith | `add`, `sub`, `neg`, `mul`, `madd`, `udiv`, `sdiv` |
| Logical | `and`, `orr`, `eor`, `mvn`, `lsl`, `lsr`, `asr` (immediate variants) |
| Compare | `cmp` (imm + reg), `cbz`, `cbnz`, `tbz`, `tbnz` |
| Branch | `b`, `bl`, `br`, `blr`, `ret`, `b.cond` |
| NEON FP | `fadd`, `fsub`, `fmul`, `fdiv`, `fmla`, `fmls`, `fmin`, `fmax` (vector + scalar) |
| NEON integer | `add`, `sub`, `mul`, `mla`, `mls` with element size |
| NEON misc | `dup` (gpr→vec, lane→vec), `uxtl`, `sxtl`, `ushl`, `sshl`, `xtn`, `xtn2` |
| NEON logical | `and`, `orr`, `eor`, `bic` (vector) |
| PAC / BTI | `paciasp`, `autiasp`, `bti c/j/jc` |
| Misc | `nop`, `dmb`, `isb` |

About 50 helpers, ~400–600 lines of Kotlin including doc comments and bit-pack helpers.

### Tests (in `src/test/kotlin/`)

Golden-bytes tests for every instruction. For each helper, assert the emitted opcode matches a known-good value verified against `aarch64-linux-gnu-as` reference output. Catches encoding bugs at unit-test time, not at "shellcode silently does the wrong thing" time.

**Files written:**
- `nativekt/src/main/kotlin/io/simdkt/nativekt/engine/Arm64.kt`
- `nativekt/src/test/kotlin/io/simdkt/nativekt/engine/Arm64Test.kt`

**Exit criterion:** all golden-bytes tests pass; `MemoryExecutor.probeEpIndex` compiles.

---

## Phase 4 — `Trampoline` (Kotlin + C++) (~1–2 hours)

A minimal JNI shim that takes a code pointer + data pointer + magic and calls the code. Built as `libnktrampoline.so` for arm64-v8a only.

### Kotlin (`Trampoline.kt`, ~30 lines)

```kotlin
internal object Trampoline {
    var loaded: Boolean = false; private set
    fun init() { /* System.loadLibrary("nktrampoline"), set loaded */ }
    external fun callAndCheck(codePtr: Long, dataPtr: Long, magic: Int): Boolean
}
```

### C++ (`trampoline.cpp`, ~30 lines)

`Java_io_simdkt_nativekt_engine_Trampoline_callAndCheck` clears the magic slot, calls the function pointer with the data address in x0 (AAPCS64), reads the magic slot back. `JNICALL` linkage.

### CMake (`CMakeLists.txt`, ~10 lines)

`add_library(nktrampoline SHARED trampoline.cpp)`, link `log`, set `arm64-v8a` only.

**Files written:**
- `nativekt/src/main/kotlin/io/simdkt/nativekt/engine/Trampoline.kt`
- `nativekt/src/main/cpp/trampoline.cpp`
- `nativekt/src/main/cpp/CMakeLists.txt`

**Exit criterion:** AAR builds with the `.so` packaged inside; `Trampoline.init()` returns `loaded = true` on a real device.

---

## Phase 5 — Demo app + smoke test (~1 hour)

Replace the empty `MainActivity` with a minimal screen that:

1. Calls `NativeKt.init(cacheDir)`
2. Displays `ok` + the `lastError` if any
3. Runs a tiny kernel (the same probe pattern: write `0xDEADBEEF` to the buffer) using `execute()` and `executeDirect()`, displays results
4. Logs the discovered `epIndex`, `trigger`, `unsafeEp`, `methodHandle`, `trampoline` flags

This is the "does the SDK actually work end-to-end" verification.

**Files written:**
- `app/src/main/kotlin/com/example/slim/MainActivity.kt` (rewritten)
- `app/src/main/res/layout/activity_main.xml` (simple)

**Exit criterion:** running the app on a real arm64 Android 12+ device shows a green `ok` screen with discovered flags.

---

## Phase 6 — Maven publishing + README (~30 min)

### Publishing config in `nativekt/build.gradle.kts`

```kotlin
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "io.simdkt"
            artifactId = "nativekt"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories { mavenLocal() }
}
```

### README

For the SDK module covering: what it does, the EP-hijack execution model, deployment story, API examples, supported devices (API 31+, arm64-v8a only), known limitations (PAC/BTI caveats, anti-tamper SDK incompatibility), how to build and publish locally.

**Files written:**
- `nativekt/build.gradle.kts` (publishing block added)
- `nativekt/README.md`

**Exit criterion:** `./gradlew :nativekt:publishToMavenLocal` succeeds; the AAR appears in `~/.m2/repository/io/simdkt/nativekt/0.1.0/`.

---

## Total estimate

~6–8 hours of focused work. Phase 3 (the encoder) is the bulk by far. Phases 1, 2, 4, 5, 6 are all under 90 minutes each.

---

## Open questions before starting

1. **Module name confirmed as `nativekt`?** Or change?
2. **Group ID `io.simdkt` for Maven?** Or different?
3. **Encoder scope OK as listed?** Or also include subgroup / saturation / crypto instructions in V1?
4. **Trampoline kept in V1?** Recommended — it's the low-overhead small-kernel path.
5. **Demo app — minimal smoke test UI** (as described in Phase 5) or a Compose-based bench screen with timing? The latter adds ~2 hours.
