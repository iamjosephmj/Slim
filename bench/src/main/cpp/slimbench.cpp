// JNI surface: one entry point that runs the fused 8-stage pipeline on
// the caller's thread. Buffer pointer comes from a direct ByteBuffer on
// the Java side (see JniPipeline.kt) — zero-copy, no thread pool.

#include "kernels.h"

#include <jni.h>
#include <cstdint>

extern "C" JNIEXPORT void JNICALL
Java_com_example_slim_bench_JniPipeline_runFused(
        JNIEnv* /*env*/, jclass /*clazz*/,
        jlong dataPtr, jint n) {
    auto* p = reinterpret_cast<uint8_t*>(static_cast<uintptr_t>(dataPtr));
    kernel_pipeline_fused(p, static_cast<size_t>(n));
}

// Dispatch baseline: empty native function. Pure measurement of the
// Java→native crossing — no kernel work, no memory touched. Slim's
// counterpart is a Slim template that is just `placeholderDataPtr` +
// `ret`, which still exercises the imm16 slot patching + EP-hijack.
extern "C" JNIEXPORT void JNICALL
Java_com_example_slim_bench_JniPipeline_runEmpty(
        JNIEnv* /*env*/, jclass /*clazz*/,
        jlong /*dataPtr*/) {
    // intentionally empty
}
