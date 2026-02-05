package com.xelth.eckwms_movfast.utils

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Утилита для дешифровки ECK Smart QR-кодов (AES-192-GCM + Base32).
 * Алгоритм совместим с реализацией на Node.js и Go серверах.
 *
 * Формат URL: ECKn.COM/{DATA:56}{IV:9}{SUFFIX:2} = 76 символов
 */
object EckSecurityManager {
    private const val TAG = "EckSecurity"

    // Кастомный Base32 алфавит (Crockford-like)
    private const val BASE32_CHARS = "0123456789ABCDEFGHJKLMNPQRTUVWXY"
    private val BASE32_LOOKUP = IntArray(256) { -1 }

    init {
        for (i in BASE32_CHARS.indices) {
            BASE32_LOOKUP[BASE32_CHARS[i].code] = i
            // Также поддерживаем lowercase
            BASE32_LOOKUP[BASE32_CHARS[i].lowercaseChar().code] = i
        }
    }

    /**
     * Пытается дешифровать ECK URL.
     * @return расшифрованный путь (например "/p/000000000000000123") или null при ошибке
     */
    fun tryDecryptBarcode(barcode: String): String? {
        val cleanCode = barcode.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .uppercase()

        // 1. Проверка формата: должно быть ровно 76 символов
        if (cleanCode.length != 76) {
            Log.d(TAG, "Invalid length: ${cleanCode.length} (expected 76)")
            return null
        }

        // 2. Проверка префикса
        if (!cleanCode.startsWith("ECK1.COM/") &&
            !cleanCode.startsWith("ECK2.COM/") &&
            !cleanCode.startsWith("ECK3.COM/")) {
            Log.d(TAG, "Invalid prefix")
            return null
        }

        try {
            // 3. Получаем ключ шифрования (24 байта = 48 hex символов для AES-192)
            val keyHex = SettingsManager.getEncKey()
            if (keyHex.length != 48) {
                Log.e(TAG, "Invalid ENC_KEY length: ${keyHex.length} (expected 48 hex chars)")
                return null
            }
            val keyBytes = hexStringToByteArray(keyHex)

            // 4. Парсинг структуры URL
            // Префикс (9: "ECKn.COM/") + Данные (56) + IV (9) + Суффикс (2) = 76
            val base32Data = cleanCode.substring(9, 65)  // 56 символов Base32 = 35 байт
            val base32Iv = cleanCode.substring(65, 74)   // 9 символов Base32 IV
            val suffix = cleanCode.substring(74, 76)     // 2 символа суффикс

            Log.d(TAG, "Parsing: data=${base32Data.take(10)}..., iv=$base32Iv, suffix=$suffix")

            // 5. Декодирование Base32 → байты
            // 56 символов Base32 = 56 * 5 / 8 = 35 байт (19 encrypted + 16 auth tag)
            val decodedData = base32DecodeFixed(base32Data)
            if (decodedData.size < 35) {
                Log.e(TAG, "Decoded data too short: ${decodedData.size}")
                return null
            }

            // 6. Разделяем encrypted message и auth tag
            // Auth tag - последние 16 байт
            val authTagLength = 16
            val encryptedMessage = decodedData.copyOfRange(0, decodedData.size - authTagLength)
            val authTag = decodedData.copyOfRange(decodedData.size - authTagLength, decodedData.size)

            Log.d(TAG, "Encrypted: ${encryptedMessage.size} bytes, AuthTag: ${authTag.size} bytes")

            // 7. Реконструкция IV
            // В Node.js: Buffer.concat([betIv, betIv], 16) - берёт 9 байт IV + первые 7 байт повторно
            // Но betIv - это ASCII байты Base32 символов, а не декодированные байты!
            val ivBytes = base32Iv.toByteArray(Charsets.US_ASCII)
            val iv = ByteArray(16)
            System.arraycopy(ivBytes, 0, iv, 0, minOf(9, ivBytes.size))
            for (i in 0 until 7) {
                iv[9 + i] = ivBytes[i % ivBytes.size]
            }

            // 8. Дешифровка AES-192-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val spec = GCMParameterSpec(128, iv) // 128 bit = 16 byte tag

            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            // Для GCM в Android нужно передать ciphertext + authTag вместе
            val cipherTextWithTag = encryptedMessage + authTag
            val decryptedBytes = cipher.doFinal(cipherTextWithTag)

            val result = String(decryptedBytes, Charsets.UTF_8)
            Log.d(TAG, "Decryption SUCCESS: $result")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    /**
     * Декодирует Base32 строку фиксированной длины (56 символов → 35 байт)
     * Алгоритм из Node.js betrugerToHex: каждые 8 символов Base32 = 5 байт
     */
    private fun base32DecodeFixed(input: String): ByteArray {
        val iterations = input.length / 8  // 56 / 8 = 7 итераций
        val output = ByteArray(iterations * 5)  // 7 * 5 = 35 байт

        for (i in 0 until iterations) {
            val b0 = BASE32_LOOKUP[input[i * 8 + 0].code]
            val b1 = BASE32_LOOKUP[input[i * 8 + 1].code]
            val b2 = BASE32_LOOKUP[input[i * 8 + 2].code]
            val b3 = BASE32_LOOKUP[input[i * 8 + 3].code]
            val b4 = BASE32_LOOKUP[input[i * 8 + 4].code]
            val b5 = BASE32_LOOKUP[input[i * 8 + 5].code]
            val b6 = BASE32_LOOKUP[input[i * 8 + 6].code]
            val b7 = BASE32_LOOKUP[input[i * 8 + 7].code]

            // Проверка на невалидные символы
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0 || b5 < 0 || b6 < 0 || b7 < 0) {
                Log.e(TAG, "Invalid Base32 character at iteration $i")
                return ByteArray(0)
            }

            // Преобразование 8 x 5-bit → 5 x 8-bit (из Node.js betrugerToHex)
            output[i * 5 + 0] = ((b0 shl 3) or (b1 shr 2)).toByte()
            output[i * 5 + 1] = ((b1 shl 6) or (b2 shl 1) or (b3 shr 4)).toByte()
            output[i * 5 + 2] = ((b3 shl 4) or (b4 shr 1)).toByte()
            output[i * 5 + 3] = ((b4 shl 7) or (b5 shl 2) or (b6 shr 3)).toByte()
            output[i * 5 + 4] = ((b6 shl 5) or b7).toByte()
        }

        return output
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Проверяет, является ли баркод зашифрованным ECK URL
     */
    fun isEncryptedEckUrl(barcode: String): Boolean {
        val clean = barcode.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .uppercase()
        return clean.length == 76 &&
               (clean.startsWith("ECK1.COM/") ||
                clean.startsWith("ECK2.COM/") ||
                clean.startsWith("ECK3.COM/"))
    }
}
