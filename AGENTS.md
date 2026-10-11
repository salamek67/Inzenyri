# Pokyny pro práci na projektu

- Před změnou si ověř aktuální stav souborů a relevantní části projektu. Zachovej existující rozhodnutí a styl; neopakuj zamítnuté pokusy bez nového důvodu.
- `CHANGELOG.md` obsahuje pouze sekci `Android`; Python skript ani web vlastní changelog nemají.
- Ke každé změně Android aplikace přidej stručný řádek pod `## Android`. Řádek popisuje výsledek pro uživatele. Nepoužívej sekci `Unreleased` ani historické verze v souboru.
- Při bumpu Androidu skript vezme položky z `## Android` do GitHub Release a sekci vyprázdní ještě před vytvořením hlavního commitu. Vyčištění tak jde společně s vydanou verzí v jednom commitu. U staré verze se release poznámky doplní odkazem `Full Changelog`.
- Verzi webu bumpuj přes nabídku v `script.py`. Skript vloží verzi webu do zprávy commitu; web nemá položky v `CHANGELOG.md`.
- Verzi Android aplikace bumpuj přes nabídku v `script.py`. Skript vloží verzi do zprávy commitu, pushne commit a vytvoří GitHub Release označený samotnou verzí bez prefixu `v`; GitHub CLI (`gh`) musí být přihlášené. Python skript nemá vlastní verzi ani release.
- `script.py` používá pro příkazy `gh` oddělený profil `~/.config/gh-inzenyri`. Přihlašuje se do něj jednou příkazem `GH_CONFIG_DIR="$HOME/.config/gh-inzenyri" gh auth login --hostname github.com`; skript pak tento profil nastavuje automaticky.
- Před dokončením zkontroluj diff a syntaxi nebo sestavení podle rozsahu změny. Necommituj ani nepushuj ručně, pokud o to uživatel nepožádal.

## Spolupráce

- Pokud v kořeni existuje `resume.md`, přečti ho celý spolu s těmito pokyny. Ber ho jako předání kontextu, ale tvrzení o aktuálním stavu ověř v souborech a Gitu. Případné rozdíly stručně pojmenuj a pokračuj podle ověřeného stavu.
- Po shrnutí předání pokračuj prvním nedokončeným krokem. Neopakuj hotovou práci ani zamítnuté pokusy, pokud se neobjevil nový důvod.
- Pracuj samostatně na základě dostupného kontextu. Ptej se během práce jen na informaci, která skutečně blokuje pokračování; jinak zvol rozumný postup a úpravu dokonči.
- Zachovej nesouvisející rozpracované změny uživatele. Před úpravami zkontroluj pracovní strom a při dokončení uveď, které změny patří k aktuálnímu požadavku.
- Verzi aplikace ani webu neposouvej, pokud to uživatel výslovně nepožádal nebo to není nezbytná součást zadaného výsledku.
- U Android rozhraní zachovávej vlastní vizuální styl aplikace a používej její vlastní dialogy a průběhové obrazovky tam, kde uživatel interaguje s aplikací. Systémová obrazovka je vhodná pro kroky, které musí provést Android, například instalaci APK nebo oprávnění.
