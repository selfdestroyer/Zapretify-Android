package io.github.selfdestroyer.zapretify.selection

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * TLS ClientHello для проверки стратегий — такого же вида и размера, как у Chrome и приложения YouTube.
 *
 * Зачем свой: современные клиенты отправляют в ClientHello постквантовый ключ (группа X25519MLKEM768, 1216 байт),
 * поэтому сообщение не помещается в один TCP-пакет. Стратегия, которая работает с маленьким ClientHello, с большим
 * может ломать соединение (замер 10.11.2026) — проверка маленьким ClientHello выбирает негодные стратегии.
 *
 * Ключи здесь случайные, но правильной формы (коэффициенты ML-KEM меньше 3329), поэтому сервер отвечает обычным
 * ServerHello. Дальше рукопожатие не продолжается: для проверки обхода достаточно увидеть ответ сервера.
 *
 * Chrome переставляет расширения случайно, и имя сайта (SNI) оказывается то в первом пакете, то во втором.
 * Поэтому есть две раскладки — [Layout.SNI_FIRST] и [Layout.SNI_LAST]; стратегия должна работать с обеими.
 */
object ClientHello {
    enum class Layout { SNI_FIRST, SNI_LAST }

    const val GROUP_X25519_MLKEM768 = 0x11ec
    const val GROUP_X25519 = 0x001d
    private const val MLKEM768_KEY = 1184
    private const val MLKEM_Q = 3329

    private val cipherSuites = intArrayOf(
        0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8, 0xc013, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035,
    )
    private val signatureAlgorithms = intArrayOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601)

    /** Полная TLS-запись с ClientHello для сайта [host]. */
    fun build(host: String, layout: Layout, random: SecureRandom = SecureRandom()): ByteArray {
        val sni = ext(0x0000) {
            val name = host.toByteArray(Charsets.US_ASCII)
            u16(name.size + 3); u8(0); u16(name.size); write(name)
        }
        val keyShare = ext(0x0033) {
            val hybrid = mlkem768Key(random) + bytes(random, 32)
            val x25519 = bytes(random, 32)
            u16(4 + hybrid.size + 4 + x25519.size)
            u16(GROUP_X25519_MLKEM768); u16(hybrid.size); write(hybrid)
            u16(GROUP_X25519); u16(x25519.size); write(x25519)
        }
        val middle = listOf(
            ext(0x0017) {}, // extended_master_secret
            ext(0xff01) { u8(0) }, // renegotiation_info
            ext(0x000a) { u16(8); u16(GROUP_X25519_MLKEM768); u16(GROUP_X25519); u16(0x0017); u16(0x0018) }, // supported_groups
            ext(0x000b) { u8(1); u8(0) }, // ec_point_formats
            ext(0x0023) {}, // session_ticket
            ext(0x0010) { u16(12); u8(2); write("h2".toByteArray()); u8(8); write("http/1.1".toByteArray()) }, // ALPN
            ext(0x0005) { u8(1); u16(0); u16(0) }, // status_request
            ext(0x000d) { u16(signatureAlgorithms.size * 2); signatureAlgorithms.forEach { u16(it) } },
            ext(0x0012) {}, // signed_certificate_timestamp
            ext(0x002b) { u8(4); u16(0x0304); u16(0x0303) }, // supported_versions
            ext(0x002d) { u8(1); u8(1) }, // psk_key_exchange_modes
            ext(0x001b) { u8(2); u16(0x0002) }, // compress_certificate
            ext(0x4469) { u16(3); u8(2); write("h2".toByteArray()) }, // application_settings
            // encrypted_client_hello со случайным содержимым — как «заглушка» у Chrome; сервер его пропускает.
            ext(0xfe0d) { u8(0); u16(1); u16(1); u8(random.nextInt(256)); u16(32); write(bytes(random, 32)); u16(176); write(bytes(random, 176)) },
        )
        val extensions = when (layout) {
            Layout.SNI_FIRST -> listOf(sni) + middle + keyShare
            Layout.SNI_LAST -> listOf(keyShare) + middle + sni
        }.fold(ByteArray(0)) { acc, e -> acc + e }

        val body = out {
            u16(0x0303)
            write(bytes(random, 32))
            u8(32); write(bytes(random, 32)) // session id — как у клиентов с TLS 1.3
            u16(cipherSuites.size * 2); cipherSuites.forEach { u16(it) }
            u8(1); u8(0) // без сжатия
            u16(extensions.size); write(extensions)
        }
        val handshake = out { u8(0x01); u8(body.size shr 16); u16(body.size and 0xffff); write(body) }
        return out { u8(0x16); u16(0x0301); u16(handshake.size); write(handshake) }
    }

    /** Открытый ключ ML-KEM-768 правильной формы: 768 коэффициентов по 12 бит, каждый меньше 3329, и 32 байта зерна. */
    internal fun mlkem768Key(random: SecureRandom): ByteArray {
        val key = ByteArray(MLKEM768_KEY)
        var pos = 0
        repeat(384) {
            val a = random.nextInt(MLKEM_Q)
            val b = random.nextInt(MLKEM_Q)
            key[pos++] = (a and 0xff).toByte()
            key[pos++] = ((a shr 8) or ((b and 0x0f) shl 4)).toByte()
            key[pos++] = (b shr 4).toByte()
        }
        System.arraycopy(bytes(random, 32), 0, key, pos, 32)
        return key
    }

    private fun bytes(random: SecureRandom, n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun out(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()

    private fun ext(type: Int, block: ByteArrayOutputStream.() -> Unit): ByteArray {
        val data = out(block)
        return out { u16(type); u16(data.size); write(data) }
    }

    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xff)
    private fun ByteArrayOutputStream.u16(v: Int) { write((v shr 8) and 0xff); write(v and 0xff) }
}
