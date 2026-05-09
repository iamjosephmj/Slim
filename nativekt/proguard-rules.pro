# Keep the engine classes intact — MemoryExecutor uses reflection on its own
# probe methods to discover the ART entrypoint offset, and Trampoline is bound
# by name from libnktrampoline.so via JNI.
-keep class io.simdkt.nativekt.NativeKt { *; }
-keep class io.simdkt.nativekt.engine.MemoryExecutor { *; }
-keep class io.simdkt.nativekt.engine.MemoryExecutor$* { *; }
-keep class io.simdkt.nativekt.engine.Trampoline { *; }
