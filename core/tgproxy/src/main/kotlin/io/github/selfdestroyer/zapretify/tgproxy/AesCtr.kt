package io.github.selfdestroyer.zapretify.tgproxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Потоковый AES-CTR, как cipher.NewCTR в Go: счётчик — весь 128-битный IV (big-endian), шифрование и
 * расшифровка — одно и то же исключающее ИЛИ с ключевым потоком. Каждый экземпляр используется одним потоком.
 */
internal class AesCtr(key: ByteArray, iv: ByteArray) {
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    /** Применяет ключевой поток к [buf] на месте. */
    fun xor(buf: ByteArray, off: Int = 0, len: Int = buf.size - off) {
        if (len <= 0) return
        val n = cipher.update(buf, off, len, buf, off)
        check(n == len) { "AES-CTR вернул $n байт вместо $len" }
    }

    /** Сдвигает поток на [n] байт (как XOR с нулями в Go). */
    fun skip(n: Int) = xor(ByteArray(n))
}
