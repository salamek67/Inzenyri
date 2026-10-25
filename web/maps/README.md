# Mapové podklady

`world.json` obsahuje zjednodušené hranice 177 států a území z Natural Earth
1:110m, vydání v5.1.2. Souřadnice jsou v pořadí zeměpisná délka, šířka (WGS84),
zaokrouhlené na tři desetinná místa. Zachovány jsou ostrovy a otvory polygonů.
Podklad je určený pro školní přehledové mapy, nikoli přesné určení hranic. Malé
státy a některá území v tomto měřítku nejsou obsažené.

- Zdroj:
  https://github.com/nvkelso/natural-earth-vector/blob/v5.1.2/geojson/ne_110m_admin_0_countries.geojson
- Licence: public domain — https://www.naturalearthdata.com/about/terms-of-use/
- `id` je ISO_A3_EH, případně ADM0_A3. `code` slouží k lokalizaci názvu.
- `polygons` obsahuje polygony, každý je seznam prstenců: vnější obrys a otvory.

Stejný soubor používá web, lokální editor i Android (přibalený jako asset).
