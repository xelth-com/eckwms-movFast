package com.xelth.eckwms_movfast.utils

import android.util.Log
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Decrypts ECK Smart QR codes (AES-192-GCM + Base32).
 *
 * V1 (legacy): text-based, 16-byte nonce reconstructed from 9-char IV.
 *   Format: ECKn.COM/{DATA:56}{IV:9}{SUFFIX:2} = 76 chars exactly.
 *   Returns the decrypted plaintext string (e.g. "p000000000000000031").
 *
 * V2 (SmartTag): binary 19-byte payload, 12-byte nonce derived via SHA-256.
 *   Format: {PREFIX}/{DATA:56}{IV:dynamic}{SUFFIX:2}, body >= 58 chars.
 *   Returns "{type_prefix}-{uuid}" (e.g. "p-550e8400-e29b-41d4-a716-446655440000").
 */
object EckSecurityManager {
    private const val TAG = "EckSecurity"

    // Custom Base32 alphabet (Crockford-like, excludes I, O, S, Z)
    private const val BASE32_CHARS = "0123456789ABCDEFGHJKLMNPQRTUVWXY"
    private val BASE32_LOOKUP = IntArray(256) { -1 }

    // Canonical (2026-07-14): the type byte IS the ASCII letter of the prefix.
    // One letter per entity, source system is NOT encoded in the type (a partner
    // mirrored from Odoo/Twenty scans the same as a native one; origin lives in
    // external_ref on the record). Tickets/mail have no scan type — they attach
    // to scannable entities via graph edges.
    private val CANONICAL_TYPES = setOf(
        'i', // item
        'b', // box
        'p', // place / location
        'o', // order
        'l', // label
        'u', // user
        'c', // company (partner)
        'h', // person / human (partner)
        'd', // deal / opportunity
        'a', // article / product
    )

    // Legacy numeric type bytes from already-printed V2 tags — decode-only,
    // never generated anymore. They collapse into the canonical letters.
    private val LEGACY_ENTITY_PREFIX = mapOf(
        0x00 to "i",  // WMS Item
        0x01 to "b",  // WMS Box
        0x02 to "p",  // WMS Location
        0x03 to "o",  // WMS Order
        0x04 to "l",  // WMS Label
        0x05 to "u",  // WMS User
        0x10 to "c",  // Twenty Company      (was "company")
        0x11 to "h",  // Twenty Person       (was "person")
        0x12 to "d",  // Twenty Opportunity  (was "opp")
        0x20 to "a",  // Odoo Product        (was "oprod")
        0x21 to "c",  // Odoo Partner        (was "opartner")
    )

    init {
        for (i in BASE32_CHARS.indices) {
            BASE32_LOOKUP[BASE32_CHARS[i].code] = i
            BASE32_LOOKUP[BASE32_CHARS[i].lowercaseChar().code] = i
        }
    }

    /**
     * Checks whether [barcode] starts with any configured QR prefix (dynamic from server
     * + hardcoded fallbacks like 9eck.com/, xelth.com/).
     * Used for security filtering: trusted link barcodes vs. potentially spoofed ones.
     */
    fun isTrustedLinkBarcode(barcode: String): Boolean {
        val clean = barcode.trim()
            .removePrefix("http://")
            .removePrefix("https://")
        return SettingsManager.getQrPrefixes().any { prefix ->
            clean.startsWith(prefix, ignoreCase = true)
        }
    }

    /**
     * Checks whether [barcode] looks like an encrypted ECK QR.
     *
     * V1 legacy: ECKn.COM/{67 chars} = 76 total.
     * V2 SmartTag: any URL with `/` where the body after the last `/` is >= 58 chars.
     */
    fun isEncryptedEckUrl(barcode: String): Boolean {
        val clean = barcode.trim()
            .removePrefix("http://")
            .removePrefix("https://")

        // Must contain a slash
        val slashIdx = clean.lastIndexOf('/')
        if (slashIdx < 0) return false

        val body = clean.substring(slashIdx + 1).uppercase()
        // Body: 56 data + dynamic IV (>=0) + 2 suffix = at least 58 chars
        if (body.length < 58) return false

        // Quick validation: first 56 chars must all be valid Base32
        for (i in 0 until 56) {
            if (BASE32_LOOKUP[body[i].code] < 0) return false
        }
        return true
    }

    /**
     * Attempts to decrypt an ECK QR barcode.
     *
     * V2 path (SmartTag): strips prefix + 2-char suffix, takes first 56 chars as data,
     * remainder as iv_string. SHA-256(iv_string)[:12] = GCM nonce.
     * Returns "{type_prefix}-{uuid}".
     *
     * V1 fallback: if V2 decryption fails AND length == 76, tries the legacy
     * 16-byte nonce path. Returns the raw plaintext string.
     */
    fun tryDecryptBarcode(barcode: String): String? {
        val cleanCode = barcode.trim()
            .removePrefix("http://")
            .removePrefix("https://")

        // Extract the body after the last '/'
        val slashIdx = cleanCode.lastIndexOf('/')
        if (slashIdx < 0) {
            Log.d(TAG, "No slash found in barcode")
            return null
        }
        val body = cleanCode.substring(slashIdx + 1).uppercase()

        val keyHex = SettingsManager.getEncKey()
        if (keyHex.length != 48) {
            Log.e(TAG, "Invalid ENC_KEY length: ${keyHex.length} (expected 48 hex chars)")
            return null
        }
        val keyBytes = hexStringToByteArray(keyHex)

        // Try V2 (SmartTag binary) first
        val v2Result = tryDecryptV2(body, keyBytes)
        if (v2Result != null) return v2Result

        // Fallback to V1 (legacy text-based) for exactly 67-char body
        // V1 body: {DATA:56}{IV:9}{SUFFIX:2} = 67
        if (body.length == 67) {
            return tryDecryptV1(body, keyBytes)
        }

        Log.e(TAG, "Decryption failed: no method succeeded")
        return null
    }

    /**
     * V2 decryption: binary SmartTag with dynamic IV.
     * Body layout: {DATA:56}{IV:dynamic}{SUFFIX:2}
     */
    private fun tryDecryptV2(afterPrefix: String, keyBytes: ByteArray): String? {
        if (afterPrefix.length < 58) return null // 56 data + 2 suffix minimum

        try {
            // Strip 2-char suffix
            val body = afterPrefix.substring(0, afterPrefix.length - 2)
            if (body.length < 56) return null

            val dataStr = body.substring(0, 56)
            val ivString = body.substring(56)

            // SHA-256(ivString)[:12] → 12-byte GCM nonce
            val sha256 = MessageDigest.getInstance("SHA-256")
            val ivHash = sha256.digest(ivString.toByteArray(Charsets.US_ASCII))
            val nonce = ivHash.copyOfRange(0, 12)

            // Base32 decode: 56 chars → 35 bytes
            val ciphertext = base32DecodeFixed(dataStr)
            if (ciphertext.size != 35) {
                Log.d(TAG, "V2: Base32 decoded ${ciphertext.size} bytes, expected 35")
                return null
            }

            // AES-192-GCM decrypt with 12-byte nonce
            val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
            val params = AEADParameters(KeyParameter(keyBytes), 128, nonce, null)
            cipher.init(false, params)

            val outputBytes = ByteArray(cipher.getOutputSize(ciphertext.size))
            var len = cipher.processBytes(ciphertext, 0, ciphertext.size, outputBytes, 0)
            len += cipher.doFinal(outputBytes, len)

            if (len != 19) {
                Log.d(TAG, "V2: Decrypted $len bytes, expected 19")
                return null
            }

            // Parse 19-byte SmartTag
            val plaintext = outputBytes.copyOfRange(0, 19)
            val uuidBytes = plaintext.copyOfRange(0, 16)
            val entityType = plaintext[16].toInt() and 0xFF
            // bytes 17-18 = flags (not used in routing string)

            // Convert 16 bytes → UUID
            val bb = ByteBuffer.wrap(uuidBytes)
            val msb = bb.long
            val lsb = bb.long
            val uuid = UUID(msb, lsb)

            val typeChar = entityType.toChar()
            val typePrefix = if (typeChar in CANONICAL_TYPES) typeChar.toString()
                else LEGACY_ENTITY_PREFIX[entityType] ?: "x"
            val result = "$typePrefix-$uuid"
            Log.d(TAG, "V2 decryption SUCCESS: $result (entity=0x${"%02x".format(entityType)})")
            return result

        } catch (e: Exception) {
            Log.d(TAG, "V2 decryption failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    /**
     * V1 decryption: legacy text-based with 16-byte nonce.
     * Body layout: {DATA:56}{IV:9} (suffix already stripped by caller context — but
     * here afterPrefix includes suffix, so: {DATA:56}{IV:9}{SUFFIX:2} = 67 chars)
     */
    private fun tryDecryptV1(afterPrefix: String, keyBytes: ByteArray): String? {
        if (afterPrefix.length != 67) return null // 56 + 9 + 2

        try {
            val base32Data = afterPrefix.substring(0, 56)
            val base32Iv = afterPrefix.substring(56, 65)

            val decodedData = base32DecodeFixed(base32Data)
            if (decodedData.size < 35) {
                Log.e(TAG, "V1: Decoded data too short: ${decodedData.size}")
                return null
            }

            // Reconstruct 16-byte IV from 9 ASCII chars
            val ivBytes = base32Iv.toByteArray(Charsets.US_ASCII)
            val iv = ByteArray(16)
            System.arraycopy(ivBytes, 0, iv, 0, 9)
            System.arraycopy(ivBytes, 0, iv, 9, 7)

            val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
            val params = AEADParameters(KeyParameter(keyBytes), 128, iv, null)
            cipher.init(false, params)

            val outputBytes = ByteArray(cipher.getOutputSize(decodedData.size))
            var len = cipher.processBytes(decodedData, 0, decodedData.size, outputBytes, 0)
            len += cipher.doFinal(outputBytes, len)

            val result = String(outputBytes, 0, len, Charsets.UTF_8)
            Log.d(TAG, "V1 decryption SUCCESS: $result")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "V1 decryption failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    /**
     * Decodes a Base32 string of fixed length (56 chars → 35 bytes).
     * Each 8 Base32 chars → 5 bytes.
     */
    private fun base32DecodeFixed(input: String): ByteArray {
        val iterations = input.length / 8
        val output = ByteArray(iterations * 5)

        for (i in 0 until iterations) {
            val b0 = BASE32_LOOKUP[input[i * 8 + 0].code]
            val b1 = BASE32_LOOKUP[input[i * 8 + 1].code]
            val b2 = BASE32_LOOKUP[input[i * 8 + 2].code]
            val b3 = BASE32_LOOKUP[input[i * 8 + 3].code]
            val b4 = BASE32_LOOKUP[input[i * 8 + 4].code]
            val b5 = BASE32_LOOKUP[input[i * 8 + 5].code]
            val b6 = BASE32_LOOKUP[input[i * 8 + 6].code]
            val b7 = BASE32_LOOKUP[input[i * 8 + 7].code]

            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0 || b5 < 0 || b6 < 0 || b7 < 0) {
                Log.e(TAG, "Invalid Base32 character at iteration $i")
                return ByteArray(0)
            }

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
}
