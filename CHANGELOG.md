# Changelog

Nové položky přidávej pod Android. Při bumpu aplikace je skript vloží do GitHub Release a odstraní je z tohoto souboru ve stejném commitu.

## Android
- Aplikace se při spuštění automaticky odemkne bezpečně uloženým heslem; automatické přihlášení lze vypnout v nastavení.
- Před stažením aktualizace se zobrazí vlastní potvrzovací okno s changelogem z GitHub Release.
- Přihlášení QR kódem má vlastní obrazovku na výšku, přehledný zaměřovací rámeček a tlačítko svítilny.
- Debug i release sestavení používají Gradle 9.8.1.
- Oznámení mají čitelnější ikonu kalendáře s fajfkou sladěnou s aplikací.
- Propojení GitHubu a push DB ověřují instalaci i oprávnění GitHub App; chyby přístupu mají postup nápravy a účet lze znovu propojit v nastavení.
