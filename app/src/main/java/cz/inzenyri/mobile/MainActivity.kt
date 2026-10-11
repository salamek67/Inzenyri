package cz.inzenyri.mobile

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val networkWorker = Executors.newSingleThreadExecutor()
    private val purple = Color.rgb(107, 76, 210)
    private val ink = Color.rgb(31, 38, 56)
    private val muted = Color.rgb(103, 113, 132)
    private var database: JSONObject? = null
    private var originalDatabase: String? = null
    private var password: CharArray? = null
    private var sourceEnvelope: JSONObject? = null
    private var selectedType = "task"
    private var dateValue = today()
    private var statusView: TextView? = null
    private var form: LinearLayout? = null
    private var typeButtons = mutableMapOf<String, Button>()
    private var titleInput: EditText? = null
    private var descriptionInput: EditText? = null
    private var solutionInput: EditText? = null
    private var quizPromptInput: EditText? = null
    private var quizAnswersInput: EditText? = null
    private var dateButton: Button? = null
    private var exportButton: Button? = null
    private var pushButton: Button? = null
    private var updateButton: Button? = null
    private var pendingApk: File? = null
    private var updateAsset: JSONObject? = null
    private var sourceButton: Button? = null
    private var originButton: Button? = null

    private val openDocument = registerForActivityResultCompat(101)
    private val createDocument = registerForActivityResultCompat(102)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        checkForUpdate(silent = true)
    }

    private fun registerForActivityResultCompat(requestCode: Int): (Intent) -> Unit = { intent ->
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Uses document picker callbacks for compatibility with the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 103) {
            if (android.os.Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) installDownloadedApk()
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            101 -> {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (raw == null) return toast("Soubor se nepodařilo otevřít.")
                askPassword(raw)
            }
            102 -> {
                worker.execute {
                    try {
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                            it.write(encryptedOutput())
                        } ?: error("Soubor se nepodařilo uložit.")
                        runOnUiThread {
                            statusView?.text = "Uloženo. Nahraď tímto souborem data.enc.json v projektu."
                            toast("Zašifrovaná databáze je uložená.")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast(e.message ?: "Uložení se nezdařilo.") }
                    }
                }
            }
        }
    }

    private fun render() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(20))
            background = rounded(Color.rgb(247, 245, 255), 0)
        }
        val scroller = ScrollView(this).apply { addView(page) }
        setContentView(scroller)

        page.addView(label("INŽENÝŘI", 13, purple, true))
        page.addView(label("Rychlé přidání", 30, ink, true), margin(0, 4, 0, 4))
        page.addView(label("Otevři šifrovaná data, přidej položku a pushni ji na origin.", 15, muted, false), margin(0, 0, 0, 16))
        sourceButton = button("Otevřít data.enc.json") { chooseSource() }
        page.addView(sourceButton, margin(0, 4, 0, 8))
        originButton = button("Načíst databázi z origin") { loadFromOrigin() }
        page.addView(originButton, margin(0, 0, 0, 8))
        statusView = label("Data zůstávají zašifrovaná i v uloženém souboru.", 13, muted, false)
        page.addView(statusView, margin(0, 0, 0, 12))
        exportButton = button("Uložit šifrovanou databázi") { chooseDestination() }.also { it.visibility = View.GONE }
        page.addView(exportButton, margin(0, 0, 0, 12))
        pushButton = button("Pushnout DB na origin") { pushToOrigin() }.also { it.visibility = View.GONE }
        page.addView(pushButton, margin(0, 0, 0, 12))
        updateButton = button("Zkontrolovat aktualizace aplikace") {
            if (updateAsset == null) checkForUpdate(silent = false) else downloadUpdate()
        }
        page.addView(updateButton, margin(0, 0, 0, 12))

        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        page.addView(form)
        form?.apply {
            addView(label("Typ položky", 14, ink, true), margin(0, 8, 0, 7))
            val types = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("task" to "Úkol", "test" to "Test", "event" to "Akce").forEach { (type, title) ->
                val b = button(title) { selectType(type) }
                typeButtons[type] = b
                types.addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(7) })
            }
            addView(types)
            titleInput = input("Název", false).also { addView(it, margin(0, 14, 0, 0)) }
            dateButton = button("Datum · $dateValue") { chooseDate() }.also { addView(it, margin(0, 10, 0, 0)) }
            descriptionInput = input("Popis (volitelné)", true).also { addView(it, margin(0, 10, 0, 0)) }
            solutionInput = input("Řešení v Markdownu (volitelné)", true).also { addView(it, margin(0, 10, 0, 0)) }
            quizPromptInput = input("Otázka (volitelné)", true).also { addView(it, margin(0, 10, 0, 0)) }
            quizAnswersInput = input("Správné odpovědi (odděl |)", false).also { addView(it, margin(0, 8, 0, 0)) }
            addView(button("Přidat položku") { addItem() }, margin(0, 16, 0, 4))
        }
        selectType("task")
    }

    private fun chooseSource() {
        confirmReplacingDatabase {
            chooseSourceDocument()
        }
    }

    private fun chooseSourceDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        openDocument(intent)
    }

    private fun loadFromOrigin() {
        confirmReplacingDatabase {
            runGithubTask { token ->
                val response = JSONObject(githubRequest("repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json?ref=$GITHUB_BRANCH", token = token))
                val encoded = response.optString("content").ifBlank {
                    response.optJSONObject("content")?.optString("content").orEmpty()
                }
                require(encoded.isNotBlank()) { "GitHub API nevrátil obsah databáze." }
                val raw = String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8)
                runOnUiThread {
                    statusView?.text = "Databáze stažena z origin. Zadej heslo."
                    askPassword(raw)
                }
            }
        }
    }

    private fun confirmReplacingDatabase(action: () -> Unit) {
        if (database == null || database.toString() == originalDatabase) {
            action()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Nahradit rozpracovanou databázi?")
            .setMessage("Máš položky, které ještě nejsou pushnuté na origin. Při načtení jiné databáze budou ztraceny.")
            .setNegativeButton("Zpět", null)
            .setPositiveButton("Pokračovat") { _, _ -> action() }
            .show()
    }

    private fun askPassword(raw: String) {
        val field = EditText(this).apply {
            hint = "Heslo k databázi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Odemknout databázi")
            .setMessage("Heslo se uchová pouze v paměti po dobu otevření aplikace.")
            .setView(field)
            .setNegativeButton("Zrušit", null)
            .setPositiveButton("Odemknout", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val candidate = field.text.toString().toCharArray()
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        statusView?.text = "Ověřuji šifrování…"
                        worker.execute {
                            try {
                                val envelope = JSONObject(raw)
                                val plain = decrypt(envelope, candidate)
                                val parsed = JSONObject(String(plain, StandardCharsets.UTF_8))
                                require(parsed.optJSONArray("tasks") != null) { "Databáze nemá seznam položek." }
                                runOnUiThread {
                                    password?.fill('\u0000')
                                    password = candidate
                                    database = parsed
                                    originalDatabase = parsed.toString()
                                    sourceEnvelope = envelope
                                    form?.visibility = View.VISIBLE
                                    exportButton?.visibility = View.VISIBLE
                                    pushButton?.visibility = View.VISIBLE
                                    statusView?.text = "Odemčeno · ${parsed.getJSONArray("tasks").length()} položek"
                                    dialog.dismiss()
                                }
                            } catch (_: Exception) {
                                candidate.fill('\u0000')
                                runOnUiThread {
                                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                    statusView?.text = ""
                                    toast("Heslo nesouhlasí nebo je soubor poškozený.")
                                }
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun addItem() {
        val db = database ?: return toast("Nejdřív otevři databázi.")
        val name = titleInput?.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            titleInput?.error = "Zadej název"
            return
        }
        val parsedDate = parseDate(dateValue) ?: return toast("Vyber platné datum.")
        val item = JSONObject()
            .put("type", selectedType)
            .put("name", name)
            .put("date", SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(parsedDate))
            .put("task", descriptionInput?.text?.toString()?.trim().orEmpty())
            .put("solution", if (selectedType == "task") solutionInput?.text?.toString()?.trim().orEmpty() else "")
        if (selectedType == "test") {
            val prompt = quizPromptInput?.text?.toString()?.trim().orEmpty()
            val answers = quizAnswersInput?.text?.toString().orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (prompt.isNotEmpty() != answers.isNotEmpty()) return toast("Vyplň otázku i alespoň jednu správnou odpověď.")
            if (prompt.isNotEmpty()) {
                item.put("quiz", JSONObject().put("shuffle", false).put("questions", JSONArray().put(
                    JSONObject().put("type", "text").put("prompt", prompt).put("answers", JSONArray(answers)).put("ignoreCase", true)
                )))
            }
        }
        db.getJSONArray("tasks").put(item)
        titleInput?.text?.clear()
        descriptionInput?.text?.clear()
        solutionInput?.text?.clear()
        quizPromptInput?.text?.clear()
        quizAnswersInput?.text?.clear()
        statusView?.text = "Přidáno · ${db.getJSONArray("tasks").length()} položek celkem. Teď můžeš pushnout na origin."
        exportButton?.visibility = View.VISIBLE
        pushButton?.visibility = View.VISIBLE
        titleInput?.requestFocus()
        toast("Položka přidána")
    }

    private fun chooseDestination() {
        if (database == null) return toast("Nejdřív otevři databázi.")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "data.enc.json")
        }
        createDocument(intent)
    }

    private fun pushToOrigin() {
        if (database == null || password == null) return toast("Nejdřív otevři a odemkni databázi.")
        if (database.toString() == originalDatabase) return toast("Zatím nejsou žádné nové položky.")
        runGithubTask(freezeForm = true) { token ->
            val path = "repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json?ref=$GITHUB_BRANCH"
            val current = JSONObject(githubRequest(path, token = token))
            val encodedContent = current.optString("content").ifBlank {
                current.optJSONObject("content")?.optString("content").orEmpty()
            }
            require(encodedContent.isNotBlank()) { "GitHub API nevrátil obsah databáze." }
            val content = Base64.getMimeDecoder().decode(encodedContent)
            val remoteEnvelope = JSONObject(String(content, StandardCharsets.UTF_8))
            val remotePlain = decrypt(remoteEnvelope, password ?: error("Databáze je zamčená."))
            val remoteDatabase = JSONObject(String(remotePlain, StandardCharsets.UTF_8))
            if (remoteDatabase.toString() != originalDatabase) {
                throw IOException("Databáze na origin se od načtení změnila. Načti nejnovější data a zkus znovu.")
            }
            sourceEnvelope = remoteEnvelope
            val updatedEnvelope = JSONObject(encryptedOutput())
            val commitMessage = "Update DB (${updatedEnvelope.getString("dbVersion")})"
            val payload = JSONObject()
                .put("message", commitMessage)
                .put("content", Base64.getEncoder().encodeToString(updatedEnvelope.toString(2).toByteArray(StandardCharsets.UTF_8) + byteArrayOf(10)))
                .put("sha", current.getString("sha"))
                .put("branch", GITHUB_BRANCH)
            githubRequest("repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json", "PUT", token, payload.toString())
            sourceEnvelope = updatedEnvelope
            originalDatabase = database.toString()
            runOnUiThread {
                statusView?.text = "Push hotov · $commitMessage"
                toast("Databáze byla odeslána na origin.")
            }
        }
    }

    private fun githubRequest(path: String, method: String = "GET", token: String? = null, body: String? = null): String {
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
                throw IOException("GitHub HTTP $code${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private data class GitHubCredentials(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    private fun runGithubTask(freezeForm: Boolean = false, task: (String) -> Unit) {
        val clientId = BuildConfig.GITHUB_APP_CLIENT_ID
        if (clientId.isBlank()) {
            toast("GitHub App Client ID není nastaven. Nastav GH_APP_CLIENT_ID v Actions Variables a sestav aplikaci znovu.")
            statusView?.text = "GitHub přihlášení není nakonfigurované."
            return
        }
        val saved = readGithubCredentials()
        if (saved != null) {
            executeGithubTask(saved, freezeForm, task)
        } else {
            startDeviceLogin(clientId, freezeForm, task)
        }
    }

    private fun executeGithubTask(credentials: GitHubCredentials, freezeForm: Boolean, task: (String) -> Unit) {
        updateButton?.isEnabled = false
        pushButton?.isEnabled = false
        if (freezeForm) setFormEnabled(false)
        statusView?.text = "Připojuji se k GitHubu…"
        networkWorker.execute {
            try {
                val active = validCredentials(credentials)
                task(active.accessToken)
            } catch (e: Exception) {
                if (e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true) clearGithubToken()
                runOnUiThread {
                    statusView?.text = e.message ?: "Požadavek na GitHub se nezdařil."
                    toast(e.message ?: "Požadavek na GitHub se nezdařil.")
                }
            } finally {
                runOnUiThread {
                    updateButton?.isEnabled = true
                    pushButton?.isEnabled = true
                    if (freezeForm) setFormEnabled(true)
                }
            }
        }
    }

    private fun startDeviceLogin(clientId: String, freezeForm: Boolean, task: (String) -> Unit) {
        updateButton?.isEnabled = false
        pushButton?.isEnabled = false
        if (freezeForm) setFormEnabled(false)
        statusView?.text = "Připravuji přihlášení přes GitHub…"
        networkWorker.execute {
            try {
                val response = oauthRequest("https://github.com/login/device/code", mapOf("client_id" to clientId, "scope" to "offline_access"))
                val deviceCode = response.getString("device_code")
                val userCode = response.getString("user_code")
                val verificationUri = response.optString("verification_uri", "https://github.com/login/device")
                require(verificationUri == "https://github.com/login/device") { "GitHub vrátil neplatnou adresu přihlášení." }
                val expiresAt = System.currentTimeMillis() + response.optLong("expires_in", 900) * 1000
                val cancelled = AtomicBoolean(false)
                runOnUiThread {
                    val dialog = android.app.AlertDialog.Builder(this)
                        .setTitle("Přihlášení přes GitHub")
                        .setMessage("Otevři GitHub tlačítkem níže a zadej tento jednorázový kód:\n\n$userCode\n\nKód zadávej pouze na github.com a nikomu ho neposílej.")
                        .setNegativeButton("Zrušit") { _, _ -> cancelled.set(true) }
                        .setPositiveButton("Otevřít GitHub", null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri)))
                        }
                    }
                    dialog.setOnCancelListener { cancelled.set(true) }
                    dialog.show()
                    networkWorker.execute {
                        try {
                            var interval = response.optLong("interval", 5).coerceAtLeast(1)
                            var authorized: GitHubCredentials? = null
                            while (!cancelled.get() && System.currentTimeMillis() < expiresAt) {
                                Thread.sleep(interval * 1000)
                                if (cancelled.get()) break
                                val poll = oauthRequest("https://github.com/login/oauth/access_token", mapOf(
                                    "client_id" to clientId,
                                    "device_code" to deviceCode,
                                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                                ), allowOAuthError = true)
                                when (poll.optString("error")) {
                                    "authorization_pending" -> continue
                                    "slow_down" -> { interval += 5; continue }
                                    "" -> { authorized = credentialsFrom(poll); break }
                                    "access_denied" -> throw IOException("Přihlášení přes GitHub bylo zamítnuto.")
                                    "expired_token", "token_expired" -> throw IOException("Kód pro přihlášení vypršel. Zkus to znovu.")
                                    else -> throw IOException("GitHub přihlášení selhalo: ${poll.optString("error_description", poll.optString("error"))}")
                                }
                            }
                            if (cancelled.get()) throw IOException("Přihlášení bylo zrušeno.")
                            val credentials = authorized ?: throw IOException("Kód pro přihlášení vypršel. Zkus to znovu.")
                            saveGithubCredentials(credentials)
                            runOnUiThread { dialog.dismiss() }
                            executeGithubTask(credentials, freezeForm, task)
                        } catch (e: Exception) {
                            runOnUiThread {
                                dialog.dismiss()
                                statusView?.text = e.message ?: "Přihlášení přes GitHub selhalo."
                                toast(statusView?.text.toString())
                                updateButton?.isEnabled = true
                                pushButton?.isEnabled = true
                                if (freezeForm) setFormEnabled(true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView?.text = e.message ?: "Přihlášení přes GitHub selhalo."
                    toast(statusView?.text.toString())
                    updateButton?.isEnabled = true
                    pushButton?.isEnabled = true
                    if (freezeForm) setFormEnabled(true)
                }
            }
        }
    }

    private fun oauthRequest(endpoint: String, fields: Map<String, String>, allowOAuthError: Boolean = false): JSONObject {
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
            if (!allowOAuthError && json.has("error")) throw IOException(json.optString("error_description", json.optString("error")))
            return json
        } finally { connection.disconnect() }
    }

    private fun credentialsFrom(json: JSONObject, oldRefreshToken: String = ""): GitHubCredentials {
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token").ifBlank { oldRefreshToken }
        val lifetime = json.optLong("expires_in", 28_800)
        require(refresh.isNotBlank()) { "GitHub neposlal obnovovací token." }
        return GitHubCredentials(access, refresh, System.currentTimeMillis() + lifetime * 1000)
    }

    private fun validCredentials(credentials: GitHubCredentials): GitHubCredentials {
        if (credentials.expiresAt > System.currentTimeMillis() + 60_000) return credentials
        val refreshed = try {
            oauthRequest("https://github.com/login/oauth/access_token", mapOf(
                "client_id" to BuildConfig.GITHUB_APP_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to credentials.refreshToken
            ))
        } catch (e: Exception) {
            if (e.message?.contains("bad_refresh_token", ignoreCase = true) == true ||
                e.message?.contains("invalid_grant", ignoreCase = true) == true
            ) clearGithubToken()
            throw e
        }
        return credentialsFrom(refreshed, credentials.refreshToken).also(::saveGithubCredentials)
    }

    private fun setFormEnabled(enabled: Boolean) {
        fun visit(view: View) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        form?.let { visit(it) }
        sourceButton?.isEnabled = enabled
        originButton?.isEnabled = enabled
        exportButton?.isEnabled = enabled
    }

    private fun tokenKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(TOKEN_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(TOKEN_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun saveGithubCredentials(credentials: GitHubCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, tokenKey())
        val payload = JSONObject()
            .put("access_token", credentials.accessToken)
            .put("refresh_token", credentials.refreshToken)
            .put("expires_at", credentials.expiresAt)
            .toString()
        val encrypted = cipher.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE).edit()
            .putString("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .putString("credentials", Base64.getEncoder().encodeToString(encrypted))
            .remove("token")
            .apply()
    }

    private fun readGithubCredentials(): GitHubCredentials? {
        return try {
            val prefs = getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE)
            val iv = prefs.getString("iv", null) ?: return null
            val encrypted = prefs.getString("credentials", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, tokenKey(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
            val data = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8))
            GitHubCredentials(data.getString("access_token"), data.getString("refresh_token"), data.getLong("expires_at"))
        } catch (_: Exception) { null }
    }

    private fun clearGithubToken() {
        getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE).edit().clear().apply()
    }

    private fun checkForUpdate(silent: Boolean) {
        val saved = readGithubCredentials()
        if (saved != null) {
            networkWorker.execute {
                try { checkLatestRelease(validCredentials(saved).accessToken, showResult = !silent) }
                catch (e: Exception) {
                    if (e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true) clearGithubToken()
                    runOnUiThread {
                        statusView?.text = e.message ?: "Kontrola aktualizací se nezdařila."
                        if (!silent) toast(statusView?.text.toString())
                    }
                }
            }
            return
        }
        if (!silent) {
            runGithubTask { checkLatestRelease(it, showResult = true) }
            return
        }
        networkWorker.execute {
            try {
                checkLatestRelease(null, showResult = !silent)
            } catch (e: Exception) {
                runOnUiThread {
                    statusView?.text = "Pro kontrolu soukromých vydání použij tlačítko aktualizací a přihlas se přes GitHub."
                    if (!silent) toast(statusView?.text.toString())
                }
            }
        }
    }

    private fun checkLatestRelease(token: String?, showResult: Boolean) {
        val release = JSONObject(githubRequest("repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest", token = token))
        val asset = release.optJSONArray("assets")?.let { assets ->
            (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == RELEASE_APK_NAME }
        } ?: throw IOException("Nejnovější release neobsahuje $RELEASE_APK_NAME.")
        val tag = release.optString("tag_name").removePrefix("v")
        val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
        if (compareVersions(tag, current) > 0) {
            updateAsset = asset
            runOnUiThread {
                updateButton?.text = "Stáhnout aktualizaci $tag"
                statusView?.text = "Je dostupná verze $tag."
                if (showResult) toast("Je dostupná nová verze $tag.")
            }
        } else {
            runOnUiThread {
                statusView?.text = "Aplikace je aktuální · $current"
                if (showResult) toast("Používáš aktuální verzi.")
            }
        }
    }

    private fun compareVersions(first: String, second: String): Int {
        val a = Regex("\\d+").findAll(first).map { it.value.toInt() }.toList()
        val b = Regex("\\d+").findAll(second).map { it.value.toInt() }.toList()
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun downloadUpdate() {
        val asset = updateAsset ?: return checkForUpdate(silent = false)
        val saved = readGithubCredentials()
        updateButton?.isEnabled = false
        statusView?.text = "Stahuji aktualizaci…"
        networkWorker.execute {
            try {
                val token = saved?.let { validCredentials(it).accessToken }
                val file = downloadReleaseAsset(asset, token)
                val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: error("Stažený soubor není platný APK.")
                require(info.packageName == packageName) { "APK nepatří této aplikaci." }
                val installedVersion = packageManager.getPackageInfo(packageName, 0).versionCode
                require(info.versionCode > installedVersion) { "Release neobsahuje novější verzi." }
                pendingApk = file
                runOnUiThread {
                    statusView?.text = "Aktualizace stažena. Android požádá o potvrzení instalace."
                    if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                        startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), 103)
                    } else installDownloadedApk()
                }
            } catch (e: Exception) {
                runOnUiThread { statusView?.text = e.message ?: "Stažení aktualizace se nezdařilo."; toast(statusView?.text.toString()) }
            } finally {
                runOnUiThread { updateButton?.isEnabled = true }
            }
        }
    }

    private fun downloadReleaseAsset(asset: JSONObject, token: String?): File {
        val assetUrl = asset.getString("url")
        require(assetUrl.startsWith("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/assets/")) { "Neplatná adresa APK." }
        val first = (URL(assetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "Inzenyri-Android")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            instanceFollowRedirects = false
        }
        val code = first.responseCode
        val downloadUrl = if (code in 300..399) {
            val target = URL(first.getHeaderField("Location"))
            require(target.protocol == "https" && (target.host == "github.com" || target.host.endsWith(".githubusercontent.com"))) { "Neplatný server aktualizace." }
            target
        } else {
            if (code !in 200..299) throw IOException("GitHub odmítl APK (HTTP $code).")
            URL(assetUrl)
        }
        val connection = if (downloadUrl.toString() == assetUrl && code in 200..299) first else (downloadUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Inzenyri-Android")
        }
        try {
            val file = File(cacheDir, "updates/$RELEASE_APK_NAME")
            file.parentFile?.mkdirs()
            connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            val digest = asset.optString("digest").removePrefix("sha256:")
            if (digest.isNotBlank()) {
                val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                    String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
                }
                require(actual.equals(digest, ignoreCase = true)) { "Kontrola integrity APK selhala." }
            }
            return file
        } finally {
            first.disconnect()
            if (connection !== first) connection.disconnect()
        }
    }

    private fun installDownloadedApk() {
        val file = pendingApk ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun encryptedOutput(): String {
        val source = sourceEnvelope ?: error("Chybí původní obálka.")
        val pass = password ?: error("Databáze je zamčená.")
        val data = database ?: error("Databáze není načtená.")
        val sortedItems = (0 until data.getJSONArray("tasks").length())
            .map { data.getJSONArray("tasks").getJSONObject(it) }
            .sortedBy { parseDatabaseDate(it.optString("date"))?.time ?: Long.MAX_VALUE }
        data.put("tasks", JSONArray(sortedItems))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(pass, salt), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("inzenyri-data:v1".toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(data.toString().toByteArray(StandardCharsets.UTF_8))
        val out = JSONObject(source.toString())
        out.put("dbVersion", nextVersion(source.optString("dbVersion")))
        out.put("kdf", JSONObject().put("name", "PBKDF2").put("hash", "SHA-256").put("iterations", ITERATIONS).put("salt", b64(salt)))
        out.put("cipher", JSONObject().put("name", "AES-GCM").put("keyLength", 256).put("tagLength", 128).put("iv", b64(iv)))
        out.put("ciphertext", b64(encrypted))
        return out.toString(2) + "\n"
    }

    private fun decrypt(envelope: JSONObject, pass: CharArray): ByteArray {
        require(envelope.optString("format") == "inzenyri-encrypted-data" && envelope.optInt("version") == 1) { "Nepodporovaný formát." }
        val kdf = envelope.getJSONObject("kdf")
        val cipherInfo = envelope.getJSONObject("cipher")
        require(kdf.optString("name") == "PBKDF2" && kdf.optString("hash") == "SHA-256")
        require(cipherInfo.optString("name") == "AES-GCM" && cipherInfo.optInt("tagLength") == 128)
        val salt = unb64(kdf.getString("salt"))
        val iv = unb64(cipherInfo.getString("iv"))
        require(salt.size >= 16 && iv.size == 12)
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(pass, salt, kdf.optInt("iterations", ITERATIONS)), "AES"), GCMParameterSpec(128, iv))
        aes.updateAAD("inzenyri-data:v1".toByteArray(StandardCharsets.UTF_8))
        return aes.doFinal(unb64(envelope.getString("ciphertext")))
    }

    private fun derive(pass: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(iterations in 100_000..2_000_000) { "Neplatné parametry klíče." }
        // hashlib.pbkdf2_hmac() in script.py consumes UTF-8 password bytes.
        // Doing PBKDF2 here explicitly avoids provider-specific char[] conversion.
        val passwordBytes = String(pass).toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
        val block = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = block.copyOf()
        var previous = block
        repeat(iterations - 1) {
            previous = mac.doFinal(previous)
            for (index in result.indices) result[index] = (result[index].toInt() xor previous[index].toInt()).toByte()
        }
        passwordBytes.fill(0)
        previous.fill(0)
        return result
    }

    private fun nextVersion(current: String): String {
        val now = Calendar.getInstance()
        val prefix = String.format(Locale.ROOT, "%02d.%d.%d", now.get(Calendar.YEAR) % 100, now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val match = Regex("^(\\d{2}\\.\\d{1,2}\\.\\d{1,2})-(\\d+)$").matchEntire(current)
        val next = if (match?.groupValues?.get(1) == prefix) match.groupValues[2].toInt() + 1 else 1
        return "$prefix-$next"
    }

    private fun parseDatabaseDate(value: String): Date? {
        for (pattern in listOf("dd.MM.yyyy", "dd.MM.yy", "yyyy-MM-dd")) {
            try {
                return SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }.parse(value)
            } catch (_: Exception) { }
        }
        return null
    }

    private fun selectType(type: String) {
        selectedType = type
        typeButtons.forEach { (key, button) ->
            button.background = rounded(if (key == type) purple else Color.WHITE, 16)
            button.setTextColor(if (key == type) Color.WHITE else ink)
        }
        solutionInput?.visibility = if (type == "task") View.VISIBLE else View.GONE
        quizPromptInput?.visibility = if (type == "test") View.VISIBLE else View.GONE
        quizAnswersInput?.visibility = if (type == "test") View.VISIBLE else View.GONE
    }

    private fun chooseDate() {
        val date = parseDate(dateValue) ?: Date()
        val calendar = Calendar.getInstance().apply { time = date }
        DatePickerDialog(this, { _, year, month, day ->
            dateValue = String.format(Locale.ROOT, "%02d.%02d.%04d", day, month + 1, year)
            dateButton?.text = "Datum · $dateValue"
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun input(hintText: String, multiline: Boolean) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setTextColor(ink)
        setHintTextColor(muted)
        setPadding(dp(15), dp(12), dp(15), dp(12))
        background = rounded(Color.WHITE, 14)
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = if (multiline) 3 else 1
        gravity = if (multiline) Gravity.TOP else Gravity.CENTER_VERTICAL
        imeOptions = if (multiline) EditorInfo.IME_FLAG_NO_EXTRACT_UI else EditorInfo.IME_ACTION_NEXT
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 15f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(purple, 16)
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (color == Color.WHITE) setStroke(dp(1), Color.rgb(230, 228, 239))
    }

    private fun margin(left: Int, top: Int, right: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        password?.fill('\u0000')
        worker.shutdownNow()
        networkWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val ITERATIONS = 600_000
        private const val GITHUB_OWNER = "salamek67"
        private const val GITHUB_REPO = "Inzenyri"
        private const val GITHUB_BRANCH = "main"
        private const val RELEASE_APK_NAME = "inzenyri-android.apk"
        private const val TOKEN_PREFS = "github_credentials"
        private const val TOKEN_KEY_ALIAS = "inzenyri_github_token"
        private fun b64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        private fun unb64(value: String) = Base64.getDecoder().decode(value)
        private fun today() = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date())
        private fun parseDate(value: String): Date? = try {
            SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).apply { isLenient = false }.parse(value)
        } catch (_: Exception) { null }
    }
}
