package cz.inzenyri.mobile

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.BadPaddingException
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val credentialStore by lazy(LazyThreadSafetyMode.NONE) { SecureCredentialStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val networkWorker = Executors.newSingleThreadExecutor()
    private var darkMode = false
    private val purple
        get() = if (darkMode) Color.rgb(177, 153, 255) else Color.rgb(107, 76, 210)

    private val ink
        get() = if (darkMode) Color.rgb(244, 242, 252) else Color.rgb(31, 38, 56)

    private val muted
        get() = if (darkMode) Color.rgb(174, 174, 192) else Color.rgb(103, 113, 132)

    private var database: JSONObject? = null
    private var originalDatabaseHash: String? = null
    private var password: CharArray? = null
    private var sourceEnvelope: JSONObject? = null
    private var selectedType = "task"
    private var dateValue = today()
    private var timeValue = ""
    private var timeButton: Button? = null
    private var statusView: TextView? = null
    private var footerView: TextView? = null
    private var reminderButton: Button? = null
    private var form: LinearLayout? = null
    private var typeButtons = mutableMapOf<String, Button>()
    private var titleInput: EditText? = null
    private var descriptionInput: EditText? = null
    private var solutionInput: EditText? = null
    private var aiReviewEditor: LinearLayout? = null
    private val selectedAiReviews = linkedSetOf<String>()
    private val aiReviewNames =
        linkedMapOf(
            "chatgpt" to "ChatGPT",
            "claude" to "Claude",
            "gemini" to "Gemini",
            "deepseek" to "DeepSeek",
        )
    private var quizPromptInput: EditText? = null
    private var quizAnswersInput: EditText? = null
    private var dateButton: Button? = null
    private var exportButton: Button? = null
    private var pushButton: Button? = null
    private var addButton: Button? = null
    private var updateButton: Button? = null
    private var pendingApk: File? = null
    private var updateAsset: JSONObject? = null
    private var updateReleaseNotes = ""
    private var updateVersion: String? = null
    private var updateProgressDialog: Dialog? = null
    private var pushProgressDialog: OperationProgressDialog? = null
    private var databaseQrDialog: Dialog? = null
    private var updateProgressStatus: TextView? = null
    private var updateProgressSpinner: ProgressBar? = null
    private var updateProgressRows = mutableListOf<TextView>()
    private var updateProgressTitles = mutableListOf<String>()
    private var updateProgressCloseButton: Button? = null
    private var updateProgressHeaderCloseButton: Button? = null
    private var updateInstallerLaunched = false
    private var pendingUpdateVersionCode = -1L
    private var maintainerButton: Button? = null
    private var maintainerItemsContainer: LinearLayout? = null
    private var editingItemIndex: Int? = null
    private var originalQuizPrompt = ""
    private var originalQuizAnswers = ""
    private var originalQuizIsSimpleText = false
    private var clearQuizCheckBox: CheckBox? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var dashboardView: View? = null
    private var dashboardStatusView: TextView? = null
    private var inMaintainerWorkspace = false
    private var itemsContainer: LinearLayout? = null
    private var calendarContainer: LinearLayout? = null
    private var dashboardCalendarMonth =
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    private var dashboardSelectedDay = Calendar.getInstance()
    private var calendarExpanded = true
    private var workspaceDir: File? = null
    private var databaseFile: File? = null
    private var maintainerMode = false
    private var themeButton: Button? = null
    private var lockButton: Button? = null
    private var githubLoginDialog: Dialog? = null
    private var githubLoginStatusView: TextView? = null
    private var githubLoginCodeView: TextView? = null
    private var githubLoginOpenButton: Button? = null
    private var githubLoginCancelButton: Button? = null
    private var githubLoginStepViews = mutableListOf<TextView>()
    private var loginPasswordInput: EditText? = null
    private var loginUnlockButton: Button? = null
    private val carouselAnchors = mutableMapOf<String, CarouselAnchor>()
    private val quizProgress by lazy { QuizProgressStore(this) }
    private val richText by lazy { RichTextRenderer(this) }
    private val aiReviewLogos by lazy { AiReviewLogoRenderer(this) }
    private val aiReviewBubble by lazy { AiReviewBubble(this) }
    private var quizExitAction: (() -> Unit)? = null
    private var solutionTemplateButton: Button? = null
    private var updateNotificationsCheckBox: CheckBox? = null
    private val updateNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppUpdateNotifications.setEnabled(this, granted)
            updateNotificationsCheckBox?.isChecked = granted
            if (granted) checkForUpdate(silent = true)
            else toast("Oznámení o aktualizacích nejsou povolená.")
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                worker.execute {
                    try {
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                            it.write(encryptedOutput())
                        } ?: error("Soubor se nepodařilo uložit.")
                        runOnUiThread {
                            statusView?.text =
                                "Uloženo. Nahraď tímto souborem data.enc.json v projektu."
                            toast("Zašifrovaná databáze je uložená.")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast(e.message ?: "Uložení se nezdařilo.") }
                    }
                }
            }
        }

    private val installPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (packageManager.canRequestPackageInstalls()) {
                setUpdateProgressStep(3, "SUCCESS")
                installDownloadedApk()
            } else {
                failUpdateProgress(3, "Aplikace nemá povolení požádat o instalaci APK.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val exitQuiz = quizExitAction
                    if (exitQuiz != null) {
                        exitQuiz()
                    } else {
                        isEnabled = false
                        try {
                            onBackPressedDispatcher.onBackPressed()
                        } finally {
                            isEnabled = true
                        }
                    }
                }
            },
        )
        workspaceDir =
            File(filesDir, "inzenyri").apply {
                if (!exists() && !mkdirs())
                    throw IOException("Lokální složku aplikace se nepodařilo vytvořit.")
            }
        databaseFile = File(workspaceDir, "data.enc.json")
        maintainerMode =
            getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE).getBoolean("maintainer_mode", false)
        darkMode = getSharedPreferences(ITEM_PREFS, MODE_PRIVATE).getBoolean("dark_mode", false)
        calendarExpanded =
            getSharedPreferences(ITEM_PREFS, MODE_PRIVATE).getBoolean("calendar_expanded", true)
        ReminderNotifications.schedule(this)
        AppUpdateNotifications.schedule(this)
        render()
        openStoredDatabaseOrClone()
        checkForUpdate(silent = intent.action != AppUpdateNotifications.OPEN_UPDATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == AppUpdateNotifications.OPEN_UPDATE) checkForUpdate(silent = false)
    }

    override fun onResume() {
        super.onResume()
        updateReminderButton()
        if (updateInstallerLaunched) {
            updateInstallerLaunched = false
            val installedVersion =
                PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
            if (installedVersion >= pendingUpdateVersionCode) {
                setUpdateProgressStep(4, "SUCCESS")
                finishUpdateProgress("Aktualizace byla nainstalována.")
            } else {
                failUpdateProgress(4, "Instalace nebyla dokončena.")
            }
            pendingUpdateVersionCode = -1L
        }
    }

    private val qrScanner =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents
            if (contents.isNullOrBlank()) {
                statusView?.text = "Skenování QR kódu bylo zrušeno."
            } else {
                applyLoginQr(contents)
            }
        }

    private fun toggleDarkMode() {
        val draft =
            listOf(
                titleInput?.text?.toString().orEmpty(),
                descriptionInput?.text?.toString().orEmpty(),
                solutionInput?.text?.toString().orEmpty(),
                quizPromptInput?.text?.toString().orEmpty(),
                quizAnswersInput?.text?.toString().orEmpty(),
            )
        val oldStatus = statusView?.text?.toString().orEmpty()
        darkMode = !darkMode
        getSharedPreferences(ITEM_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("dark_mode", darkMode)
            .apply()
        render()
        listOf(titleInput, descriptionInput, solutionInput, quizPromptInput, quizAnswersInput)
            .zip(draft)
            .forEach { (view, text) -> view?.setText(text) }
        selectType(selectedType)
        statusView?.text = oldStatus
        if (database != null) {
            itemsContainer?.visibility = View.VISIBLE
            lockButton?.visibility = View.VISIBLE
            form?.visibility =
                if (inMaintainerWorkspace && maintainerMode) View.VISIBLE else View.GONE
            exportButton?.visibility =
                if (inMaintainerWorkspace && maintainerMode) View.VISIBLE else View.GONE
            pushButton?.visibility =
                if (inMaintainerWorkspace && maintainerMode) View.VISIBLE else View.GONE
            renderDatabase()
        }
    }

    private fun lockDatabase() {
        val current = database?.let { JSONObject(it.toString()) } ?: return
        val pass = password?.clone() ?: return
        val envelope =
            sourceEnvelope?.let { JSONObject(it.toString()) }
                ?: run {
                    pass.fill('\u0000')
                    return
                }
        statusView?.text = "Ukládám a zamykám databázi…"
        lockButton?.isEnabled = false
        worker.execute {
            try {
                val raw = encryptedOutput(current, pass, envelope)
                val verification =
                    JSONObject(String(decrypt(JSONObject(raw), pass), StandardCharsets.UTF_8))
                require(verification.optJSONArray("tasks") != null) {
                    "Ověření zašifrované databáze selhalo."
                }
                saveLocalDatabase(raw)
                val persistedRaw =
                    databaseFile?.readText(StandardCharsets.UTF_8)
                        ?: error("Uloženou databázi se nepodařilo znovu načíst.")
                val persistedData =
                    JSONObject(
                        String(decrypt(JSONObject(persistedRaw), pass), StandardCharsets.UTF_8),
                    )
                require(persistedData.optJSONArray("tasks") != null) {
                    "Uložený soubor databáze je poškozený."
                }
                runOnUiThread {
                    password?.fill('\u0000')
                    password = null
                    database = null
                    sourceEnvelope = null
                    originalDatabaseHash = null
                    render()
                    askPassword(persistedRaw, preserveBaseline = true)
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

    private fun render() {
        // Resizing for the IME can expose the window behind the content during its animation.
        window.setBackgroundDrawable(
            ColorDrawable(
                if (darkMode) Color.rgb(17, 20, 31) else Color.rgb(248, 247, 251),
            ),
        )
        val page =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(24), dp(20), dp(22))
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        if (darkMode) intArrayOf(Color.rgb(24, 23, 39), Color.rgb(17, 20, 31))
                        else intArrayOf(Color.rgb(248, 247, 251), Color.rgb(248, 247, 251)),
                    )
            }
        val scroller = ScrollView(this).apply { addView(page) }
        swipeRefreshLayout =
            SwipeRefreshLayout(this).apply {
                setColorSchemeColors(purple)
                setOnRefreshListener { refreshFromOrigin() }
                addView(scroller)
            }
        dashboardView = swipeRefreshLayout
        inMaintainerWorkspace = false
        setContentView(swipeRefreshLayout)

        val topBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        topBar.addView(
            label("ŠKOLNÍ PŘEHLED", 11, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val settingsButton =
            Button(this).apply {
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
        topBar.addView(
            settingsButton,
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) },
        )
        lockButton =
            Button(this).apply {
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
        maintainerButton =
            Button(this).apply {
                text = if (maintainerMode) "🔑" else "🔧"
                contentDescription =
                    if (maintainerMode) "GitHub maintainer účet je propojen"
                    else "Propojit maintainer účet s GitHubem"
                textSize = 22f
                minWidth = dp(48)
                minHeight = dp(48)
                isAllCaps = false
                setTextColor(ink)
                background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                setOnClickListener {
                    if (maintainerMode && database != null && readGithubCredentials() != null)
                        showMaintainerWorkspace()
                    else connectMaintainer()
                }
            }
        topBar.addView(maintainerButton)
        page.addView(topBar)
        page.addView(label("Inženýři", 32, ink, true), margin(0, 16, 0, 6))
        page.addView(
            label("Testy, úkoly a akce. Všechno na jednom místě.", 15, muted, false),
            margin(0, 0, 0, 20),
        )
        page.addView(label("Přehled", 30, ink, true), margin(0, 4, 0, 4))
        page.addView(
            label("Testy, úkoly a akce na jednom místě.", 15, muted, false),
            margin(0, 0, 0, 16),
        )
        calendarContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(if (darkMode) Color.rgb(29, 29, 44) else Color.WHITE, 22)
            }
        page.addView(calendarContainer, margin(0, 0, 0, 14))
        statusView = label("Připravuji lokální data…", 13, muted, false)
        dashboardStatusView = statusView
        page.addView(statusView, margin(0, 0, 0, 12))
        itemsContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
        page.addView(itemsContainer)
        exportButton =
            button("Uložit šifrovanou databázi") { chooseDestination() }
                .also { it.visibility = View.GONE }
        pushButton =
            button("Pushnout DB na origin") { pushToOrigin() }.also { it.visibility = View.GONE }

        form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
        form?.apply {
            addView(label("Typ položky", 14, ink, true), margin(0, 8, 0, 7))
            val types =
                LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("task" to "Úkol", "test" to "Test", "event" to "Akce").forEach { (type, title) ->
                val b = button(title) { selectType(type) }
                typeButtons[type] = b
                types.addView(
                    b,
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(7) },
                )
            }
            addView(types)
            titleInput = input("Název", false).also { addView(it, margin(0, 14, 0, 0)) }
            dateButton =
                button("Datum · $dateValue") { chooseDate() }
                    .also { addView(it, margin(0, 10, 0, 0)) }
            timeButton =
                button(timeValue.ifBlank { "Celý den" }) { chooseItemTime() }
                    .also { addView(it, margin(0, 10, 0, 0)) }
            descriptionInput =
                input("Popis (volitelné)", true).also { addView(it, margin(0, 10, 0, 0)) }
            solutionInput =
                input("Řešení v Markdownu (volitelné)", true).also {
                    addView(it, margin(0, 10, 0, 0))
                }
            solutionTemplateButton =
                button("＋ Šablona pracovního sešitu") { showWorksheetTemplate() }
                    .also { addView(it, margin(0, 8, 0, 0)) }
            aiReviewEditor =
                LinearLayout(this@MainActivity)
                    .apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    .also { addView(it, margin(0, 10, 0, 0)) }
            renderAiReviewEditor()
            quizPromptInput =
                input("Otázka (volitelné)", true).also { addView(it, margin(0, 10, 0, 0)) }
            quizAnswersInput =
                input("Správné odpovědi (odděl |)", false).also { addView(it, margin(0, 8, 0, 0)) }
            clearQuizCheckBox =
                CheckBox(this@MainActivity)
                    .apply {
                        text = "Odebrat procvičování"
                        setTextColor(ink)
                        visibility = View.GONE
                        setOnCheckedChangeListener { _, checked ->
                            if (editingItemIndex != null && !originalQuizIsSimpleText) {
                                quizPromptInput?.isEnabled = checked
                                quizAnswersInput?.isEnabled = checked
                            }
                        }
                    }
                    .also { addView(it, margin(0, 4, 0, 0)) }
            addButton = button("Přidat položku") { addItem() }
            addView(addButton, margin(0, 16, 0, 4))
        }
        val footer =
            label("", 12, muted, false).apply {
                gravity = Gravity.CENTER
            }
        footerView = footer
        page.addView(footer, margin(0, 24, 0, 4))
        updateFooter()
        selectType("task")
    }

    private fun openStoredDatabaseOrClone() {
        val local = databaseFile ?: return
        if (local.isFile && local.length() > 0) {
            try {
                statusView?.text = "Lokální databáze připravená · zadej heslo."
                askPassword(
                    local.readText(StandardCharsets.UTF_8),
                    preserveBaseline = true,
                    allowAutoLogin = true,
                )
                return
            } catch (e: Exception) {
                statusView?.text = "Lokální databázi se nepodařilo načíst: ${e.message}"
            }
        }
        statusView?.text = "Stahuji šifrovanou databázi do složky aplikace…"
        networkWorker.execute {
            try {
                val token =
                    readGithubCredentials()?.let { saved ->
                        try {
                            validCredentials(saved).accessToken
                        } catch (_: Exception) {
                            null
                        }
                    }
                val raw =
                    try {
                        fetchRemoteDatabase(token)
                    } catch (e: Exception) {
                        if (token == null) throw e
                        fetchRemoteDatabase()
                    }
                saveLocalDatabase(raw)
                runOnUiThread {
                    statusView?.text = "Databáze stažena. Zadej heslo."
                    askPassword(raw, allowAutoLogin = true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView?.text =
                        "Databázi se nepodařilo stáhnout. Zkontroluj připojení a obnov tažením dolů."
                    toast(e.message ?: "Stažení databáze se nezdařilo.")
                }
            }
        }
    }

    private fun fetchRemoteDatabase(token: String? = null): String {
        val response =
            JSONObject(
                githubRequest(
                    "repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json?ref=$GITHUB_BRANCH",
                    token = token,
                ),
            )
        val raw = GitHubApiClient.databaseContent(GITHUB_OWNER, GITHUB_REPO, response, token)
        JSONObject(raw)
        return raw
    }

    private fun saveLocalDatabase(raw: String) {
        val target = databaseFile ?: error("Lokální složka aplikace není připravená.")
        val temporary = File(target.parentFile, "data.enc.json.tmp")
        temporary.writeText(raw, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun refreshFromOrigin() {
        confirmReplacingDatabase {
            statusView?.text = "Stahuji databázi…"
            networkWorker.execute {
                var activePassword: CharArray? = null
                try {
                    val token =
                        readGithubCredentials()?.let { saved ->
                            try {
                                validCredentials(saved).accessToken
                            } catch (_: Exception) {
                                null
                            }
                        }
                    val raw =
                        try {
                            fetchRemoteDatabase(token)
                        } catch (e: Exception) {
                            if (token == null) throw e
                            fetchRemoteDatabase()
                        }
                    activePassword = password?.clone()
                    val refreshPassword = activePassword
                    val parsed = refreshPassword?.let {
                        try {
                            JSONObject(String(decrypt(JSONObject(raw), it), StandardCharsets.UTF_8))
                        } catch (_: BadPaddingException) {
                            null
                        }
                    }
                    runOnUiThread {
                        try {
                            swipeRefreshLayout?.isRefreshing = false
                            if (parsed != null) {
                                password?.fill('\u0000')
                                password = refreshPassword.clone()
                                database = parsed
                                sourceEnvelope = JSONObject(raw)
                                originalDatabaseHash = databaseHash(parsed)
                                getSharedPreferences(DB_PREFS, MODE_PRIVATE)
                                    .edit()
                                    .putString(DB_BASELINE_HASH, originalDatabaseHash)
                                    .apply()
                                try {
                                    saveLocalDatabase(raw)
                                } catch (e: Exception) {
                                    toast(e.message ?: "Lokální kopii se nepodařilo uložit.")
                                }
                                statusView?.text =
                                    "Databáze obnovena · ${parsed.getJSONArray("tasks").length()} položek."
                                renderDatabase()
                            } else {
                                val message =
                                    if (refreshPassword != null) {
                                        "Uložené heslo novou databázi neodemklo. Zadej heslo k databázi z origin."
                                    } else {
                                        "Databáze stažena. Zadej heslo."
                                    }
                                password?.fill('\u0000')
                                password = null
                                askPassword(raw, initialMessage = message)
                            }
                        } finally {
                            activePassword?.fill('\u0000')
                        }
                    }
                } catch (e: Exception) {
                    activePassword?.fill('\u0000')
                    runOnUiThread {
                        swipeRefreshLayout?.isRefreshing = false
                        val message =
                            if (e is BadPaddingException) {
                                "Databázi z origin se nepodařilo odemknout. Zadej heslo k této verzi."
                            } else {
                                e.message ?: "Stažení databáze se nezdařilo."
                            }
                        statusView?.text = message
                        toast(message)
                    }
                }
            }
        }
    }

    private fun openSettings() {
        val dialog = Dialog(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(22))
                background =
                    rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(label("Nastavení", 23, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("✕") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(header)
        content.addView(
            label("Inženýři · verze ${BuildConfig.VERSION_NAME}", 13, muted, false),
            margin(0, 5, 0, 20),
        )
        themeButton =
            button(if (darkMode) "☀  Světlý vzhled" else "☾  Tmavý vzhled") {
                dialog.dismiss()
                toggleDarkMode()
            }
        content.addView(themeButton, margin(0, 0, 0, 10))
        val autoLogin =
            CheckBox(this).apply {
                text = "Automaticky přihlásit při spuštění"
                setTextColor(ink)
                isChecked =
                    getSharedPreferences(ITEM_PREFS, MODE_PRIVATE).getBoolean("auto_login", true)
                setOnCheckedChangeListener { _, enabled ->
                    if (enabled) {
                        try {
                            val currentPassword = password ?: error("Nejdřív odemkni databázi.")
                            saveDatabasePassword(currentPassword)
                        } catch (e: Exception) {
                            isChecked = false
                            toast(e.message ?: "Automatické přihlášení se nepodařilo zapnout.")
                            return@setOnCheckedChangeListener
                        }
                    }
                    getSharedPreferences(ITEM_PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean("auto_login", enabled)
                        .apply()
                }
            }
        content.addView(autoLogin, margin(0, 0, 0, 5))
        content.addView(
            label(
                "Při spuštění odemkne databázi bezpečně uloženým heslem. Používej jen na vlastním zařízení. Vypnutí zachová uložené heslo pro ruční přihlášení.",
                13,
                muted,
                false,
            ),
            margin(0, 0, 0, 16),
        )
        content.addView(label("Oznámení", 17, ink, true), margin(0, 4, 0, 5))
        updateNotificationsCheckBox =
            CheckBox(this)
                .apply {
                    text = "Upozorňovat na aktualizace aplikace"
                    setTextColor(ink)
                    buttonTintList = android.content.res.ColorStateList.valueOf(purple)
                    isChecked = AppUpdateNotifications.enabled(this@MainActivity)
                    setOnCheckedChangeListener { _, checked ->
                        if (
                            checked &&
                                android.os.Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(
                                    android.Manifest.permission.POST_NOTIFICATIONS,
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            if (
                                getSharedPreferences("app_updates", MODE_PRIVATE)
                                    .getBoolean("permission_requested", false) &&
                                    !shouldShowRequestPermissionRationale(
                                        android.Manifest.permission.POST_NOTIFICATIONS,
                                    )
                            ) {
                                startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                                )
                                AppUpdateNotifications.setEnabled(this@MainActivity, true)
                            } else {
                                getSharedPreferences("app_updates", MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("permission_requested", true)
                                    .apply()
                                updateNotificationPermission.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS,
                                )
                            }
                        } else {
                            AppUpdateNotifications.setEnabled(this@MainActivity, checked)
                            if (checked) checkForUpdate(silent = true)
                        }
                    }
                }
                .also { content.addView(it) }
        content.addView(
            label(
                "Kontrola na pozadí přibližně jednou denně; upozornění nejvýše jednou za tři dny. Oznámení musí být povolená v Androidu.",
                13,
                muted,
                false,
            ),
            margin(0, 0, 0, 12),
        )
        content.addView(
            button("Nastavení oznámení v Androidu") {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            },
            margin(0, 0, 0, 12),
        )
        content.addView(
            label(
                "Pro každý druh položky nastav předstih a čas připomenutí. Vychází z poslední odemčené databáze; Android může doručení posunout.",
                13,
                muted,
                false,
            ),
            margin(0, 0, 0, 8),
        )
        reminderButton =
            button("") {
                if (ReminderNotifications.enabled(this)) {
                    ReminderNotifications.setEnabled(this, false)
                } else if (
                    android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    if (
                        getSharedPreferences("reminders", MODE_PRIVATE)
                            .getBoolean("permission_requested", false) &&
                            !shouldShowRequestPermissionRationale(
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            )
                    ) {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                        )
                        toast("Povol oznámení v Androidu a potom zapni připomínky.")
                    } else {
                        getSharedPreferences("reminders", MODE_PRIVATE)
                            .edit()
                            .putBoolean("permission_requested", true)
                            .apply()
                        reminderPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else if (!ReminderNotifications.allowed(this)) {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    )
                    toast("Povol oznámení v Androidu a potom zapni připomínky.")
                } else {
                    ReminderNotifications.setEnabled(this, true)
                    database?.let { ReminderNotifications.sync(this, it, doneItems()) }
                }
                updateReminderButton()
            }
        content.addView(reminderButton, margin(0, 0, 0, 16))
        updateReminderButton()
        ReminderNotifications.types.forEach { (type, title) ->
            val timingButton = button(reminderTimingLabel(type, title)) {}
            timingButton.setOnClickListener {
                showReminderTiming(type, title) {
                    timingButton.text = reminderTimingLabel(type, title)
                }
            }
            content.addView(timingButton, margin(0, 0, 0, 10))
        }
        content.addView(
            button("Poslat testovací oznámení") { sendTestNotification() },
            margin(0, 0, 0, 16),
        )
        content.addView(
            label(
                "QR kód obsahuje heslo k databázi. Zobraz ho jen před důvěryhodnou osobou; přístup k údržbě GitHubu tím nesdílíš.",
                14,
                muted,
                false,
            ),
            margin(0, 0, 0, 10),
        )
        content.addView(
            button("Zobrazit QR kód pro přihlášení") { showDatabaseLoginQr() }
                .apply {
                    isEnabled = password != null
                    alpha = if (isEnabled) 1f else 0.5f
                },
            margin(0, 0, 0, 10),
        )
        updateButton =
            button(
                if (updateAsset == null) "Zkontrolovat aktualizace aplikace"
                else "Stáhnout dostupnou aktualizaci",
            ) {
                if (updateAsset == null) checkForUpdate(silent = false) else confirmAppUpdate()
            }
        content.addView(updateButton)
        if (hasStoredGithubCredentials()) {
            content.addView(
                button("Znovu propojit GitHub účet") {
                    dialog.dismiss()
                    clearGithubToken()
                    connectMaintainer()
                },
                margin(0, 10, 0, 0),
            )
        }
        (header.getChildAt(1) as Button).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(ScrollView(this).apply { addView(content) })
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun updateReminderButton() {
        reminderButton?.text =
            when {
                !ReminderNotifications.enabled(this) -> "Zapnout připomínky"
                !ReminderNotifications.allowed(this) -> "Vypnout připomínky · Android je blokuje"
                else -> "Vypnout připomínky"
            }
    }

    private fun reminderTimingLabel(type: String, title: String): String {
        if (!ReminderNotifications.typeEnabled(this, type)) return "$title · vypnuto"
        val value = ReminderNotifications.timing(this, type)
        val days =
            when (value.days) {
                0 -> "V daný den"
                1 -> "1 den předem"
                in 2..4 -> "${value.days} dny předem"
                else -> "${value.days} dní předem"
            }
        return "$title · $days · ${String.format(Locale.ROOT, "%02d:%02d", value.hour, value.minute)}"
    }

    private fun showReminderTiming(type: String, title: String, onSaved: () -> Unit) {
        val value = ReminderNotifications.timing(this, type)
        val dialog = Dialog(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(22))
                background =
                    rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
            }
        content.addView(label("$title · oznámení", 21, ink, true))
        val enabled =
            CheckBox(this).apply {
                text = "Připomínat $title"
                setTextColor(ink)
                buttonTintList = android.content.res.ColorStateList.valueOf(purple)
                isChecked = ReminderNotifications.typeEnabled(this@MainActivity, type)
            }
        content.addView(enabled, margin(0, 10, 0, 0))
        content.addView(
            label("Kolik dní předem (0 = v daný den)", 14, muted, false),
            margin(0, 14, 0, 5),
        )
        val days =
            input("Dní předem", false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.days.toString())
            }
        content.addView(days)
        content.addView(label("Čas oznámení (24hodinový)", 14, muted, false), margin(0, 14, 0, 5))
        val timeRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val hour =
            input("Hodina", false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                contentDescription = "Hodina oznámení"
                setText(String.format(Locale.ROOT, "%02d", value.hour))
            }
        val minute =
            input("Minuta", false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                contentDescription = "Minuta oznámení"
                setText(String.format(Locale.ROOT, "%02d", value.minute))
            }
        timeRow.addView(hour, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(label(" : ", 20, ink, true))
        timeRow.addView(minute, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(timeRow)
        content.addView(
            button("Uložit") {
                val d = days.text.toString().toIntOrNull()
                val h = hour.text.toString().toIntOrNull()
                val m = minute.text.toString().toIntOrNull()
                if (
                    d == null ||
                        d !in 0..365 ||
                        h == null ||
                        h !in 0..23 ||
                        m == null ||
                        m !in 0..59
                ) {
                    toast("Zadej 0–365 dní, hodinu 0–23 a minutu 0–59.")
                    return@button
                }
                ReminderNotifications.saveTiming(this, type, ReminderNotifications.Timing(d, h, m))
                ReminderNotifications.setTypeEnabled(this, type, enabled.isChecked)
                onSaved()
                dialog.dismiss()
            },
            margin(0, 18, 0, 8),
        )
        content.addView(button("Zrušit") { dialog.dismiss() })
        dialog.setContentView(ScrollView(this).apply { addView(content) })
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private val testNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) sendTestNotification()
            else toast("Testovací oznámení potřebuje povolení v Androidu.")
        }

    private val reminderPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ReminderNotifications.setEnabled(this, true)
                database?.let { ReminderNotifications.sync(this, it, doneItems()) }
                toast("Připomínky jsou zapnuté.")
            } else {
                toast("Připomínky potřebují povolení oznámení.")
            }
            updateReminderButton()
        }

    private fun sendTestNotification() {
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (
                getSharedPreferences("reminders", MODE_PRIVATE)
                    .getBoolean("permission_requested", false) &&
                    !shouldShowRequestPermissionRationale(
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    )
            ) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
                toast("Povol oznámení a potom pošli test znovu.")
            } else {
                getSharedPreferences("reminders", MODE_PRIVATE)
                    .edit()
                    .putBoolean("permission_requested", true)
                    .apply()
                testNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (ReminderNotifications.sendTest(this)) {
            toast("Testovací oznámení odesláno.")
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
            toast("Android oznámení blokuje. Povol je a potom pošli test znovu.")
        }
    }

    private fun showDatabaseLoginQr() {
        databaseQrDialog?.dismiss()
        val secret = password?.clone() ?: return toast("Nejdřív odemkni databázi.")
        var bitmap: Bitmap? = null
        try {
            val payload =
                JSONObject()
                    .put("application", "inzenyri")
                    .put("purpose", "database-login")
                    .put("version", 1)
                    .put("password", String(secret))
                    .toString()
            val size = 720
            val matrix =
                QRCodeWriter()
                    .encode(
                        payload,
                        BarcodeFormat.QR_CODE,
                        size,
                        size,
                        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
                    )
            val pixels =
                IntArray(size * size) { index ->
                    val x = index % size
                    val y = index / size
                    if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            bitmap =
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                    setPixels(pixels, 0, size, 0, 0, size, size)
                }
        } catch (_: WriterException) {
            secret.fill('\u0000')
            return toast("Přihlašovací QR kód se nepodařilo vytvořit.")
        } catch (e: Exception) {
            secret.fill('\u0000')
            return toast(e.message ?: "Přihlašovací QR kód se nepodařilo vytvořit.")
        }
        secret.fill('\u0000')

        val dialog = Dialog(this)
        val image =
            ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "QR kód pro přihlášení k databázi"
                setBackgroundColor(Color.WHITE)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(22), dp(20), dp(22), dp(20))
                background =
                    rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
            }
        content.addView(label("Přihlášení přes QR kód", 21, ink, true), margin(0, 0, 0, 8))
        content.addView(
            label(
                "Kód zpřístupní heslo každému, kdo ho naskenuje. Po přihlášení ho můžeš zase zavřít.",
                14,
                muted,
                false,
            ),
            margin(0, 0, 0, 14),
        )
        content.addView(image, LinearLayout.LayoutParams(dp(280), dp(280)))
        content.addView(button("Zavřít") { dialog.dismiss() }, margin(0, 14, 0, 0))
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            if (databaseQrDialog === dialog) databaseQrDialog = null
            image.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
        }
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        databaseQrDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(380)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun applyLoginQr(contents: String) {
        try {
            val payload = JSONObject(contents)
            require(payload.optString("application") == "inzenyri")
            require(
                payload.optString("purpose") == "database-login" && payload.optInt("version") == 1,
            )
            val secret = payload.optString("password")
            require(secret.isNotEmpty())
            val field = loginPasswordInput ?: error("Přihlašovací formulář není dostupný.")
            field.setText(secret)
            field.setSelection(field.text.length)
            loginUnlockButton?.performClick() ?: error("Tlačítko pro přihlášení není dostupné.")
        } catch (_: Exception) {
            statusView?.text = "QR kód neobsahuje platné přihlášení k databázi Inženýrů."
            toast(statusView?.text.toString())
        }
    }

    private fun showMaintainerWorkspace() {
        if (!maintainerMode || database == null) {
            connectMaintainer()
            return
        }
        inMaintainerWorkspace = true
        val page =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(24))
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        if (darkMode) intArrayOf(Color.rgb(24, 23, 39), Color.rgb(17, 20, 31))
                        else intArrayOf(Color.rgb(248, 247, 251), Color.rgb(248, 247, 251)),
                    )
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(
            button("← Zpět") { showDashboard() },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) },
        )
        header.addView(label("Údržba databáze", 22, ink, true))
        page.addView(header, margin(0, 0, 0, 16))
        statusView = label("Přidávej položky nebo pushni změny na origin.", 13, muted, false)
        page.addView(statusView, margin(0, 0, 0, 12))
        page.addView(button("＋  Nová položka") { startNewItem() }, margin(0, 0, 0, 12))
        form?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.visibility = View.GONE
            view.setPadding(dp(14), dp(14), dp(14), dp(14))
            view.background =
                rounded(if (darkMode) Color.rgb(29, 29, 44) else Color.rgb(238, 237, 246), 20)
            page.addView(view)
        }
        val maintenanceItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        maintainerItemsContainer = maintenanceItems
        page.addView(maintenanceItems, margin(0, 2, 0, 8))
        renderMaintainerItems()
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
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(page, ViewGroup.LayoutParams(-1, -1))
            },
        )
    }

    private fun showDashboard() {
        inMaintainerWorkspace = false
        editingItemIndex = null
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
        val dialog = Dialog(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(20), dp(22), dp(20))
                background =
                    rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val warning =
            TextView(this).apply {
                text = "!"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(210, 139, 38), 14)
            }
        header.addView(
            warning,
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) },
        )
        header.addView(
            label("Nahradit rozpracovanou databázi?", 20, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        content.addView(header)
        content.addView(
            label(
                "Máš položky, které ještě nejsou pushnuté na origin. Při načtení jiné databáze budou ztraceny.",
                15,
                muted,
                false,
            ),
            margin(0, 14, 0, 20),
        )
        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        actions.addView(
            button("Zpět") {
                    swipeRefreshLayout?.isRefreshing = false
                    dialog.dismiss()
                }
                .apply {
                    setTextColor(ink)
                    background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
        )
        actions.addView(
            button("Pokračovat") {
                dialog.dismiss()
                action()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        content.addView(actions)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { swipeRefreshLayout?.isRefreshing = false }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun askPassword(
        raw: String,
        preserveBaseline: Boolean = false,
        initialMessage: String = "",
        allowAutoLogin: Boolean = false,
    ) {
        val savedPassword = readSavedDatabasePassword()
        val shouldAutoLogin =
            allowAutoLogin &&
                savedPassword != null &&
                getSharedPreferences(ITEM_PREFS, MODE_PRIVATE).getBoolean("auto_login", true)
        val screen =
            ScrollView(this).apply {
                isFillViewport = true
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        if (darkMode) intArrayOf(Color.rgb(24, 23, 39), Color.rgb(17, 20, 31))
                        else intArrayOf(Color.rgb(248, 247, 251), Color.rgb(248, 247, 251)),
                    )
            }
        val page =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(28), dp(30), dp(28), dp(30))
            }
        screen.addView(page, ViewGroup.LayoutParams(-1, -1))
        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }
        page.addView(card, LinearLayout.LayoutParams(-1, -2))
        card.addView(label("INŽENÝŘI", 14, purple, true), margin(0, 0, 0, 20))
        card.addView(label("Odemkni databázi", 31, ink, true), margin(0, 0, 0, 8))
        card.addView(
            label("Zadej heslo, kterým je zašifrovaná databáze.", 15, muted, false),
            margin(0, 0, 0, 20),
        )
        val field =
            EditText(this).apply {
                hint = "Heslo k databázi"
                textSize = 16f
                setTextColor(ink)
                setHintTextColor(muted)
                setPadding(dp(15), dp(12), dp(15), dp(12))
                background =
                    rounded(if (darkMode) Color.rgb(27, 28, 43) else Color.rgb(250, 250, 253), 14)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = EditorInfo.IME_ACTION_DONE
                minHeight = dp(52)
                savedPassword?.let { setText(String(it)) }
            }
        card.addView(field)
        loginPasswordInput = field
        val savePassword =
            CheckBox(this).apply {
                text = "Bezpečně uložit heslo na tomto zařízení"
                setTextColor(ink)
                isChecked = savedPassword != null
            }
        savedPassword?.fill('\u0000')
        card.addView(savePassword, margin(0, 10, 0, 8))
        card.addView(
            label("Uložené heslo je šifrované klíčem z Android Keystore.", 13, muted, false),
            margin(0, 0, 0, 16),
        )
        statusView = label(initialMessage, 14, muted, false)
        card.addView(statusView, margin(0, 0, 0, 12))
        val unlockButton = button("Odemknout") {}
        loginUnlockButton = unlockButton
        card.addView(unlockButton)
        val qrLoginButton =
            button("Přihlásit se QR kódem") {
                    qrScanner.launch(
                        ScanOptions()
                            .setCaptureActivity(QrScanActivity::class.java)
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Naskenuj přihlašovací QR kód Inženýrů")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true),
                    )
                }
                .apply {
                    setTextColor(ink)
                    background =
                        rounded(
                            if (darkMode) Color.rgb(43, 42, 61) else Color.rgb(243, 241, 250),
                            14,
                        )
                }
        card.addView(qrLoginButton, margin(0, 10, 0, 0))
        updateButton =
            button(
                if (updateAsset == null) "Zkontrolovat aktualizace aplikace"
                else "Stáhnout dostupnou aktualizaci",
            ) {
                if (updateAsset == null) checkForUpdate(silent = false) else confirmAppUpdate()
            }
        card.addView(updateButton, margin(0, 10, 0, 0))
        page.addView(
            label("Verze ${BuildConfig.VERSION_NAME}", 12, muted, false).apply {
                gravity = Gravity.CENTER
            },
            margin(0, 18, 0, 0),
        )
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
                    require(parsed.optJSONArray("tasks") != null) {
                        "Databáze nemá seznam položek."
                    }
                    runOnUiThread {
                        password?.fill('\u0000')
                        password = candidate
                        if (savePassword.isChecked) {
                            try {
                                saveDatabasePassword(candidate)
                            } catch (e: Exception) {
                                forgetDatabasePassword()
                                toast(e.message ?: "Heslo se nepodařilo bezpečně uložit.")
                            }
                        } else {
                            forgetDatabasePassword()
                        }
                        database = parsed
                        val savedBaseline =
                            getSharedPreferences(DB_PREFS, MODE_PRIVATE)
                                .getString(DB_BASELINE_HASH, null)
                        originalDatabaseHash =
                            if (preserveBaseline && savedBaseline != null) savedBaseline
                            else
                                databaseHash(parsed).also {
                                    getSharedPreferences(DB_PREFS, MODE_PRIVATE)
                                        .edit()
                                        .putString(DB_BASELINE_HASH, it)
                                        .apply()
                                }
                        sourceEnvelope = envelope
                        if (!preserveBaseline) {
                            try {
                                saveLocalDatabase(raw)
                            } catch (e: Exception) {
                                toast(e.message ?: "Databázi se nepodařilo uložit do zařízení.")
                            }
                        }
                        render()
                        lockButton?.visibility = View.VISIBLE
                        itemsContainer?.visibility = View.VISIBLE
                        form?.visibility = View.GONE
                        exportButton?.visibility = View.GONE
                        pushButton?.visibility = View.GONE
                        val dbVersion =
                            sourceEnvelope?.optString("dbVersion")?.takeIf { it.isNotBlank() }
                                ?: "neuvedena"
                        statusView?.text =
                            "Odemčeno · ${parsed.getJSONArray("tasks").length()} položek · DB $dbVersion"
                        renderDatabase()
                    }
                } catch (e: Exception) {
                    candidate.fill('\u0000')
                    runOnUiThread {
                        statusView?.text =
                            if (e is BadPaddingException) {
                                "Heslo nesouhlasí. Zkontroluj ho a zkus to znovu."
                            } else {
                                "Lokální databáze je poškozená: ${e.message ?: "neplatný obsah"}"
                            }
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
        if (shouldAutoLogin) unlockButton.performClick()
    }

    private fun connectMaintainer() {
        runGithubTask { token ->
            try {
                val user = JSONObject(githubRequest("user", token = token)).optString("login")
                require(user.isNotBlank()) { "GitHub nevrátil přihlášený účet." }
                setGithubLoginStep(2, "SUCCESS")
                setGithubLoginStep(3, "RUNNING")
                GitHubApiClient.verifyWriteAccess(GITHUB_OWNER, GITHUB_REPO, token)
                if (database == null) {
                    val raw = fetchRemoteDatabase(token)
                    saveLocalDatabase(raw)
                    runOnUiThread {
                        statusView?.text = "Databáze stažena. Zadej heslo."
                        askPassword(raw)
                    }
                } else {
                    githubRequest(
                        "repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json?ref=$GITHUB_BRANCH",
                        token = token,
                    )
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
        getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("maintainer_mode", connected)
            .apply()
        maintainerButton?.text = if (connected) "🔑" else "🔧"
        maintainerButton?.contentDescription =
            if (connected) {
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

    private fun databaseHash(value: JSONObject): String = DatabaseRecords.hash(value)

    private fun updateFooter() {
        val version =
            sourceEnvelope?.optString("dbVersion")?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    databaseFile
                        ?.takeIf { it.isFile }
                        ?.readText(StandardCharsets.UTF_8)
                        ?.let { JSONObject(it).optString("dbVersion").takeIf(String::isNotBlank) }
                }
                    .getOrNull()
                ?: "neuvedena"
        footerView?.text = "Aplikace ${BuildConfig.VERSION_NAME} · DB $version"
    }

    private fun renderDatabase() {
        val data = database ?: return
        val container = itemsContainer ?: return
        container.removeAllViews()
        val tasks = data.optJSONArray("tasks") ?: JSONArray()
        val today =
            Calendar.getInstance()
                .apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                .time
        val allEntries =
            (0 until tasks.length())
                .mapNotNull { index ->
                    val item = tasks.optJSONObject(index) ?: return@mapNotNull null
                    val date = parseDatabaseDate(item.optString("date"))
                    Triple(item, index, date)
                }
                .sortedWith(
                    compareBy<Triple<JSONObject, Int, Date?>>(
                        { it.third ?: Date(Long.MAX_VALUE) },
                        { DatabaseRecords.time(it.first) },
                        { it.second },
                    ),
                )
        val activeEntries = allEntries.filter { entry ->
            val date = entry.third
            date == null || !date.before(today)
        }
        val doneKeys = doneItems()
        allEntries.forEach { (item, _, _) ->
            val legacyKey = DatabaseRecords.legacyKey(item)
            val stableKey = itemKey(item)
            if (stableKey != legacyKey && doneKeys.remove(legacyKey)) doneKeys.add(stableKey)
        }
        doneKeys.retainAll(allEntries.map { itemKey(it.first) }.toSet())
        getSharedPreferences(ITEM_PREFS, MODE_PRIVATE)
            .edit()
            .putStringSet(DONE_ITEMS_KEY, doneKeys)
            .apply()
        ReminderNotifications.sync(this, data, doneKeys)
        renderCalendar(activeEntries)
        updateFooter()
        val categories = listOf("test" to "Testy", "task" to "Úkoly", "event" to "Akce")
        for ((type, title) in categories) {
            val categoryItems = activeEntries.filter {
                itemType(it.first) == type && itemKey(it.first) !in doneKeys
            }
            addSection(container, title, categoryItems)
        }
        val completed = activeEntries.filter { itemKey(it.first) in doneKeys }
        if (completed.isNotEmpty()) addSection(container, "Dokončené", completed)
        if (activeEntries.isEmpty()) {
            container.addView(
                label("Žádné nadcházející položky.", 15, muted, false),
                margin(0, 8, 0, 16),
            )
        }
        if (inMaintainerWorkspace) renderMaintainerItems()
    }

    private fun renderCalendar(entries: List<Triple<JSONObject, Int, Date?>>) {
        val container = calendarContainer ?: return
        container.removeAllViews()
        val czechLocale = Locale("cs", "CZ")
        val calendarHeader =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription =
                    if (calendarExpanded) "Sbalit kalendář" else "Rozbalit kalendář"
                setOnClickListener {
                    calendarExpanded = !calendarExpanded
                    getSharedPreferences(ITEM_PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean("calendar_expanded", calendarExpanded)
                        .apply()
                    renderCalendar(entries)
                }
            }
        calendarHeader.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_down)
                setColorFilter(ink)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                rotation = if (calendarExpanded) 180f else 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        calendarHeader.addView(
            label("Kalendář", 18, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        container.addView(calendarHeader)
        val heading =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val monthName =
            SimpleDateFormat("LLLL yyyy", czechLocale)
                .format(dashboardCalendarMonth.time)
                .replaceFirstChar { it.titlecase(czechLocale) }
        val monthLabel = label(monthName, 14, muted, true).apply { gravity = Gravity.CENTER }
        heading.addView(monthLabel, LinearLayout.LayoutParams(0, -2, 1.2f))
        heading.addView(
            button("‹") {
                    dashboardCalendarMonth.add(Calendar.MONTH, -1)
                    dashboardSelectedDay.time = dashboardCalendarMonth.time
                    renderCalendar(entries)
                }
                .apply {
                    minWidth = dp(38)
                    minHeight = dp(38)
                    setPadding(0, 0, 0, 0)
                    setTextColor(ink)
                    background =
                        rounded(
                            if (darkMode) Color.rgb(43, 42, 61) else Color.rgb(246, 245, 250),
                            12,
                        )
                },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(5) },
        )
        heading.addView(
            button("›") {
                    dashboardCalendarMonth.add(Calendar.MONTH, 1)
                    dashboardSelectedDay.time = dashboardCalendarMonth.time
                    renderCalendar(entries)
                }
                .apply {
                    minWidth = dp(38)
                    minHeight = dp(38)
                    setPadding(0, 0, 0, 0)
                    setTextColor(ink)
                    background =
                        rounded(
                            if (darkMode) Color.rgb(43, 42, 61) else Color.rgb(246, 245, 250),
                            12,
                        )
                },
            LinearLayout.LayoutParams(dp(38), dp(38)),
        )
        container.addView(heading)

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val weekdays = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne").forEach { day ->
            weekdays.addView(
                label(day, 11, muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, dp(30), 1f),
            )
        }
        grid.addView(weekdays)
        val first = (dashboardCalendarMonth.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        val monthDays = dashboardCalendarMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val weekCount = (first + monthDays + 6) / 7
        val selectedKey =
            SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(dashboardSelectedDay.time)
        val todayKey = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        for (week in 0 until weekCount) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (weekday in 0..6) {
                val dayNumber = week * 7 + weekday - first + 1
                val cell =
                    if (dayNumber in 1..monthDays) {
                        val date =
                            Calendar.getInstance()
                                .apply {
                                    set(
                                        dashboardCalendarMonth.get(Calendar.YEAR),
                                        dashboardCalendarMonth.get(Calendar.MONTH),
                                        dayNumber,
                                    )
                                    set(Calendar.HOUR_OF_DAY, 0)
                                    set(Calendar.MINUTE, 0)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                .time
                        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(date)
                        val dayItems = entries.filter {
                            it.third?.let { due ->
                                SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(due) == dayKey
                            } == true
                        }
                        LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(2), dp(3), dp(2), dp(2))
                            val selected = dayKey == selectedKey
                            setBackgroundColor(Color.TRANSPARENT)
                            background =
                                rounded(
                                    when {
                                        selected -> purple
                                        dayKey == todayKey && darkMode -> Color.rgb(55, 49, 77)
                                        dayKey == todayKey -> Color.rgb(241, 237, 255)
                                        darkMode -> Color.rgb(38, 38, 55)
                                        else -> Color.rgb(248, 248, 251)
                                    },
                                    12,
                                )
                            contentDescription = "$dayNumber. ${dayItems.size} položek"
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                dashboardSelectedDay.time = date
                                renderCalendar(entries)
                            }
                            addView(
                                label(
                                    dayNumber.toString(),
                                    13,
                                    if (selected) Color.WHITE else ink,
                                    selected,
                                ),
                                LinearLayout.LayoutParams(-1, 0, 1f),
                            )
                            addView(
                                label(
                                    if (dayItems.isEmpty()) " " else "•",
                                    11,
                                    if (selected) Color.WHITE
                                    else
                                        dayItems.firstOrNull()?.let {
                                            accentForType(itemType(it.first))
                                        } ?: muted,
                                    true,
                                ),
                            )
                        }
                    } else View(this)
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
            grid.addView(row)
        }
        container.addView(grid, margin(0, 6, 0, 0))

        val selectedDateKey =
            SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(dashboardSelectedDay.time)
        val selectedDateLabel =
            SimpleDateFormat("EEEE d. MMMM", czechLocale)
                .format(dashboardSelectedDay.time)
                .replaceFirstChar { it.titlecase(czechLocale) }
        container.addView(label(selectedDateLabel, 14, ink, true), margin(0, 14, 0, 4))
        val selectedEntries = entries.filter {
            it.third?.let { due ->
                SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(due) == selectedDateKey
            } == true
        }
        if (selectedEntries.isEmpty()) {
            container.addView(
                label("Na tento den zatím nic není.", 13, muted, false),
                margin(0, 3, 0, 0),
            )
        } else {
            selectedEntries.forEach { (item, index, _) ->
                container.addView(itemCard(item, index), margin(0, 7, 0, 0))
            }
        }
        val undatedEntries = entries.filter { it.third == null }
        if (undatedEntries.isNotEmpty()) {
            container.addView(label("Bez data", 14, ink, true), margin(0, 14, 0, 4))
            undatedEntries.forEach { (item, index, _) ->
                container.addView(itemCard(item, index), margin(0, 7, 0, 0))
            }
        }
        for (index in 1 until container.childCount) {
            container.getChildAt(index).visibility =
                if (calendarExpanded) View.VISIBLE else View.GONE
        }
    }

    private fun accentForType(type: String): Int =
        when (type) {
            "test" -> Color.rgb(236, 108, 104)
            "event" -> Color.rgb(60, 130, 246)
            else -> purple
        }

    private fun renderMaintainerItems() {
        val container = maintainerItemsContainer ?: return
        val data = database ?: return
        container.removeAllViews()
        val tasks = data.optJSONArray("tasks") ?: JSONArray()
        val entries =
            (0 until tasks.length())
                .mapNotNull { index ->
                    val item = tasks.optJSONObject(index) ?: return@mapNotNull null
                    val date = parseDatabaseDate(item.optString("date"))
                    Triple(item, index, date)
                }
                .sortedWith(
                    compareBy<Triple<JSONObject, Int, Date?>>(
                        { it.third ?: Date(Long.MAX_VALUE) },
                        { DatabaseRecords.time(it.first) },
                        { it.second },
                    ),
                )
        for ((type, title) in listOf("test" to "Testy", "task" to "Úkoly", "event" to "Akce")) {
            addSection(container, title, entries.filter { itemType(it.first) == type })
        }
        if (entries.isEmpty()) {
            container.addView(
                label("Zatím tu nejsou žádné položky k úpravě.", 14, muted, false),
                margin(0, 8, 0, 16),
            )
        }
    }

    private fun addSection(
        container: LinearLayout,
        title: String,
        entries: List<Triple<JSONObject, Int, Date?>>,
    ) {
        if (entries.isEmpty()) return
        val (symbol, accent) =
            when (title) {
                "Testy" -> "✎" to Color.rgb(236, 108, 104)
                "Úkoly" -> "✓" to purple
                "Akce" -> "◷" to Color.rgb(60, 130, 246)
                else -> "✓" to Color.rgb(36, 133, 91)
            }
        val section =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(4))
                background =
                    rounded(if (darkMode) Color.rgb(29, 29, 44) else Color.rgb(249, 248, 252), 22)
            }
        val heading =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(10))
            }
        heading.addView(
            TextView(this).apply {
                text = symbol
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(accent)
                background =
                    rounded(if (darkMode) Color.rgb(48, 43, 69) else Color.rgb(241, 237, 255), 11)
            },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) },
        )
        heading.addView(label(title, 17, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(
            label(entries.size.toString(), 13, accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background =
                    rounded(if (darkMode) Color.rgb(48, 43, 69) else Color.rgb(241, 237, 255), 99)
            },
        )
        section.addView(heading)
        if (inMaintainerWorkspace) {
            entries.forEach { (item, index, _) ->
                section.addView(itemCard(item, index), margin(0, 0, 0, 8))
            }
        } else {
            val cardWidth =
                (resources.displayMetrics.widthPixels - dp(if (entries.size > 1) 96 else 64))
                    .coerceAtMost(dp(380))
                    .coerceAtLeast(dp(180))
            section.addView(
                LoopingCardCarousel(
                    this,
                    entries.map { itemKey(it.first) },
                    carouselAnchors[title],
                    cardWidth,
                    ink,
                    muted,
                    darkMode,
                    { carouselAnchors[title] = it },
                    { position -> itemCard(entries[position].first, entries[position].second) },
                ),
            )
        }
        container.addView(section, margin(0, 14, 0, 0))
    }

    private fun itemCard(item: JSONObject, index: Int? = null): View {
        val completed = itemKey(item) in doneItems()
        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
                background =
                    rounded(
                            when {
                                darkMode && completed -> Color.rgb(48, 47, 63)
                                darkMode -> Color.rgb(35, 35, 53)
                                completed -> Color.rgb(240, 239, 245)
                                else -> Color.WHITE
                            },
                            22,
                        )
                        .apply {
                            setStroke(
                                dp(1),
                                if (darkMode) Color.rgb(55, 53, 73) else Color.rgb(233, 230, 240),
                            )
                        }
            }
        val heading =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val checkbox =
            TextView(this)
                .apply {
                    gravity = Gravity.CENTER
                    textSize = 16f
                    isSelected = completed
                    text = if (completed) "✓" else ""
                    setTextColor(if (completed) Color.WHITE else muted)
                    background =
                        rounded(
                                if (completed) purple
                                else if (darkMode) Color.rgb(62, 61, 78) else Color.WHITE,
                                9,
                            )
                            .apply {
                                if (!completed)
                                    setStroke(
                                        dp(2),
                                        if (darkMode) Color.rgb(132, 127, 153)
                                        else Color.rgb(177, 169, 200),
                                    )
                            }
                    contentDescription =
                        if (completed) "Vrátit mezi aktivní" else "Označit jako dokončené"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        isClickable = false
                        val checked = !isSelected
                        val scroll = swipeRefreshLayout?.getChildAt(0) as? ScrollView
                        val previousY = scroll?.scrollY ?: 0
                        card
                            .animate()
                            .alpha(0f)
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(160)
                            .withEndAction {
                                val values = doneItems()
                                if (checked) values.add(itemKey(item))
                                else values.remove(itemKey(item))
                                getSharedPreferences(ITEM_PREFS, MODE_PRIVATE)
                                    .edit()
                                    .putStringSet(DONE_ITEMS_KEY, values)
                                    .apply()
                                renderDatabase()
                                scroll?.post { scroll.scrollTo(0, previousY) }
                            }
                            .start()
                    }
                }
                .also {
                    it.layoutParams =
                        LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) }
                }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val accent =
            when (itemType(item)) {
                "test" -> Color.rgb(236, 108, 104)
                "event" -> Color.rgb(60, 130, 246)
                else -> purple
            }
        titleBlock.addView(
            label(
                item.optString("name").ifBlank { "Bez názvu" },
                17,
                if (completed) muted else ink,
                true,
            ),
        )
        titleBlock.addView(
            label(
                item.optString("date").ifBlank { "Bez data" } +
                    " · " +
                    DatabaseRecords.time(item).ifBlank { "Celý den" },
                13,
                accent,
                true,
            ),
            margin(0, 3, 0, 0),
        )
        heading.addView(checkbox)
        heading.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        val description = item.optString("task").trim()
        if (description.isNotEmpty()) {
            val body =
                label(description, 15, ink, false).apply {
                    richText.setText(this, description)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    linksClickable = true
                    movementMethod = LinkMovementMethod.getInstance()
                }
            card.addView(body, margin(0, 10, 0, 0))
        }
        val solution = item.optString("solution").trim()
        if (solution.isNotEmpty()) {
            var visible = false
            val solutionContent =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }
            val solutionText =
                label(solution, 14, muted, false).apply {
                    richText.setText(this, solution)
                    linksClickable = true
                    movementMethod = LinkMovementMethod.getInstance()
                }
            if (itemType(item) == "task") {
                val reviews = item.optJSONArray("aiReviews")
                val reviewsToShow = aiReviewNames.filterKeys { key ->
                    reviews != null &&
                        (0 until reviews.length()).any { reviews.optString(it) == key }
                }
                if (reviewsToShow.isNotEmpty()) {
                    val row =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                    reviewsToShow.forEach { (key, name) ->
                        val icon =
                            when (key) {
                                "chatgpt" -> R.drawable.ai_chatgpt
                                "claude" -> R.drawable.ai_claude
                                "gemini" -> R.drawable.ai_gemini
                                else -> R.drawable.ai_deepseek
                            }
                        row.addView(
                            ImageView(this).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                imageTintList = null
                                clearColorFilter()
                                setImageBitmap(aiReviewLogos.bitmap(icon))
                                contentDescription = "Úkol byl zkontrolován pomocí $name"
                                tooltipText = contentDescription
                                isFocusable = true
                                setOnClickListener { aiReviewBubble.show(it, name, darkMode) }
                                setPadding(dp(12), dp(12), dp(12), dp(12))
                                background =
                                    rounded(
                                        if (darkMode) Color.rgb(61, 49, 43)
                                        else Color.rgb(245, 237, 229),
                                        50,
                                    )
                            },
                            LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                                marginStart = dp(3)
                                marginEnd = dp(3)
                            },
                        )
                    }
                    solutionContent.addView(row, margin(0, 5, 0, 0))
                }
            }
            solutionContent.addView(solutionText, margin(0, 8, 0, 0))
            val initialLabel =
                if (itemType(item) == "test") "Zobrazit procvičování" else "Zobrazit řešení"
            val solutionButton = button(initialLabel) {}
            solutionButton.setOnClickListener {
                visible = !visible
                solutionContent.visibility = if (visible) View.VISIBLE else View.GONE
                solutionButton.text = if (visible) "Skrýt" else initialLabel
            }
            card.addView(solutionButton, margin(0, 10, 0, 0))
            card.addView(solutionContent, margin(0, 6, 0, 0))
        }
        if (itemType(item) == "test") {
            card.addView(
                button("Vybrat procvičování →") { showQuizSelection(item) },
                margin(0, 8, 0, 0),
            )
        }
        if (inMaintainerWorkspace && maintainerMode && index != null) {
            val actions =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            actions.addView(
                button("Upravit") { startEditingItem(index) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
            )
            actions.addView(
                button("Smazat") { confirmDeleteItem(index) }
                    .apply {
                        background = rounded(Color.rgb(183, 54, 66), 16)
                    },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            card.addView(actions, margin(0, 10, 0, 0))
        }
        return card
    }

    private fun showQuizSelection(item: JSONObject) {
        val previousScreen = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        val previousExit = quizExitAction
        fun goBack() {
            quizExitAction = previousExit
            setContentView(previousScreen)
        }
        quizExitAction = ::goBack
        val page =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(24))
                setBackgroundColor(
                    if (darkMode) Color.rgb(24, 23, 39) else Color.rgb(246, 243, 255),
                )
            }
        page.addView(button("← Zpět k testům") { goBack() })
        page.addView(label(item.optString("name"), 26, ink, true), margin(0, 20, 0, 8))
        page.addView(
            label(
                "Vyber si, co chceš procvičit. Každý kvíz můžeš spustit samostatně.",
                15,
                muted,
                false,
            ),
            margin(0, 0, 0, 20),
        )
        if (maintainerMode && inMaintainerWorkspace) {
            page.addView(
                button("＋ Přidat kvíz") {
                    addPracticeQuiz(item) {
                        goBack()
                        showQuizSelection(item)
                    }
                },
                margin(0, 0, 0, 16),
            )
        }
        val quizzes = PracticeQuizzes.forTest(item)
        if (quizzes.isEmpty()) {
            page.addView(
                label("Pro tento test zatím nejsou připravené žádné kvízy.", 17, muted, false),
            )
        }
        quizzes.forEachIndexed { index, quiz ->
            val title = PracticeQuizzes.title(quiz, index)
            val count = PracticeQuizzes.questions(quiz).size
            val card =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                    background = rounded(if (darkMode) Color.rgb(35, 35, 53) else Color.WHITE, 22)
                }
            card.addView(label(title, 21, ink, true))
            val progress = quizProgress.read(quizProgress.identity(item, quiz, index))
            if (progress?.complete == true) {
                card.addView(
                    ProgressRingView(
                        this,
                        progress.percent,
                        purple,
                        if (darkMode) Color.rgb(58, 53, 77) else Color.rgb(232, 226, 248),
                    ),
                    LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(12)
                    },
                )
            } else {
                card.addView(
                    label("${progress?.answered ?: 0}/$count zodpovězeno", 17, purple, true),
                    margin(0, 12, 0, 12),
                )
            }
            val action =
                if (progress != null && progress.cursor < progress.order.size) "Pokračovat →"
                else if (progress?.complete == true) "Zobrazit výsledek"
                else if (progress != null) "Pokračovat →" else "Spustit kvíz →"
            card.addView(button(action) { startQuiz(item, quiz, index, title) })
            page.addView(card, margin(0, 0, 0, 14))
        }
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(page)
            },
        )
    }

    private fun addPracticeQuiz(item: JSONObject, onSaved: () -> Unit) {
        val previousScreen = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        val previousExit = quizExitAction
        fun cancel() {
            quizExitAction = previousExit
            setContentView(previousScreen)
        }
        quizExitAction = ::cancel
        val page =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(24))
                setBackgroundColor(
                    if (darkMode) Color.rgb(24, 23, 39) else Color.rgb(246, 243, 255),
                )
            }
        page.addView(button("← Zrušit") { cancel() })
        page.addView(label("Nový kvíz", 26, ink, true), margin(0, 16, 0, 12))
        val title = input("Název tématu, např. Sčítání", false)
        page.addView(title)
        page.addView(
            label(
                "Textové otázky. Více uznávaných odpovědí odděl znakem |. Velikost písmen se nerozlišuje.",
                14,
                muted,
                false,
            ),
            margin(0, 12, 0, 12),
        )
        val fields = mutableListOf<Pair<EditText, EditText>>()
        val questions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(questions)
        fun addQuestion() {
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = rounded(if (darkMode) Color.rgb(35, 35, 53) else Color.WHITE, 18)
                }
            val prompt = input("Zadání otázky", true)
            val answers = input("Správná odpověď (varianty odděl |)", false)
            val pair = prompt to answers
            fields.add(pair)
            row.addView(prompt)
            row.addView(answers, margin(0, 8, 0, 8))
            row.addView(
                button("Odebrat otázku") {
                    fields.remove(pair)
                    questions.removeView(row)
                },
            )
            questions.addView(row, margin(0, 0, 0, 12))
        }
        addQuestion()
        page.addView(button("＋ Další otázka") { addQuestion() }, margin(0, 0, 0, 12))
        val shuffle =
            CheckBox(this).apply {
                text = "Promíchat otázky"
                setTextColor(ink)
            }
        page.addView(shuffle)
        page.addView(
            button("Uložit kvíz") {
                if (title.text.toString().isBlank()) {
                    title.error = "Zadej název kvízu"
                    return@button
                }
                if (fields.isEmpty()) {
                    toast("Přidej alespoň jednu otázku.")
                    return@button
                }
                val values = JSONArray()
                for ((prompt, answers) in fields) {
                    val accepted =
                        answers.text
                            .toString()
                            .split('|')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    if (prompt.text.toString().isBlank() || accepted.isEmpty()) {
                        toast("Vyplň zadání i správnou odpověď u každé otázky.")
                        return@button
                    }
                    values.put(
                        JSONObject()
                            .put("type", "text")
                            .put("prompt", prompt.text.toString().trim())
                            .put("answers", JSONArray(accepted))
                            .put("ignoreCase", true),
                    )
                }
                val quiz =
                    JSONObject()
                        .put("id", java.util.UUID.randomUUID().toString())
                        .put("title", title.text.toString().trim())
                        .put("shuffle", shuffle.isChecked)
                        .put("questions", values)
                if (!item.has("quiz")) item.put("quiz", quiz)
                else item.put("quizzes", (item.optJSONArray("quizzes") ?: JSONArray()).put(quiz))
                persistLocalDatabase()
                renderDatabase()
                cancel()
                onSaved()
            },
            margin(0, 12, 0, 0),
        )
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(page)
            },
        )
    }

    private fun startQuiz(item: JSONObject, quiz: JSONObject, quizIndex: Int, title: String) {
        val questions = JSONArray(PracticeQuizzes.questions(quiz))
        if (questions.length() == 0) return
        val previousScreen = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        val previousExit = quizExitAction
        fun returnToItems() {
            quizExitAction = previousExit
            if (previousExit != null) previousExit() else setContentView(previousScreen)
            showQuizSelection(item)
        }
        quizExitAction = ::returnToItems
        val identity = quizProgress.identity(item, quiz, quizIndex)
        val attempt = quizProgress.resume(identity, quiz.optBoolean("shuffle"))
        var index = attempt.cursor
        var score = attempt.roundScore
        var questionOrder = attempt.order
        if (questionOrder.isEmpty()) {
            returnToItems()
            return
        }
        val failedQuestions = attempt.failed.toMutableList()
        fun saveProgress(cursor: Int) {
            attempt.order = questionOrder
            attempt.cursor = cursor
            quizProgress.write(identity, attempt)
        }
        fun showQuestion() {
            val isComplete = index >= questionOrder.size
            val page =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(18), dp(20), dp(20))
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            if (darkMode) intArrayOf(Color.rgb(24, 23, 39), Color.rgb(17, 20, 31))
                            else intArrayOf(Color.rgb(248, 247, 251), Color.rgb(248, 247, 251)),
                        )
                }
            val header =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            header.addView(
                button("←") { returnToItems() }
                    .apply {
                        contentDescription = "Ukončit procvičování"
                        minWidth = dp(48)
                        setTextColor(ink)
                        background =
                            rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                    },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) },
            )
            header.addView(
                label(title, 20, ink, true),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            page.addView(header, margin(0, 0, 0, 16))

            if (isComplete) {
                val result =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(22), dp(28), dp(22), dp(28))
                        background =
                            rounded(if (darkMode) Color.rgb(35, 35, 53) else Color.WHITE, 24)
                    }
                result.addView(
                    label("Procvičování dokončeno", 25, ink, true).apply {
                        gravity = Gravity.CENTER
                    },
                    margin(0, 0, 0, 20),
                )
                if (attempt.complete) {
                    result.addView(
                        ProgressRingView(
                            this,
                            attempt.percent,
                            purple,
                            if (darkMode) Color.rgb(58, 53, 77) else Color.rgb(232, 226, 248),
                        ),
                        LinearLayout.LayoutParams(dp(152), dp(152)).apply { bottomMargin = dp(16) },
                    )
                } else {
                    result.addView(
                        label("${attempt.answered}/${identity.size}", 42, purple, true),
                        margin(0, 0, 0, 12),
                    )
                    result.addView(
                        label("Přeskočené otázky můžeš doplnit při pokračování.", 15, muted, false),
                        margin(0, 0, 0, 12),
                    )
                }
                result.addView(
                    label("Toto kolo: $score / ${questionOrder.size} správně", 16, muted, false),
                    margin(0, 0, 0, 20),
                )
                val remaining = attempt.answers.indices.filter { attempt.answers[it] != 1 }
                if (remaining.isNotEmpty()) {
                    result.addView(
                        button("Zopakovat chybné a přeskočené (${remaining.size})") {
                            questionOrder = remaining
                            failedQuestions.clear()
                            index = 0
                            score = 0
                            saveProgress(0)
                            showQuestion()
                        },
                        margin(0, 0, 0, 10),
                    )
                }
                result.addView(
                    button("Zkusit znovu") {
                        questionOrder =
                            (0 until questions.length()).filter {
                                questions.optJSONObject(it) != null
                            }
                        if (quiz.optBoolean("shuffle")) questionOrder = questionOrder.shuffled()
                        failedQuestions.clear()
                        index = 0
                        score = 0
                        attempt.answers.indices.forEach { attempt.answers[it] = -1 }
                        saveProgress(0)
                        showQuestion()
                    },
                )
                result.addView(button("Vybrat další kvíz") { returnToItems() }, margin(0, 10, 0, 0))
                page.addView(
                    ScrollView(this).apply {
                        isFillViewport = true
                        addView(result)
                    },
                    LinearLayout.LayoutParams(-1, 0, 1f),
                )
                setContentView(page)
                return
            }

            page.addView(
                label("Otázka ${index + 1} z ${questionOrder.size}", 14, muted, true),
                margin(0, 0, 0, 8),
            )
            page.addView(
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = questionOrder.size
                    progress = index
                    progressTintList = android.content.res.ColorStateList.valueOf(purple)
                    progressBackgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            if (darkMode) Color.rgb(55, 53, 72) else Color.rgb(221, 218, 234),
                        )
                },
                LinearLayout.LayoutParams(-1, dp(7)).apply { bottomMargin = dp(16) },
            )
            val questionIndex = questionOrder[index]
            val question = questions.optJSONObject(questionIndex)
            if (question == null) {
                index++
                showQuestion()
                return
            }
            val kind = question.optString("type").let { if (it == "binary") "choice" else it }
            val prompt =
                if (kind == "punctuation") question.optString("sentence")
                else question.optString("prompt").ifBlank { "Otázka" }
            val body =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(20), dp(18), dp(20))
                    background = rounded(if (darkMode) Color.rgb(35, 35, 53) else Color.WHITE, 22)
                }
            body.addView(
                label("", 21, ink, true).apply { richText.setText(this, prompt) },
                margin(0, 0, 0, 18),
            )
            val bodyScroll =
                ScrollView(this).apply {
                    isFillViewport = true
                    addView(body)
                }
            QuestionImageView.create(this, question)?.let { image ->
                body.addView(image, margin(0, 0, 0, 18))
            }
            page.addView(bodyScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            val footer =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            var answered = false
            var mapQuestion: MapQuestionView? = null
            fun showFeedback(correct: Boolean) {
                if (answered) return
                answered = true
                currentFocus?.windowToken?.let { token ->
                    (getSystemService(INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(token, 0)
                }
                if (correct) score++ else failedQuestions.add(questionIndex)
                attempt.answers[questionIndex] = if (correct) 1 else 0
                saveProgress(index + 1)
                fun disable(view: View) {
                    view.isEnabled = false
                    if (view is ViewGroup)
                        for (i in 0 until view.childCount) disable(view.getChildAt(i))
                }
                disable(body)
                mapQuestion?.review()
                footer.removeAllViews()
                val correctAnswer =
                    if (kind == "map") {
                        MapQuestionView.solution(this, question)
                    } else if (kind == "fill") {
                        FillInQuestionView.solution(question)
                    } else if (kind == "choice") {
                        question
                            .optJSONArray("options")
                            ?.optString(question.optInt("answer", -1))
                            .orEmpty()
                    } else {
                        question.optJSONArray("answers")?.let { answers ->
                            (0 until answers.length()).joinToString(" / ") { answers.optString(it) }
                        } ?: question.optString("answer")
                    }
                val feedback =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        background =
                            rounded(
                                if (darkMode) Color.rgb(40, 39, 56) else Color.rgb(246, 245, 250),
                                16,
                            )
                    }
                feedback.addView(
                    label(
                        if (kind == "flashcard") {
                            if (correct) "✓ Umím" else "Ještě procvičit"
                        } else if (correct) "✓ Správně" else "Zkusíme to příště",
                        18,
                        if (correct)
                            (if (darkMode) Color.rgb(126, 220, 169) else Color.rgb(31, 126, 80))
                        else ink,
                        true,
                    ),
                )
                if (!correct && kind != "flashcard")
                    feedback.addView(
                        label("", 16, ink, false).apply {
                            richText.setText(this, "Správná odpověď: $correctAnswer")
                        },
                        margin(0, 8, 0, 0),
                    )
                question
                    .optString("explanation")
                    .takeIf { it.isNotBlank() }
                    ?.let { explanation ->
                        feedback.addView(
                            label("", 15, muted, false).apply {
                                richText.setText(this, explanation)
                            },
                            margin(0, 8, 0, 0),
                        )
                    }
                body.addView(feedback, margin(0, 16, 0, 0))
                footer.addView(
                    button(
                        if (index + 1 == questionOrder.size) "Zobrazit výsledek"
                        else "Další otázka",
                    ) {
                        index++
                        showQuestion()
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                bodyScroll.post { bodyScroll.fullScroll(View.FOCUS_DOWN) }
            }
            if (kind == "flashcard") {
                val answer = question.opt("answer") as? String
                if (
                    question.opt("prompt") !is String ||
                        question.optString("prompt").isBlank() ||
                        answer.isNullOrBlank()
                ) {
                    body.addView(label("Neplatná kartička. Můžeš ji přeskočit.", 15, muted, false))
                } else {
                    body.addView(
                        label("Vybav si odpověď a potom kartičku otoč.", 15, muted, false),
                        margin(0, 0, 0, 14),
                    )
                    val back =
                        label("", 21, ink, false).apply {
                            richText.setText(this, answer)
                            visibility = View.GONE
                            setPadding(dp(16), dp(18), dp(16), dp(18))
                            background =
                                rounded(
                                    if (darkMode) Color.rgb(45, 44, 63)
                                    else Color.rgb(244, 243, 249),
                                    16,
                                )
                        }
                    body.addView(back, margin(0, 0, 0, 14))
                    val ratings =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            visibility = View.GONE
                        }
                    ratings.addView(button("Umím") { showFeedback(true) })
                    ratings.addView(
                        button("Ještě procvičit") { showFeedback(false) },
                        margin(0, 8, 0, 0),
                    )
                    val reveal = button("Otočit kartičku") {}
                    reveal.setOnClickListener {
                        reveal.visibility = View.GONE
                        back.visibility = View.VISIBLE
                        ratings.visibility = View.VISIBLE
                        back.announceForAccessibility(back.text)
                    }
                    body.addView(reveal)
                    body.addView(ratings)
                }
            } else if (kind == "choice") {
                val options = question.optJSONArray("options") ?: JSONArray()
                for (selected in 0 until options.length()) {
                    body.addView(
                        button("") {
                                showFeedback(selected == question.optInt("answer", -1))
                            }
                            .apply {
                                setTextColor(ink)
                                richText.setText(this, options.optString(selected))
                                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                                setPadding(dp(16), dp(12), dp(16), dp(12))
                                minHeight = dp(54)
                                setTextColor(ink)
                                background =
                                    rounded(
                                        if (darkMode) Color.rgb(45, 44, 63)
                                        else Color.rgb(244, 243, 249),
                                        16,
                                    )
                            },
                        margin(0, 0, 0, 10),
                    )
                }
            } else if (kind == "map") {
                if (MapQuestionView.valid(this, question)) {
                    val fields = MapQuestionView(this, question, ink, muted, darkMode)
                    mapQuestion = fields
                    body.addView(fields, margin(0, 0, 0, 14))
                    footer.addView(
                        button("Vyhodnotit") {
                            if (!fields.complete()) toast("Vyber oblast nebo umísti špendlík.")
                            else showFeedback(fields.correct())
                        },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
                    )
                } else {
                    body.addView(
                        label(
                            "Neplatné zadání mapové otázky. Můžeš ji přeskočit.",
                            15,
                            muted,
                            false,
                        ),
                    )
                }
            } else if (kind == "fill") {
                if (FillInQuestionView.valid(question)) {
                    val fields =
                        FillInQuestionView(
                            this,
                            question,
                            ink,
                            muted,
                            if (darkMode) Color.rgb(45, 44, 63) else Color.rgb(246, 245, 250),
                            muted,
                        )
                    body.addView(fields, margin(0, 0, 0, 14))
                    footer.addView(
                        button("Vyhodnotit") {
                            if (!fields.complete()) toast("Doplň všechna políčka.")
                            else showFeedback(fields.correct())
                        },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
                    )
                } else {
                    body.addView(
                        label(
                            "Neplatné zadání doplňovací otázky. Můžeš ji přeskočit.",
                            15,
                            muted,
                            false,
                        ),
                    )
                }
            } else {
                val field =
                    EditText(this).apply {
                        hint = "Napiš odpověď…"
                        textSize = 16f
                        setTextColor(ink)
                        setHintTextColor(muted)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background =
                            rounded(if (darkMode) Color.rgb(35, 35, 53) else Color.WHITE, 14)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        if (kind == "punctuation") setText(question.optString("sentence"))
                    }
                body.addView(field, margin(0, 0, 0, 14))
                footer.addView(
                    button("Vyhodnotit") {
                        val answers =
                            question.optJSONArray("answers")
                                ?: JSONArray().put(question.optString("answer"))
                        val input = field.text.toString().trim()
                        if (input.isBlank()) {
                            field.error = "Napiš odpověď"
                            return@button
                        }
                        val accepted =
                            (0 until answers.length()).any { answerIndex ->
                                fun normalized(value: String): String {
                                    var result = value.trim()
                                    if (question.optBoolean("ignoreCase", false))
                                        result = result.lowercase(Locale.ROOT)
                                    if (kind == "punctuation")
                                        result =
                                            result
                                                .replace(Regex("\\s+"), " ")
                                                .replace(Regex("\\s*,\\s*"), ",")
                                    return result
                                }
                                normalized(input) == normalized(answers.optString(answerIndex))
                            }
                        showFeedback(accepted)
                    },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
                )
            }
            footer.addView(
                button("Přeskočit") {
                        failedQuestions.add(questionIndex)
                        attempt.answers[questionIndex] = 2
                        saveProgress(index + 1)
                        index++
                        showQuestion()
                    }
                    .apply {
                        setTextColor(ink)
                        background =
                            rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                    },
                LinearLayout.LayoutParams(
                    if (kind in setOf("choice", "flashcard")) -1 else 0,
                    -2,
                    if (kind in setOf("choice", "flashcard")) 0f else 1f,
                ),
            )
            page.addView(footer, margin(0, 14, 0, 0))
            setContentView(page)
        }
        showQuestion()
    }

    private fun showWorksheetTemplate() {
        if (!maintainerMode) return
        val target = solutionInput ?: return
        val (dialog, content) = customDialog("Pracovní sešit")
        val page = input("Stránka, např. 24", false)
        val exercises = input("Čísla cvičení, např. 1, 2, 3", false)
        content.addView(page, margin(0, 0, 0, 8))
        content.addView(exercises, margin(0, 0, 0, 10))
        content.addView(
            label(
                "Pro každé cvičení vloží tučný nadpis a po jednom prázdném řádku místo pro řešení. Dosavadní text zůstane zachovaný.",
                14,
                muted,
                false,
            ),
        )
        content.addView(
            button("Vložit šablonu") {
                val pageValue = page.text.toString().trim()
                val exerciseValues =
                    exercises.text
                        .toString()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                if (!pageValue.matches(Regex("[0-9]+"))) {
                    page.error = "Zadej číslo stránky"
                    return@button
                }
                if (
                    exerciseValues.isEmpty() || exerciseValues.any { !it.matches(Regex("[0-9]+")) }
                ) {
                    exercises.error = "Zadej čísla cvičení oddělená čárkou"
                    return@button
                }
                val template = WorksheetSolutionTemplate.create(pageValue, exerciseValues)
                val existing = target.text.toString()
                val separator = if (existing.isBlank()) "" else "\n\n"
                val prefix = existing.trimEnd() + separator
                target.setText(prefix + template)
                val firstAnswer = prefix.length + template.indexOf("\n\n") + 2
                target.requestFocus()
                target.setSelection(
                    firstAnswer,
                    firstAnswer + WorksheetSolutionTemplate.PLACEHOLDER.length,
                )
                dialog.dismiss()
                (getSystemService(INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(
                        target,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
                    )
            },
            margin(0, 14, 0, 0),
        )
        (content.parent as? ViewGroup)?.removeView(content)
        dialog.setContentView(ScrollView(this).apply { addView(content) })
        showCustomDialog(dialog, page)
    }

    private fun customDialog(title: String): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(20), dp(22), dp(20))
                background =
                    rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(label(title, 20, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            button("✕") { dialog.dismiss() }
                .apply {
                    minWidth = dp(42)
                    minHeight = dp(42)
                    setPadding(0, 0, 0, 0)
                },
            LinearLayout.LayoutParams(dp(42), dp(42)),
        )
        content.addView(header)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        return dialog to content
    }

    private fun showCustomDialog(dialog: Dialog, focusView: View? = null) {
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        focusView?.let { view ->
            view.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
        }
    }

    private fun itemType(item: JSONObject): String = DatabaseRecords.type(item)

    private fun itemKey(item: JSONObject): String = DatabaseRecords.key(item)

    private fun doneItems(): MutableSet<String> =
        getSharedPreferences(ITEM_PREFS, MODE_PRIVATE)
            .getStringSet(DONE_ITEMS_KEY, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

    private fun renderAiReviewEditor() {
        val editor = aiReviewEditor ?: return
        editor.removeAllViews()
        editor.addView(label("AI kontrola · označí maintainer", 14, ink, true))
        aiReviewNames.forEach { (key, name) ->
            editor.addView(
                button("${if (key in selectedAiReviews) "✓" else "○"} $name") {
                        if (!selectedAiReviews.add(key)) selectedAiReviews.remove(key)
                        renderAiReviewEditor()
                    }
                    .apply {
                        contentDescription =
                            "$name · ${if (key in selectedAiReviews) "označeno" else "neoznačeno"}"
                    },
                margin(0, 4, 0, 0),
            )
        }
    }

    private fun startNewItem() {
        selectedAiReviews.clear()
        renderAiReviewEditor()
        editingItemIndex = null
        originalQuizPrompt = ""
        originalQuizAnswers = ""
        originalQuizIsSimpleText = false
        titleInput?.text?.clear()
        descriptionInput?.text?.clear()
        solutionInput?.text?.clear()
        quizPromptInput?.text?.clear()
        quizAnswersInput?.text?.clear()
        quizPromptInput?.isEnabled = true
        quizAnswersInput?.isEnabled = true
        clearQuizCheckBox?.isChecked = false
        clearQuizCheckBox?.visibility = View.GONE
        dateValue = today()
        timeValue = ""
        timeButton?.text = "Celý den"
        dateButton?.text = "Datum · $dateValue"
        addButton?.text = "Přidat položku"
        selectType("task")
        form?.visibility = View.VISIBLE
        statusView?.text = "Vyplň novou položku."
    }

    private fun startEditingItem(index: Int) {
        if (!maintainerMode) return
        val tasks = database?.optJSONArray("tasks") ?: return
        val item = tasks.optJSONObject(index) ?: return
        editingItemIndex = index
        selectType(itemType(item))
        titleInput?.setText(item.optString("name"))
        dateValue = item.optString("date").ifBlank { today() }
        timeValue = DatabaseRecords.time(item)
        timeButton?.text = timeValue.ifBlank { "Celý den" }
        dateButton?.text = "Datum · $dateValue"
        descriptionInput?.setText(item.optString("task"))
        solutionInput?.setText(item.optString("solution"))
        selectedAiReviews.clear()
        val reviews = item.optJSONArray("aiReviews")
        aiReviewNames.keys.filterTo(selectedAiReviews) { key ->
            reviews != null && (0 until reviews.length()).any { reviews.optString(it) == key }
        }
        renderAiReviewEditor()

        val firstQuestion = item.optJSONObject("quiz")?.optJSONArray("questions")?.optJSONObject(0)
        val simpleTextQuestion = firstQuestion?.optString("type") == "text"
        originalQuizIsSimpleText = simpleTextQuestion
        originalQuizPrompt = if (simpleTextQuestion) firstQuestion.optString("prompt") else ""
        originalQuizAnswers =
            if (simpleTextQuestion) {
                firstQuestion
                    .optJSONArray("answers")
                    ?.let { answers ->
                        (0 until answers.length()).joinToString(" | ") { answers.optString(it) }
                    }
                    .orEmpty()
            } else ""
        quizPromptInput?.setText(originalQuizPrompt)
        quizAnswersInput?.setText(originalQuizAnswers)
        quizPromptInput?.isEnabled = !item.has("quiz") || simpleTextQuestion
        quizAnswersInput?.isEnabled = !item.has("quiz") || simpleTextQuestion
        clearQuizCheckBox?.text =
            if (item.has("quiz") && !simpleTextQuestion) {
                "Nahradit nebo odebrat rozšířené procvičování"
            } else "Odebrat procvičování"
        clearQuizCheckBox?.isChecked = false
        clearQuizCheckBox?.visibility = if (item.has("quiz")) View.VISIBLE else View.GONE
        addButton?.text = "Uložit změny"
        form?.visibility = View.VISIBLE
        statusView?.text =
            if (item.has("quiz") && !simpleTextQuestion) {
                "Uprav položku. Rozšířené procvičování můžeš zachovat nebo odebrat."
            } else {
                "Uprav položku a ulož změny."
            }
        titleInput?.requestFocus()
    }

    private fun confirmDeleteItem(index: Int) {
        if (!maintainerMode) return
        val tasks = database?.optJSONArray("tasks") ?: return
        val item = tasks.optJSONObject(index) ?: return
        val (dialog, content) = customDialog("Smazat položku?")
        content.addView(
            label(item.optString("name").ifBlank { "Bez názvu" }, 15, muted, false),
            margin(0, 12, 0, 18),
        )
        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        actions.addView(
            button("Zrušit") { dialog.dismiss() }
                .apply {
                    setTextColor(ink)
                    background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
        )
        actions.addView(
            button("Smazat") {
                    dialog.dismiss()
                    val done = doneItems().apply { remove(itemKey(item)) }
                    getSharedPreferences(ITEM_PREFS, MODE_PRIVATE)
                        .edit()
                        .putStringSet(DONE_ITEMS_KEY, done)
                        .apply()
                    tasks.remove(index)
                    statusView?.text = "Položka smazána · ulož změny pushnutím na origin."
                    renderDatabase()
                    persistLocalDatabase()
                    toast("Položka smazána")
                }
                .apply { background = rounded(Color.rgb(183, 54, 66), 14) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        content.addView(actions)
        showCustomDialog(dialog)
    }

    private fun addItem() {
        if (!maintainerMode)
            return toast("Pro přidávání se nejdřív propoj s GitHubem tlačítkem 🔧.")
        if (editingItemIndex != null) {
            saveEditedItem(editingItemIndex!!)
            return
        }
        val db = database ?: return toast("Nejdřív otevři databázi.")
        val name = titleInput?.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            titleInput?.error = "Zadej název"
            return
        }
        val parsedDate = parseDate(dateValue) ?: return toast("Vyber platné datum.")
        val item =
            JSONObject()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("type", selectedType)
                .put("name", name)
                .put("date", SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(parsedDate))
                .put("task", descriptionInput?.text?.toString()?.trim().orEmpty())
                .put(
                    "solution",
                    if (selectedType == "task") solutionInput?.text?.toString()?.trim().orEmpty()
                    else "",
                )
        if (selectedType == "task") item.put("aiReviews", JSONArray(selectedAiReviews.toList()))
        if (timeValue.isNotBlank()) item.put("time", timeValue)
        if (selectedType == "test") {
            val prompt = quizPromptInput?.text?.toString()?.trim().orEmpty()
            val answers =
                quizAnswersInput
                    ?.text
                    ?.toString()
                    .orEmpty()
                    .split('|')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            if (prompt.isNotEmpty() != answers.isNotEmpty())
                return toast("Vyplň otázku i alespoň jednu správnou odpověď.")
            if (prompt.isNotEmpty()) {
                item.put(
                    "quiz",
                    JSONObject()
                        .put("shuffle", false)
                        .put(
                            "questions",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("prompt", prompt)
                                        .put("answers", JSONArray(answers))
                                        .put("ignoreCase", true),
                                ),
                        ),
                )
            }
        }
        clearQuizCheckBox?.isChecked = false
        db.getJSONArray("tasks").put(item)
        selectedAiReviews.clear()
        renderAiReviewEditor()
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

    private fun saveEditedItem(index: Int) {
        val db = database ?: return
        val tasks = db.optJSONArray("tasks") ?: return
        val original = tasks.optJSONObject(index) ?: return toast("Položka už v databázi není.")
        val name = titleInput?.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            titleInput?.error = "Zadej název"
            return
        }
        val parsedDate = parseDate(dateValue) ?: return toast("Vyber platné datum.")
        val updated =
            JSONObject(original.toString())
                .put(
                    "id",
                    original.optString("id").takeIf { it.isNotBlank() }
                        ?: java.util.UUID.randomUUID().toString(),
                )
                .put("type", selectedType)
                .put("name", name)
                .put("date", SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(parsedDate))
                .put("task", descriptionInput?.text?.toString()?.trim().orEmpty())
        if (timeValue.isBlank()) updated.remove("time") else updated.put("time", timeValue)
        if (selectedType == "task") {
            updated.put("solution", solutionInput?.text?.toString()?.trim().orEmpty())
            updated.put("aiReviews", JSONArray(selectedAiReviews.toList()))
        } else {
            updated.remove("aiReviews")
        }

        val prompt = quizPromptInput?.text?.toString()?.trim().orEmpty()
        val answers =
            quizAnswersInput
                ?.text
                ?.toString()
                .orEmpty()
                .split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val quizChanged =
            prompt != originalQuizPrompt || answers.joinToString(" | ") != originalQuizAnswers
        val removeOrReplaceQuiz = clearQuizCheckBox?.isChecked == true
        val advancedQuiz = original.has("quiz") && !originalQuizIsSimpleText
        if (removeOrReplaceQuiz && (!advancedQuiz || prompt.isBlank() && answers.isEmpty())) {
            updated.remove("quiz")
        } else if (removeOrReplaceQuiz || quizChanged) {
            if (prompt.isBlank() || answers.isEmpty()) {
                val oldQuestions = original.optJSONObject("quiz")?.optJSONArray("questions")
                if (
                    originalQuizIsSimpleText &&
                        oldQuestions?.length() == 1 &&
                        prompt.isBlank() &&
                        answers.isEmpty()
                ) {
                    updated.remove("quiz")
                } else {
                    return toast(
                        "Vyplň otázku i alespoň jednu správnou odpověď, nebo zaškrtni odebrání procvičování.",
                    )
                }
            } else if (originalQuizIsSimpleText && !removeOrReplaceQuiz) {
                val quiz = updated.optJSONObject("quiz") ?: JSONObject().put("shuffle", false)
                val questions = quiz.optJSONArray("questions") ?: JSONArray()
                val firstQuestion =
                    questions.optJSONObject(0)?.let { JSONObject(it.toString()) } ?: JSONObject()
                firstQuestion
                    .put("type", "text")
                    .put("prompt", prompt)
                    .put("answers", JSONArray(answers))
                if (!firstQuestion.has("ignoreCase")) firstQuestion.put("ignoreCase", true)
                if (questions.length() == 0) questions.put(firstQuestion)
                else questions.put(0, firstQuestion)
                quiz.put("questions", questions)
                updated.put("quiz", quiz)
            } else if (original.has("quiz") && !removeOrReplaceQuiz) {
                return toast(
                    "Rozšířené procvičování můžeš zachovat, nebo zaškrtnutím nahradit jednoduchou otázkou.",
                )
            } else {
                updated.put(
                    "quiz",
                    JSONObject()
                        .put("shuffle", false)
                        .put(
                            "questions",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("prompt", prompt)
                                        .put("answers", JSONArray(answers))
                                        .put("ignoreCase", true),
                                ),
                        ),
                )
            }
        }
        if (updated.toString() == original.toString()) {
            statusView?.text = "Nebyla provedena žádná změna."
            form?.visibility = View.GONE
            editingItemIndex = null
            return
        }
        tasks.put(index, updated)
        editingItemIndex = null
        clearQuizCheckBox?.isChecked = false
        clearQuizCheckBox?.visibility = View.GONE
        addButton?.text = "Přidat položku"
        form?.visibility = View.GONE
        statusView?.text = "Položka upravena · ulož změny pushnutím na origin."
        renderDatabase()
        persistLocalDatabase()
        toast("Změny uloženy")
    }

    private fun chooseDestination() {
        if (database == null) return toast("Nejdřív otevři databázi.")
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "data.enc.json")
            }
        createDocument.launch(intent)
    }

    private fun pushToOrigin() {
        if (database == null || password == null) return toast("Nejdřív otevři a odemkni databázi.")
        if (!isDatabaseDirty()) return toast("Zatím nejsou žádné nové položky.")
        runGithubTask(freezeForm = true) { token ->
            var activeStep = 0
            runOnUiThread {
                pushProgressDialog?.dismiss()
                pushProgressDialog =
                    OperationProgressDialog(
                            this,
                            "Odesílání databáze",
                            listOf(
                                "Ověřuji oprávnění GitHubu",
                                "Kontroluji aktuální databázi na originu",
                                "Šifruji změny",
                                "Odesílám databázi na origin",
                                "Ukládám místní kopii",
                            ),
                            darkMode,
                            purple,
                        )
                        .also { it.step(0) }
            }
            try {
                GitHubApiClient.verifyWriteAccess(GITHUB_OWNER, GITHUB_REPO, token)
                activeStep = 1
                runOnUiThread { pushProgressDialog?.step(1) }
                val path =
                    "repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json?ref=$GITHUB_BRANCH"
                val current = JSONObject(githubRequest(path, token = token))
                val remoteEnvelope =
                    JSONObject(
                        GitHubApiClient.databaseContent(GITHUB_OWNER, GITHUB_REPO, current, token),
                    )
                val remotePlain = decrypt(remoteEnvelope, password ?: error("Databáze je zamčená."))
                val remoteDatabase = JSONObject(String(remotePlain, StandardCharsets.UTF_8))
                if (databaseHash(remoteDatabase) != originalDatabaseHash) {
                    throw IOException(
                        "Databáze na origin se od načtení změnila. Načti nejnovější data a zkus znovu.",
                    )
                }
                sourceEnvelope = remoteEnvelope
                activeStep = 2
                runOnUiThread { pushProgressDialog?.step(2) }
                val updatedEnvelope = JSONObject(encryptedOutput())
                val commitMessage =
                    "[app skip] Update DB (${updatedEnvelope.getString("dbVersion")})"
                val payload =
                    JSONObject()
                        .put("message", commitMessage)
                        .put(
                            "content",
                            Base64.getEncoder()
                                .encodeToString(
                                    updatedEnvelope
                                        .toString(2)
                                        .toByteArray(StandardCharsets.UTF_8) + byteArrayOf(10),
                                ),
                        )
                        .put("sha", current.getString("sha"))
                        .put("branch", GITHUB_BRANCH)
                activeStep = 3
                runOnUiThread { pushProgressDialog?.step(3) }
                githubRequest(
                    "repos/$GITHUB_OWNER/$GITHUB_REPO/contents/data.enc.json",
                    "PUT",
                    token,
                    payload.toString(),
                )
                activeStep = 4
                runOnUiThread { pushProgressDialog?.step(4) }
                sourceEnvelope = updatedEnvelope
                originalDatabaseHash = databaseHash(database!!)
                getSharedPreferences(DB_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(DB_BASELINE_HASH, originalDatabaseHash)
                    .apply()
                saveLocalDatabase(updatedEnvelope.toString(2) + "\n")
                runOnUiThread {
                    statusView?.text = "Push hotov · $commitMessage"
                    addButton?.isEnabled = true
                    toast("Databáze byla odeslána na origin.")
                    pushProgressDialog?.finish(
                        "Databáze byla odeslána · ${updatedEnvelope.getString("dbVersion")}",
                    )
                }
            } catch (e: Exception) {
                val failedStep = activeStep
                runOnUiThread {
                    val prefix =
                        if (failedStep == 4) "DB je na originu, ale místní uložení selhalo: "
                        else "Odeslání selhalo: "
                    pushProgressDialog?.finish(prefix + (e.message ?: "Neznámá chyba."), failedStep)
                }
                throw e
            }
        }
    }

    private fun githubRequest(
        path: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
    ): String = GitHubApiClient.request(path, method, token, body)

    private fun runGithubTask(freezeForm: Boolean = false, task: (String) -> Unit) {
        val clientId = BuildConfig.GITHUB_APP_CLIENT_ID
        if (clientId.isBlank()) {
            toast(
                "GitHub App Client ID není nastaven. Nastav GH_APP_CLIENT_ID v Actions Variables a sestav aplikaci znovu.",
            )
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
                statusView?.text =
                    "Uložené přihlášení nejde na tomto zařízení přečíst. Přihlas se znovu."
            }
            startDeviceLogin(clientId, freezeForm, task)
        }
    }

    private fun executeGithubTask(
        credentials: GitHubCredentials,
        freezeForm: Boolean,
        task: (String) -> Unit,
    ) {
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
                    if (
                        e.message?.contains("HTTP 403") == true ||
                            e.message?.contains("GitHub App") == true
                    ) {
                        val (help, content) = customDialog("GitHub odmítl přístup")
                        content.addView(
                            label(e.message ?: "Zkontroluj oprávnění GitHub App.", 14, ink, false),
                            margin(0, 10, 0, 16),
                        )
                        content.addView(
                            button("Otevřít instalace GitHub App") {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/settings/installations"),
                                    ),
                                )
                            },
                        )
                        content.addView(button("Zavřít") { help.dismiss() }, margin(0, 10, 0, 0))
                        showCustomDialog(help)
                    }
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

    private fun showGithubLoginProgress(cancelled: AtomicBoolean): Dialog {
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(20))
                background =
                    rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(
            label("GitHub · přihlášení", 21, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val closeButton =
            button("✕") {
                    cancelled.set(true)
                    githubLoginDialog?.dismiss()
                }
                .apply {
                    textSize = 17f
                    minWidth = dp(42)
                    minHeight = dp(42)
                    background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                    setTextColor(ink)
                }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(header)
        content.addView(label("Propoj účet s repozitářem", 13, muted, false), margin(0, 5, 0, 18))

        val progressHeader =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        progressHeader.addView(
            ProgressBar(this).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) },
        )
        githubLoginStatusView = label("Připravuji přihlášení…", 14, muted, false)
        progressHeader.addView(githubLoginStatusView, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(progressHeader, margin(0, 0, 0, 12))

        githubLoginStepViews = mutableListOf()
        listOf(
                "Získání přihlašovacího kódu",
                "Potvrzení v GitHubu",
                "Ověření účtu",
                "Kontrola oprávnění repozitáře",
                "Dokončení propojení",
            )
            .forEach { title ->
                val row = label("○  $title", 14, muted, false)
                githubLoginStepViews += row
                content.addView(row, margin(0, 5, 0, 5))
            }
        githubLoginCodeView =
            label("Kód se načte za chvíli…", 20, ink, true).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
            }
        githubLoginCodeView?.background =
            rounded(if (darkMode) Color.rgb(37, 37, 55) else Color.WHITE, 16)
        githubLoginCodeView?.setPadding(dp(12), dp(14), dp(12), dp(14))
        content.addView(githubLoginCodeView, margin(0, 14, 0, 4))
        content.addView(
            label("Zadej ho pouze na github.com.", 12, muted, false).apply {
                gravity = Gravity.CENTER
            },
        )
        content.addView(
            label("Verze ${BuildConfig.VERSION_NAME}", 12, muted, false).apply {
                gravity = Gravity.CENTER
            },
            margin(0, 14, 0, 0),
        )

        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        githubLoginCancelButton =
            button("Zrušit") {
                    cancelled.set(true)
                    githubLoginDialog?.dismiss()
                }
                .apply {
                    background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 16)
                    setTextColor(ink)
                }
        githubLoginOpenButton =
            button("Otevřít GitHub") {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/login/device"),
                            ),
                        )
                    } catch (e: Exception) {
                        failGithubLoginDialog(e.message ?: "GitHub se nepodařilo otevřít.")
                    }
                }
                .apply {
                    isEnabled = false
                    alpha = 1f
                    setTextColor(Color.WHITE)
                    background =
                        rounded(
                            if (darkMode) Color.rgb(92, 67, 164) else Color.rgb(77, 49, 157),
                            16,
                        )
                }
        actions.addView(
            githubLoginCancelButton,
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
        )
        actions.addView(githubLoginOpenButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions, margin(0, 18, 0, 0))

        val dialog = Dialog(this)
        githubLoginDialog = dialog
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener {
            cancelled.set(true)
            clearGithubLoginDialog()
        }
        dialog.setOnDismissListener { if (githubLoginDialog === dialog) clearGithubLoginDialog() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setGithubLoginStep(0, "RUNNING")
        return dialog
    }

    private fun setGithubLoginStep(index: Int, state: String) {
        runOnUiThread {
            val row = githubLoginStepViews.getOrNull(index) ?: return@runOnUiThread
            val title = row.text.toString().substringAfter("  ")
            val (icon, color) =
                when (state) {
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
            dialog
                ?.window
                ?.decorView
                ?.postDelayed(
                    {
                        if (githubLoginDialog === dialog) {
                            dialog.dismiss()
                            clearGithubLoginDialog()
                        }
                    },
                    900,
                )
        }
    }

    private fun failGithubLoginDialog(message: String) {
        if (githubLoginDialog == null) return
        val current = githubLoginStepViews.indexOfFirst { it.currentTextColor == purple }
        if (current >= 0) setGithubLoginStep(current, "FAILED")
        githubLoginStatusView?.text = message
        githubLoginOpenButton?.text = "Zavřít"
        githubLoginOpenButton?.isEnabled = true
        githubLoginOpenButton?.alpha = 1f
        githubLoginOpenButton?.setOnClickListener { githubLoginDialog?.dismiss() }
        githubLoginCancelButton?.visibility = View.GONE
    }

    private fun clearGithubLoginDialog() {
        githubLoginDialog = null
        githubLoginStatusView = null
        githubLoginCodeView = null
        githubLoginOpenButton = null
        githubLoginCancelButton = null
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
                val response =
                    oauthRequest(
                        "https://github.com/login/device/code",
                        mapOf("client_id" to clientId, "scope" to "offline_access"),
                    )
                val deviceCode = response.getString("device_code")
                val userCode = response.getString("user_code")
                val verificationUri =
                    response.optString("verification_uri", "https://github.com/login/device")
                require(verificationUri == "https://github.com/login/device") {
                    "GitHub vrátil neplatnou adresu přihlášení."
                }
                val expiresAt =
                    System.currentTimeMillis() + response.optLong("expires_in", 900) * 1000
                runOnUiThread {
                    githubLoginCodeView?.text = userCode
                    githubLoginOpenButton?.isEnabled = true
                    githubLoginOpenButton?.alpha = 1f
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
                                val poll =
                                    try {
                                        oauthRequest(
                                            "https://github.com/login/oauth/access_token",
                                            mapOf(
                                                "client_id" to clientId,
                                                "device_code" to deviceCode,
                                                "grant_type" to
                                                    "urn:ietf:params:oauth:grant-type:device_code",
                                            ),
                                            allowOAuthError = true,
                                        )
                                    } catch (e: IOException) {
                                        // Device authorization can succeed in the browser while
                                        // the app briefly loses DNS/network access. Keep polling
                                        // so an approved code is not thrown away on one failed
                                        // request.
                                        if (e.message?.startsWith("GitHub OAuth HTTP") == true)
                                            throw e
                                        Thread.sleep(interval * 1000)
                                        continue
                                    }
                                when (poll.optString("error")) {
                                    "authorization_pending" -> continue
                                    "slow_down" -> {
                                        interval += 5
                                        continue
                                    }
                                    "" -> {
                                        authorized = credentialsFrom(poll)
                                        break
                                    }
                                    "access_denied" ->
                                        throw IOException("Přihlášení přes GitHub bylo zamítnuto.")
                                    "expired_token",
                                    "token_expired" ->
                                        throw IOException(
                                            "Kód pro přihlášení vypršel. Zkus to znovu.",
                                        )
                                    else ->
                                        throw IOException(
                                            "GitHub přihlášení selhalo: ${poll.optString("error_description", poll.optString("error"))}",
                                        )
                                }
                            }
                            if (cancelled.get()) throw IOException("Přihlášení bylo zrušeno.")
                            val credentials =
                                authorized
                                    ?: throw IOException(
                                        "Kód pro přihlášení vypršel. Zkus to znovu.",
                                    )
                            setGithubLoginStep(1, "SUCCESS")
                            setGithubLoginStep(2, "RUNNING")
                            saveGithubCredentials(credentials)
                            executeGithubTask(credentials, freezeForm, task)
                        } catch (e: Exception) {
                            runOnUiThread {
                                failGithubLoginDialog(
                                    e.message ?: "Přihlášení přes GitHub selhalo.",
                                )
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

    private fun oauthRequest(
        endpoint: String,
        fields: Map<String, String>,
        allowOAuthError: Boolean = false,
    ): JSONObject = GitHubApiClient.oauthRequest(endpoint, fields, allowOAuthError)

    private fun credentialsFrom(json: JSONObject, oldRefreshToken: String = ""): GitHubCredentials =
        GitHubApiClient.credentialsFrom(json, oldRefreshToken)

    private fun validCredentials(credentials: GitHubCredentials): GitHubCredentials {
        if (credentials.expiresAt > System.currentTimeMillis() + 60_000) return credentials
        val refreshed =
            try {
                oauthRequest(
                    "https://github.com/login/oauth/access_token",
                    mapOf(
                        "client_id" to BuildConfig.GITHUB_APP_CLIENT_ID,
                        "grant_type" to "refresh_token",
                        "refresh_token" to credentials.refreshToken,
                    ),
                )
            } catch (e: Exception) {
                if (
                    e.message?.contains("bad_refresh_token", ignoreCase = true) == true ||
                        e.message?.contains("invalid_grant", ignoreCase = true) == true
                )
                    clearGithubToken()
                throw e
            }
        return credentialsFrom(refreshed, credentials.refreshToken).also(::saveGithubCredentials)
    }

    private fun setFormEnabled(enabled: Boolean) {
        fun visit(view: View) {
            view.isEnabled = enabled
            if (view is ViewGroup)
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
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
                    statusView?.text =
                        "Uloženo v zařízení · ${data.getJSONArray("tasks").length()} položek. Připraveno k pushnutí."
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

    private fun saveDatabasePassword(value: CharArray) = credentialStore.saveDatabasePassword(value)

    private fun readSavedDatabasePassword(): CharArray? =
        credentialStore.readSavedDatabasePassword()

    private fun forgetDatabasePassword() = credentialStore.forgetDatabasePassword()

    private fun saveGithubCredentials(credentials: GitHubCredentials) =
        credentialStore.saveGithubCredentials(credentials)

    private fun readGithubCredentials(): GitHubCredentials? =
        credentialStore.readGithubCredentials()

    private fun hasStoredGithubCredentials(): Boolean = credentialStore.hasStoredGithubCredentials()

    private fun clearGithubToken() {
        credentialStore.clearGithubCredentials()
        maintainerMode = false
        getSharedPreferences(TOKEN_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("maintainer_mode", false)
            .apply()
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
        updateReleaseNotes = ""
        updateVersion = null
        updateButton?.text = "Zkontrolovat aktualizace aplikace"
        if (!silent) updateButton?.isEnabled = false
        if (!silent) {
            showUpdateProgress(downloadFlow = false)
            setUpdateProgressStep(0, "RUNNING")
        }
        networkWorker.execute {
            try {
                val available = fetchLatestUpdate()
                if (silent && available != null)
                    AppUpdateNotifications.notifyAvailable(this, available.first)
                if (available != null) {
                    updateAsset = available.second
                    updateVersion = available.first
                    updateReleaseNotes = available.third
                }
                runOnUiThread {
                    updateButton?.isEnabled = true
                    if (available == null) {
                        updateButton?.text = "Zkontrolovat aktualizace aplikace"
                        statusView?.text = "Aplikace je aktuální · ${BuildConfig.VERSION_NAME}"
                        if (!silent) {
                            setUpdateProgressStep(0, "SUCCESS")
                            finishUpdateProgress("Používáš aktuální verzi.")
                        }
                    } else {
                        updateButton?.text = "Stáhnout aktualizaci ${available.first}"
                        statusView?.text = "Je dostupná verze ${available.first}."
                        if (!silent) {
                            setUpdateProgressStep(0, "SUCCESS")
                            finishUpdateProgress("Je dostupná verze ${available.first}.")
                            updateProgressDialog?.dismiss()
                            confirmAppUpdate()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateButton?.isEnabled = true
                    statusView?.text = e.message ?: "Kontrola aktualizací se nezdařila."
                    if (!silent)
                        failUpdateProgress(0, e.message ?: "Kontrola aktualizací se nezdařila.")
                }
            }
        }
    }

    private fun fetchLatestUpdate(): Triple<String, JSONObject, String>? {
        val release = JSONObject(githubRequest("repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"))
        return AppUpdateNotifications.available(this, release)
    }

    private fun confirmAppUpdate() {
        val version = updateVersion ?: return
        val (dialog, content) = customDialog("Aktualizace na $version")
        content.addView(
            label("Chceš stáhnout a nainstalovat novou verzi aplikace?", 15, muted, false),
            margin(0, 12, 0, 12),
        )
        content.addView(label("Co je nového", 17, ink, true))
        val notes =
            label("", 15, ink, false).apply {
                richText.setText(
                    this,
                    updateReleaseNotes.ifBlank { "Tato verze nemá na GitHubu uvedený changelog." },
                )
                setTextIsSelectable(true)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setPadding(0, dp(12), 0, dp(12))
            }
        content.addView(
            ScrollView(this).apply { addView(notes) },
            LinearLayout.LayoutParams(
                -1,
                (resources.displayMetrics.heightPixels / 3).coerceAtMost(dp(280)),
            ),
        )
        content.addView(
            button("Aktualizovat na $version") {
                dialog.dismiss()
                downloadUpdate(version)
            },
            margin(0, 12, 0, 8),
        )
        content.addView(button("Později") { dialog.dismiss() })
        showCustomDialog(dialog)
    }

    private fun downloadUpdate(confirmedVersion: String) {
        val saved = readGithubCredentials()
        updateButton?.isEnabled = false
        statusView?.text = "Stahuji aktualizaci…"
        showUpdateProgress(downloadFlow = true)
        setUpdateProgressStep(0, "RUNNING")
        networkWorker.execute {
            var activeStep = 0
            try {
                val token = saved?.let { validCredentials(it).accessToken }
                val available = fetchLatestUpdate()
                if (available == null) {
                    updateAsset = null
                    updateVersion = null
                    runOnUiThread {
                        updateButton?.text = "Zkontrolovat aktualizace aplikace"
                        statusView?.text = "Aplikace je aktuální · ${BuildConfig.VERSION_NAME}"
                        setUpdateProgressStep(0, "SUCCESS")
                        for (index in 1 until updateProgressRows.size) setUpdateProgressStep(
                            index,
                            "SKIPPED",
                        )
                        finishUpdateProgress("Používáš aktuální verzi.")
                    }
                    return@execute
                }
                val version = available.first
                val asset = available.second
                updateAsset = asset
                updateVersion = version
                updateReleaseNotes = available.third
                if (version != confirmedVersion) {
                    runOnUiThread {
                        updateProgressDialog?.dismiss()
                        updateButton?.text = "Stáhnout aktualizaci $version"
                        confirmAppUpdate()
                    }
                    return@execute
                }
                runOnUiThread {
                    updateProgressTitles.getOrNull(1)?.let {
                        updateProgressTitles[1] = "Stahuji verzi $version"
                    }
                    updateButton?.text = "Stahuji aktualizaci $version…"
                    setUpdateProgressStep(0, "SUCCESS")
                    setUpdateProgressStep(1, "RUNNING")
                    statusView?.text = "Stahuji aktualizaci $version…"
                }
                activeStep = 1
                val file =
                    downloadReleaseAsset(asset, token) { percent, received ->
                        runOnUiThread {
                            val indicator =
                                if (percent != null) {
                                    val filled = percent * 6 / 100
                                    "[${"•".repeat(filled)}${"·".repeat(6 - filled)}] $percent %"
                                } else {
                                    "[······] ${received / 1024} kB"
                                }
                            if (updateProgressTitles.size > 1) {
                                updateProgressTitles[1] = "Stahuji verzi $version  $indicator"
                                setUpdateProgressStep(1, "RUNNING")
                            }
                        }
                    }
                runOnUiThread { setUpdateProgressStep(1, "SUCCESS") }
                activeStep = 2
                runOnUiThread {
                    setUpdateProgressStep(2, "RUNNING")
                    statusView?.text = "Ověřuji staženou aplikaci…"
                }
                val info =
                    packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                        ?: error("Stažený soubor není platný APK.")
                require(info.packageName == packageName) { "APK nepatří této aplikaci." }
                val installedVersion =
                    PackageInfoCompat.getLongVersionCode(
                        packageManager.getPackageInfo(packageName, 0),
                    )
                require(PackageInfoCompat.getLongVersionCode(info) > installedVersion) {
                    "Release neobsahuje novější verzi."
                }
                pendingApk = file
                pendingUpdateVersionCode = PackageInfoCompat.getLongVersionCode(info)
                activeStep = 3
                runOnUiThread {
                    setUpdateProgressStep(2, "SUCCESS")
                    setUpdateProgressStep(3, "RUNNING")
                    statusView?.text = "Žádám Android o povolení instalace…"
                    try {
                        if (
                            android.os.Build.VERSION.SDK_INT >= 26 &&
                                !packageManager.canRequestPackageInstalls()
                        ) {
                            installPermission.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        } else {
                            setUpdateProgressStep(3, "SUCCESS")
                            installDownloadedApk()
                        }
                    } catch (e: Exception) {
                        failUpdateProgress(3, e.message ?: "Žádost o instalaci se nepodařila.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView?.text = e.message ?: "Stažení aktualizace se nezdařilo."
                    updateButton?.text =
                        updateVersion?.let { "Stáhnout aktualizaci $it" }
                            ?: "Zkontrolovat aktualizace aplikace"
                    failUpdateProgress(activeStep, e.message ?: "Stažení aktualizace se nezdařilo.")
                }
            } finally {
                runOnUiThread { updateButton?.isEnabled = true }
            }
        }
    }

    private fun downloadReleaseAsset(
        asset: JSONObject,
        token: String?,
        onProgress: (Int?, Long) -> Unit,
    ): File {
        val assetUrl = asset.getString("url")
        require(
            assetUrl.startsWith(
                "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/assets/",
            ),
        ) {
            "Neplatná adresa APK."
        }
        val first =
            (URL(assetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Inzenyri-Android")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                instanceFollowRedirects = false
            }
        val code = first.responseCode
        val downloadUrl =
            if (code in 300..399) {
                val target = URL(first.getHeaderField("Location"))
                require(
                    target.protocol == "https" &&
                        (target.host == "github.com" ||
                            target.host.endsWith(".githubusercontent.com")),
                ) {
                    "Neplatný server aktualizace."
                }
                target
            } else {
                if (code !in 200..299) throw IOException("GitHub odmítl APK (HTTP $code).")
                URL(assetUrl)
            }
        val connection =
            if (downloadUrl.toString() == assetUrl && code in 200..299) first
            else
                (downloadUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "Inzenyri-Android")
                }
        try {
            val file = File(cacheDir, "updates/$RELEASE_APK_NAME")
            file.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                val total =
                    connection.contentLengthLong.takeIf { it > 0 }
                        ?: asset.optLong("size", -1).takeIf { it > 0 }
                var received = 0L
                var lastReportedAt = android.os.SystemClock.elapsedRealtime()
                onProgress(if (total != null) 0 else null, received)
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        received += count
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastReportedAt >= 150) {
                            val percent = total?.let {
                                (received.toDouble() / it * 100).toInt().coerceIn(0, 99)
                            }
                            onProgress(percent, received)
                            lastReportedAt = now
                        }
                    }
                }
                require(total == null || received == total) {
                    "Stažení APK není úplné. Zkus aktualizaci znovu."
                }
                onProgress(100, received)
            }
            val digest = asset.optString("digest").removePrefix("sha256:")
            if (digest.isNotBlank()) {
                val actual =
                    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                        String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
                    }
                require(actual.equals(digest, ignoreCase = true)) {
                    "Kontrola integrity APK selhala."
                }
            }
            return file
        } finally {
            first.disconnect()
            if (connection !== first) connection.disconnect()
        }
    }

    private fun installDownloadedApk() {
        val file = pendingApk ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            setUpdateProgressStep(4, "RUNNING")
            statusView?.text = "Čekám na potvrzení instalace od Androidu…"
            updateInstallerLaunched = true
            startActivity(intent)
        } catch (e: Exception) {
            updateInstallerLaunched = false
            failUpdateProgress(4, e.message ?: "Instalační okno Androidu se nepodařilo otevřít.")
        }
    }

    private fun showUpdateProgress(downloadFlow: Boolean) {
        updateProgressDialog?.dismiss()
        updateProgressTitles =
            if (downloadFlow)
                mutableListOf(
                    "Kontroluji nejnovější verzi",
                    "Stahuji APK",
                    "Ověřuji staženou aplikaci",
                    "Žádám Android o povolení instalace",
                    "Čekám na potvrzení instalace",
                )
            else mutableListOf("Kontroluji nejnovější verzi")
        updateProgressRows = mutableListOf()
        val dialog = Dialog(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(20))
                background =
                    rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(
            label("Aktualizace aplikace", 21, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        updateProgressSpinner = ProgressBar(this).apply { isIndeterminate = true }
        header.addView(
            updateProgressSpinner,
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) },
        )
        updateProgressHeaderCloseButton =
            button("✕") { dialog.dismiss() }
                .apply {
                    minWidth = dp(40)
                    minHeight = dp(40)
                    setPadding(0, 0, 0, 0)
                    setTextColor(ink)
                    background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 13)
                    isEnabled = false
                    alpha = 0.5f
                }
        header.addView(updateProgressHeaderCloseButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        content.addView(header)
        updateProgressStatus = label("Připravuji kontrolu…", 14, muted, false)
        content.addView(updateProgressStatus, margin(0, 8, 0, 12))
        updateProgressTitles.forEach { title ->
            val row = label("○  $title", 14, muted, false)
            updateProgressRows += row
            content.addView(row, margin(0, 5, 0, 5))
        }
        updateProgressCloseButton =
            button("Zavřít") { dialog.dismiss() }.apply { visibility = View.GONE }
        content.addView(updateProgressCloseButton, margin(0, 16, 0, 0))
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            if (updateProgressDialog === dialog) clearUpdateProgress()
        }
        updateProgressDialog = dialog
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun setUpdateProgressStep(index: Int, state: String) {
        runOnUiThread {
            val row = updateProgressRows.getOrNull(index) ?: return@runOnUiThread
            val color =
                when (state) {
                    "RUNNING" -> purple
                    "SUCCESS" -> Color.rgb(35, 135, 75)
                    "FAILED" -> Color.rgb(190, 45, 55)
                    else -> muted
                }
            val icon =
                when (state) {
                    "RUNNING" -> "●"
                    "SUCCESS" -> "✓"
                    "FAILED" -> "✕"
                    "SKIPPED" -> "–"
                    else -> "○"
                }
            row.text = "$icon  ${updateProgressTitles.getOrElse(index) { "" }}"
            row.setTextColor(color)
            if (state == "RUNNING")
                updateProgressStatus?.text = updateProgressTitles.getOrElse(index) { "" }
        }
    }

    private fun finishUpdateProgress(message: String) {
        runOnUiThread {
            updateProgressSpinner?.visibility = View.GONE
            updateProgressStatus?.text = message
            updateProgressCloseButton?.visibility = View.VISIBLE
            updateProgressHeaderCloseButton?.isEnabled = true
            updateProgressHeaderCloseButton?.alpha = 1f
        }
    }

    private fun failUpdateProgress(index: Int, message: String) {
        runOnUiThread {
            if (index >= 0) setUpdateProgressStep(index, "FAILED")
            updateProgressSpinner?.visibility = View.GONE
            updateProgressStatus?.text = message
            updateProgressCloseButton?.visibility = View.VISIBLE
            updateProgressHeaderCloseButton?.isEnabled = true
            updateProgressHeaderCloseButton?.alpha = 1f
        }
    }

    private fun clearUpdateProgress() {
        updateProgressDialog = null
        updateProgressStatus = null
        updateProgressSpinner = null
        updateProgressRows.clear()
        updateProgressTitles.clear()
        updateProgressCloseButton = null
        updateProgressHeaderCloseButton = null
    }

    private fun encryptedOutput(): String {
        val source = sourceEnvelope ?: error("Chybí původní obálka.")
        val pass = password ?: error("Databáze je zamčená.")
        val data = database ?: error("Databáze není načtená.")
        return encryptedOutput(data, pass, source)
    }

    private fun encryptedOutput(data: JSONObject, pass: CharArray, source: JSONObject): String {
        return DatabaseCipher.encrypt(data, pass, source)
    }

    private fun decrypt(envelope: JSONObject, pass: CharArray): ByteArray =
        DatabaseCipher.decrypt(envelope, pass)

    private fun parseDatabaseDate(value: String): Date? = DatabaseRecords.parseDate(value)

    private fun selectType(type: String) {
        selectedType = type
        typeButtons.forEach { (key, button) ->
            button.background =
                rounded(
                    if (key == type) purple
                    else if (darkMode) Color.rgb(39, 39, 57) else Color.WHITE,
                    16,
                )
            button.setTextColor(if (key == type) Color.WHITE else ink)
        }
        solutionInput?.visibility = if (type == "task") View.VISIBLE else View.GONE
        solutionTemplateButton?.visibility = if (type == "task") View.VISIBLE else View.GONE
        aiReviewEditor?.visibility = if (type == "task") View.VISIBLE else View.GONE
        quizPromptInput?.visibility = if (type == "test") View.VISIBLE else View.GONE
        quizAnswersInput?.visibility = if (type == "test") View.VISIBLE else View.GONE
    }

    private fun chooseItemTime() {
        val dialog = Dialog(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(22))
                background =
                    rounded(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255), 24)
            }
        content.addView(label("Čas položky", 22, ink, true))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val parts = timeValue.split(':')
        val hour =
            input("Hodina", false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(parts.getOrNull(0).orEmpty())
            }
        val minute =
            input("Minuta", false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(parts.getOrNull(1).orEmpty())
            }
        row.addView(hour, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(" : ", 20, ink, true))
        row.addView(minute, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, margin(0, 14, 0, 14))
        content.addView(
            button("Uložit čas") {
                val h = hour.text.toString().toIntOrNull()
                val m = minute.text.toString().toIntOrNull()
                if (h == null || h !in 0..23 || m == null || m !in 0..59) {
                    toast("Zadej hodinu 0–23 a minutu 0–59.")
                    return@button
                }
                timeValue = String.format(Locale.ROOT, "%02d:%02d", h, m)
                timeButton?.text = timeValue
                dialog.dismiss()
            },
        )
        content.addView(
            button("Celý den") {
                timeValue = ""
                timeButton?.text = "Celý den"
                dialog.dismiss()
            },
            margin(0, 8, 0, 0),
        )
        content.addView(button("Zrušit") { dialog.dismiss() }, margin(0, 8, 0, 0))
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
            -2,
        )
    }

    private fun chooseDate() {
        val selectedDate = parseDate(dateValue) ?: Date()
        val selectedCalendar = Calendar.getInstance().apply { time = selectedDate }
        val visibleMonth =
            (selectedCalendar.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        val surface = if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255)
        val dialog = Dialog(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                background = rounded(surface, 24)
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(label("Vybrat datum", 22, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            button("✕") { dialog.dismiss() }
                .apply {
                    minWidth = dp(42)
                    minHeight = dp(42)
                    setPadding(0, 0, 0, 0)
                },
            LinearLayout.LayoutParams(dp(42), dp(42)),
        )
        content.addView(header)
        content.addView(label("Vyber termín položky", 13, muted, false), margin(0, 4, 0, 16))

        val monthHeading = label("", 17, ink, true).apply { gravity = Gravity.CENTER }
        val calendarGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val weekdays = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne").forEach { day ->
            weekdays.addView(
                label(day, 12, muted, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, dp(32), 1f),
            )
        }
        fun renderMonth() {
            val czechLocale = Locale("cs", "CZ")
            val monthName = SimpleDateFormat("LLLL yyyy", czechLocale).format(visibleMonth.time)
            monthHeading.text = monthName.replaceFirstChar { it.titlecase(czechLocale) }
            calendarGrid.removeAllViews()
            val firstDayOffset = (visibleMonth.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            val daysInMonth = visibleMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
            val weekCount = (firstDayOffset + daysInMonth + 6) / 7
            for (week in 0 until weekCount) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (weekday in 0..6) {
                    val day = week * 7 + weekday - firstDayOffset + 1
                    val cell =
                        if (day in 1..daysInMonth) {
                            val isSelected =
                                selectedCalendar.get(Calendar.YEAR) ==
                                    visibleMonth.get(Calendar.YEAR) &&
                                    selectedCalendar.get(Calendar.MONTH) ==
                                        visibleMonth.get(Calendar.MONTH) &&
                                    selectedCalendar.get(Calendar.DAY_OF_MONTH) == day
                            TextView(this).apply {
                                text = day.toString()
                                textSize = 14f
                                gravity = Gravity.CENTER
                                setTextColor(if (isSelected) Color.WHITE else ink)
                                typeface =
                                    if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                background =
                                    if (isSelected) rounded(purple, 50) else rounded(surface, 50)
                                isClickable = true
                                setOnClickListener {
                                    selectedCalendar.set(
                                        visibleMonth.get(Calendar.YEAR),
                                        visibleMonth.get(Calendar.MONTH),
                                        day,
                                    )
                                    dateValue =
                                        String.format(
                                            Locale.ROOT,
                                            "%02d.%02d.%04d",
                                            selectedCalendar.get(Calendar.DAY_OF_MONTH),
                                            selectedCalendar.get(Calendar.MONTH) + 1,
                                            selectedCalendar.get(Calendar.YEAR),
                                        )
                                    dateButton?.text = "Datum · $dateValue"
                                    dialog.dismiss()
                                }
                            }
                        } else {
                            TextView(this)
                        }
                    row.addView(
                        cell,
                        LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        },
                    )
                }
                calendarGrid.addView(row)
            }
        }

        val monthNavigation =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        monthNavigation.addView(
            button("‹") {
                    visibleMonth.add(Calendar.MONTH, -1)
                    renderMonth()
                }
                .apply {
                    setTextColor(ink)
                    background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                },
            LinearLayout.LayoutParams(dp(48), dp(44)),
        )
        monthNavigation.addView(monthHeading, LinearLayout.LayoutParams(0, dp(44), 1f))
        monthNavigation.addView(
            button("›") {
                    visibleMonth.add(Calendar.MONTH, 1)
                    renderMonth()
                }
                .apply {
                    setTextColor(ink)
                    background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                },
            LinearLayout.LayoutParams(dp(48), dp(44)),
        )
        content.addView(monthNavigation, margin(0, 0, 0, 8))
        content.addView(weekdays)
        content.addView(calendarGrid)
        renderMonth()

        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        actions.addView(
            button("Dnes") {
                    dateValue = today()
                    dateButton?.text = "Datum · $dateValue"
                    dialog.dismiss()
                }
                .apply {
                    setTextColor(ink)
                    background = rounded(if (darkMode) Color.rgb(43, 42, 61) else Color.WHITE, 14)
                },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
        )
        actions.addView(button("Zavřít") { dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions, margin(0, 12, 0, 0))

        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun input(hintText: String, multiline: Boolean) =
        EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(if (darkMode) Color.rgb(35, 35, 53) else Color.WHITE, 14)
            inputType =
                if (multiline)
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = if (multiline) 3 else 1
            gravity = if (multiline) Gravity.TOP else Gravity.CENTER_VERTICAL
            imeOptions =
                if (multiline) EditorInfo.IME_FLAG_NO_EXTRACT_UI else EditorInfo.IME_ACTION_NEXT
        }

    private fun button(title: String, action: () -> Unit) =
        Button(this).apply {
            text = title
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(purple, 16)
            minHeight = dp(48)
            setOnClickListener { action() }
        }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), if (darkMode) Color.rgb(67, 66, 87) else Color.rgb(230, 228, 239))
        }

    private fun margin(left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        aiReviewBubble.dismiss()
        databaseQrDialog?.dismiss()
        super.onPause()
    }

    override fun onDestroy() {
        pushProgressDialog?.dismiss()
        pushProgressDialog = null
        databaseQrDialog?.dismiss()
        password?.fill('\u0000')
        worker.shutdownNow()
        networkWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val GITHUB_OWNER = AppUpdateNotifications.OWNER
        private const val GITHUB_REPO = AppUpdateNotifications.REPOSITORY
        private const val GITHUB_BRANCH = "main"
        private const val RELEASE_APK_NAME = AppUpdateNotifications.APK_NAME
        private const val TOKEN_PREFS = SecureCredentialStore.PREFERENCES
        private const val DB_PREFS = "database_state"
        private const val ITEM_PREFS = "item_state"
        private const val DB_BASELINE_HASH = "baseline_sha256"
        private const val DONE_ITEMS_KEY = "done_items"

        private fun today() = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date())

        private fun parseDate(value: String): Date? =
            try {
                SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).apply { isLenient = false }.parse(value)
            } catch (_: Exception) {
                null
            }
    }
}
