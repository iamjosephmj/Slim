// Native trampoline + bypass helpers for the NativeKt runtime.
//
// JNI entry points:
//   * Java_io_simdkt_nativekt_engine_Trampoline_callAndCheck     — fallback dispatch
//   * Java_io_simdkt_nativekt_engine_Trampoline_clearCache       — I-cache flush
//   * Java_io_simdkt_nativekt_engine_Trampoline_artRuntimeAddr   — locate art::Runtime*
//
// `artRuntimeAddr` is a setup-time helper that ELF-parses libart.so on disk to
// find the offset of `_ZN3art7Runtime9instance_E`, adds the libart load base
// from /proc/self/maps, and dereferences to return the `art::Runtime*`
// singleton as a Java `long`. The Kotlin side uses Unsafe to probe nearby
// int slots for the `hidden_api_policy_` field and flip it to `kDisabled`.

#include <jni.h>
#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdint>
#include <cstdio>
#include <cstring>

namespace {

constexpr const char* kTag = "nk-jni";

// Walk /proc/self/maps for the lowest mapped segment of `libname`. Returns
// (base, path-on-disk) pair, or (0, "") on miss.
uintptr_t FindLibBase(const char* libname, char* out_path, size_t out_path_sz) {
    FILE* f = fopen("/proc/self/maps", "re");
    if (f == nullptr) return 0;
    char line[1024];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strstr(line, libname) == nullptr) continue;
        uintptr_t s = 0, e = 0;
        unsigned long off = 0;
        char perms[8] = {0};
        char path[512] = {0};
        if (sscanf(line, "%lx-%lx %7s %lx %*s %*s %511s",
                   &s, &e, perms, &off, path) >= 4) {
            if (off == 0 && perms[0] == 'r') {
                base = s;
                if (out_path != nullptr && out_path_sz > 0) {
                    strncpy(out_path, path, out_path_sz - 1);
                    out_path[out_path_sz - 1] = '\0';
                }
                break;
            }
        }
    }
    fclose(f);
    return base;
}

// ELF64-parse `path`, scan .dynsym for `name`, return runtime address
// (= base + st_value) on hit, 0 on miss.
uintptr_t FindSymbolAddr(const char* path, uintptr_t base, const char* name) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); return 0; }
    void* m = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return 0;

    uintptr_t result = 0;
    do {
        auto* eh = reinterpret_cast<const Elf64_Ehdr*>(m);
        if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0
            || eh->e_ident[EI_CLASS] != ELFCLASS64) break;

        auto* sh = reinterpret_cast<const Elf64_Shdr*>(
            static_cast<const char*>(m) + eh->e_shoff);
        const Elf64_Shdr* dynsym = nullptr;
        const Elf64_Shdr* dynstr = nullptr;
        for (int i = 0; i < eh->e_shnum; ++i) {
            if (sh[i].sh_type == SHT_DYNSYM) dynsym = &sh[i];
            else if (sh[i].sh_type == SHT_STRTAB && sh[i].sh_addr != 0
                     && dynsym != nullptr && dynsym->sh_link == (uint32_t)i) {
                dynstr = &sh[i];
            }
        }
        // Fall back: pair dynsym's sh_link into shdr table.
        if (dynsym != nullptr && dynstr == nullptr) {
            dynstr = &sh[dynsym->sh_link];
        }
        if (dynsym == nullptr || dynstr == nullptr || dynsym->sh_entsize == 0) break;

        auto* sym = reinterpret_cast<const Elf64_Sym*>(
            static_cast<const char*>(m) + dynsym->sh_offset);
        const char* str = static_cast<const char*>(m) + dynstr->sh_offset;
        const size_t count = dynsym->sh_size / dynsym->sh_entsize;
        for (size_t i = 0; i < count; ++i) {
            if (sym[i].st_value == 0) continue;
            const char* sname = str + sym[i].st_name;
            if (strcmp(sname, name) == 0) {
                result = base + sym[i].st_value;
                break;
            }
        }
    } while (false);

    munmap(m, st.st_size);
    return result;
}

}  // namespace

extern "C" {

// callAndCheck: jump to codePtr with dataPtr in x0, then return whether the
// caller's contract magic was honored.
JNIEXPORT jboolean JNICALL
Java_io_simdkt_nativekt_engine_Trampoline_callAndCheck(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong codePtr, jlong dataPtr, jint magic) {
    if (codePtr == 0 || dataPtr == 0) return JNI_FALSE;
    auto* slot = reinterpret_cast<volatile int32_t*>(dataPtr);
    *slot = 0;
    using Kernel = void (*)(void*);
    auto kernel = reinterpret_cast<Kernel>(static_cast<uintptr_t>(codePtr));
    kernel(reinterpret_cast<void*>(static_cast<uintptr_t>(dataPtr)));
    return (*slot == magic) ? JNI_TRUE : JNI_FALSE;
}

// clearCache: ARM64 instruction-cache flush over [addr, addr+length).
JNIEXPORT void JNICALL
Java_io_simdkt_nativekt_engine_Trampoline_clearCache(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong addr, jlong length) {
    if (addr == 0 || length <= 0) return;
    auto* start = reinterpret_cast<char*>(static_cast<uintptr_t>(addr));
    __builtin___clear_cache(start, start + length);
}

// artRuntimeAddr: returns the value of `art::Runtime::instance_` (i.e. the
// `art::Runtime*` singleton) as a Java long. 0 if libart can't be located or
// the symbol isn't in .dynsym.
//
// This is a setup-time helper used by MemoryExecutor's hidden-API bypass to
// flip the runtime's policy via Unsafe writes — no per-call JNI.
JNIEXPORT jlong JNICALL
Java_io_simdkt_nativekt_engine_Trampoline_artRuntimeAddr(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    char path[512] = {0};
    uintptr_t base = FindLibBase("/libart.so", path, sizeof(path));
    if (base == 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "libart not found in /proc/self/maps");
        return 0;
    }
    uintptr_t instance_sym_addr = FindSymbolAddr(
        path, base, "_ZN3art7Runtime9instance_E");
    if (instance_sym_addr == 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "Runtime::instance_ symbol not found in %s", path);
        return 0;
    }
    auto* slot = reinterpret_cast<void**>(instance_sym_addr);
    void* runtime = *slot;
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "art::Runtime* = %p (instance_ slot @ %p, libart base %p)",
                        runtime, slot, reinterpret_cast<void*>(base));
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(runtime));
}

}  // extern "C"
