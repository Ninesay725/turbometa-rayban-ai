package com.smartview.glassai.glasses

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameConversionsTest {

    @Test
    fun i420ToNv21InterleavesVThenU() {
        // 2x2 frame: Y = 1,2,3,4 ; U = 5 ; V = 6  ->  NV21 = Y..., V, U
        val i420 = byteArrayOf(1, 2, 3, 4, 5, 6)
        val nv21 = FrameConversions.i420ToNv21(i420, width = 2, height = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 6, 5), nv21)
    }

    @Test
    fun i420ToNv21HandlesAFourByFourFrame() {
        val size = 16
        val quarter = 4
        val i420 = ByteArray(size + 2 * quarter) { it.toByte() }
        val nv21 = FrameConversions.i420ToNv21(i420, width = 4, height = 4)
        // Y plane untouched
        assertArrayEquals(i420.copyOfRange(0, size), nv21.copyOfRange(0, size))
        // chroma: V[n] then U[n]
        for (n in 0 until quarter) {
            assertEquals(i420[size + quarter + n], nv21[size + n * 2])
            assertEquals(i420[size + n], nv21[size + n * 2 + 1])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun i420ToNv21RejectsAShortBuffer() {
        FrameConversions.i420ToNv21(byteArrayOf(1, 2, 3), width = 2, height = 2)
    }

    @Test
    fun copyI420CopiesRemainingBytesAndRestoresPosition() {
        val buffer = ByteBuffer.wrap(byteArrayOf(9, 1, 2, 3))
        buffer.position(1)
        val copy = FrameConversions.copyI420(buffer)
        assertArrayEquals(byteArrayOf(1, 2, 3), copy)
        assertEquals(1, buffer.position())
    }
}
