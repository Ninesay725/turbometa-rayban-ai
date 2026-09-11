package com.smartview.glassai.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTranslateVisualLeaseTest {
    @Test fun replacementOwnerCannotSupplyAFrameToTheRetiredReader() = runTest {
        var reads = 0
        var encodes = 0
        val result = runCatching {
            readOwnedTranslationImage(1L, owns = { false }, frame = { reads++; "B frame" }) {
                encodes++; byteArrayOf(1)
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, reads); assertEquals(0, encodes)
    }

    @Test fun leaseReplacementDuringEncodingDiscardsTheOldJpeg() = runTest {
        var currentLease = 1L
        val encoded = CompletableDeferred<ByteArray>()
        val result = async {
            runCatching {
                readOwnedTranslationImage(1L, owns = { it == currentLease }, frame = { "A frame" }) {
                    encoded.await()
                }
            }
        }
        runCurrent()
        currentLease = 2L // Also fences a new stream generation for the same owner.
        encoded.complete(byteArrayOf(1)); runCurrent()
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
    }

    @Test fun unchangedLeaseReturnsItsEncodedFrameAndMissingFrameDoesNotEncode() = runTest {
        val bytes = byteArrayOf(2, 3)
        assertArrayEquals(bytes, readOwnedTranslationImage(7L, { it == 7L }, { "Owned frame" }) { bytes })
        var encodes = 0
        assertNull(readOwnedTranslationImage<String>(7L, { true }, { null }) { encodes++; bytes })
        assertEquals(0, encodes)
    }
}
