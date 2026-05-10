// Single fused pipeline kernel. NEON intrinsics implementation of the
// 8 transforms inlined per 16-byte chunk:
//   invert → contrast → brighten(40) → darken(20) → invert → contrast
//   → brighten(40) → darken(20)
// One load and one store per byte. Slim's bench-side equivalent is
// fusedPipelineTemplate in Transforms.kt.

#include "kernels.h"

#include <arm_neon.h>

void kernel_pipeline_fused(uint8_t* data, size_t n) {
    const uint8x16_t ones = vdupq_n_u8(0xFF);
    const uint8x16_t k64  = vdupq_n_u8(64);
    const uint8x16_t k40  = vdupq_n_u8(40);
    const uint8x16_t k20  = vdupq_n_u8(20);

    for (size_t i = 0; i < n; i += 16) {
        uint8x16_t v = vld1q_u8(data + i);
        // Stage 1: invert
        v = vsubq_u8(ones, v);
        // Stage 2: contrast — clamp(2*y - 128, 0, 255) via uqsub+uqadd
        v = vqsubq_u8(v, k64);
        v = vqaddq_u8(v, v);
        // Stage 3: brighten(40)
        v = vqaddq_u8(v, k40);
        // Stage 4: darken(20)
        v = vqsubq_u8(v, k20);
        // Stage 5: invert
        v = vsubq_u8(ones, v);
        // Stage 6: contrast
        v = vqsubq_u8(v, k64);
        v = vqaddq_u8(v, v);
        // Stage 7: brighten(40)
        v = vqaddq_u8(v, k40);
        // Stage 8: darken(20)
        v = vqsubq_u8(v, k20);
        vst1q_u8(data + i, v);
    }
}
