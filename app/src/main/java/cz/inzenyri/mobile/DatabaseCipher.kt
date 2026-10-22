package cz.inzenyri.mobile

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** Encryption envelope codec shared by local save, lock, and GitHub sync. */
internal object DatabaseCipher {
    const val ITERATIONS = 600_000
    private val additionalData = "inzenyri-data:v1".toByteArray(StandardCharsets.UTF_8)

    fun encrypt(data: JSONObject, pass: CharArray, source: JSONObject): String {
        val sortedItems =
            (0 until data.getJSONArray("tasks").length())
                .map { data.getJSONArray("tasks").getJSONObject(it) }
                .sortedBy {
                    DatabaseRecords.parseDate(it.optString("date"))?.time ?: Long.MAX_VALUE
                }
        data.put("tasks", JSONArray(sortedItems))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(derive(pass, salt), "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(additionalData)
        val encrypted = cipher.doFinal(data.toString().toByteArray(StandardCharsets.UTF_8))
        val out = JSONObject(source.toString())
        out.put("dbVersion", nextVersion(source.optString("dbVersion")))
        out.put(
            "kdf",
            JSONObject()
                .put("name", "PBKDF2")
                .put("hash", "SHA-256")
                .put("iterations", ITERATIONS)
                .put("salt", encode(salt)),
        )
        out.put(
            "cipher",
            JSONObject()
                .put("name", "AES-GCM")
                .put("keyLength", 256)
                .put("tagLength", 128)
                .put("iv", encode(iv)),
        )
        out.put("ciphertext", encode(encrypted))
        return out.toString(2) + "\n"
    }

    fun decrypt(envelope: JSONObject, pass: CharArray): ByteArray {
        require(
            envelope.optString("format") == "inzenyri-encrypted-data" &&
                envelope.optInt("version") == 1,
        ) {
            "Nepodporovaný formát."
        }
        val kdf = envelope.getJSONObject("kdf")
        val cipherInfo = envelope.getJSONObject("cipher")
        require(kdf.optString("name") == "PBKDF2" && kdf.optString("hash") == "SHA-256")
        require(cipherInfo.optString("name") == "AES-GCM" && cipherInfo.optInt("tagLength") == 128)
        val salt = decode(kdf.getString("salt"))
        val iv = decode(cipherInfo.getString("iv"))
        require(salt.size >= 16 && iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(derive(pass, salt, kdf.optInt("iterations", ITERATIONS)), "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(additionalData)
        return cipher.doFinal(decode(envelope.getString("ciphertext")))
    }

    fun nextVersion(current: String): String {
        val now = Calendar.getInstance()
        val prefix =
            String.format(
                Locale.ROOT,
                "%02d.%d.%d",
                now.get(Calendar.YEAR) % 100,
                now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH),
            )
        val match = Regex("^(\\d{2}\\.\\d{1,2}\\.\\d{1,2})-(\\d+)$").matchEntire(current)
        val next = if (match?.groupValues?.get(1) == prefix) match.groupValues[2].toInt() + 1 else 1
        return "$prefix-$next"
    }

    private fun derive(pass: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(iterations in 100_000..2_000_000) { "Neplatné parametry klíče." }
        // Python's hashlib.pbkdf2_hmac() consumes UTF-8 password bytes.
        val passwordBytes = String(pass).toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
        val block = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = block.copyOf()
        var previous = block
        repeat(iterations - 1) {
            previous = mac.doFinal(previous)
            for (index in result.indices) {
                result[index] = (result[index].toInt() xor previous[index].toInt()).toByte()
            }
        }
        passwordBytes.fill(0)
        previous.fill(0)
        return result
    }

    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
