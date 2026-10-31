package com.neljohn76.lgflash.laf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KILO challenge/response tests using the OFFICIAL vectors from
 * Lekensteyn/lglaf tests/test_crypto.py (MIT):
 *  - key_transform("qndiakxxuiemdklseqid~a~niq,zjuxl") == "dqoev)ohnsWu\\bk`oiicmZ_lpqe\\ealp"
 *  - xor_key(transformed, challenge f29ae130) == 5490f597...80f682
 *  - encrypt_kilo_challenge(key, f29ae130) == 2f47ca81ebeee6f414263c0542c8d132
 */
class KiloTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun unhex(b: ByteArray): String =
        b.joinToString("") { String.format("%02x", it) }

    private val key =
        "qndiakxxuiemdklseqid~a~niq,zjuxl".toByteArray(Charsets.US_ASCII)

    @Test
    fun keyTransform_matchesLglafVector() {
        val transformed = LafClient.kiloKeyTransform(key)
        assertEquals(
            "dqoev)ohnsWu\\bk`oiicmZ_lpqe\\ealp",
            String(transformed, Charsets.US_ASCII)
        )
    }

    @Test
    fun xorKey_matchesLglafVector() {
        val transformed = LafClient.kiloKeyTransform(key)
        val xored = LafClient.kiloXorKey(transformed, hex("f29ae130"))
        assertEquals(
            "5490f59746c8f59a5e92cd876c83f1925f88f3915dbbc59e4090ffae5580f682",
            unhex(xored)
        )
    }

    @Test
    fun kiloEncrypt_matchesLglafVector() {
        val response = LafClient.kiloEncrypt(key, hex("f29ae130"))
        assertEquals(16, response.size)
        assertEquals("2f47ca81ebeee6f414263c0542c8d132", unhex(response))
    }

    @Test
    fun kiloEncrypt_isDeterministic() {
        val a = LafClient.kiloEncrypt(key, hex("00010203"))
        val b = LafClient.kiloEncrypt(key, hex("00010203"))
        assertArrayEquals(a, b)
    }
}
