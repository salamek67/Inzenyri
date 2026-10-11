package cz.inzenyri.mobile

import android.app.Activity
import android.app.Dialog
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
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private var darkMode = false
    private val purple get() = if (darkMode) Color.rgb(177, 153, 255) else Color.rgb(107, 76, 210)
    private val ink get() = if (darkMode) Color.rgb(244, 242, 252) else Color.rgb(31, 38, 56)
    private val muted get() = if (darkMode) Color.rgb(174, 174, 192) else Color.rgb(103, 113, 132)
    private var database: JSONObject? = null
    private var originalDatabaseHash: String? = null
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
    private var addButton: Button? = null
    private var updateButton: Button? = null
    private var pendingApk: File? = null
    private var updateAsset: JSONObject? = null
    private var maintainerButton: Button? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var dashboardView: View? = null
    private var dashboardStatusView: TextView? = null
    private var inMaintainerWorkspace = false
    private var itemsContainer: LinearLayout? = null
    private var workspaceDir: File? = null
    private var databaseFile: File? = null
    private var maintainerMode = false
    private var searchInput: EditText? = null
    private var selectedFilter = "all"
    private var filterButtons = mutableMapOf<String, Button>()
    private var themeButton: Button? = null
    private var lockButton: Button? = null
    private var githubLoginDialog: android.app.AlertDialog? = null
    private var githubLoginStatusView: TextView? = null
    private var githubLoginCodeView: TextView? = null
    private var githubLoginOpenButton: Button? = null
    private var githubLoginStepViews = mutableListOf<TextView>()

    private val createDocument = registerForActivityResultCompat(102)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workspaceDir = File(filesDir, "inzenyri").apply {
            if (!exists() && !mkdirs()) throw IOException("Lokální složku aplikace se nepodařilo vytvořit.")
        }
        databaseFile = File(workspaceDir, "data.enc.json")
        maintainerMode = getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE).getBoolean("maintainer_mode", false)
        darkMode = getSharedPreferences(ITEM_PREFS, MODE_PRIVATE).getBoolean("dark_mode", false)
        render()
        openStoredDatabaseOrClone()
        checkForUpdate(silent = true)
    }

    private fun registerForActivityResultCompat(requestCode: Int): (Intent) -> Unit = { intent ->
        startActivityForResult(intent, requestCode)
    }

    private fun toggleDarkMode() {
        val oldQuery = searchInput?.text?.toString().orEmpty()
        val draft = listOf(
            titleInput?.text?.toString().orEmpty(),
            descriptionInput?.text?.toString().orEmpty(),
            solutionInput?.text?.toString().orEmpty(),
            quizPromptInput?.text?.toString().orEmpty(),
            quizAnswersInput?.text?.toString().orEmpty()
        )
        val oldStatus = statusView?.text?.toString().orEmpty()
        darkMode = !darkMode
        getSharedPreferences(ITEM_PREFS, MODE_PRIVATE).edit().putBoolean("dark_mode", darkMode).apply()
        render()
        searchInput?.setText(oldQuery)
        listOf(titleInput, descriptionInput, solutionInput, quizPromptInput, quizAnswersInput)
            .zip(draft).forEach { (view, text) -> view?.setText(text) }
        selectType(selectedType)
        statusView?.text = oldStatus
        if (database != null) {
            itemsContainer?.visibility = View.VISIBLE
            lockButton?.visibility = View.VISIBLE
            form?.visibility = if (inMaintainerWorkspace && maintainerMode) View.VISIBLE else View.GONE
            exportButton?.visibility = if (inMaintainerWorkspace && maintainerMode) View.VISIBLE else View.GONE
            pushButton?.visibility = if (inMaintainerWorkspace && maintainerMode) View.VISIBLE else View.GONE
            renderDatabase()
        }
    }

    private fun lockDatabase() {
        val current = database?.let { JSONObject(it.toString()) } ?: return
        val pass = password?.clone() ?: return
        val envelope = sourceEnvelope?.let { JSONObject(it.toString()) } ?: run {
            pass.fill('\u0000')
            return
        }
        statusView?.text = "Ukládám a zamykám databázi…"
        lockButton?.isEnabled = false
        worker.execute {
            try {
                val raw = encryptedOutput(current, pass, envelope)
                val verification = JSONObject(String(decrypt(JSONObject(raw), pass), StandardCharsets.UTF_8))
                require(verification.optJSONArray("tasks") != null) { "Ověření zašifrované databáze selhalo." }
                saveLocalDatabase(raw)
                runOnUiThread {
                    password?.fill('\u0000')
                    password = null
                    database = null
                    sourceEnvelope = null
                    originalDatabaseHash = null
                    render()
                    askPassword(raw, preserveBaseline = true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView?.text = e.message ?: "Databázi se nepodařilo bezpečně zamknout."
                    lockButton?.isEnabled = true
                    toast(statusView?.text.toString())
                }
            } finally {
                pass.fill('\u0000')
            }
        }
    }

    private fun updateFilterButtons() {
        filterButtons.forEach { (key, chip) ->
            val selected = key == selectedFilter
            chip.background = rounded(
                if (selected) purple else if (darkMode) Color.rgb(39, 39, 57) else Color.WHITE,
                22
            )
            chip.setTextColor(if (selected) Color.WHITE else ink)
        }
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
            setPadding(dp(20), dp(24), dp(20), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (darkMode) intArrayOf(Color.rgb(24, 23, 39), Color.rgb(17, 20, 31))
                else intArrayOf(Color.rgb(246, 243, 255), Color.rgb(239, 245, 255))
            )
        }
        val scroller = ScrollView(this).apply { addView(page) }
        swipeRefreshLayout = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(purple)
            setOnRefreshListener { refreshFromOrigin() }
            addView(scroller)
        }
        dashboardView = swipeRefreshLayout
        inMaintainerWorkspace = false
        setContentView(swipeRefreshLayout)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(label("INŽENÝŘI", 13, purple, true), LinearLayout.LayoutParams(0, -2, 1f))
        val settingsButton = Button(this).apply {
            text = "⚙"
            contentDescription = "Nastavení"
            textSize = 20f
            minWidth = dp(48)
            minHeight = dp(48)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
            setOnClickListener { openSettings() }
        }
        topBar.addView(settingsButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        lockButton = Button(this).apply {
            text = "🔒"
            contentDescription = "Zamknout databázi"
            textSize = 19f
            minWidth = dp(48)
            minHeight = dp(48)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
            visibility = if (database != null) View.VISIBLE else View.GONE
            setOnClickListener { lockDatabase() }
        }
        topBar.addView(lockButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        maintainerButton = Button(this).apply {
            text = if (maintainerMode) "🔑" else "🔧"
            contentDescription = if (maintainerMode) "GitHub maintainer účet je propojen" else "Propojit maintainer účet s GitHubem"
            textSize = 22f
            minWidth = dp(48)
            minHeight = dp(48)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
            setOnClickListener {
                if (maintainerMode && database != null && readGithubCredentials() != null) showMaintainerWorkspace()
                else connectMaintainer()
            }
        }
        topBar.addView(maintainerButton)
        page.addView(topBar)
        page.addView(label("Přehled", 30, ink, true), margin(0, 4, 0, 4))
        page.addView(label("Testy, úkoly a akce na jednom místě.", 15, muted, false), margin(0, 0, 0, 16))
        searchInput = EditText(this).apply {
            hint = "Hledat testy, úkoly a akce"
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(52)
            background = rounded(if (darkMode) Color.rgb(37, 37, 55) else Color.WHITE, 16)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderDatabase()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        page.addView(searchInput, margin(0, 0, 0, 10))
        val filters = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterButtons.clear()
        listOf("all" to "Vše", "test" to "Testy", "task" to "Úkoly", "event" to "Akce", "done" to "Hotové")
            .forEach { (key, title) ->
                val chip = button(title) {
                    selectedFilter = key
                    updateFilterButtons()
                    renderDatabase()
                }.apply { minHeight = dp(40); minWidth = 0; setPadding(dp(15), 0, dp(15), 0) }
                filterButtons[key] = chip
                filterRow.addView(chip, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(8) })
            }
        filters.addView(filterRow)
        page.addView(filters, margin(0, 0, 0, 10))
        updateFilterButtons()
        statusView = label("Připravuji lokální data…", 13, muted, false)
        dashboardStatusView = statusView
        page.addView(statusView, margin(0, 0, 0, 12))
        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        page.addView(itemsContainer)
        exportButton = button("Uložit šifrovanou databázi") { chooseDestination() }.also { it.visibility = View.GONE }
        pushButton = button("Pushnout DB na origin") { pushToOrigin() }.also { it.visibility = View.GONE }

        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
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
            addButton = button("Přidat položku") { addItem() }
            addView(addButton, margin(0, 16, 0, 4))
        }
        page.addView(label("Verze ${BuildConfig.VERSION_NAME}", 12, muted, false).apply {
            gravity = Gravity.CENTER
        }, margin(0, 24, 0, 4))
        selectType("task")
    }

    private fun openStoredDatabaseOrClone() {
        val local = databaseFile ?: return
        if (local.isFile && local.length() > 0) {
            try {
                statusView?.text = "Lokální databáze připravená · zadej heslo."
                askPassword(local.readText(StandardCharsets.UTF_8), preserveBaseline = true)
                return
            } catch (e: Exception) {
                statusView?.text = "Lokální databázi se nepodařilo načíst: ${e.message}"
            }
        }
        statusView?.text = "Stahuji šifrovanou databázi do složky aplikace…"
        networkWorker.execute {
            try {
                val token = readGithubCredentials()?.let { saved -> try { validCredentials(saved).accessToken } catch (_: Exception) { null } }
                val raw = try { fetchRemoteDatabase(token) } catch (e: Exception) {
                    if (token == null) throw e
                    fetchRemoteDatabase()
                }
                saveLocalDatabase(raw)
                runOnUiThread {
                    statusView?.text = "Databáze stažena. Zadej heslo."
                    askPassword(raw)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView?.text = "Databázi se nepodařilo stáhnout. Zkontroluj připojení a obnov tažením dolů."
                    toast(e.message ?: "Stažení databáze se nezdařilo.")
                }
            }
        }
    }

    private fun fetchRemoteDatabase(token: String? = null): String {
        val response = JSONObject(githubRequest(
            "repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json?ref=$GITHUB_BRANCH",
            token = token
        ))
        val encoded = response.optString("content").ifBlank {
            response.optJSONObject("content")?.optString("content").orEmpty()
        }
        require(encoded.isNotBlank()) { "GitHub API nevrátil obsah databáze." }
        val raw = String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8)
        JSONObject(raw)
        return raw
    }

    private fun saveLocalDatabase(raw: String) {
        val target = databaseFile ?: error("Lokální složka aplikace není připravená.")
        val temporary = File(target.parentFile, "data.enc.json.tmp")
        temporary.writeText(raw, StandardCharsets.UTF_8)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun refreshFromOrigin() {
        confirmReplacingDatabase {
            statusView?.text = "Stahuji databázi…"
            networkWorker.execute {
                try {
                    val token = readGithubCredentials()?.let { saved -> try { validCredentials(saved).accessToken } catch (_: Exception) { null } }
                    val raw = try { fetchRemoteDatabase(token) } catch (e: Exception) {
                        if (token == null) throw e
                        fetchRemoteDatabase()
                    }
                    val activePassword = password?.clone()
                    val parsed = activePassword?.let {
                        JSONObject(String(decrypt(JSONObject(raw), it), StandardCharsets.UTF_8))
                    }
                    runOnUiThread {
                        swipeRefreshLayout?.isRefreshing = false
                        if (parsed != null) {
                            password?.fill('\u0000')
                            password = activePassword.clone()
                            database = parsed
                            sourceEnvelope = JSONObject(raw)
                            originalDatabaseHash = databaseHash(parsed)
                            getSharedPreferences(DB_PREFS, MODE_PRIVATE).edit().putString(DB_BASELINE_HASH, originalDatabaseHash).apply()
                            try { saveLocalDatabase(raw) } catch (e: Exception) { toast(e.message ?: "Lokální kopii se nepodařilo uložit.") }
                            statusView?.text = "Databáze obnovena · ${parsed.getJSONArray("tasks").length()} položek."
                            renderDatabase()
                        } else {
                            statusView?.text = "Databáze stažena. Zadej heslo."
                            askPassword(raw)
                        }
                    }
        activePassword?.fill('\u0000')
                } catch (e: Exception) {
                    runOnUiThread {
                        swipeRefreshLayout?.isRefreshing = false
                        statusView?.text = e.message ?: "Stažení databáze se nezdařilo."
                        toast(statusView?.text.toString())
                    }
                }
            }
        }
    }

    private fun openSettings() {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(22))
            background = rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Nastavení", 23, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("✕") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(header)
        content.addView(label("Inženýři · verze ${BuildConfig.VERSION_NAME}", 13, muted, false), margin(0, 5, 0, 20))
        themeButton = button(if (darkMode) "☀  Světlý vzhled" else "☾  Tmavý vzhled") {
            dialog.dismiss()
            toggleDarkMode()
        }
        content.addView(themeButton, margin(0, 0, 0, 10))
        updateButton = button(if (updateAsset == null) "Zkontrolovat aktualizace aplikace" else "Stáhnout dostupnou aktualizaci") {
            if (updateAsset == null) checkForUpdate(silent = false) else downloadUpdate()
        }
        content.addView(updateButton)
        (header.getChildAt(1) as Button).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showMaintainerWorkspace() {
        if (!maintainerMode || database == null) {
            connectMaintainer()
            return
        }
        inMaintainerWorkspace = true
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (darkMode) intArrayOf(Color.rgb(24, 23, 39), Color.rgb(17, 20, 31))
                else intArrayOf(Color.rgb(246, 243, 255), Color.rgb(239, 245, 255)))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("← Zpět") { showDashboard() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        header.addView(label("Údržba databáze", 22, ink, true))
        page.addView(header, margin(0, 0, 0, 16))
        statusView = label("Přidávej položky nebo pushni změny na origin.", 13, muted, false)
        page.addView(statusView, margin(0, 0, 0, 12))
        form?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            page.addView(view)
        }
        exportButton?.visibility = View.VISIBLE
        pushButton?.visibility = View.VISIBLE
        exportButton?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            page.addView(view, margin(0, 14, 0, 8))
        }
        pushButton?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            page.addView(view, margin(0, 0, 0, 12))
        }
        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun showDashboard() {
        inMaintainerWorkspace = false
        statusView = dashboardStatusView
        form?.visibility = View.GONE
        exportButton?.visibility = View.GONE
        pushButton?.visibility = View.GONE
        dashboardView?.let(::setContentView)
    }

    private fun confirmReplacingDatabase(action: () -> Unit) {
        if (database == null || !isDatabaseDirty()) {
            action()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Nahradit rozpracovanou databázi?")
            .setMessage("Máš položky, které ještě nejsou pushnuté na origin. Při načtení jiné databáze budou ztraceny.")
            .setNegativeButton("Zpět") { _, _ -> swipeRefreshLayout?.isRefreshing = false }
            .setPositiveButton("Pokračovat") { _, _ -> action() }
            .show()
    }

    private fun askPassword(raw: String, preserveBaseline: Boolean = false) {
        val savedPassword = readSavedDatabasePassword()
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
            background = rounded(if (darkMode) Color.rgb(24, 23, 39) else Color.rgb(246, 243, 255), 0)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = rounded(if (darkMode) Color.rgb(35, 35, 53) else Color.WHITE, 22)
        }
        screen.addView(card, LinearLayout.LayoutParams(-1, -2))
        card.addView(label("INŽENÝŘI", 13, purple, true), margin(0, 0, 0, 18))
        card.addView(label("Odemkni databázi", 27, ink, true), margin(0, 0, 0, 6))
        card.addView(label("Zadej heslo, kterým je zašifrovaná databáze.", 15, muted, false), margin(0, 0, 0, 20))
        val field = EditText(this).apply {
            hint = "Heslo k databázi"
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(if (darkMode) Color.rgb(27, 28, 43) else Color.rgb(250, 250, 253), 14)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = dp(52)
            savedPassword?.let { setText(String(it)) }
        }
        card.addView(field)
        val savePassword = CheckBox(this).apply {
            text = "Bezpečně uložit heslo na tomto zařízení"
            setTextColor(ink)
            isChecked = savedPassword != null
        }
        savedPassword?.fill('\u0000')
        card.addView(savePassword, margin(0, 10, 0, 8))
        card.addView(label("Uložené heslo je šifrované klíčem z Android Keystore.", 13, muted, false), margin(0, 0, 0, 16))
        statusView = label("", 14, muted, false)
        card.addView(statusView, margin(0, 0, 0, 12))
        val unlockButton = button("Odemknout") { }
        card.addView(unlockButton)
        updateButton = button(if (updateAsset == null) "Zkontrolovat aktualizace aplikace" else "Stáhnout dostupnou aktualizaci") {
            if (updateAsset == null) checkForUpdate(silent = false) else downloadUpdate()
        }
        card.addView(updateButton, margin(0, 10, 0, 0))
        screen.addView(label("Verze ${BuildConfig.VERSION_NAME}", 12, muted, false).apply {
            gravity = Gravity.CENTER
        }, margin(0, 18, 0, 0))
        setContentView(screen)

        unlockButton.setOnClickListener {
            val candidate = field.text.toString().toCharArray()
            unlockButton.isEnabled = false
            statusView?.text = "Ověřuji heslo…"
            worker.execute {
                try {
                    val envelope = JSONObject(raw)
                    val plain = decrypt(envelope, candidate)
                    val parsed = JSONObject(String(plain, StandardCharsets.UTF_8))
                    require(parsed.optJSONArray("tasks") != null) { "Databáze nemá seznam položek." }
                    runOnUiThread {
                        password?.fill('\u0000')
                        password = candidate
                        if (savePassword.isChecked) {
                            try { saveDatabasePassword(candidate) } catch (e: Exception) {
                                forgetDatabasePassword()
                                toast(e.message ?: "Heslo se nepodařilo bezpečně uložit.")
                            }
                        } else {
                            forgetDatabasePassword()
                        }
                        database = parsed
                        val savedBaseline = getSharedPreferences(DB_PREFS, MODE_PRIVATE).getString(DB_BASELINE_HASH, null)
                        originalDatabaseHash = if (preserveBaseline && savedBaseline != null) savedBaseline else databaseHash(parsed).also {
                            getSharedPreferences(DB_PREFS, MODE_PRIVATE).edit().putString(DB_BASELINE_HASH, it).apply()
                        }
                        sourceEnvelope = envelope
                        if (!preserveBaseline) {
                            try { saveLocalDatabase(raw) } catch (e: Exception) {
                                toast(e.message ?: "Databázi se nepodařilo uložit do zařízení.")
                            }
                        }
                        render()
                        lockButton?.visibility = View.VISIBLE
                        itemsContainer?.visibility = View.VISIBLE
                        form?.visibility = View.GONE
                        exportButton?.visibility = View.GONE
                        pushButton?.visibility = View.GONE
                        val dbVersion = sourceEnvelope?.optString("dbVersion")?.takeIf { it.isNotBlank() } ?: "neuvedena"
                        statusView?.text = "Odemčeno · ${parsed.getJSONArray("tasks").length()} položek · DB $dbVersion"
                        renderDatabase()
                    }
                } catch (_: Exception) {
                    candidate.fill('\u0000')
                    runOnUiThread {
                        statusView?.text = "Heslo nesouhlasí nebo je soubor poškozený. Zkontroluj ho a zkus to znovu."
                        unlockButton.isEnabled = true
                    }
                }
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                unlockButton.performClick()
                true
            } else false
        }
    }

    private fun connectMaintainer() {
        runGithubTask { token ->
            try {
                val user = JSONObject(githubRequest("user", token = token)).optString("login")
                require(user.isNotBlank()) { "GitHub nevrátil přihlášený účet." }
                setGithubLoginStep(2, "SUCCESS")
                setGithubLoginStep(3, "RUNNING")
                val repo = JSONObject(githubRequest("repos/$GITHUB_OWNER/$GITHUB_REPO", token = token))
                require(repo.optJSONObject("permissions")?.optBoolean("push") == true) {
                    "Přihlášený GitHub účet nemá právo zápisu do repozitáře."
                }
                if (database == null) {
                    val raw = fetchRemoteDatabase(token)
                    saveLocalDatabase(raw)
                    runOnUiThread {
                        statusView?.text = "Databáze stažena. Zadej heslo."
                        askPassword(raw)
                    }
                } else {
                    githubRequest("repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json?ref=$GITHUB_BRANCH", token = token)
                }
                setGithubLoginStep(3, "SUCCESS")
                runOnUiThread {
                    setMaintainerConnection(true, user)
                    statusView?.text = "GitHub propojen · $user. Přidávání je povolené."
                    toast("GitHub propojen")
                }
            } catch (e: Exception) {
                runOnUiThread { setMaintainerConnection(false) }
                throw e
            }
        }
    }

    private fun setMaintainerConnection(connected: Boolean, user: String? = null) {
        maintainerMode = connected
        getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE).edit()
            .putBoolean("maintainer_mode", connected).apply()
        maintainerButton?.text = if (connected) "🔑" else "🔧"
        maintainerButton?.contentDescription = if (connected) {
            "GitHub propojen${user?.let { " jako $it" }.orEmpty()}"
        } else "Propojit maintainer účet s GitHubem"
        form?.visibility = View.GONE
        exportButton?.visibility = View.GONE
        pushButton?.visibility = View.GONE
        if (connected && database != null) showMaintainerWorkspace()
    }

    private fun isDatabaseDirty(): Boolean {
        val current = database ?: return false
        return databaseHash(current) != originalDatabaseHash
    }

    private fun databaseHash(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun renderDatabase() {
        val data = database ?: return
        val container = itemsContainer ?: return
        container.removeAllViews()
        val tasks = data.optJSONArray("tasks") ?: JSONArray()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time
        val allEntries = (0 until tasks.length()).mapNotNull { index ->
            val item = tasks.optJSONObject(index) ?: return@mapNotNull null
            val date = parseDatabaseDate(item.optString("date"))
            Triple(item, index, date)
        }.sortedWith(compareBy<Triple<JSONObject, Int, Date?>>({ it.third ?: Date(Long.MAX_VALUE) }, { it.second }))
        val activeEntries = allEntries.filter { entry ->
            val date = entry.third
            date == null || !date.before(today)
        }
        val doneKeys = doneItems().apply { retainAll(allEntries.map { itemKey(it.first) }.toSet()) }
        getSharedPreferences(ITEM_PREFS, MODE_PRIVATE).edit().putStringSet(DONE_ITEMS_KEY, doneKeys).apply()
        val query = searchInput?.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val visibleEntries = activeEntries.filter { (item, _, _) ->
            val done = itemKey(item) in doneKeys
            val matchesFilter = when (selectedFilter) {
                "test", "task", "event" -> itemType(item) == selectedFilter && !done
                "done" -> done
                else -> true
            }
            val searchable = listOf("name", "date", "task", "solution")
                .joinToString(" ") { item.optString(it) }
                .plus(" ")
                .plus(item.optJSONObject("quiz")?.toString().orEmpty())
                .lowercase(Locale.ROOT)
            matchesFilter && (query.isBlank() || searchable.contains(query))
        }
        val categories = listOf("test" to "Testy", "task" to "Úkoly", "event" to "Akce")
        for ((type, title) in categories) {
            val categoryItems = visibleEntries.filter {
                itemType(it.first) == type && itemKey(it.first) !in doneKeys
            }
            addSection(container, title, categoryItems)
        }
        val completed = visibleEntries.filter { itemKey(it.first) in doneKeys }
        if (completed.isNotEmpty()) addSection(container, "Dokončené", completed)
        if (visibleEntries.isEmpty()) {
            val emptyMessage = if (query.isNotBlank()) "Nic jsme nenašli. Zkus upravit hledání."
            else if (selectedFilter == "done") "Zatím tu nejsou žádné dokončené položky."
            else "Žádné nadcházející položky."
            container.addView(label(emptyMessage, 15, muted, false), margin(0, 8, 0, 16))
        }
    }

    private fun addSection(container: LinearLayout, title: String, entries: List<Triple<JSONObject, Int, Date?>>) {
        if (entries.isEmpty()) return
        val (symbol, accent) = when (title) {
            "Testy" -> "✎" to Color.rgb(236, 108, 104)
            "Úkoly" -> "✓" to purple
            "Akce" -> "◷" to Color.rgb(60, 130, 246)
            else -> "✓" to Color.rgb(36, 133, 91)
        }
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
            background = rounded(if (darkMode) Color.rgb(29, 29, 44) else Color.rgb(232, 231, 240), 22)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(10))
        }
        heading.addView(TextView(this).apply {
            text = symbol
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = rounded(if (darkMode) Color.rgb(48, 43, 69) else Color.rgb(241, 237, 255), 11)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        heading.addView(label(title, 17, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label(entries.size.toString(), 13, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(if (darkMode) Color.rgb(48, 43, 69) else Color.rgb(241, 237, 255), 99)
        })
        section.addView(heading)
        entries.forEach { (item, _, _) -> section.addView(itemCard(item), margin(0, 0, 0, 8)) }
        container.addView(section, margin(0, 14, 0, 0))
    }

    private fun itemCard(item: JSONObject): View {
        val completed = itemKey(item) in doneItems()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(when {
                darkMode && completed -> Color.rgb(48, 47, 63)
                darkMode -> Color.rgb(35, 35, 53)
                completed -> Color.rgb(240, 239, 245)
                else -> Color.WHITE
            }, 18)
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val checkbox = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            isSelected = completed
            text = if (completed) "✓" else ""
            setTextColor(if (completed) Color.WHITE else muted)
            background = rounded(if (completed) purple else if (darkMode) Color.rgb(62, 61, 78) else Color.WHITE, 9)
            contentDescription = if (completed) "Vrátit mezi aktivní" else "Označit jako dokončené"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val checked = !isSelected
                val values = doneItems()
                if (checked) values.add(itemKey(item)) else values.remove(itemKey(item))
                getSharedPreferences(ITEM_PREFS, MODE_PRIVATE).edit().putStringSet(DONE_ITEMS_KEY, values).apply()
                renderDatabase()
            }
        }.also { it.layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) } }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val accent = when (itemType(item)) {
            "test" -> Color.rgb(236, 108, 104)
            "event" -> Color.rgb(60, 130, 246)
            else -> purple
        }
        titleBlock.addView(label(item.optString("name").ifBlank { "Bez názvu" }, 17, if (completed) muted else ink, true))
        titleBlock.addView(label(item.optString("date").ifBlank { "Bez data" }, 13, accent, true), margin(0, 3, 0, 0))
        heading.addView(checkbox)
        heading.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        val description = item.optString("task").trim()
        if (description.isNotEmpty()) {
            val body = label(description, 15, ink, false).apply {
                text = formattedText(description)
                setLineSpacing(dp(2).toFloat(), 1f)
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
            }
            card.addView(body, margin(0, 10, 0, 0))
        }
        val solution = item.optString("solution").trim()
        if (solution.isNotEmpty()) {
            var visible = false
            val solutionText = label(solution, 14, muted, false).apply {
                text = formattedText(solution)
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                visibility = View.GONE
            }
            val initialLabel = if (itemType(item) == "test") "Zobrazit procvičování" else "Zobrazit řešení"
            val solutionButton = button(initialLabel) { }
            solutionButton.setOnClickListener {
                visible = !visible
                solutionText.visibility = if (visible) View.VISIBLE else View.GONE
                solutionButton.text = if (visible) "Skrýt" else initialLabel
            }
            card.addView(solutionButton, margin(0, 10, 0, 0))
            card.addView(solutionText, margin(0, 6, 0, 0))
        }
        val questions = item.optJSONObject("quiz")?.optJSONArray("questions")
        if (questions != null && questions.length() > 0) {
            card.addView(button("Spustit procvičování →") { startQuiz(item) }, margin(0, 8, 0, 0))
        }
        return card
    }

    private fun startQuiz(item: JSONObject) {
        val questions = item.optJSONObject("quiz")?.optJSONArray("questions") ?: return
        var index = 0
        var score = 0
        fun showQuestion() {
            if (index >= questions.length()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Výsledek procvičování")
                    .setMessage("$score / ${questions.length()}")
                    .setPositiveButton("Hotovo", null)
                    .setNeutralButton("Zkusit znovu") { _, _ -> index = 0; score = 0; showQuestion() }
                    .show()
                return
            }
            val question = questions.optJSONObject(index)
            if (question == null) { index++; showQuestion(); return }
            val kind = question.optString("type").let { if (it == "binary") "choice" else it }
            val prompt = if (kind == "punctuation") question.optString("sentence") else question.optString("prompt").ifBlank { "Otázka" }
            if (kind == "choice") {
                val options = question.optJSONArray("options") ?: JSONArray()
                val values = Array(options.length()) { options.optString(it) }
                android.app.AlertDialog.Builder(this)
                    .setTitle("${item.optString("name")} · ${index + 1}/${questions.length()}")
                    .setMessage(prompt)
                    .setSingleChoiceItems(values, -1) { dialog, selected ->
                        if (selected == question.optInt("answer", -1)) score++
                        index++; dialog.dismiss(); showQuestion()
                    }
                    .setNegativeButton("Přeskočit") { _, _ -> index++; showQuestion() }
                    .show()
            } else {
                val field = EditText(this).apply {
                    hint = "Napiš odpověď…"
                    inputType = InputType.TYPE_CLASS_TEXT
                    if (kind == "punctuation") setText(question.optString("sentence"))
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("${item.optString("name")} · ${index + 1}/${questions.length()}")
                    .setMessage(prompt)
                    .setView(field)
                    .setNegativeButton("Přeskočit") { _, _ -> index++; showQuestion() }
                    .setPositiveButton("Vyhodnotit") { _, _ ->
                        val answers = question.optJSONArray("answers") ?: JSONArray().put(question.optString("answer"))
                        val input = field.text.toString().trim()
                        val accepted = (0 until answers.length()).any { answerIndex ->
                            fun normalized(value: String): String {
                                var result = value.trim()
                                if (question.optBoolean("ignoreCase", false)) result = result.lowercase(Locale.ROOT)
                                if (kind == "punctuation") result = result.replace(Regex("\\s+"), " ").replace(Regex("\\s*,\\s*"), ",")
                                return result
                            }
                            normalized(input) == normalized(answers.optString(answerIndex))
                        }
                        if (accepted) score++
                        index++; showQuestion()
                    }
                    .show()
            }
        }
        showQuestion()
    }

    private fun itemType(item: JSONObject): String {
        val explicit = item.optString("type")
        if (explicit in setOf("task", "test", "event")) return explicit
        val text = "${item.optString("name")} ${item.optString("task")}".lowercase(Locale.ROOT)
        return when {
            Regex("\\b(test|písem|zkoušen|prověrk)").containsMatchIn(text) -> "test"
            Regex("\\b(akce|výlet|exkur|vysvědčen|divad|koncert)").containsMatchIn(text) -> "event"
            else -> "task"
        }
    }

    private fun formattedText(value: String): CharSequence {
        val pattern = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)|\*\*([^*]+)\*\*|\*([^*]+)\*|`([^`]+)`""")
        val output = SpannableStringBuilder()
        var cursor = 0
        for (match in pattern.findAll(value)) {
            output.append(value.substring(cursor, match.range.first))
            val start = output.length
            val linkLabel = match.groups[1]?.value
            val replacement = linkLabel ?: match.groups[3]?.value ?: match.groups[4]?.value ?: match.groups[5]?.value.orEmpty()
            output.append(replacement)
            val end = output.length
            when {
                linkLabel != null -> output.setSpan(URLSpan(match.groups[2]!!.value), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                match.groups[3] != null -> output.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                match.groups[4] != null -> output.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                match.groups[5] != null -> output.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cursor = match.range.last + 1
        }
        output.append(value.substring(cursor))
        return output
    }

    private fun itemKey(item: JSONObject): String = listOf("type", "name", "date", "task")
        .joinToString("\u001f") { field -> if (field == "type") itemType(item) else item.optString(field) }

    private fun doneItems(): MutableSet<String> = getSharedPreferences(ITEM_PREFS, MODE_PRIVATE)
        .getStringSet(DONE_ITEMS_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun addItem() {
        if (!maintainerMode) return toast("Pro přidávání se nejdřív propoj s GitHubem tlačítkem 🔧.")
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
        statusView?.text = "Přidáno · ukládám do zařízení…"
        exportButton?.visibility = View.VISIBLE
        pushButton?.visibility = View.VISIBLE
        renderDatabase()
        persistLocalDatabase()
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
        if (!isDatabaseDirty()) return toast("Zatím nejsou žádné nové položky.")
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
            if (databaseHash(remoteDatabase) != originalDatabaseHash) {
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
            originalDatabaseHash = databaseHash(database!!)
            getSharedPreferences(DB_PREFS, MODE_PRIVATE).edit().putString(DB_BASELINE_HASH, originalDatabaseHash).apply()
            saveLocalDatabase(updatedEnvelope.toString(2) + "\n")
            runOnUiThread {
                statusView?.text = "Push hotov · $commitMessage"
                addButton?.isEnabled = true
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
            updateButton?.isEnabled = true
            return
        }
        val saved = readGithubCredentials()
        if (saved != null) {
            statusView?.text = "Používám uložené přihlášení ke GitHubu…"
            executeGithubTask(saved, freezeForm, task)
        } else {
            if (maintainerMode) setMaintainerConnection(false)
            if (hasStoredGithubCredentials()) {
                statusView?.text = "Uložené přihlášení nejde na tomto zařízení přečíst. Přihlas se znovu."
            }
            startDeviceLogin(clientId, freezeForm, task)
        }
    }

    private fun executeGithubTask(credentials: GitHubCredentials, freezeForm: Boolean, task: (String) -> Unit) {
        updateButton?.isEnabled = false
        pushButton?.isEnabled = false
        maintainerButton?.isEnabled = false
        if (freezeForm) setFormEnabled(false)
        statusView?.text = "Připojuji se k GitHubu…"
        networkWorker.execute {
            try {
                val active = validCredentials(credentials)
                task(active.accessToken)
                finishGithubLoginDialog()
            } catch (e: Exception) {
                if (e.message?.contains("HTTP 401") == true) clearGithubToken()
                runOnUiThread {
                    failGithubLoginDialog(e.message ?: "Požadavek na GitHub se nezdařil.")
                    statusView?.text = e.message ?: "Požadavek na GitHub se nezdařil."
                    toast(e.message ?: "Požadavek na GitHub se nezdařil.")
                }
            } finally {
                runOnUiThread {
                    updateButton?.isEnabled = true
                    pushButton?.isEnabled = true
                    maintainerButton?.isEnabled = true
                    if (freezeForm) setFormEnabled(true)
                }
            }
        }
    }

    private fun showGithubLoginProgress(cancelled: AtomicBoolean): android.app.AlertDialog {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ProgressBar(this).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        githubLoginStatusView = label("Připravuji přihlášení…", 14, muted, false)
        header.addView(githubLoginStatusView, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header, margin(0, 0, 0, 14))

        githubLoginStepViews = mutableListOf()
        listOf(
            "Získání přihlašovacího kódu",
            "Potvrzení v GitHubu",
            "Ověření účtu",
            "Kontrola oprávnění repozitáře",
            "Dokončení propojení"
        ).forEach { title ->
            val row = label("○  $title", 14, muted, false)
            githubLoginStepViews += row
            content.addView(row, margin(0, 5, 0, 5))
        }
        githubLoginCodeView = label("Kód se načte za chvíli…", 20, ink, true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        content.addView(githubLoginCodeView, margin(0, 14, 0, 4))
        content.addView(label("Zadej ho pouze na github.com.", 12, muted, false).apply {
            gravity = Gravity.CENTER
        })
        content.addView(label("Verze ${BuildConfig.VERSION_NAME}", 12, muted, false).apply {
            gravity = Gravity.CENTER
        }, margin(0, 14, 0, 0))

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("GitHub · přihlášení")
            .setView(content)
            .setNegativeButton("Zrušit", null)
            .setPositiveButton("Otevřít GitHub", null)
            .create()
        githubLoginDialog = dialog
        dialog.setOnShowListener {
            githubLoginOpenButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device")))
                    } catch (e: Exception) {
                        failGithubLoginDialog(e.message ?: "GitHub se nepodařilo otevřít.")
                    }
                }
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelled.set(true)
                dialog.dismiss()
                clearGithubLoginDialog()
            }
        }
        dialog.setOnCancelListener {
            cancelled.set(true)
            clearGithubLoginDialog()
        }
        dialog.setOnDismissListener { if (githubLoginDialog === dialog) clearGithubLoginDialog() }
        dialog.show()
        setGithubLoginStep(0, "RUNNING")
        return dialog
    }

    private fun setGithubLoginStep(index: Int, state: String) {
        runOnUiThread {
            val row = githubLoginStepViews.getOrNull(index) ?: return@runOnUiThread
            val title = row.text.toString().substringAfter("  ")
            val (icon, color) = when (state) {
                "RUNNING" -> "●" to purple
                "SUCCESS" -> "✓" to Color.rgb(35, 135, 75)
                "FAILED" -> "✕" to Color.rgb(190, 45, 55)
                else -> "○" to muted
            }
            row.text = "$icon  $title"
            row.setTextColor(color)
            if (state == "RUNNING") {
                githubLoginStatusView?.text = title
            }
        }
    }

    private fun finishGithubLoginDialog() {
        runOnUiThread {
            if (githubLoginDialog == null) return@runOnUiThread
            setGithubLoginStep(4, "SUCCESS")
            githubLoginStatusView?.text = "Propojení účtu dokončeno."
            val dialog = githubLoginDialog
            dialog?.window?.decorView?.postDelayed({
                if (githubLoginDialog === dialog) {
                    dialog.dismiss()
                    clearGithubLoginDialog()
                }
            }, 900)
        }
    }

    private fun failGithubLoginDialog(message: String) {
        if (githubLoginDialog == null) return
        val current = githubLoginStepViews.indexOfFirst { it.currentTextColor == purple }
        if (current >= 0) setGithubLoginStep(current, "FAILED")
        githubLoginStatusView?.text = message
        githubLoginOpenButton?.text = "Zavřít"
        githubLoginOpenButton?.isEnabled = true
        githubLoginOpenButton?.setOnClickListener { githubLoginDialog?.dismiss() }
        githubLoginDialog?.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
    }

    private fun clearGithubLoginDialog() {
        githubLoginDialog = null
        githubLoginStatusView = null
        githubLoginCodeView = null
        githubLoginOpenButton = null
        githubLoginStepViews.clear()
    }

    private fun startDeviceLogin(clientId: String, freezeForm: Boolean, task: (String) -> Unit) {
        updateButton?.isEnabled = false
        pushButton?.isEnabled = false
        maintainerButton?.isEnabled = false
        if (freezeForm) setFormEnabled(false)
        statusView?.text = "Připravuji přihlášení přes GitHub…"
        val cancelled = AtomicBoolean(false)
        showGithubLoginProgress(cancelled)
        networkWorker.execute {
            try {
                val response = oauthRequest("https://github.com/login/device/code", mapOf("client_id" to clientId, "scope" to "offline_access"))
                val deviceCode = response.getString("device_code")
                val userCode = response.getString("user_code")
                val verificationUri = response.optString("verification_uri", "https://github.com/login/device")
                require(verificationUri == "https://github.com/login/device") { "GitHub vrátil neplatnou adresu přihlášení." }
                val expiresAt = System.currentTimeMillis() + response.optLong("expires_in", 900) * 1000
                runOnUiThread {
                    githubLoginCodeView?.text = userCode
                    githubLoginOpenButton?.isEnabled = true
                    githubLoginStatusView?.text = "Zadej kód v GitHubu a potvrď přístup."
                    setGithubLoginStep(0, "SUCCESS")
                    setGithubLoginStep(1, "RUNNING")
                }
                if (cancelled.get()) throw IOException("Přihlášení bylo zrušeno.")
                runOnUiThread {
                    networkWorker.execute {
                        try {
                            var interval = response.optLong("interval", 5).coerceAtLeast(1)
                            var authorized: GitHubCredentials? = null
                            while (!cancelled.get() && System.currentTimeMillis() < expiresAt) {
                                Thread.sleep(interval * 1000)
                                if (cancelled.get()) break
                                val poll = try {
                                    oauthRequest("https://github.com/login/oauth/access_token", mapOf(
                                        "client_id" to clientId,
                                        "device_code" to deviceCode,
                                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                                    ), allowOAuthError = true)
                                } catch (e: IOException) {
                                    // Device authorization can succeed in the browser while
                                    // the app briefly loses DNS/network access. Keep polling
                                    // so an approved code is not thrown away on one failed request.
                                    if (e.message?.startsWith("GitHub OAuth HTTP") == true) throw e
                                    Thread.sleep(interval * 1000)
                                    continue
                                }
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
                            setGithubLoginStep(1, "SUCCESS")
                            setGithubLoginStep(2, "RUNNING")
                            saveGithubCredentials(credentials)
                            executeGithubTask(credentials, freezeForm, task)
                        } catch (e: Exception) {
                            runOnUiThread {
                                failGithubLoginDialog(e.message ?: "Přihlášení přes GitHub selhalo.")
                                statusView?.text = e.message ?: "Přihlášení přes GitHub selhalo."
                                toast(statusView?.text.toString())
                                updateButton?.isEnabled = true
                                pushButton?.isEnabled = true
                                maintainerButton?.isEnabled = true
                                if (freezeForm) setFormEnabled(true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    failGithubLoginDialog(e.message ?: "Přihlášení přes GitHub selhalo.")
                    statusView?.text = e.message ?: "Přihlášení přes GitHub selhalo."
                    toast(statusView?.text.toString())
                    updateButton?.isEnabled = true
                    pushButton?.isEnabled = true
                    maintainerButton?.isEnabled = true
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
        exportButton?.isEnabled = enabled
        addButton?.isEnabled = enabled
        maintainerButton?.isEnabled = enabled
    }

    private fun persistLocalDatabase() {
        val data = database?.let { JSONObject(it.toString()) } ?: return
        val source = sourceEnvelope?.let { JSONObject(it.toString()) } ?: return
        val pass = password?.clone() ?: return
        addButton?.isEnabled = false
        worker.execute {
            try {
                val raw = encryptedOutput(data, pass, source)
                saveLocalDatabase(raw)
                val updatedSource = JSONObject(raw)
                runOnUiThread {
                    sourceEnvelope = updatedSource
                    statusView?.text = "Uloženo v zařízení · ${data.getJSONArray("tasks").length()} položek. Připraveno k pushnutí."
                    addButton?.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView?.text = "Položka zůstala v paměti, ale lokální uložení selhalo."
                    addButton?.isEnabled = true
                    toast(e.message ?: "Lokální uložení se nezdařilo.")
                }
            } finally {
                pass.fill('\u0000')
            }
        }
    }

    private fun tokenKey(): SecretKey {
        return keystoreKey(TOKEN_KEY_ALIAS)
    }

    private fun databasePasswordKey(): SecretKey = keystoreKey(DATABASE_PASSWORD_KEY_ALIAS)

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

    private fun saveDatabasePassword(value: CharArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, databasePasswordKey())
        val encrypted = cipher.doFinal(String(value).toByteArray(StandardCharsets.UTF_8))
        getSharedPreferences(DB_PREFS, MODE_PRIVATE).edit()
            .putString(SAVED_PASSWORD_IV, Base64.getEncoder().encodeToString(cipher.iv))
            .putString(SAVED_PASSWORD_VALUE, Base64.getEncoder().encodeToString(encrypted))
            .apply()
    }

    private fun readSavedDatabasePassword(): CharArray? {
        return try {
            val prefs = getSharedPreferences(DB_PREFS, MODE_PRIVATE)
            val iv = prefs.getString(SAVED_PASSWORD_IV, null) ?: return null
            val encrypted = prefs.getString(SAVED_PASSWORD_VALUE, null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, databasePasswordKey(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
            String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8).toCharArray()
        } catch (_: Exception) {
            forgetDatabasePassword()
            null
        }
    }

    private fun forgetDatabasePassword() {
        getSharedPreferences(DB_PREFS, MODE_PRIVATE).edit()
            .remove(SAVED_PASSWORD_IV)
            .remove(SAVED_PASSWORD_VALUE)
            .apply()
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
            // Persist before starting the first API call. The app may be backgrounded
            // while GitHub's device authorization page is open, so an async write can
            // otherwise be lost if Android kills the process at that point.
            .commit()
            .also { persisted ->
                if (!persisted) throw IOException("Přihlášení ke GitHubu se nepodařilo uložit do zařízení.")
            }
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

    private fun hasStoredGithubCredentials(): Boolean {
        val prefs = getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE)
        return prefs.contains("iv") || prefs.contains("credentials") || prefs.contains("token")
    }

    private fun clearGithubToken() {
        getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE).edit().clear().apply()
        maintainerMode = false
        runOnUiThread {
            maintainerButton?.text = "🔧"
            maintainerButton?.contentDescription = "Propojit maintainer účet s GitHubem"
            form?.visibility = View.GONE
            exportButton?.visibility = View.GONE
            pushButton?.visibility = View.GONE
        }
    }

    private fun checkForUpdate(silent: Boolean) {
        updateAsset = null
        updateButton?.text = "Zkontrolovat aktualizace aplikace"
        if (!silent) updateButton?.isEnabled = false
        networkWorker.execute {
            try {
                checkLatestRelease(null, showResult = !silent)
            } catch (e: Exception) {
                runOnUiThread {
                    statusView?.text = e.message ?: "Kontrola aktualizací se nezdařila."
                    if (!silent) toast(statusView?.text.toString())
                    updateButton?.isEnabled = true
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
                updateButton?.isEnabled = true
            }
        } else {
            runOnUiThread {
                statusView?.text = "Aplikace je aktuální · $current"
                if (showResult) toast("Používáš aktuální verzi.")
                updateButton?.isEnabled = true
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
        return encryptedOutput(data, pass, source)
    }

    private fun encryptedOutput(data: JSONObject, pass: CharArray, source: JSONObject): String {
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
            button.background = rounded(if (key == type) purple else if (darkMode) Color.rgb(39, 39, 57) else Color.WHITE, 16)
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
        background = rounded(if (darkMode) Color.rgb(35, 35, 53) else Color.WHITE, 14)
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
        setStroke(dp(1), if (darkMode) Color.rgb(67, 66, 87) else Color.rgb(230, 228, 239))
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
        private const val DATABASE_PASSWORD_KEY_ALIAS = "inzenyri_database_password"
        private const val DB_PREFS = "database_state"
        private const val ITEM_PREFS = "item_state"
        private const val DB_BASELINE_HASH = "baseline_sha256"
        private const val SAVED_PASSWORD_IV = "database_password_iv"
        private const val SAVED_PASSWORD_VALUE = "database_password_value"
        private const val DONE_ITEMS_KEY = "done_items"
        private fun b64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        private fun unb64(value: String) = Base64.getDecoder().decode(value)
        private fun today() = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date())
        private fun parseDate(value: String): Date? = try {
            SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).apply { isLenient = false }.parse(value)
        } catch (_: Exception) { null }
    }
}
