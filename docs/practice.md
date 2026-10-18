# Doplňovací otázky v procvičování

Typ `fill` je společný pro web i Android. V Python správci jej vytvoříš
volbou `[d]oplňování` při sestavování kvízu.

```json
{
  "type": "fill",
  "prompt": "Doplň nejmenší celé kladné koeficienty. Vyplň i jedničky.",
  "template": "{{1}} H₂ + {{2}} O₂ → {{3}} H₂O",
  "answers": ["2", "1", "2"],
  "numeric": true,
  "explanation": "Na obou stranách jsou 4 atomy H a 2 atomy O."
}
```

- Políčka musí být číslovaná od `{{1}}` postupně, každé právě jednou.
- `answers` obsahuje jednu správnou hodnotu pro každé políčko ve stejném pořadí.
  Nejde o alternativní odpovědi jako u typu `text`: musí souhlasit všechna políčka.
- `numeric: true` zobrazí číselnou klávesnici; chemické koeficienty vyplňuj včetně 1.
- Pro běžný text použij `numeric: false`; volitelně lze nastavit `ignoreCase: true`.
- `template` je prostý text, ne HTML nebo Markdown. Chemické indexy zapisuj jako Unicode
  dolní indexy (`H₂O`), které se při vyplňování nemění.
- Výsledky se ukládají po jednotlivých otázkách stejně jako u ostatních kvízů.
  Správná odpověď znamená správně vyplněnou celou rovnici.

Nový typ vyžaduje aktualizovanou webovou nebo Android aplikaci; starší typy otázek
zůstávají beze změny. Lokální Android preview standardně načítá DB z originu,
takže nové lokální otázky se v něm bez aktualizace zdroje databáze samy neobjeví.

## Lokální ukládání výsledků

Web i Android ukládají poslední průběh každého kvízu včetně výsledku, nikoli historii
všech pokusů. Ukládají pouze hashe, pořadí otázek a stavy odpovědí; text otázek,
napsané odpovědi ani hesla do tohoto úložiště nepatří. Změna obsahu kvízu zneplatní
jeho starý výsledek.

Záznamy se odstraňují po 90 dnech bez procvičování daného kvízu a při překročení
200 kvízů se mažou nejstarší. Úklid probíhá při inicializaci úložiště a při ukládání;
prošlý záznam se odstraní i při čtení. Dosavadní záznamy bez časového údaje dostanou
při přechodu na tento formát novou 90denní lhůtu. Na webu se při nedostupném
localStorage používá pouze paměť pro aktuální otevření stránky.
