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
    /** Account push permission alone does not prove that this App can write contents. */
    fun verifyWriteAccess(owner: String, repository: String, token: String) {
        val repo = JSONObject(request("repos/$owner/$repository", token = token))
        if (repo.optJSONObject("permissions")?.optBoolean("push") != true) {
            throw IOException("Přihlášený GitHub účet nemá právo zápisu do $owner/$repository.")
        }
        var page = 1
        while (true) {
            val result = JSONObject(request("user/installations?per_page=100&page=$page", token = token))
            val installations = result.optJSONArray("installations") ?: break
            for (index in 0 until installations.length()) {
                val installation = installations.getJSONObject(index)
                if (!installation.optJSONObject("account")?.optString("login").equals(owner, ignoreCase = true)) continue
                if (!installation.isNull("suspended_at")) {
                    throw IOException("Instalace GitHub App je pozastavená. Obnov ji v GitHub Settings → Applications → Installed GitHub Apps.")
                }
                if (installation.optJSONObject("permissions")?.optString("contents") != "write") {
                    throw IOException("GitHub App nemá Contents: Read and write. Nastav toto oprávnění v Developer settings → GitHub Apps → Permissions & events, schval změnu instalace a účet znovu propoj.")
                }
                var repoPage = 1
                while (true) {
                    val reposResult = JSONObject(request("user/installations/${installation.getLong("id")}/repositories?per_page=100&page=$repoPage", token = token))
                    val repositories = reposResult.optJSONArray("repositories") ?: break
                    for (repoIndex in 0 until repositories.length()) {
                        if (repositories.getJSONObject(repoIndex).optString("full_name").equals("$owner/$repository", ignoreCase = true)) return
                    }
                    if (repositories.length() < 100) break
                    repoPage++
                }
                throw IOException("GitHub App není povolená pro $owner/$repository. V Settings → Applications → Installed GitHub Apps → Configure přidej tento repozitář.")
            }
            if (installations.length() < 100) break
            page++
        }
        throw IOException("GitHub App není nainstalovaná pro účet $owner. V Developer settings → GitHub Apps → Install App ji nainstaluj pro repozitář $owner/$repository; samotné přihlášení nestačí.")
    }

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
                    (detail.contains("Resource not accessible by integration", ignoreCase = true) ||
                        requiredPermissions?.contains("contents=write", ignoreCase = true) == true) &&
                    method == "PUT" && path.substringBefore('?').endsWith("/contents/data.enc.json")
                ) {
                    " GitHub App potřebuje oprávnění repozitáře Contents: Read and write. " +
                        "Také musí být nainstalovaná pro tento repozitář. Změň oprávnění v Developer settings → GitHub Apps, " +
                        "schval změnu v Settings → Applications → Installed GitHub Apps a potom účet v aplikaci znovu propoj."
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
