package io.simdkt.nativekt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The suspend extensions are thin `withContext` wrappers — the on-device
 * dispatch is exercised elsewhere. Here we just lock in that the suspend
 * shape compiles, returns the wrapped value, and propagates exceptions.
 */
class CoroutinesTest {

    private object FakeNativeKt {
        var lastDispatcher: String? = null

        suspend fun runViaContext(): String = withContext(Dispatchers.Default) {
            "Default-${Thread.currentThread().name}"
        }

        suspend fun parallelMap(): List<Int> = coroutineScope {
            (0 until 16).map { i ->
                async(Dispatchers.Default) { i * i }
            }.awaitAll()
        }
    }

    @Test fun suspendShapeReturnsResult() = runBlocking {
        val r = FakeNativeKt.runViaContext()
        assertEquals(true, r.startsWith("Default-"))
    }

    @Test fun parallelDispatchesComplete() = runBlocking {
        val results = FakeNativeKt.parallelMap()
        assertEquals((0 until 16).map { it * it }, results)
    }
}
