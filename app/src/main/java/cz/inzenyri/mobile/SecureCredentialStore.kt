package cz.inzenyri.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts saved passwords and GitHub credentials with per-device Android Keystore keys. */
internal class SecureCredentialStore(context: Context) {
    private val appContext = context.applicationContext

    fun saveDatabasePassword(value: CharArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(DATABASE_PASSWORD_KEY_ALIAS))
        val encrypted = cipher.doFinal(String(value).toByteArray(StandardCharsets.UTF_8))
        appContext.getSharedPreferences(DATABASE_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(SAVED_PASSWORD_IV, Base64.getEncoder().encodeToString(cipher.iv))
            .putString(SAVED_PASSWORD_VALUE, Base64.getEncoder().encodeToString(encrypted))
            .apply()
    }

    fun readSavedDatabasePassword(): CharArray? {
        return try {
            val prefs = appContext.getSharedPreferences(DATABASE_PREFERENCES, Context.MODE_PRIVATE)
            val iv = prefs.getString(SAVED_PASSWORD_IV, null) ?: return null
            val encrypted = prefs.getString(SAVED_PASSWORD_VALUE, null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey(DATABASE_PASSWORD_KEY_ALIAS),
                GCMParameterSpec(128, Base64.getDecoder().decode(iv))
            )
            String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8).toCharArray()
        } catch (_: Exception) {
            forgetDatabasePassword()
            null
        }
    }

    fun forgetDatabasePassword() {
        appContext.getSharedPreferences(DATABASE_PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove(SAVED_PASSWORD_IV)
            .remove(SAVED_PASSWORD_VALUE)
            .apply()
    }

    fun saveGithubCredentials(credentials: GitHubCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(TOKEN_KEY_ALIAS))
        val payload = JSONObject()
            .put("access_token", credentials.accessToken)
            .put("refresh_token", credentials.refreshToken)
            .put("expires_at", credentials.expiresAt)
            .toString()
        val encrypted = cipher.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .putString("credentials", Base64.getEncoder().encodeToString(encrypted))
            .remove("token")
            // Save before the first API call so Android cannot kill the app before credentials persist.
            .commit()
            .also { persisted ->
                if (!persisted) throw IOException("Přihlášení ke GitHubu se nepodařilo uložit do zařízení.")
            }
    }

    fun readGithubCredentials(): GitHubCredentials? {
        return try {
            val prefs = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val iv = prefs.getString("iv", null) ?: return null
            val encrypted = prefs.getString("credentials", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey(TOKEN_KEY_ALIAS),
                GCMParameterSpec(128, Base64.getDecoder().decode(iv))
            )
            val data = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8))
            GitHubCredentials(data.getString("access_token"), data.getString("refresh_token"), data.getLong("expires_at"))
        } catch (_: Exception) {
            null
        }
    }

    fun hasStoredGithubCredentials(): Boolean {
        val prefs = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return prefs.contains("iv") || prefs.contains("credentials") || prefs.contains("token")
    }

    fun clearGithubCredentials() {
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun keystoreKey(alias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    companion object {
        const val PREFERENCES = "github_credentials"
        private const val DATABASE_PREFERENCES = "database_state"
        private const val TOKEN_KEY_ALIAS = "inzenyri_github_token"
        private const val DATABASE_PASSWORD_KEY_ALIAS = "inzenyri_database_password"
        private const val SAVED_PASSWORD_IV = "database_password_iv"
        private const val SAVED_PASSWORD_VALUE = "database_password_value"
    }
}
