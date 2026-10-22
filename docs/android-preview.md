# Android preview bez Android Studia

## Požadavky

- Linux s procesorem Intel/AMD 64bit a grafickým prostředím.
- Zapnutá virtualizace v BIOS/UEFI, dostupné `/dev/kvm` a oprávnění jej
  používat.
- JDK 17 a Gradle 9.8.1 dostupné v terminálu (`java`, `gradle`).
- Několik GB volného místa a internet pro první stažení SDK, obrazu Androidu a
  závislostí.

Pokud `/dev/kvm` neexistuje, musíš nejprve zprovoznit KVM podle své distribuce.
Pokud existuje, ale nemáš přístup, zkontroluj členství ve skupině `kvm`; po
změně skupin bývá nutné odhlášení a přihlášení. Skript nemění systémová
oprávnění.

## Spuštění

Z kořene projektu:

```sh
python3 scripts/preview_android.py --setup
```

Skript stáhne oficiální Command-line Tools z Googlu, ověří kontrolní součet,
nabídne odsouhlasení SDK licencí a nainstaluje emulátor, Android 35 a build
nástroje. Vytvoří virtuální Pixel 7, spustí jej, sestaví aplikaci a otevře ji.

Stahování Command-line Tools zobrazuje progress bar s procenty, objemem dat,
rychlostí a odhadem zbývajícího času. Při přerušení nebo výpadku připojení
zůstane částečný archiv uložený; zopakuj stejný příkaz s `--setup` a stahování
naváže. Pokud server navázání nepodporuje, skript bezpečně začne od začátku.
Před použitím se vždy ověří kontrolní součet celého archivu. Ostatní SDK balíčky
stahuje `sdkmanager` s vlastním ukazatelem průběhu; navázání jednotlivých SDK
balíčků tento skript neřídí.

Při dalších změnách spusť:

```sh
python3 scripts/preview_android.py
```

Použije už běžící preview emulátor, případně jej spustí. Znovu sestaví a
nainstaluje aplikaci bez mazání dat. Okno emulátoru můžeš zavřít ručně.

Pokud jsi kód neměnil a chceš jen otevřít poslední sestavenou verzi:

```sh
python3 scripts/preview_android.py --reuse-apk
```

Tento přepínač vynechá Gradle. Pokud APK chybí nebo poslední build nebyl
preview, skript požádá o spuštění bez přepínače.

## Výkon na slabším PC

Preview používá rozlišení 720 × 1280 místo původních 1080 × 2400, hustotu 280
dpi, 2 virtuální CPU, požadavek 2048 MB RAM a hardwarovou GPU (`host`). Android
emulátor může podle verze a systémového obrazu požadavek na paměť navýšit;
skutečnost je v logu. Akcelerace CPU je povinně přes KVM (`-accel on`). Build má
nejvýše 2 workery, JVM limit 1 GB a Kotlin kompiluje ve stejném procesu. Jeho
JVM po buildu skončí; pokud emulátor neběží, spouští se až po dokončení buildu.

**Po změně nastavení jednou zavři staré okno emulátoru a spusť preview znovu.**
Skript nemění konfiguraci běžícího zařízení. Původní konfiguraci zálohuje vedle
`config.ini` jako `config.ini.before-preview-tuning`, uživatelská data nemaže.
Při první změně konfigurace se nepoužije starý snapshot; další starty ho mohou
opět využívat.

Pokud hardwarové vykreslování způsobuje chyby nebo černé okno, vyzkoušej:

```sh
python3 scripts/preview_android.py --reuse-apk --gpu auto
```

Po změně GPU je rovněž nutné zavřít předchozí emulátor. Poslední možnost je
`--gpu swiftshader`, která ale vykresluje softwarově a může být podstatně
pomalejší. Při velkém využití swapu pomůže zavřít další náročné aplikace; nižší
rozlišení ani hardwarová GPU neodstraní nedostatek systémové paměti.

### Pokud neodpovídá samotné okno emulátoru na PC

Výchozí start nyní vypíná Vulkan a používá OpenGL. Pokud je dostupné `DISPLAY`,
Qt okno spouští přes X11/XWayland (`xcb`), aby nevyžadovalo chybějící Qt Wayland
plugin. Jde o změnu okna emulátoru, nastavení produkční Android aplikace se tím
nemění. Zavři starý emulátor a spusť:

```sh
python3 scripts/preview_android.py --reuse-apk --cold-boot
```

`--cold-boot` nepoužije uložený snapshot, ale nesmaže uživatelská data. Změny
rendereru se evidují; první start po změně rovněž nepoužije starý snapshot.
Pokud i tak zamrzá okno s Intel GPU, můžeš vyzkoušet `--gpu swiftshader`. Vulkan
lze pro porovnání zapnout přepínačem `--vulkan`.

### Úplné vynechání Qt okna přes scrcpy

Nainstaluj `scrcpy` z balíčků své distribuce. Zavři starý emulátor a spusť:

```sh
python3 scripts/preview_android.py --reuse-apk --scrcpy
```

Emulátor poběží bez vlastního grafického okna. Displej Androidu uvidíš a ovládáš
v samostatném okně scrcpy. Výchozí přenos má limit 960 px na delší straně, 30
FPS, H.264 při 2 Mb/s a vypnutý přenos zvuku. Tím se obejde Qt okno emulátoru;
spotřeba paměti virtuálního Androidu zůstává. Zavření scrcpy nevypíná emulátor;
skript po zavření vypíše přesný příkaz pro jeho ukončení.

Při trhaném přenosu použij nižší rozlišení 640 px při 1 Mb/s a výpis FPS:

```sh
python3 scripts/preview_android.py --reuse-apk --scrcpy --stream-quality low --show-fps
```

Zavři předchozí okno scrcpy, emulátor přitom může běžet dál; změna kvality
přenosu nevyžaduje jeho restart. Nižší rozlišení sníží práci s obrazem při
kódování a dekódování, ale obraz bude méně ostrý. Profil `balanced` je výchozí,
`high` má 1280 px, limit 60 FPS a 8 Mb/s. Pro přenos zvuku přidej
`--with-audio`. Počet FPS sleduj při scrollování: scrcpy při neměnném obrazu
neposílá zbytečně nové snímky, takže nízké FPS v klidu samo o sobě neznamená
problém.

Pokud je pomalý samotný virtuální Android, nižší kvalita přenosu to nemusí
vyřešit. Pak použij níže popsaný profil `--lite`; případně lze skutečný Android
telefon ovládat přes scrcpy a vynechat režii emulace úplně.

### Lehčí systém bez Google služeb

Pro menší paměťovou zátěž je dostupný oddělený profil čistého Androidu 11 (API
30):

```sh
# Jednorázově stáhne další obraz a vytvoří samostatné zařízení:
python3 scripts/preview_android.py --lite --setup --reuse-apk

# Další spuštění:
python3 scripts/preview_android.py --lite --reuse-apk
```

Zavři nejdřív původní emulátor, aby oba neběžely současně. Lite profil požaduje
1536 MB RAM a nemá Google služby. Má vlastní data a vyžaduje první přihlášení;
původní zařízení a jeho data se nemění. Log je
`private/android-preview/emulator-lite.log`. Je vhodný pro náhled rozhraní;
chování novějších Android API (například oprávnění oznámení od Androidu 13)
ověřuj v běžném profilu. Lze kombinovat s `--scrcpy`.

## Kam se soubory ukládají

- Emulátor, jeho data a log jsou v ignorovaném `private/android-preview/`.
- Stažené a rozpracované archivy Command-line Tools jsou v
  `private/android-preview/downloads/`.
- SDK se vybírá podle `ANDROID_HOME`, případně `ANDROID_SDK_ROOT`, potom
  `~/Android/Sdk`. Jinak se stáhne do `private/android-preview/sdk/`.
- Sestavené APK je v `app/build/outputs/apk/debug/`.
- Log startu emulátoru je `private/android-preview/emulator.log`.

SDK uložené mimo projekt zůstává na svém místě. Proměnné potřebné pro běh skript
nastaví jen svým podprocesům; nemění konfiguraci shellu.

## Oddělení od běžné aplikace

Skript sestavuje s `-PlocalPreview=true`. Aplikace se jmenuje **Inženýři
Preview** a používá ID `cz.inzenyri.mobile.preview`, vlastní úložiště a vlastní
FileProvider. Může být nainstalovaná vedle běžné aplikace. Bez této property se
způsob sestavení pro GitHub workflow nemění.

Databáze se při prvním startu načítá ze stejného originu jako v běžné aplikaci;
lokální nepushnutá DB se do emulátoru automaticky nekopíruje. GitHub přihlášení
maintainera je volitelné. Pokud ho potřebuješ, nastav před spuštěním proměnnou
`GH_APP_CLIENT_ID` na Client ID své GitHub App. Samotný preview skript nepoužívá
Git ani GitHub CLI, nevytváří commity ani releasy.

Aktualizace z běžných GitHub Releases jsou určené běžné aplikaci, ne preview
balíčku; preview aktualizuj opětovným spuštěním skriptu.

## Problémy při startu

- **Chybí KVM:** povol virtualizaci a přístup k `/dev/kvm`.
- **Chybí Java nebo Gradle:** nainstaluj JDK 17 a Gradle 9.8.1, ověř jejich
  dostupnost v PATH.
- **Emulátor skončil:** přečti `emulator.log`; mohou chybět grafické nebo
  systémové knihovny.
- **Chybí SDK nebo virtuální zařízení:** zopakuj příkaz s `--setup`.
- **SDK licence:** setup vyžaduje jejich ruční potvrzení, nepřijímá je
  automaticky.

Oficiální dokumentace:
[Android emulátor z příkazové řádky](https://developer.android.com/studio/run/emulator-commandline).
