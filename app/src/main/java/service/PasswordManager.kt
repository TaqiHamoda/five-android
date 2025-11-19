package service

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordManager {

    private const val PREFS_FILE_NAME = "secure_prefs"
    private const val KEY_PASSWORD_HASH = "password_hash"
    private const val KEY_PASSWORD_SALT = "password_salt"
    private const val ITERATIONS = 10000 // Number of iterations for PBKDF2
    private const val KEY_LENGTH = 256 // Key length in bits

    private lateinit var encryptedSharedPreferences: SharedPreferences

    fun init(context: Context) {
        val keyGenParameterSpec = MasterKeys.AES256_GCM_SPEC
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        encryptedSharedPreferences = EncryptedSharedPreferences.create(
            PREFS_FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun setPassword(password: String) { // Changed return type to Unit
        if (password.isEmpty()) {
            clearPassword()
            return
        }

        val salt = generateSalt()
        val hash = hashPassword(password, salt)

        encryptedSharedPreferences.edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .putString(KEY_PASSWORD_SALT, salt)
            .apply()
    }

    fun checkPassword(password: String): Boolean {
        if (!hasPassword()) {
            return true // No password set, so any attempt is "correct"
        }

        val storedHash = encryptedSharedPreferences.getString(KEY_PASSWORD_HASH, null) ?: return false
        val storedSalt = encryptedSharedPreferences.getString(KEY_PASSWORD_SALT, null) ?: return false

        val enteredPasswordHash = hashPassword(password, storedSalt)
        return storedHash == enteredPasswordHash
    }

    fun hasPassword(): Boolean {
        return encryptedSharedPreferences.contains(KEY_PASSWORD_HASH) &&
               encryptedSharedPreferences.contains(KEY_PASSWORD_SALT)
    }

    fun clearPassword() { // Changed return type to Unit
        encryptedSharedPreferences.edit()
            .remove(KEY_PASSWORD_HASH)
            .remove(KEY_PASSWORD_SALT)
            .apply()
    }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(16) // 16 bytes for salt
        random.nextBytes(salt)
        return salt.toHexString()
    }

    private fun hashPassword(password: String, salt: String): String {
        val saltBytes = salt.hexStringToByteArray()
        val spec = PBEKeySpec(password.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val hash = factory.generateSecret(spec).encoded
        return hash.toHexString()
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
    private fun String.hexStringToByteArray(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}