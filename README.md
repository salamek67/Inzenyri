# Inženýři

Školní přehled úkolů, testů a akcí pro web a Android.

**Free to fork.** Projekt můžeš zdarma používat, upravovat a šířit i komerčně.
Vlastní kód je pod licencí [0BSD](LICENSE), bez povinnosti uvádět autora.
Převzaté knihovny a fonty mají vlastní licence přiložené ve `web/vendor/`
a u Android závislostí.

Pro vlastní nasazení začni [návodem krok za krokem](docs/tutorial.md).

## Složky

- `app/` — Android aplikace, Kotlin zdroje a Gradle konfigurace.
- `web/` — veřejný web: `index.html`, `js/`, `css/`, `icons/`, `vendor/` a `sw.js`.
- `scripts/` — Python správce databáze a jeho `requirements.txt`.
- `docs/` — dokumentace, včetně [Android aplikace](docs/android.md).
- `private/` — ignorovaná lokální databáze a heslo; nepublikuje se.
- `.github/workflows/` — sestavení Androidu a nasazení webu.

`data.enc.json` zůstává v kořeni, aby fungovaly existující Android aplikace.
`CHANGELOG.md` a `AGENTS.md` zůstávají společné pro projekt.

## Správa databáze

```sh
python3 -m pip install -r scripts/requirements.txt
python3 script.py
```

Původní příkaz `python script.py` funguje také. Vlastní implementace je v `scripts/script.py`.

## Lokální web

Z kořene spusť `python3 scripts/serve.py` a otevři `http://127.0.0.1:8000`.
Lokální server zpřístupňuje pouze veřejný web a šifrovanou DB.
Pages workflow připraví veřejný artefakt z `web/` a přidá pouze šifrovanou databázi.

## Android preview na PC bez IDE

Na Linuxu s Intel/AMD 64bit, JDK 17, Gradlem 9.8.1 a dostupným KVM:

```sh
# Poprvé stáhne Android SDK a obraz Androidu, nabídne licence a spustí preview:
python3 scripts/preview_android.py --setup

# Při dalších změnách stačí:
python3 scripts/preview_android.py

# Jen otevření posledního preview APK, bez nového buildu:
python3 scripts/preview_android.py --reuse-apk
```

Otevře se Android emulátor s aplikací **Inženýři Preview**. Je oddělená od běžné
aplikace a zachovává vlastní data mezi sestaveními. Nic se necommituje ani nepushuje.
První setup stáhne několik GB; Android Studio není potřeba.
Podrobnosti a řešení problémů jsou v [návodu](docs/android-preview.md).
