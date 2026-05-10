#pragma once

#include <cstdint>
#include <cstddef>

// The 8-stage pipeline fused into a single 16-byte-vector loop body: one
// load, all 8 transforms applied in NEON registers, one store. Mirrors
// fusedPipelineTemplate in Transforms.kt instruction-for-instruction —
// the apples-to-apples comparison target for Slim's runtime-emitted
// equivalent. `n` MUST be a multiple of 16.
void kernel_pipeline_fused(uint8_t* data, size_t n);
