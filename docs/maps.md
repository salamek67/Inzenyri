# Zeměpisné mapy v procvičování

Mapové otázky fungují na webu i v Androidu. V lokálním editoru při vytváření
otázky vyber **Zeměpisná mapa**. Zvol podklad a režim; správné oblasti nebo
místo potom zadej kliknutím do mapy. V Python správci je stejný typ dostupný
jako `[m]apa`.

## Označování oblastí

Uživatel označí jeden nebo více států/území. Dalším klepnutím výběr zruší.
Správná odpověď znamená přesně všechny požadované oblasti bez dalších. Po
vyhodnocení jsou správné oblasti zelené a chybně přidané červené. Názvy států se
před vyhodnocením v procvičování nezobrazují; v autorském editoru je najdeš při
najetí.

```json
{
  "type": "map",
  "prompt": "Označ Česko, Německo a Rakousko.",
  "map": "europe",
  "mode": "regions",
  "targets": ["CZE", "DEU", "AUT"]
}
```

`targets` je neprázdný seznam nejvýše 200 různých ID dostupných oblastí.
Přijímají se pouze oblasti zasahující do zvoleného výřezu. ID vycházejí z
`ISO_A3_EH`, případně `ADM0_A3`; najdeš je v editoru nebo ve výpisu správce.
Tento režim vybírá existující státní polygony; nejde o kreslení libovolných
vlastních polygonů. Města, pohoří a další místa lze procvičit špendlíkem.

## Špendlík do mapy

Uživatel klepnutím umístí bod. Před vyhodnocením ho může přesunout novým
klepnutím nebo odpověď vymazat. Odpověď je správná při vzdálenosti menší nebo
rovné toleranci. Po vyhodnocení mapa ukáže správný bod i vzdálenost od něj.

```json
{
  "type": "map",
  "prompt": "Umísti špendlík do Prahy.",
  "map": "europe",
  "mode": "pin",
  "target": [14.4378, 50.0755],
  "toleranceKm": 50,
  "explanation": "Praha leží na Vltavě ve středních Čechách."
}
```

`target` obsahuje **délku, šířku** v desetinných stupních WGS84, ve stejném
pořadí jako GeoJSON. Západní délka a jižní šířka jsou záporné. `toleranceKm` je
číslo od 1 do 2000. Cíl musí být uvnitř zvoleného výřezu. Oba přehrávače
používají haversinovu vzdálenost s poloměrem Země 6371,0088 km; tolerance tak
nezávisí na velikosti displeje ani přiblížení.

## Mapy a ovládání

K dispozici jsou výřezy `world`, `europe`, `africa`, `asia`, `northAmerica`,
`southAmerica` a `oceania`. Jde o výřezy stejného podkladu, nikoli filtrování
států podle politického nebo kontinentálního členství. Výřezy mohou zahrnovat
části sousedních kontinentů a nezahrnují všechna vzdálená zámořská území.

- Přiblížení a oddálení: tlačítka **+ / −**, na dotykových zařízeních dvěma
  prsty.
- Posun: tažením mapy. Klepnutí bez tažení zadá odpověď.
- **Celá mapa** obnoví výchozí výřez a zachová odpověď.
- **Vymazat** odstraní odpověď, dokud není vyhodnocená.
- Na webu lze oblasti vybrat Enterem nebo mezerníkem při zaměření klávesnicí.
  Špendlík lze na webu i s hardwarovou klávesnicí Androidu posouvat šipkami;
  Shift zvětší krok.

Po vyhodnocení lze mapu dál přibližovat a posouvat, ale odpověď už nelze změnit.
Nezodpovězenou nebo neplatnou otázku lze přeskočit. Výsledky, opakování chybných
otázek a ukládání průběhu používají dosavadní společný mechanismus. Samotné
rozpracované výběry a špendlíky se lokálně neukládají, stejně jako rozepsané
texty.

## Podklad a offline režim

Podklad [Natural Earth 1:110m](https://www.naturalearthdata.com/) obsahuje 177
států a území a je veřejným dílem (public domain). Je schematický; malé státy,
vnitrostátní kraje, města a podrobné hranice v něm nejsou. Pro takové úlohy zvol
špendlík a přiměřenou toleranci, případně detailnější otázku jiného typu.

Web, editor a Android používají stejný `web/maps/world.json`. Android ho balí
přímo do APK, web ho ukládá mezi soubory pro offline použití. Nevytváří se žádné
externí mapové požadavky, nepotřebuješ API klíč ani službu mapových dlaždic.
Podrobnosti o původu jsou v [popisu podkladu](../web/maps/README.md).

Mapový typ vyžaduje aktualizované klienty. Stávající typy otázek ani uložená
data ostatních kvízů se nemění. Verze aplikace, webu ani serveru se přidáním
typu automaticky nezvyšuje.
