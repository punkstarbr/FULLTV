package app.opentv.data.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncOperationGateTest {
    @Test
    fun operationsAreSerializedAcrossIndependentCallers() = runTest {
        val events = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch {
            SyncOperationGate.withLock {
                events += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "first-end"
            }
        }
        firstEntered.await()

        val second = async {
            SyncOperationGate.withLock { events += "second-start" }
        }
        runCurrent()
        assertEquals(listOf("first-start"), events)

        releaseFirst.complete(Unit)
        first.join()
        second.await()
        assertEquals(listOf("first-start", "first-end", "second-start"), events)
    }
}
