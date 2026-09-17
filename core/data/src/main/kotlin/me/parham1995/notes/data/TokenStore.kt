package me.parham1995.notes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the GitHub access token, encrypted with a key that cannot leave the
 * device.
 *
 * Deliberately not `EncryptedSharedPreferences`: it has been deprecated since
 * androidx.security 1.1.0-alpha07, it does its crypto synchronously on the
 * calling thread, and it has a documented history of keyset corruption on
 * exactly the sort of custom ROM this app is sideloaded onto. What it would do
 * for a single short string is what this does in a few dozen lines, with no
 * extra dependency and nothing deprecated.
 *
 * The key lives in the AndroidKeyStore and is generated once. User
 * authentication is deliberately not required, because sync has to run in the
 * background while the device is locked.
 */
@Singleton
class TokenStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** The stored token, or null when none has been set. */
        suspend fun token(): String? =
            withContext(Dispatchers.IO) {
                val stored =
                    context.settingsDataStore.data
                        .map { it[TOKEN] }
                        .first() ?: return@withContext null
                runCatching { decrypt(stored) }.getOrNull()
            }

        suspend fun setToken(token: String) =
            withContext(Dispatchers.IO) {
                val encrypted = encrypt(token)
                context.settingsDataStore.edit { it[TOKEN] = encrypted }
                Unit
            }

        suspend fun clear() =
            withContext(Dispatchers.IO) {
                context.settingsDataStore.edit { it.remove(TOKEN) }
                Unit
            }

        suspend fun hasToken(): Boolean = token() != null

        private fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray())
            // The IV is generated per encryption and is not secret, so it is
            // simply prefixed to the ciphertext.
            return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        }

        private fun decrypt(stored: String): String {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, raw, 0, IV_LENGTH),
            )
            return String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH))
        }

        private fun secretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    // Background sync must work while the device is locked.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }

        private companion object {
            val TOKEN = stringPreferencesKey("github_token")
            const val ANDROID_KEY_STORE = "AndroidKeyStore"
            const val KEY_ALIAS = "notes_pat"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val KEY_SIZE_BITS = 256
            const val TAG_LENGTH_BITS = 128
            const val IV_LENGTH = 12
        }
    }
