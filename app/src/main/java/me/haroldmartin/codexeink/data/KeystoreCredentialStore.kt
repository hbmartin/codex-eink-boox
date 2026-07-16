package me.haroldmartin.codexeink.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun hasStoredProfile(): Boolean =
        preferences.contains(KEY_PAYLOAD) && preferences.contains(KEY_IV)

    override fun read(): ConnectionProfile? {
        val encrypted = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val iv = requireNotNull(preferences.getString(KEY_IV, null)) {
            "The encrypted connection profile is incomplete"
        }
        return decode(decrypt(encrypted, iv))
    }

    override fun write(profile: ConnectionProfile) {
        val (payload, iv) = encrypt(encode(profile))
        check(
            preferences.edit()
                .putString(KEY_PAYLOAD, payload)
                .putString(KEY_IV, iv)
                .commit(),
        ) { "Unable to persist encrypted connection profile" }
    }

    override fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to remove the encrypted connection profile" }
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(clearText: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.doFinal(clearText.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
        )
        val clearText = cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP))
        return String(clearText, StandardCharsets.UTF_8)
    }

    private fun encode(profile: ConnectionProfile): String = listOf(
        profile.displayName,
        profile.endpoint,
        profile.credential,
        profile.mode.name,
    ).joinToString(SEPARATOR) { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }

    private fun decode(value: String): ConnectionProfile {
        val fields = value.split(SEPARATOR).map {
            String(Base64.decode(it, Base64.NO_WRAP), StandardCharsets.UTF_8)
        }
        require(fields.size == PROFILE_FIELD_COUNT) { "Invalid encrypted connection profile" }
        return ConnectionProfile(
            displayName = fields[0],
            endpoint = fields[1],
            credential = fields[2],
            mode = TransportMode.valueOf(fields[3]),
        )
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "codex-eink-connection-v1"
        const val PREFERENCES_NAME = "encrypted_connection"
        const val KEY_PAYLOAD = "payload"
        const val KEY_IV = "iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val PROFILE_FIELD_COUNT = 4
        const val SEPARATOR = "."
    }
}
