# Android aplikace Inženýři

Otevři v aplikaci aktuální `data.enc.json`, odemkni ho stejným heslem jako web a přidej úkol, akci nebo test. Tlačítko **Pushnout DB na origin** zapíše šifrovaný soubor do `salamek67/Inzenyri`, větev `main`, pomocí GitHub Contents API. Vytvořený commit má zprávu `Update DB (dbver)`. Před zápisem aplikace zkontroluje, jestli se databáze na GitHubu mezitím nezměnila.

Při prvním načtení nebo pushi se aplikace přihlásí přes GitHub Device Flow. Uživatel potvrdí jednorázový kód na GitHubu; přístupový a obnovovací token aplikace ukládá šifrovaně pomocí Android Keystore. Tokeny jsou omezené oprávněními GitHub App a oprávněními přihlášeného uživatele.

## Nastavení GitHub přihlášení

Vytvoř GitHub App pro tento projekt, povol **Device Flow** a nastav repository permission **Contents: Read and write**. App nainstaluj pouze do repozitáře `salamek67/Inzenyri`. Její veřejné Client ID nastav v repozitáři v **Settings → Secrets and variables → Actions → Variables** jako `GH_APP_CLIENT_ID`. Client ID není tajné; aplikace ho potřebuje pro Device Flow. Debug i release workflow ho předávají Gradlu při sestavení. Bez této proměnné lze aplikaci sestavit, ale přihlášení a push budou vypnuté.

## APK a aktualizace

Workflow `.github/workflows/android.yml` sestavuje debug APK jako artefakt při změně aplikace nebo ručním spuštění. Po publikování GitHub Release sestaví podepsané APK `inzenyri-android.apk` a přiloží ho k vydání. Aplikace při spuštění kontroluje nejnovější release; instalaci nové verze vždy potvrzuje uživatel v Androidu.

Podepsané vydání vyžaduje tyto GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64` – base64 obsah release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keystore vytvoř jednou příkazem `keytool -genkeypair -keystore inzenyri-release.jks -alias inzenyri -keyalg RSA -keysize 2048 -validity 10000`. Hodnotu pro `ANDROID_KEYSTORE_BASE64` získáš příkazem `base64 -w0 inzenyri-release.jks`; hesla vlož do odpovídajících secrets a alias nastav na `inzenyri`. Bezpečně si keystore zálohuj: další vydání musí být podepsána stejným klíčem, jinak Android aktualizaci odmítne. Nevkládej keystore ani hesla do repozitáře. Vytvoř release tag, například `v1.0.1`, a publikuj GitHub Release. Debug APK a podepsané release APK mají odlišný podpis; jestli máš nainstalovanou debug verzi, před první instalací podepsaného release APK ji musíš odinstalovat, čímž se smažou její lokální data. Další aktualizace release verze už zachovají data, pokud zůstane stejný podpisový klíč a vyšší `versionCode`.

Při instalaci APK mimo Play Store Android vyžaduje, abys pro tuto aplikaci povolil instalaci z tohoto zdroje a potvrdil instalaci každé aktualizace. Aplikace otevře systémové nastavení, pokud toto oprávnění chybí; aktualizaci neinstaluje potichu.
