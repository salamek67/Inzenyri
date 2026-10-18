# AI loga

Jednobarevné SVG pocházejí z https://github.com/lobehub/lobe-icons
(`packages/static-svg/icons`: openai, claude, gemini, deepseek), licence MIT.
Pro projekt byly upraveny barvy a rozměry. Android používá vektorové převody
týchž cest v `app/src/main/res/drawable/ai_*.xml`.

Android vektory mají všechny souřadnice a příznaky oblouků oddělené mezerami.
Minifikované SVG příznaky jako `00` nebo `01` před další souřadnicí Android
nepřečte stejně jako prohlížeč; při převodu je nutné je rozdělit na samostatné hodnoty.

Loga označují nástroj kontroly zvolený maintainerem, nikoliv partnerství s poskytovatelem.
