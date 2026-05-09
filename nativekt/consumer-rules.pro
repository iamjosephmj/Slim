# Consumers of nativekt must keep the JNI entry points so the trampoline can resolve
# them by name at runtime.
-keepclassmembers class io.simdkt.nativekt.engine.Trampoline {
    *;
}
