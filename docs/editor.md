# Lokální editor v prohlížeči

V kořeni projektu spusť:

```sh
python3 script.py server
```

Stejný příkaz `server` funguje v interaktivní nabídce správce. Editor se
automaticky otevře v nové kartě výchozího prohlížeče. Pokud otevření není
dostupné, použij celý odkaz vypsaný do terminálu, například
`http://127.0.0.1:8765/#…`. Část za `#` je náhodný přístupový klíč pro právě
spuštěný server. Server je dostupný pouze na tomto počítači; pro jiný port
použij například `python3 script.py server 8766`. Ctrl+C zastaví server a uvolní
jeho port. Při spuštění z nabídky se vrátíš do správce; při přímém spuštění se
skript ukončí. Karta prohlížeče zůstane otevřená, ale server už neběží. Editor
neprovádí automatické mazání minulých položek.

Používá stejné Python závislosti jako správce (`scripts/requirements.txt`).
Pokud už je databáze nastavená a lokálně uložené heslo platí, editor se otevře
rovnou. Jinak zadáš heslo v prohlížeči. Editor heslo z formuláře neukládá na
disk. Chybějící databázi nejdřív nastav podle [základního návodu](tutorial.md).

## Rychlé zadávání

- Vlevo hledej podle názvu, popisu nebo data a filtruj úkoly, testy a akce.
- Výchozí přehled ukazuje dnešní a nadcházející položky. Přepnutím období
  zobrazíš také minulé položky, které editor automaticky neodstraňuje.
- **Přidat položku** otevře formulář. Datum je povinné, čas je volitelný;
  prázdný čas znamená **Celý den**.
- Pro úkoly můžeš zadat řešení a označit AI kontrolu. Popis a řešení podporují
  Markdown. Záložka **Náhled** ukáže formátovaný text před uložením.
- **Duplikovat** připraví novou položku podle vybrané, včetně procvičování.
- **Ctrl+S** (na macOS Cmd+S) uloží položku. **Ctrl+K** otevře hledání.
- Po úspěšném přidání se formulář zavře a položka zůstane v přehledu. Při chybě
  ukládání zůstane formulář rozepsaný. Úprava existující položky zůstává
  otevřená.
- Rozpracovaná data zůstávají pouze v paměti stránky. Při odchodu editor
  upozorní na neuložené změny; obsah formulářů se neukládá do úložiště
  prohlížeče.

## Procvičování

K testu přidej libovolný počet pojmenovaných sad. V každé zapni nebo vypni
promíchání otázek a zvol typ jednotlivých otázek:

| Typ             | Co vyplnit                                                        |
| --------------- | ----------------------------------------------------------------- |
| Textová odpověď | Zadání a správné alternativy, každou na nový řádek                |
| Výběr možností  | Zadání, možnosti po řádcích a správnou možnost                    |
| Doplňování      | Zadání, text s `{{1}}`, `{{2}}`, … a hodnoty políček po řádcích   |
| Čárky ve větě   | Větu bez čárek a správnou větu s čárkami                          |
| Kartička        | Přední a zadní stranu                                             |
| Zeměpisná mapa  | Podklad a režim; správné oblasti nebo bod vyber kliknutím do mapy |

Každá otázka může mít vysvětlení. Nová otázka přebírá typ předchozí, takže sady
kartiček nebo doplňování sestavíš bez opakovaného přepínání typu. Náhled ukazuje
i správné odpovědi; jde o autorskou kontrolu obsahu, nikoli běh testu.
Matematické značky zůstávají uložené beze změny; tento lokální náhled
nezpracovává LaTeX, jeho zobrazení ověř ve veřejném webu nebo Androidu.

## Ukládání a zveřejnění

Server i jeho rozhraní mají společnou vlastní verzi ve formátu
`major.minor.patch`. Zobrazuje se v záhlaví editoru a při spuštění v terminálu.
API ji vrací jako `serverVersion`; dosavadní pole `version` dál označuje verzi
databáze. Hodnota je uložená pouze v konstantě `SERVER_VERSION` v
`scripts/editor_server.py`.

Pro zvýšení použij `bump` v hlavním správci. Příkaz `commit` nabídne zvýšení
také při změně `scripts/editor_server.py` nebo souborů v `scripts/editor/`.
Vybereš major/minor/patch, stejně jako u webu a Androidu. Verze serveru je
nezávislá na ostatních verzích; nemá vlastní tag, release ani changelog. Po
změně verze restartuj běžící server, aby novou verzi načetl.

**Uložit položku** aktualizuje `private/data.private.json` i kořenový
`data.enc.json` stejným šifrovacím formátem jako Python správce. Zachovává ID
existujících položek, ostatní položky i verzi DB; nové položce vytvoří nové ID.
Při odstranění položky nebo procvičování se editor nejdřív zeptá.

Před načtením i zápisem kontroluje shodu privátní a šifrované databáze. Pokud se
liší, zastaví práci a požádá o jejich sjednocení ve správci; žádnou kopii slepě
nepřepíše. Pokud data změní jiná záložka nebo skript, uložení odmítne a ponechá
formulář rozepsaný. Nejdřív si zkopíruj potřebné rozepsané texty, potom použij
**Načíst aktuální data** v levém panelu a proveď úpravu znovu.

Editor nevytváří commity, tagy ani releasy, nepushuje a nemění verzi aplikace
ani webu. Po dokončení použij běžný příkaz `commit` ve správci. Starší veřejný
server `python3 scripts/serve.py` zůstává samostatně dostupný na portu 8000.

Soubory editoru jsou v `scripts/editor/`, server v `scripts/editor_server.py`;
nejsou součástí veřejného webu ani Pages artefaktu. HTTP server zpřístupňuje
pouze výslovně vybrané soubory rozhraní a autorizované API, nikdy složku
`private/`.
