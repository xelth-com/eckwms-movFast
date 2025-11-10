package com.xelth.eckwms_movfast.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair

object CryptoManager {
    private const val TAG = "CryptoManager"
    private const val PREFS_NAME = "secure_device_keys"
    private const val KEY_PRIVATE = "device_private_key"
    private const val KEY_PUBLIC = "device_public_key"

    private val sodium = LazySodiumAndroid(SodiumAndroid())
    private lateinit var securePrefs: SharedPreferences

    fun initialize(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            securePrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            Log.d(TAG, "CryptoManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CryptoManager", e)
            throw e
        }
    }

    fun getOrCreateKeyPair(): KeyPair {
        val privateKeyHex = securePrefs.getString(KEY_PRIVATE, null)
        val publicKeyHex = securePrefs.getString(KEY_PUBLIC, null)

        return if (privateKeyHex != null && publicKeyHex != null) {
            Log.d(TAG, "Loading existing key pair")
            KeyPair(
                Key.fromHexString(publicKeyHex),
                Key.fromHexString(privateKeyHex)
            )
        } else {
            Log.d(TAG, "Generating new Ed25519 key pair")
            generateAndStoreKeyPair()
        }
    }

    private fun generateAndStoreKeyPair(): KeyPair {
        val keyPair = sodium.cryptoSignKeypair()

        // Store keys securely
        securePrefs.edit().apply {
            putString(KEY_PRIVATE, keyPair.secretKey.asHexString)
            putString(KEY_PUBLIC, keyPair.publicKey.asHexString)
            apply()
        }

        Log.d(TAG, "New key pair generated and stored")
        return keyPair
    }

    fun sign(data: ByteArray): ByteArray {
        val keyPair = getOrCreateKeyPair()
        val signature = ByteArray(Sign.BYTES)

        val success = sodium.cryptoSignDetached(
            signature,
            data,
            data.size.toLong(),
            keyPair.secretKey.asBytes
        )

        if (!success) {
            throw RuntimeException("Failed to sign data")
        }

        Log.d(TAG, "Data signed successfully")
        return signature
    }

    fun getPublicKeyBase64(): String {
        val keyPair = getOrCreateKeyPair()
        val publicKeyBytes = keyPair.publicKey.asBytes
        return Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
    }

    fun getPublicKeyHex(): String {
        val keyPair = getOrCreateKeyPair()
        return keyPair.publicKey.asHexString
    }

    fun clearKeys() {
        securePrefs.edit().clear().apply()
        Log.d(TAG, "Keys cleared")
    }
}
