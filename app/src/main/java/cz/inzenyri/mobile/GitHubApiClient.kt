package cz.inzenyri.mobile

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class GitHubCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

/** HTTP and OAuth transport for GitHub; UI and token storage stay in MainActivity. */
internal object GitHubApiClient {
    fun request(path: String, method: String = "GET", token: String? = null, body: String? = null): String {
        val connection = (URL("https://api.github.com/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Inzenyri-Android")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(response).optString("message") } catch (_: Exception) { "" }
                val requiredPermissions = connection.getHeaderField("X-Accepted-GitHub-Permissions")
                val permissionHint = if (
                    code == HttpURLConnection.HTTP_FORBIDDEN &&
                    requiredPermissions?.contains("contents=write", ignoreCase = true) == true
                ) {
                    " GitHub App potřebuje oprávnění repozitáře Contents: Read and write. " +
                        "Změň ho v nastavení GitHub App, schval změnu oprávnění a potom účet v aplikaci znovu propoj."
                } else ""
                val permissionInfo = requiredPermissions?.takeIf { it.isNotBlank() }
                    ?.let { " Požadované oprávnění: $it." }.orEmpty()
                throw IOException(
                    "GitHub HTTP $code${if (detail.isNotBlank()) ": $detail" else ""}." +
                        permissionHint + permissionInfo
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun oauthRequest(endpoint: String, fields: Map<String, String>, allowOAuthError: Boolean = false): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("User-Agent", "Inzenyri-Android")
        }
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = JSONObject(response)
            if (code !in 200..299) {
                val detail = json.optString("error_description").ifBlank { json.optString("error") }
                throw IOException("GitHub OAuth HTTP $code${if (detail.isNotBlank()) ": $detail" else ""}.")
            }
            if (!allowOAuthError && json.has("error")) {
                throw IOException(json.optString("error_description", json.optString("error")))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun credentialsFrom(json: JSONObject, oldRefreshToken: String = ""): GitHubCredentials {
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token").ifBlank { oldRefreshToken }
        val lifetime = json.optLong("expires_in", 28_800)
        require(refresh.isNotBlank()) { "GitHub neposlal obnovovací token." }
        return GitHubCredentials(access, refresh, System.currentTimeMillis() + lifetime * 1000)
    }
}
