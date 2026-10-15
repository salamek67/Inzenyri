# Android aplikace Inženýři

Při prvním spuštění aplikace vytvoří soukromou složku ve svém interním úložišti a stáhne do ní pouze šifrovaný `data.enc.json`. Po zadání stejného hesla jako na webu zobrazí přehled testů, úkolů a akcí. Databáze zůstává šifrovaná i v zařízení. Nová data z origin načteš tažením přehledu dolů; neuložené změny před přepsáním potvrdíš.

Ikona klíče vpravo nahoře spustí propojení s GitHubem. Po přihlášení se zpřístupní formulář pro přidávání položek a tlačítko **Pushnout DB na origin**. Push zapíše šifrovaný soubor do `salamek67/Inzenyri`, větev `main`, pomocí GitHub Contents API. Commit má zprávu `[app skip] Update DB (dbver)`, takže se nasadí nový webový obsah bez zbytečného sestavení APK. Před zápisem aplikace zkontroluje, jestli se databáze na GitHubu mezitím nezměnila. GitHub účet musí mít pro repozitář právo zápisu.

Při prvním načtení nebo pushi se aplikace přihlásí přes GitHub Device Flow. Uživatel potvrdí jednorázový kód na GitHubu; přístupový a obnovovací token aplikace ukládá šifrovaně pomocí Android Keystore. Heslo databáze lze uložit po výslovném zaškrtnutí. Je šifrované samostatným klíčem z Android Keystore a při dalším spuštění se předvyplní do dialogu. Tokeny jsou omezené oprávněními GitHub App a oprávněními přihlášeného uživatele.

## Nastavení GitHub přihlášení

Vytvoř GitHub App pro tento projekt, povol **Device Flow** a nastav repository permission **Contents: Read and write**. App nainstaluj pouze do repozitáře `salamek67/Inzenyri`. Její Client ID z nastavení GitHub App vlož do repozitáře v **Settings → Secrets and variables → Actions → Variables** jako `GH_APP_CLIENT_ID` (workflow přijímá také Actions secret se stejným názvem). Client ID není tajné; aplikace ho potřebuje pro Device Flow. Workflow při prázdné hodnotě sestavení zastaví s jasnou chybou místo vytvoření APK bez funkčního přihlášení. Pro lokální sestavení nastav Gradle property `githubAppClientId` nebo proměnnou prostředí `GH_APP_CLIENT_ID`.

## APK a aktualizace

Workflowy sestaví Android i web při commitu bez zvláštního prefixu. Prefix `[app skip]` přeskočí Android, `[page skip]` přeskočí nasazení webu a `[ci skip]` přeskočí oboje. `script.py` prefix automaticky odvodí ze změněných souborů, pokud ho ve zprávě commitu neurčíš sám. Android workflow vytváří debug APK artefakt; po publikování GitHub Release sestaví podepsané APK `inzenyri-android.apk` a přiloží ho k vydání. Aplikace při spuštění kontroluje nejnovější release; instalaci nové verze vždy potvrzuje uživatel v Androidu.

Podepsané vydání vyžaduje tyto GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64` – base64 obsah release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keystore vytvoř jednou příkazem `keytool -genkeypair -keystore inzenyri-release.jks -alias inzenyri -keyalg RSA -keysize 2048 -validity 10000`. Hodnotu pro `ANDROID_KEYSTORE_BASE64` získáš příkazem `base64 -w0 inzenyri-release.jks`; hesla vlož do odpovídajících secrets a alias nastav na `inzenyri`. Bezpečně si keystore zálohuj: další vydání musí být podepsána stejným klíčem, jinak Android aktualizaci odmítne. Nevkládej keystore ani hesla do repozitáře. Vytvoř release tag, například `1.0.1`, a publikuj GitHub Release. Debug APK a podepsané release APK mají odlišný podpis; jestli máš nainstalovanou debug verzi, před první instalací podepsaného release APK ji musíš odinstalovat, čímž se smažou její lokální data. Další aktualizace release verze už zachovají data, pokud zůstane stejný podpisový klíč a vyšší `versionCode`.

Při instalaci APK mimo Play Store Android vyžaduje, abys pro tuto aplikaci povolil instalaci z tohoto zdroje a potvrdil instalaci každé aktualizace. Aplikace otevře systémové nastavení, pokud toto oprávnění chybí; aktualizaci neinstaluje potichu.
