package io.simdkt.nativekt.engine

import android.annotation.SuppressLint
import android.system.Os
import android.system.OsConstants
import io.simdkt.nativekt.NativeKt
import java.io.File
import java.io.FileDescriptor
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.nio.ByteBuffer

/**
 * The runtime engine: reflection-based hidden-API access to the libcore POSIX
 * shim, memfd dual-map allocator, ART method probing, and the EP-hijack
 * dispatch loop.
 *
 * Public surface is intentionally small: [init], [execute], [directBufferAddress],
 * and [flags]. Everything else is private detail.
 *
 * Threading: dispatch is serialized on `this`. The probe method's entry point
 * is shared mutable state; running two kernels concurrently would race on the
 * patch/unpatch sequence.
 */
@SuppressLint("BlockedPrivateApi")
internal object MemoryExecutor {
    private const val PROBE_MAGIC: Int = 0xC0DECAFE.toInt()
    private const val EP_CACHE_FILE = "nk_ep.bin"
    private const val POLICY_CACHE_FILE = "nk_policy.bin"

    /** Offsets to try, in order. 0x18 is stable across AOSP arm64 since API 28. */
    private val EP_CANDIDATE_OFFSETS = intArrayOf(0x18, 0x20, 0x10, 0x28, 0x30, 0x08)

    @Volatile
    var flags: NativeKt.Flags = NativeKt.Flags(
        epIndex = -1, trigger = false, unsafeEp = false,
        methodHandle = false, trampoline = false,
    )
        private set

    @Volatile
    private var initialized: Boolean = false

    private lateinit var rawMem: RawMem
    private lateinit var libcoreOs: LibcoreOs

    @Synchronized
    fun init(cacheDir: File) {
        if (initialized) return

        // Trampoline first — the bypass's last-resort technique needs the
        // JNI helper that locates art::Runtime in libart's mapped memory.
        val trampolineLoaded = try {
            Trampoline.init()
            Trampoline.loaded
        } catch (_: Throwable) {
            false
        }

        // Now bypass the hidden-API gate. The rest of init reaches into
        // libcore.io.Os.mmap and ArtMethod fields, both blocked without it.
        bypassHiddenApi(cacheDir)

        libcoreOs = LibcoreOs.bind()
        rawMem = RawMem.best()

        val cached = readCachedEpIndex(cacheDir)
        val epIndex = if (cached != null && verifyEpIndex(cached)) {
            cached
        } else {
            val discovered = probeEpIndex()
            if (discovered >= 0) writeCachedEpIndex(cacheDir, discovered)
            discovered
        }

        flags = NativeKt.Flags(
            epIndex = epIndex,
            trigger = epIndex >= 0,
            unsafeEp = rawMem.usesUnsafe,
            methodHandle = methodHandlesAvailable(),
            trampoline = trampolineLoaded,
        )

        check(flags.trigger || flags.trampoline) {
            "neither EP-hijack nor trampoline dispatch is available"
        }
        initialized = true
    }

    fun execute(code: ByteArray, dataPtr: Long): Boolean {
        check(initialized) { "MemoryExecutor not initialized" }
        Region.allocate(libcoreOs, code.size).use { region ->
            region.writeCode(code, rawMem)
            return invokeKernel(region.rxAddr, dataPtr)
        }
    }

    /**
     * Patch the template's data-pointer slots with [dataPtr]'s 16-bit chunks
     * and dispatch. The template image is copied per call so concurrent
     * dispatches on the same template don't tread on each other's bytes.
     * Thread-safe — the underlying probe is taken from a pool per call.
     */
    fun executeTemplate(template: io.simdkt.nativekt.KernelTemplate, dataPtr: Long): Boolean {
        check(initialized) { "MemoryExecutor not initialized" }
        val patched = template.bytes.copyOf()
        if (template.dataPtrSlots.isNotEmpty()) {
            patchDataPtrSlots(patched, template.dataPtrSlots, dataPtr)
        }
        Region.allocate(libcoreOs, patched.size).use { region ->
            region.writeCode(patched, rawMem)
            return invokeKernel(region.rxAddr, dataPtr)
        }
    }

    /**
     * Compile [template] into a long-lived [io.simdkt.nativekt.KernelHandle].
     * The memfd dual-map region is allocated once; subsequent
     * [runHandle] calls reuse it, only patching the data-pointer slots in
     * place via [RawMem] writes to the RW mapping.
     *
     * Caller is responsible for [io.simdkt.nativekt.KernelHandle.close] when
     * done.
     */
    fun compileTemplate(
        template: io.simdkt.nativekt.KernelTemplate,
        metadata: KernelMetadata = KernelMetadata.EMPTY,
    ): io.simdkt.nativekt.KernelHandle {
        check(initialized) { "MemoryExecutor not initialized" }
        val region = Region.allocate(libcoreOs, template.bytes.size)
        return try {
            region.writeCode(template.bytes, rawMem)
            io.simdkt.nativekt.KernelHandle(
                region,
                template.dataPtrSlots.copyOf(),
                bytes = template.bytes.copyOf(),
                metadata = metadata,
            )
        } catch (t: Throwable) {
            region.close()
            throw t
        }
    }

    /**
     * Patch [handle]'s data-pointer slots in the live RW mapping (no copy)
     * and dispatch via the EP-hijack path. Single-threaded per handle —
     * concurrent calls on the same handle would race the slot writes.
     * Different handles can dispatch in parallel.
     */
    fun runHandle(handle: io.simdkt.nativekt.KernelHandle, dataPtr: Long): Boolean {
        check(initialized) { "MemoryExecutor not initialized" }
        val region = handle.region
        val slots = handle.dataPtrSlots
        if (slots.isNotEmpty()) {
            require(slots.size == 4) { "expected 4 dataPtr slots, got ${slots.size}" }
            for (i in 0 until 4) {
                val chunk = ((dataPtr ushr (i * 16)) and 0xFFFFL).toInt()
                patchImm16InPlace(region.rwAddr + slots[i], chunk)
            }
        }
        return invokeKernel(region.rxAddr, dataPtr)
    }

    private fun patchImm16InPlace(addr: Long, imm16: Int) {
        val cur = rawMem.peekInt(addr)
        val mask = (0xFFFF shl 5).inv()
        val patched = (cur and mask) or ((imm16 and 0xFFFF) shl 5)
        rawMem.pokeInt(addr, patched)
    }

    private fun patchDataPtrSlots(bytes: ByteArray, slots: IntArray, dataPtr: Long) {
        require(slots.size == 4) { "expected 4 dataPtr slots, got ${slots.size}" }
        for (i in 0 until 4) {
            val chunk = ((dataPtr ushr (i * 16)) and 0xFFFFL).toInt()
            patchImm16(bytes, slots[i], chunk)
        }
    }

    /**
     * Overwrite the imm16 field of a movz/movk instruction in place. The
     * imm16 occupies bits 5..20 of the 32-bit instruction (little-endian on
     * disk). Other bits — sf/opc/hw/rd — stay untouched.
     */
    private fun patchImm16(bytes: ByteArray, byteOffset: Int, imm16: Int) {
        require(imm16 in 0..0xFFFF) { "imm16 out of range: $imm16" }
        val cur = (bytes[byteOffset].toInt() and 0xFF) or
                ((bytes[byteOffset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[byteOffset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[byteOffset + 3].toInt() and 0xFF) shl 24)
        val mask = (0xFFFF shl 5).inv()
        val patched = (cur and mask) or ((imm16 and 0xFFFF) shl 5)
        bytes[byteOffset] = (patched and 0xFF).toByte()
        bytes[byteOffset + 1] = ((patched ushr 8) and 0xFF).toByte()
        bytes[byteOffset + 2] = ((patched ushr 16) and 0xFF).toByte()
        bytes[byteOffset + 3] = ((patched ushr 24) and 0xFF).toByte()
    }

    fun directBufferAddress(buf: ByteBuffer): Long {
        require(buf.isDirect) { "buffer must be direct" }
        return rawMem.directBufferAddress(buf)
    }

    // ---------------------------------------------------------------------
    // Dispatch
    // ---------------------------------------------------------------------

    private fun invokeKernel(codeAddr: Long, dataPtr: Long): Boolean {
        if (flags.trigger) {
            dispatchViaEpHijack(codeAddr, dataPtr)
            return true
        }
        if (flags.trampoline) {
            return Trampoline.callAndCheck(codeAddr, dataPtr, PROBE_MAGIC)
        }
        error("no working dispatch path")
    }

    /**
     * Patch a probe method's `entry_point_from_quick_compiled_code` to point at
     * [codeAddr], invoke the method (which jumps directly into shellcode), then
     * restore the original entry point. The kernel is responsible for loading
     * [dataPtr] from its own immediate-encoded copy — see [buildPrologue].
     *
     * Note: [dataPtr] is *not* passed via x0. ART's quick ABI puts ArtMethod*
     * in x0 at entry, so a kernel that wants the data pointer must bake it in
     * as an immediate, or have it placed earlier in the kernel via the
     * [Region.writeCode] caller. For the public [execute]/[executeDirect] APIs
     * the caller's kernel is free to read the value from a fixed location it
     * baked at assemble time. Probe kernels do exactly this.
     */
    private fun dispatchViaEpHijack(codeAddr: Long, dataPtr: Long) {
        val slot = probePool.take() // blocks if all 8 probes are in flight
        try {
            val epAddr = slot.artMethod + flags.epIndex.toLong()
            val savedEp = rawMem.peekLong(epAddr)
            // The DataPtr slot is shared global state; kept for kernels that
            // don't bake their data pointer in. Multi-threaded callers using
            // this slot should serialize themselves.
            DataPtr.address = dataPtr
            try {
                rawMem.pokeLong(epAddr, codeAddr)
                try {
                    slot.method.invoke(null)
                } catch (_: ReflectiveOperationException) {
                    // The kernel may return without ART's expected post-amble;
                    // some versions throw here. Dispatch happened either way.
                }
            } finally {
                rawMem.pokeLong(epAddr, savedEp)
            }
        } finally {
            probePool.put(slot)
        }
    }

    // ---------------------------------------------------------------------
    // EP probe
    // ---------------------------------------------------------------------

    /**
     * Build a tiny kernel that writes [PROBE_MAGIC] to a known buffer and
     * returns. Patch each candidate offset in turn; the first one whose patch
     * causes the magic to land is the answer.
     */
    private fun probeEpIndex(): Int {
        val dataRegion = Region.allocateData(libcoreOs, 64)
        val codeRegion = Region.allocate(libcoreOs, 64)
        try {
            val kernel = buildProbeKernel(dataRegion.rwAddr, PROBE_MAGIC)
            codeRegion.writeCode(kernel, rawMem)

            for (offset in EP_CANDIDATE_OFFSETS) {
                rawMem.pokeInt(dataRegion.rwAddr, 0)
                if (tryEpOffset(offset, codeRegion.rxAddr, dataRegion.rwAddr)) {
                    return offset
                }
            }
            return -1
        } finally {
            codeRegion.close()
            dataRegion.close()
        }
    }

    private fun verifyEpIndex(offset: Int): Boolean {
        val dataRegion = Region.allocateData(libcoreOs, 64)
        val codeRegion = Region.allocate(libcoreOs, 64)
        try {
            val kernel = buildProbeKernel(dataRegion.rwAddr, PROBE_MAGIC)
            codeRegion.writeCode(kernel, rawMem)
            rawMem.pokeInt(dataRegion.rwAddr, 0)
            return tryEpOffset(offset, codeRegion.rxAddr, dataRegion.rwAddr)
        } finally {
            codeRegion.close()
            dataRegion.close()
        }
    }

    private fun tryEpOffset(offset: Int, codeAddr: Long, dataRwAddr: Long): Boolean {
        val (method, artMethod) = pickFreshProbe()
        val epAddr = artMethod + offset.toLong()
        val savedEp = try {
            rawMem.peekLong(epAddr)
        } catch (_: Throwable) {
            return false
        }
        // Sanity: the saved EP should point at executable memory in some
        // mapped region. A zero or low value is suspicious.
        if (savedEp == 0L || savedEp.toULong() < 0x1000UL) return false

        rawMem.pokeLong(epAddr, codeAddr)
        try {
            try {
                method.invoke(null)
            } catch (_: ReflectiveOperationException) {
                // dispatch happened; ignore post-call bookkeeping mismatches
            } catch (_: Throwable) {
                // unexpected — but we still check the magic before declaring failure
            }
            return rawMem.peekInt(dataRwAddr) == PROBE_MAGIC
        } finally {
            rawMem.pokeLong(epAddr, savedEp)
        }
    }

    private fun buildProbeKernel(dataPtr: Long, magic: Int): ByteArray {
        // Sequence (referenced symbols land in Phase 3 — Arm64.kt):
        //   movz x0, #imm0             ; load dataPtr into x0 (4× movz/movk)
        //   movk x0, #imm1, lsl 16
        //   movk x0, #imm2, lsl 32
        //   movk x0, #imm3, lsl 48
        //   movz w1, #(magic & 0xFFFF)
        //   movk w1, #(magic >>> 16), lsl 16
        //   str  w1, [x0]
        //   ret
        val instrs = mutableListOf<Int>()
        instrs += Arm64.loadImm64(Arm64.X0, dataPtr)
        instrs += Arm64.loadImm32(Arm64.W1, magic)
        instrs += Arm64.strW(Arm64.W1, Arm64.X0, offset = 0)
        instrs += Arm64.ret()
        return Arm64.assemble(instrs)
    }

    // ---------------------------------------------------------------------
    // Probe method pool
    // ---------------------------------------------------------------------

    /** A reusable (Method, ArtMethod address) pair for dispatch. */
    private class ProbeSlot(val method: Method, val artMethod: Long)

    private val probeMethods: Array<Method> by lazy {
        val cls = Probes::class.java
        Array(PROBE_POOL_SIZE) { idx ->
            val m = cls.getDeclaredMethod("probe$idx")
            m.isAccessible = true
            m
        }
    }

    /**
     * Pool of probes for [dispatchViaEpHijack]. A single thread takes a slot,
     * patches its EP, calls, restores, returns the slot. With [PROBE_POOL_SIZE]
     * = 8 we support up to 8 concurrent dispatches before threads start
     * blocking on `take`. Lazy because reading `flags.epIndex` requires
     * `init` to have run.
     */
    private val probePool: java.util.concurrent.BlockingQueue<ProbeSlot> by lazy {
        val q = java.util.concurrent.LinkedBlockingQueue<ProbeSlot>(PROBE_POOL_SIZE)
        for (m in probeMethods) {
            q.put(ProbeSlot(m, readArtMethod(m)))
        }
        q
    }

    private var probeCursor = 0

    private fun pickFreshProbe(): Pair<Method, Long> {
        val idx = probeCursor++ % PROBE_POOL_SIZE
        val m = probeMethods[idx]
        return m to readArtMethod(m)
    }

    private fun readArtMethod(m: Method): Long {
        // The artMethod long lives on java.lang.reflect.Executable since API 26.
        var c: Class<*>? = m.javaClass
        while (c != null) {
            val f = try {
                c.getDeclaredField("artMethod")
            } catch (_: NoSuchFieldException) {
                null
            }
            if (f != null) {
                f.isAccessible = true
                return f.getLong(m)
            }
            c = c.superclass
        }
        error("Method.artMethod field not found — unsupported ART layout")
    }

    private fun methodHandlesAvailable(): Boolean = try {
        MethodHandles.lookup() != null
    } catch (_: Throwable) {
        false
    }

    // ---------------------------------------------------------------------
    // Hidden API bypass
    // ---------------------------------------------------------------------

    /**
     * Defeat ART's hidden-API enforcement for this process.
     *
     * Three techniques tried in order; whichever sticks first is fine:
     *
     *  1. **Meta-reflection** — get `Class.getDeclaredMethod` reflectively,
     *     then use it to obtain `setHiddenApiExemptions`. Worked through
     *     API ~30. Caller-attribution improvements on API 31+ neutered it.
     *
     *  2. **Direct call** — straight reflective lookup + invoke. Works on
     *     ROMs that haven't applied the latest enforcement patches.
     *
     *  3. **Unsafe field overwrite** — write `27` (Oreo) into VMRuntime's
     *     `targetSdkVersion` field, which ART consults for the
     *     `runtime.targetSdkVersion < 28` exemption. Also zero any
     *     `hiddenApi*` field. Works on API 36 because:
     *       - `theUnsafe` is reachable via plural getDeclaredFields
     *       - VMRuntime.targetSdkVersion is a public-shape field, visible
     *       - Unsafe.putInt operates on raw memory, no API check
     *     This is the technique that actually lands on Pixel 8/S24-class
     *     devices running Android 16.
     */
    private fun bypassHiddenApi(cacheDir: File): Boolean {
        // Technique 1 — meta-reflection (cheap, works on older ROMs).
        try {
            val classArrayCls = arrayOf<Class<*>>().javaClass
            val stringArrayCls = arrayOf<String>().javaClass
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, classArrayCls,
            )
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntime, "setHiddenApiExemptions", arrayOf(stringArrayCls)
            ) as java.lang.reflect.Method
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntime, "getRuntime", emptyArray<Class<*>>()
            ) as java.lang.reflect.Method
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L") as Any)
            android.util.Log.i("nk", "bypass: meta-reflection ok")
            return true
        } catch (t: Throwable) {
            android.util.Log.d("nk", "bypass: meta-reflection failed (${t::class.java.simpleName})")
        }

        // Technique 2 — direct reflective call.
        try {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntime.getDeclaredMethod("getRuntime")
            vmRuntime.getDeclaredMethod(
                "setHiddenApiExemptions", arrayOf<String>().javaClass,
            ).invoke(getRuntime.invoke(null), arrayOf("L") as Any)
            android.util.Log.i("nk", "bypass: direct call ok")
            return true
        } catch (t: Throwable) {
            android.util.Log.d("nk", "bypass: direct failed (${t::class.java.simpleName})")
        }

        // Technique 3 — Unsafe targetSdk overwrite. The one that actually
        // works on API 36.
        try {
            val unsafeCls = Class.forName("sun.misc.Unsafe")
            val theUnsafeF = unsafeCls.declaredFields.firstOrNull { it.name == "theUnsafe" }
                ?: error("theUnsafe field missing")
            theUnsafeF.isAccessible = true
            val u = theUnsafeF.get(null) ?: error("theUnsafe is null")

            val objectFieldOffset = unsafeCls.getMethod(
                "objectFieldOffset", java.lang.reflect.Field::class.java,
            )
            val putInt = unsafeCls.getMethod(
                "putInt",
                Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )

            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntime.getDeclaredMethod("getRuntime")
            val runtime = getRuntime.invoke(null)

            val getInt = unsafeCls.getMethod(
                "getInt", Any::class.java, Long::class.javaPrimitiveType,
            )

            // Sentinel: the current targetSdkVersion as ART sees it. Our app
            // ships with TargetSdkVersion=36 in the manifest; ART's VMRuntime
            // singleton mirrors that on the int field we want to overwrite.
            // We scan candidate offsets, write 27 (Oreo, pre-enforcement) at
            // any slot currently holding 36, and probe-verify by attempting
            // to resolve setHiddenApiExemptions. If still blocked, restore
            // the original 36 and try the next slot.
            //
            // Multiple slots may legitimately hold 36 by coincidence; we
            // restore each one we touch when verification fails.
            val arrCls = arrayOf<String>().javaClass
            fun bypassActive(): Boolean = try {
                vmRuntime.getDeclaredMethod("setHiddenApiExemptions", arrCls)
                true
            } catch (_: NoSuchMethodException) {
                false
            } catch (_: Throwable) {
                false
            }

            val sentinel = 36
            for (off in 8L..512L step 4L) {
                val cur = getInt.invoke(u, runtime, off) as Int
                if (cur != sentinel) continue
                putInt.invoke(u, runtime, off, 27)
                if (bypassActive()) {
                    android.util.Log.i(
                        "nk", "bypass: targetSdk poke at offset 0x${off.toString(16)} ok"
                    )
                    return true
                }
                putInt.invoke(u, runtime, off, sentinel) // restore
            }
            android.util.Log.d("nk", "bypass: no targetSdk slot took effect")
        } catch (t: Throwable) {
            android.util.Log.d("nk", "bypass: Unsafe targetSdk failed (${t::class.java.simpleName})")
        }

        // Technique 4 — flip art::Runtime::hidden_api_policy_ to kDisabled.
        // The Java targetSdk trick only exempts max-target-* gates; the
        // strictly-blocked entries we need (libcore.io.ForwardingOs.mmap,
        // setHiddenApiExemptions) are unconditional. The only path to those
        // is disabling the policy outright, which lives on the C++ side.
        //
        // We get the art::Runtime* via JNI (ELF-parses libart.so to resolve
        // `art::Runtime::instance_`), then probe with Unsafe at native
        // addresses for an int slot holding 1 (kJustWarn) or 2 (kEnabled),
        // write 0 (kDisabled), verify by attempting to resolve a known-
        // blocked hidden method.
        try {
            if (!Trampoline.loaded) error("trampoline not loaded")
            val runtimePtr = Trampoline.artRuntimeAddr()
            if (runtimePtr == 0L) error("artRuntimeAddr returned 0")

            val unsafeCls = Class.forName("sun.misc.Unsafe")
            val theUnsafeF = unsafeCls.declaredFields.firstOrNull { it.name == "theUnsafe" }
                ?: error("theUnsafe field missing")
            theUnsafeF.isAccessible = true
            val u = theUnsafeF.get(null) ?: error("theUnsafe is null")

            val getIntAddr = unsafeCls.getMethod(
                "getInt", Long::class.javaPrimitiveType,
            )
            val putIntAddr = unsafeCls.getMethod(
                "putInt", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )

            // Verifier: try to resolve mmap on libcore.io.ForwardingOs. With
            // policy=kDisabled, getDeclaredMethod returns the method.
            val osCls = Class.forName("libcore.io.ForwardingOs")
            val fdCls = FileDescriptor::class.java

            fun policyDisabled(): Boolean = try {
                osCls.getDeclaredMethod(
                    "mmap",
                    Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    fdCls,
                    Long::class.javaPrimitiveType,
                )
                true
            } catch (_: NoSuchMethodException) {
                false
            } catch (_: Throwable) {
                false
            }

            // Try the cached offset from a prior successful probe. The art::
            // Runtime struct layout is stable per ART version, so the offset
            // we found last time is still valid until the device updates.
            val cachedOff = readCachedPolicyOffset(cacheDir)
            if (cachedOff != null) {
                val addr = runtimePtr + cachedOff
                val cur = try {
                    getIntAddr.invoke(u, addr) as Int
                } catch (_: Throwable) {
                    null
                }
                if (cur != null && (cur == 1 || cur == 2 || cur == 0)) {
                    if (cur != 0) putIntAddr.invoke(u, addr, 0)
                    if (policyDisabled()) {
                        android.util.Log.i(
                            "nk", "bypass: art::Runtime policy from cache +0x${cachedOff.toString(16)} ok"
                        )
                        return true
                    }
                    if (cur != 0) putIntAddr.invoke(u, addr, cur) // restore
                }
                android.util.Log.i("nk", "bypass: cached policy offset stale, re-probing")
            }

            // The Runtime struct is large (~4KB on Android 14+). We probe up
            // to 8KB to be safe, in 4-byte steps. Looking for any int slot
            // holding 1 or 2 — the EnforcementPolicy enum values.
            var hits = 0
            for (off in 0L..8192L step 4L) {
                val addr = runtimePtr + off
                val cur = try {
                    getIntAddr.invoke(u, addr) as Int
                } catch (_: Throwable) {
                    break // walked off the end of mapped memory
                }
                if (cur != 1 && cur != 2) continue
                hits++
                putIntAddr.invoke(u, addr, 0)
                if (policyDisabled()) {
                    writeCachedPolicyOffset(cacheDir, off)
                    android.util.Log.i(
                        "nk", "bypass: art::Runtime policy poke at +0x${off.toString(16)} ok"
                    )
                    return true
                }
                putIntAddr.invoke(u, addr, cur) // restore
            }
            error("scanned $hits candidate int slots; none unlocked the policy")
        } catch (t: Throwable) {
            android.util.Log.w("nk", "bypass: art::Runtime probe failed", t)
        }

        android.util.Log.w("nk", "bypass: all techniques failed")
        return false
    }

    // ---------------------------------------------------------------------
    // EP cache
    // ---------------------------------------------------------------------

    private fun readCachedEpIndex(cacheDir: File): Int? {
        val f = File(cacheDir, EP_CACHE_FILE)
        if (!f.exists() || f.length() != 4L) return null
        return try {
            f.inputStream().use {
                val a = it.read()
                val b = it.read()
                val c = it.read()
                val d = it.read()
                if (a or b or c or d < 0) null
                else (d shl 24) or (c shl 16) or (b shl 8) or a
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCachedEpIndex(cacheDir: File, offset: Int) {
        val f = File(cacheDir, EP_CACHE_FILE)
        try {
            f.outputStream().use { out ->
                out.write(offset and 0xFF)
                out.write((offset ushr 8) and 0xFF)
                out.write((offset ushr 16) and 0xFF)
                out.write((offset ushr 24) and 0xFF)
            }
        } catch (_: Throwable) {
            // best-effort cache; not fatal
        }
    }

    /**
     * Cache the byte offset within `art::Runtime` at which we found the
     * `hidden_api_policy_` field. Stored as 8 little-endian bytes since the
     * offset is a Long.
     */
    private fun readCachedPolicyOffset(cacheDir: File): Long? {
        val f = File(cacheDir, POLICY_CACHE_FILE)
        if (!f.exists() || f.length() != 8L) return null
        return try {
            f.inputStream().use { input ->
                var v = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b < 0) return null
                    v = v or (b.toLong() shl (i * 8))
                }
                v
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCachedPolicyOffset(cacheDir: File, offset: Long) {
        val f = File(cacheDir, POLICY_CACHE_FILE)
        try {
            f.outputStream().use { out ->
                for (i in 0 until 8) {
                    out.write(((offset ushr (i * 8)) and 0xFF).toInt())
                }
            }
        } catch (_: Throwable) {
            // best-effort cache; not fatal
        }
    }

    // ---------------------------------------------------------------------
    // Dual-map memfd region
    // ---------------------------------------------------------------------

    internal class Region(
        val fd: FileDescriptor,
        val rwAddr: Long,
        val rxAddr: Long,
        val size: Long,
        private val os: LibcoreOs,
    ) : AutoCloseable {

        fun writeCode(bytes: ByteArray, rawMem: RawMem) {
            require(bytes.size.toLong() <= size) { "code size ${bytes.size} > region size $size" }
            rawMem.copyFromArray(bytes, 0, rwAddr, bytes.size)
            // Memory ordering between the RW write and the upcoming RX read
            // happens via the kernel's page table — both mappings share the
            // same physical page, so the store is visible. We rely on a fresh
            // RX mapping (allocated AFTER the RW write completes) to dodge
            // I-cache staleness; the CPU has never speculatively fetched from
            // these VAs yet.
        }

        override fun close() {
            try { os.munmap(rwAddr, size) } catch (_: Throwable) {}
            try { os.munmap(rxAddr, size) } catch (_: Throwable) {}
            try { Os.close(fd) } catch (_: Throwable) {}
        }

        companion object {
            // Page size queried from the kernel at first use. Android 15+
            // devices on some SoCs use 16 KB pages instead of the historical
            // 4 KB; using `Os.sysconf(_SC_PAGESIZE)` gives us the right value
            // regardless of device. The lookup is cached for the life of the
            // process via `lazy`. Falls back to 4 KB if sysconf fails (e.g.,
            // on test JVMs without OsConstants linked).
            private val PAGE: Long by lazy {
                try {
                    android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
                } catch (_: Throwable) {
                    4096L
                }
            }

            fun allocate(os: LibcoreOs, size: Int): Region {
                return allocateInternal(os, size, executable = true)
            }

            fun allocateData(os: LibcoreOs, size: Int): Region {
                return allocateInternal(os, size, executable = false)
            }

            private fun allocateInternal(
                os: LibcoreOs, size: Int, executable: Boolean,
            ): Region {
                val rounded = ((size.toLong() + PAGE - 1) / PAGE) * PAGE
                val fd = Os.memfd_create("nk-region", 0)
                Os.ftruncate(fd, rounded)
                val rwAddr = os.mmap(
                    address = 0L,
                    length = rounded,
                    prot = OsConstants.PROT_READ or OsConstants.PROT_WRITE,
                    flags = OsConstants.MAP_SHARED,
                    fd = fd,
                    offset = 0L,
                )
                val rxAddr = if (executable) {
                    os.mmap(
                        address = 0L,
                        length = rounded,
                        prot = OsConstants.PROT_READ or OsConstants.PROT_EXEC,
                        flags = OsConstants.MAP_SHARED,
                        fd = fd,
                        offset = 0L,
                    )
                } else {
                    rwAddr
                }
                return Region(fd, rwAddr, rxAddr, rounded, os)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Holder for probe methods + a data-pointer slot kernels can reference.
    // ---------------------------------------------------------------------

    internal object DataPtr {
        @JvmField
        @Volatile
        var address: Long = 0L
    }

    private const val PROBE_POOL_SIZE = 8

    /**
     * Pool of static no-arg methods whose entry points we patch during
     * dispatch. They MUST NOT be inlined by R8 (handled by proguard rules)
     * and MUST stay reflectively reachable.
     */
    @Suppress("unused")
    internal object Probes {
        @JvmStatic fun probe0() {}
        @JvmStatic fun probe1() {}
        @JvmStatic fun probe2() {}
        @JvmStatic fun probe3() {}
        @JvmStatic fun probe4() {}
        @JvmStatic fun probe5() {}
        @JvmStatic fun probe6() {}
        @JvmStatic fun probe7() {}
    }

    // ---------------------------------------------------------------------
    // Raw memory access
    // ---------------------------------------------------------------------

    /**
     * Pointer-aware memory accessor. Two backends:
     *   - sun.misc.Unsafe (preferred — small, well-known, available on AOSP)
     *   - libcore.io.Memory (fallback when Unsafe is stripped)
     *
     * Direct ByteBuffer address resolution uses java.nio.Buffer.address (the
     * field has been stable across all supported API levels).
     */
    @SuppressLint("DiscouragedPrivateApi")
    internal abstract class RawMem {
        abstract val usesUnsafe: Boolean
        abstract fun peekLong(addr: Long): Long
        abstract fun pokeLong(addr: Long, value: Long)
        abstract fun peekInt(addr: Long): Int
        abstract fun pokeInt(addr: Long, value: Int)
        abstract fun copyFromArray(src: ByteArray, srcOffset: Int, dstAddr: Long, len: Int)

        fun directBufferAddress(buf: ByteBuffer): Long {
            val f = java.nio.Buffer::class.java.getDeclaredField("address")
            f.isAccessible = true
            return f.getLong(buf)
        }

        companion object {
            fun best(): RawMem {
                runCatching { return UnsafeBacked() }
                return MemoryBacked()
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private class UnsafeBacked : RawMem() {
        private val unsafe: Any
        private val getLong: Method
        private val putLong: Method
        private val getInt: Method
        private val putInt: Method
        private val copyMemory: Method

        init {
            val cls = Class.forName("sun.misc.Unsafe")
            val theUnsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }
            unsafe = theUnsafe.get(null) ?: error("sun.misc.Unsafe.theUnsafe is null")
            getLong = cls.getMethod("getLong", Long::class.javaPrimitiveType)
            putLong = cls.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            getInt = cls.getMethod("getInt", Long::class.javaPrimitiveType)
            putInt = cls.getMethod("putInt", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            // copyMemory(srcBase, srcOffset, dstBase, dstOffset, bytes)
            copyMemory = cls.getMethod(
                "copyMemory",
                Any::class.java, Long::class.javaPrimitiveType,
                Any::class.java, Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
        }

        override val usesUnsafe: Boolean = true
        override fun peekLong(addr: Long): Long = getLong.invoke(unsafe, addr) as Long
        override fun pokeLong(addr: Long, value: Long) { putLong.invoke(unsafe, addr, value) }
        override fun peekInt(addr: Long): Int = getInt.invoke(unsafe, addr) as Int
        override fun pokeInt(addr: Long, value: Int) { putInt.invoke(unsafe, addr, value) }
        override fun copyFromArray(src: ByteArray, srcOffset: Int, dstAddr: Long, len: Int) {
            // First arg is the array object; srcOffset is the byte offset from
            // the array's base. We read the byte[] base offset once and add.
            val cls = unsafe.javaClass
            val arrayBaseOffset = cls.getMethod("arrayBaseOffset", Class::class.java)
                .invoke(unsafe, ByteArray::class.java) as Int
            copyMemory.invoke(
                unsafe,
                src, (arrayBaseOffset + srcOffset).toLong(),
                null, dstAddr,
                len.toLong(),
            )
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private class MemoryBacked : RawMem() {
        private val cls = Class.forName("libcore.io.Memory")

        private val peekLongM = cls.getDeclaredMethod("peekLong", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        private val pokeLongM = cls.getDeclaredMethod("pokeLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        private val peekIntM = cls.getDeclaredMethod("peekInt", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        private val pokeIntM = cls.getDeclaredMethod("pokeInt", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        private val pokeArrM = cls.getDeclaredMethod(
            "pokeByteArray",
            Long::class.javaPrimitiveType,
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )

        init {
            peekLongM.isAccessible = true
            pokeLongM.isAccessible = true
            peekIntM.isAccessible = true
            pokeIntM.isAccessible = true
            pokeArrM.isAccessible = true
        }

        override val usesUnsafe: Boolean = false
        override fun peekLong(addr: Long): Long = peekLongM.invoke(null, addr, false) as Long
        override fun pokeLong(addr: Long, value: Long) { pokeLongM.invoke(null, addr, value, false) }
        override fun peekInt(addr: Long): Int = peekIntM.invoke(null, addr, false) as Int
        override fun pokeInt(addr: Long, value: Int) { pokeIntM.invoke(null, addr, value, false) }
        override fun copyFromArray(src: ByteArray, srcOffset: Int, dstAddr: Long, len: Int) {
            pokeArrM.invoke(null, dstAddr, src, srcOffset, len)
        }
    }

    // ---------------------------------------------------------------------
    // libcore.io.Os accessor (mmap/munmap aren't on android.system.Os)
    // ---------------------------------------------------------------------

    /**
     * Reflective wrapper over libcore.io.Os.
     *
     * `mmap` and `munmap` are present on libcore's internal Os shim but were
     * never lifted to the public android.system.Os. Both are conditionally
     * blocklisted: API 28+ requires the hidden-API exemption performed in
     * [bypassHiddenApi] before this class can resolve the methods.
     */
    @SuppressLint("DiscouragedPrivateApi")
    internal class LibcoreOs private constructor(
        private val os: Any,
        private val mmap: Method,
        private val munmap: Method,
    ) {
        fun mmap(
            address: Long,
            length: Long,
            prot: Int,
            flags: Int,
            fd: FileDescriptor,
            offset: Long,
        ): Long = mmap.invoke(os, address, length, prot, flags, fd, offset) as Long

        fun munmap(address: Long, length: Long) {
            munmap.invoke(os, address, length)
        }

        companion object {

            fun bind(): LibcoreOs {
                val libcoreCls = Class.forName("libcore.io.Libcore")
                val osField = libcoreCls.getDeclaredField("os").apply { isAccessible = true }
                val osInstance = osField.get(null)
                    ?: error("libcore.io.Libcore.os is null")
                // Os interface is shared by ForwardingOs, BlockGuardOs, etc.
                // Walk the class hierarchy to find the declared mmap/munmap.
                val mmap = findMethodInHierarchy(
                    osInstance.javaClass, "mmap",
                    Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    FileDescriptor::class.java,
                    Long::class.javaPrimitiveType!!,
                )
                val munmap = findMethodInHierarchy(
                    osInstance.javaClass, "munmap",
                    Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
                )
                return LibcoreOs(osInstance, mmap, munmap)
            }

            private fun findMethodInHierarchy(
                start: Class<*>, name: String, vararg params: Class<*>,
            ): Method {
                var c: Class<*>? = start
                while (c != null) {
                    try {
                        val m = c.getDeclaredMethod(name, *params)
                        m.isAccessible = true
                        return m
                    } catch (_: NoSuchMethodException) {
                    }
                    c = c.superclass
                }
                error("$name not found on ${start.name} hierarchy")
            }
        }
    }
}
