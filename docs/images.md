# Obrázky v otázkách

Ke každému typu otázky lze připojit jeden obrázek: fotografii, schéma, graf nebo
vlastní slepou mapu. Způsob odpovědi se nemění; zvol například výběr možností,
psanou odpověď nebo kartičku. Obrázek se zobrazuje na webu i v Android aplikaci,
včetně offline režimu.

V lokálním editoru použij pole **Obrázek k otázce**. Vyber PNG nebo JPEG do 10
MB a 32 megapixelů. Editor obrázek zmenší na nejvýše 1600 bodů na delší straně,
převede na JPEG a odstraní metadata souboru. Průhlednost dostane bílé pozadí.
Výsledek musí mít nejvýše 512 KB; pokud se nevejde, editor vyzve k menšímu
výřezu. Předchozí obrázek při chybě zůstává zachovaný. Volitelně doplň popis pro
čtečku obrazovky (nejvýše 1000 znaků). Náhled ukazuje uložený obrázek. Tlačítkem
**Odstranit obrázek** jej odebereš; změnu potvrď uložením položky. Při změně
typu otázky zůstane obrázek zachovaný.

Terminálový správce při tvorbě otázky nabídne cestu k obrázku. Tam už musí být
soubor PNG/JPEG do 512 KB a nejvýše 2048 × 2048 bodů; správce jej automaticky
nezmenšuje. Obrázky jedné položky mohou mít dohromady nejvýše 6 MB.

## Uložení a mazání

Android umí načíst i větší šifrovanou databázi z GitHubu, pokud běžná odpověď
API už neobsahuje její Base64 obsah. Kontrola souběžných změn při odesílání
zůstává zachovaná.

Obrázek je přímo součástí otázky v databázi, nikoliv veřejným souborem nebo
externím odkazem. Spolu s ostatním obsahem se šifruje do `data.enc.json`.
Lokální nešifrovaná pracovní kopie zůstává stejně jako ostatní data v ignorované
složce `private/`. Editor a přehrávače zobrazují obrázek v paměti; nevytvářejí
samostatné soubory příloh ani trvalou nešifrovanou cache.

Smazáním obrázku, otázky, sady nebo celé položky se po uložení odstraní i
vložená obrazová data z aktuální databáze. Stejně funguje automatické
promazávání minulých položek ve správci. Nevznikají osiřelé soubory příloh.
Starší zálohy a Git historie mohou stále obsahovat starší šifrovanou databázi;
mazání aktuální položky tyto historické kopie nepřepisuje.

## Společný formát

Volitelné pole `image` u otázky obsahuje objekt s poli `mime` (`image/png` nebo
`image/jpeg`), `data` (samotné Base64 bez prefixu `data:`), `width`, `height`
(skutečné rozměry v bodech) a `alt` (textový popis, může být prázdný). Server
ověřuje typ, rozměry, hlavičku souboru a limity před uložením. SVG, HTML ani
vzdálené URL nejsou podporované. Starší otázky bez pole `image` fungují beze
změny.
