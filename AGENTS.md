# Pokyny pro práci na projektu

- Před změnou si ověř aktuální stav souborů a relevantní části projektu. Zachovej existující rozhodnutí a styl; neopakuj zamítnuté pokusy bez nového důvodu.
- `CHANGELOG.md` obsahuje pouze sekci `Android`; Python skript ani web vlastní changelog nemají.
- Ke každé změně Android aplikace přidej stručný řádek pod `## Android`. Řádek popisuje výsledek pro uživatele. Nepoužívej sekci `Unreleased` ani historické verze v souboru.
- Při bumpu Androidu skript vezme položky z `## Android` do GitHub Release a sekci vyprázdní ještě před vytvořením hlavního commitu. Vyčištění tak jde společně s vydanou verzí v jednom commitu. U staré verze se release poznámky doplní odkazem `Full Changelog`.
- Verzi webu bumpuj přes nabídku v `script.py`. Skript vloží verzi webu do zprávy commitu; web nemá položky v `CHANGELOG.md`.
- Verzi Android aplikace bumpuj přes nabídku v `script.py`. Skript vloží verzi do zprávy commitu, pushne commit a vytvoří GitHub Release označený samotnou verzí bez prefixu `v`; GitHub CLI (`gh`) musí být přihlášené. Python skript nemá vlastní verzi ani release.
- `script.py` používá pro příkazy `gh` oddělený profil `~/.config/gh-inzenyri`. Přihlašuje se do něj jednou příkazem `GH_CONFIG_DIR="$HOME/.config/gh-inzenyri" gh auth login --hostname github.com`; skript pak tento profil nastavuje automaticky.
- Před dokončením zkontroluj diff a syntaxi nebo sestavení podle rozsahu změny. Necommituj ani nepushuj ručně, pokud o to uživatel nepožádal.
