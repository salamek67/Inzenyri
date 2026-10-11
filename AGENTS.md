# Pokyny pro práci na projektu

- Před změnou si ověř aktuální stav souborů a relevantní části projektu. Zachovej existující rozhodnutí a styl; neopakuj zamítnuté pokusy bez nového důvodu.
- Ke každé změně webu nebo Androidu přidej stručný řádek přímo pod příslušnou část v `CHANGELOG.md`. Při změně obou částí přidej řádek do obou. Novější položky patří výš; nepoužívej sekci `Unreleased`. Python skript vlastní changelog nemá.
- Řádek changelogu popisuje výsledek pro uživatele, ne pouze název změněného souboru. Při bumpu webu nebo Androidu skript použije položky dané kategorie pro commit/release poznámky; po úspěšném pushi a případném vytvoření releasu kategorii vyčistí samostatným commitem s prefixem `[ci skip]`.
- Verzi webu bumpuj přes nabídku v `script.py`. Skript vloží verzi webu do zprávy commitu.
- Verzi Android aplikace bumpuj přes nabídku v `script.py`. Skript vloží verzi do zprávy commitu, pushne commit a vytvoří GitHub Release označený samotnou verzí bez prefixu `v`; GitHub CLI (`gh`) musí být přihlášené. Python skript nemá vlastní verzi ani release.
- `script.py` používá pro příkazy `gh` oddělený profil `~/.config/gh-inzenyri`. Přihlašuje se do něj jednou příkazem `GH_CONFIG_DIR="$HOME/.config/gh-inzenyri" gh auth login --hostname github.com`; skript pak tento profil nastavuje automaticky.
- Před dokončením zkontroluj diff a syntaxi nebo sestavení podle rozsahu změny. Necommituj ani nepushuj ručně, pokud o to uživatel nepožádal.
