package com.neljohn76.lgflash.edl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirehoseClientTest {
    @Test
    fun payloadSizeIsSectorAligned() {
        assertEquals(16384, FirehoseClient.alignedPayloadSize(16384, 512))
        assertEquals(16384, FirehoseClient.alignedPayloadSize(16384, 4096))
        assertEquals(12000, FirehoseClient.alignedPayloadSize(16384, 6000))
        assertEquals(16380, FirehoseClient.alignedPayloadSize(16384, 60))
    }

    @Test
    fun rejectsSectorLargerThanPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            FirehoseClient.alignedPayloadSize(16384, 32768)
        }
    }
}
