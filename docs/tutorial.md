# Vlastní Inženýři krok za krokem

## 1. Fork a stažení projektu

Na GitHubu klikni na **Fork**. Potom stáhni svůj repozitář:

```sh
git clone git@github.com:TVUJ_UCET/Inzenyri.git
cd Inzenyri
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -r scripts/requirements.txt
```

Potřebuješ Git a Python 3.10 nebo novější. Na Windows aktivuj prostředí
příkazem `.venv\Scripts\activate` místo `source .venv/bin/activate`.
Pro lokální náhled webu nepotřebuješ GitHub CLI ani Android SDK.

## 2. Vlastní databáze a heslo

Při novém forku vytvoř soubor `private/data.private.json` s tímto obsahem:

```json
{
  "tasks": []
}
```

Pokud složka `private/` ještě neexistuje, vytvoř ji. Pokud už máš vlastní
rozpracovaná data, soubor nepřepisuj prázdným seznamem.

Spusť první nastavení:

```sh
python3 script.py init
python3 script.py
```

`init` se zeptá dvakrát na nové heslo a vytvoří šifrovaný `data.enc.json`.
Ve vlastním forku tím nahradíš původní šifrovaná data svými.
V interaktivní nabídce použij `add` pro přidání úkolu, testu nebo akce,
`edit` pro úpravu, `list` pro přehled a `help` pro všechny příkazy.
Změny se při uložení rovnou zašifrují. `password` změní heslo databáze.

Každý úkol, test i akce může mít volitelný čas v místním čase školy.
V Androidu pod datem otevři volbu času; tlačítko **Celý den** čas odstraní.
V Python správci při přidání zadej `HH:mm`, nebo stiskni Enter pro celý den.
Při úpravě Enter ponechá původní čas a `-` jej odstraní.
Web čas zobrazuje; položky se vytvářejí a upravují v Androidu nebo správci.
Ve stejný den se nejprve zobrazují celodenní položky, potom položky podle času.
Sdílený JSON používá volitelné pole `"time": "08:30"`; chybějící nebo prázdné
pole znamená celý den, takže starší záznamy není nutné měnit.
Čas položky nemění nastavený čas Android připomínek.

`private/` obsahuje lokální nešifrovaná data a uložené heslo, je ignorované Gitem.
Přístupové heslo ani přihlašovací QR nezveřejňuj; umožňují odemknout databázi.

## 3. Lokální náhled webu

```sh
python3 scripts/serve.py
```

Otevři **http://127.0.0.1:8000** a zadej heslo z předchozího kroku.
Server ukončíš pomocí Ctrl+C. Web neotevírej přímo přes `file://`.

## 4. Nastavení vlastního repozitáře

Před používáním pushování a aktualizací změň odkazy na původní repozitář:

| Soubor | Co nastavit |
|---|---|
| `scripts/script.py` | `GITHUB_REPOSITORY` a adresu odkazu `compare` pro release poznámky |
| `app/src/main/java/cz/inzenyri/mobile/MainActivity.kt` | `GITHUB_OWNER`, `GITHUB_REPO`, případně `GITHUB_BRANCH` |
| `web/index.html` | Odkazy na stažení Android aplikace z Releases |

Ověř pomocí `git remote -v`, že `origin` ukazuje na tvůj fork.
Samotný web čte DB ze stejného nasazení a tyto konstanty pro čtení nepotřebuje.

## 5. Publikování webu

V repozitáři otevři **Settings → Pages → Source → GitHub Actions**.
Pokud jsou workflow ve forku vypnutá, povol je na kartě **Actions**.
Po pushi na větev `main` workflow `Deploy static content to Pages` zkontroluje
web a publikuje `web/` společně s šifrovaným `data.enc.json` z kořene.
Adresu nasazeného webu najdeš v dokončeném běhu workflow nebo v nastavení Pages.

## 6. Android aplikace

Pro lokální sestavení potřebuješ JDK 17, Gradle 9.8.1 a Android SDK pro API 35.
Nastav `ANDROID_HOME` na svůj Android SDK a spusť:

```sh
gradle -p app assembleDebug
```

APK najdeš v `app/build/outputs/apk/debug/`. Bez GitHub App Client ID lze
lokálně sestavit aplikaci pro čtení, propojení maintainera však nebude fungovat.
GitHub workflow Client ID vyžaduje: vytvoř vlastní GitHub App s Device Flow,
oprávněním **Contents: Read and write** a instalací do svého forku.
Client ID ulož do **Settings → Secrets and variables → Actions → Variables**
jako `GH_APP_CLIENT_ID`. Client ID není heslo ani soukromý klíč.

Podepisování APK a další nastavení jsou v [dokumentaci Androidu](android.md).
Pro svůj fork si vytvoř vlastní podpisový klíč a bezpečně ho uchovej pro další aktualizace.

## 7. Commity a vydání

Pro vytváření releasů nainstaluj GitHub CLI a přihlas oddělený profil projektu
příkazem `python3 script.py github-login`. Přihlášení obvykle stačí provést jednou;
autorizace Gitu pro push přes SSH se nastavuje samostatně.

Potom spusť `python3 script.py` a použij `commit`. Skript zkontroluje stav vůči
originu, nabídne zvýšení verzí podle změněných souborů, vytvoří commit a pushne ho.
Při zvýšení Android verze také vytvoří GitHub Release s poznámkami z `CHANGELOG.md`.
Bez bumpu Android verze se nový release nevytváří a changelog se nemaže.

Pro samotné lokální zvýšení verze použij `bump` v nabídce nebo
`python3 script.py bump`. Skript nabídne web a Android zvlášť, u každého
vybereš major/minor/patch. Neprovádí commit, push ani release, nepotřebuje
přihlášení ke GitHubu a nemění databázi ani changelog. Při pozdějším `commit`
se nabídka zvýšení verze zobrazí znovu; pokud už verzi zvyšovat nechceš, odmítni ji.

Skip prefix skript vyhodnocuje automaticky. Při ručním commitu lze použít:

- `[app skip]` — vynechá sestavení Androidu.
- `[page skip]` — vynechá nasazení webu.
- `[ci skip]` — vynechá obě části.
- Bez prefixu se spustí obě části.
