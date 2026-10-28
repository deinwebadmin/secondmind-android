# secondmind – Android

Notizen und To-Do-Listen mit **Google-Drive-Synchronisation**. Alles bleibt lokal auf dem
Gerät gespeichert (IndexedDB) und funktioniert offline – sobald Verbindung da ist, wird
synchronisiert (Standard: alle 60 Sekunden).

Identische App wie `secondmind-windows`, nur mit nativer Android-Schale (Capacitor).
Die Web-App liegt im Ordner `app/` und ist in beiden Repos identisch.

```
secondmind-android/
├── app/                          Web-App (HTML/CSS/ES-Module)
│   └── js/cap/index.js           @capacitor/core (vendored) für das native Plugin
├── android/                      Capacitor-Androidprojekt (Gradle)
│   ├── app/src/main/java/de/webadmin/secondmind/
│   │   ├── MainActivity.java     registriert das Plugin
│   │   └── SecondMindPlugin.java Loopback-OAuth + Keystore-Verschlüsselung
│   ├── keystore.properties.example    Vorlage für die lokale Signatur-Konfiguration
│   └── app/build.gradle          signingConfig (liest keystore.properties / Umgebung)
└── capacitor.config.json
```

## APK installieren (Sideload)

1. [`secondmind-1.0.26.apk`](https://github.com/deinwebadmin/secondmind-android/releases/download/v1.0.26/secondmind-1.0.26.apk)
   aus dem Release herunterladen (oder selbst bauen, siehe unten).
2. Auf dem Gerät: **Einstellungen → Apps → Installieren unbekannter Apps** → Browser/File-Manager erlauben.
3. APK antippen und installieren.
4. App starten → Google Drive verknüpfen (siehe unten).

> **Signaturschlüssel beachten:** App-Updates müssen mit demselben Schlüssel signiert sein,
> sonst lehnt Android sie ab. Der Schlüssel liegt bewusst **nicht** in diesem Repo – siehe
> [Signaturschlüssel erzeugen](#signaturschlüssel-erzeugen). Keystore-Datei und Passwort
> unbedingt sichern (Passwortmanager + Backup); geht der Schlüssel verloren, ist wieder eine
> Neuinstallation nötig.

## Signaturschlüssel erzeugen

Einmalig, **außerhalb** des Repos (JDK mit `keytool` nötig):

```powershell
keytool -genkeypair -v -keystore $HOME\.secondmind\secondmind.keystore -alias secondmind `
  -keyalg RSA -keysize 4096 -validity 36500
copy android\keystore.properties.example android\keystore.properties   # Pfad + Passwörter eintragen
```

`android/keystore.properties` ist gitignored. Alternativ die Umgebungsvariablen
`SM_KEYSTORE`, `SM_STORE_PASSWORD`, `SM_KEY_ALIAS`, `SM_KEY_PASSWORD` setzen (z. B. in CI).
Alle künftigen Releases müssen mit **diesem** Schlüssel gebaut werden, dann funktioniert das
In-App-Update weiter.

## Entwicklung: gemeinsamer `app/`-Ordner

Die Web-App in `app/` ist in `secondmind-windows` und `secondmind-android` identisch
(ausgenommen `app/js/cap/`, nur Android). **Änderungen in `secondmind-windows` machen** und von dort hierher spiegeln
(`node scripts/sync-app.mjs ../secondmind-android` im Windows-Repo):

```
node scripts/sync-app.mjs ../secondmind-windows --check   # Abweichungen prüfen
```

Beim Release `APP_VERSION` (`app/js/util.js`), `package.json` und – für Android –
`versionCode`/`versionName` in `android/app/build.gradle` gemeinsam hochzählen.

## Google Drive einrichten (einmalig)

1. <https://console.cloud.google.com> → **Neues Projekt** anlegen (z. B. `secondmind`).
2. **APIs & Dienste → Bibliothek** → „Google Drive API“ → **Aktivieren**.
3. **OAuth-Bestätigungs-Bildschirm**: Nutzertyp **External**, App-Name `secondmind`,
   Scopes `.../auth/drive.file`, `openid`, `email`, `profile`, eigene Adresse als **Testuser**.
4. **Zugangsdaten → OAuth-Client-ID** → Anwendungstyp **Desktop-App** (wichtig!).
5. Client-ID in secondmind eintragen: **Einstellungen → Google Drive → OAuth Client-ID**.
6. **„Mit Google verknüpfen“** → System-Browser öffnet sich → Anmeldung → App übernimmt den Code automatisch.

**Wichtig – einmal „In Veröffentlichung bringen“ (dauerhaftes Anmelden):** Solange der
OAuth-Client im Status **„Testing“** steht, läuft das Refresh-Token nach 7 Tagen ab und du
musst erneut verknüpfen. Deshalb:

1. **APIs & Dienste → OAuth-Bestätigungs-Bildschirm** öffnen.
2. Ganz unten **„In Veröffentlichung bringen“** („Publish app“) klicken.
3. Google warnt, dass die App nicht geprüft wurde – **„Zurück zur Google Cloud Console“**
   („Continue“) wählen. Die App nutzt ausschließlich dein eigenes Konto; für den
   Privatgebrauch ist das völlig ausreichend.
4. Danach bleibt die Anmeldung dauerhaft gültig. Erneutes Verknüpfen ist nur nötig, wenn du
   die Verbindung selbst trennst.

**Die Client-ID niemals austauschen:** Google gibt ein Refresh-Token nur an genau die
Client-ID weiter, mit der es ausgestellt wurde. Tragst du eine andere ID ein, ist die
Anmeldung weg – obwohl die Daten im Drive-Ordner „secondmind“ weiterhin da sind. Verwende
deshalb auf **allen** Geräten (Windows und Android) dieselbe Client-ID.

Der Flow funktioniert über einen lokalen Loopback-Server (`http://127.0.0.1:PORT`) in der App –
deshalb reicht eine einzige Desktop-Client-ID für Windows *und* Android, ohne SHA-1-Registrierung.
Falls der Browser den Redirect nicht erreicht: in der App erscheint ein Eingabefeld, in das du
die angezeigte `http://127.0.0.1:…`-Adresse manuell einfügen kannst.

## Gemini-KI einrichten (optional)

secondmind kann eine optionale KI begleiten lassen: Nach deinen To-Dos fragen, eine Notiz
zusammenfassen oder Vorschläge formulieren – angetrieben von Googles Gemini-Modellen.
Welches Modell läuft, wählst du selbst: in den Einstellungen gibt es ein **Dropdown** mit
den aktuellen Varianten (Standard: **`gemini-3.8-flash`**). Die Funktion ist standardmäßig
**aus** und lässt sich jederzeit wieder abschalten.

### 1. API-Key besorgen (kostenlos)

1. <https://aistudio.google.com/apikey> auf dem Gerät (oder am PC) öffnen und mit dem
   Google-Konto anmelden – dasselbe Konto wie bei der Drive-Verknüpfung ist praktisch.
2. **Create API key** wählen (beim ersten Mal ein Projekt anlegen bzw. wählen).
3. Den erzeugten Schlüssel (beginnt mit `AIza…`) kopieren.

> Im kostenlosen Tier des Google AI Studio ist der Privatgebrauch in der Regel ausreichend –
> secondmind nutzt ausschließlich dein eigenes Google-Konto und dein Kontingent.

### 2. In secondmind aktivieren

1. **Einstellungen → „Gemini KI (optional)“** → **Gemini-Integration aktivieren**.
2. Den Schlüssel ins Feld **API-Key** einfügen – er wird sofort gespeichert (das Feld
   maskiert die Eingabe). Der Key bleibt auf deinen Geräten und wird mit deinen
   Einstellungen über Google Drive auch auf dein zweites Gerät übernommen. Unter
   **Modell** wählst du danach das gewünschte Gemini-Modell (Dropdown, Standard:
   Gemini 3.8 Flash).
3. **Zugriff auf deine Inhalte** wählen:
   - **Nur exponierte** *(Standard)*: Die KI sieht ausschließlich Karten, die du freigibst –
     pro Karte (Liste/Notiz) sitzt neben dem Pin ein **Gemini-Sparkle-Button**:
     grau = nicht freigegeben, farbig = freigegeben; ein Tipp schaltet um („Für Gemini freigegeben“).
   - **Alle Einträge**: Die KI darf alle aktiven Listen und Notizen auswerten (auf ausdrückliche Wahl).
4. Fertig – in der Topbar oben rechts erscheint der **Gemini-Button** (Sparkle), der den Chat
   öffnet. Derselbe Chat ist in den Einstellungen über **„Mit Gemini chatten“** erreichbar.

### Was die KI sieht (und was nicht)

- Anfragen gehen **direkt von deinem Gerät zur Google Gemini API** – es gibt keinen
  Zwischenserver von secondmind.
- Übermittelt werden nur die freigegebenen Inhalte: Listentitel, Punkte mit Status,
  Tags und Terminen (inkl. Unterpunkte) sowie Titel und Text exponierter Notizen
  (je Notiz bis 6.000 Zeichen).
- Die KI antwortet auf Deutsch und wird angewiesen, ausschließlich diese Inhalte zu
  nutzen und nichts zu erfinden.
- Der API-Key wird nur für diese Anfragen genutzt; ungültige Schlüssel meldet secondmind
  verständlich („muss mit „AIza“ beginnen, keine Leerzeichen“).

## Build aus dem Quellcode

Voraussetzungen: **Node.js ≥ 20**, **JDK 21**, **Android SDK** ( cmdline-tools + `platform-tools`,
Lizenzen akzeptiert, `ANDROID_HOME` gesetzt).

```powershell
npm install                     # Capacitor-Abhängigkeiten
npx cap sync android            # Web-Assets in das Androidprojekt kopieren
cd android
.\gradlew.bat assembleRelease   # signiertes APK bauen
# Ergebnis: android\app\build\outputs\apk\release\app-release.apk

.\gradlew.bat assembleDebug     # Debug-APk (mit Debug-Signatur)
```

## Natives Plugin

`SecondMindPlugin.java` stellt der Web-App zur Verfügung:

| Methode | Zweck |
|---|---|
| `openAuth({url})` | Startet einen lokalen Server auf `127.0.0.1`, setzt die passende `redirect_uri` in der Google-Auth-URL, öffnet den System-Browser und löst mit `{code, redirectUri}` auf, sobald Google zurückleitet. |
| `encrypt({value})` / `decrypt({value})` | Schlüssel im **Android-Keystore** (AES-256-GCM) – OAuth-Tokens werden nie im Klartext gespeichert. |
| `openExternal({url})` | Links in der Standard-Browser-App öffnen. |
| `scanQr({timeoutMs})` | Öffnet die Kamera (ZXing, vollständig offline) und löst mit dem gescannten Text auf – für die Schlüsselübergabe. Fordert `CAMERA` zur Laufzeit an; ohne Recht meldet sich die Web-App mit der Webcam (Desktop-Fallback). |
| `pairSend({ips, port, body})` | Schickt den verschlüsselten Schlüssel über das WLAN an das andere Gerät. Nativ, weil die WebView die App von `https://localhost` lädt und dort keinen `http://`-Anfragen nachgehen darf (Mixed Content). Alle Adressen aus dem QR-Code werden mit 2 s Timeout versucht. |

## Synverhalten

| Situation | Verhalten |
|---|---|
| Änderung lokal | Sofort gespeichert, ~2,5 s später automatisch hochgeladen |
| Regelmäßiger Abgleich | Alle 60 s (30 s / 2 Min / 5 Min wählbar) |
| Keine Verbindung | Alles bleibt lokal, Status-Punkt wird grau |
| Verbindung zurück | Sofortiger Abgleich |
| Fotos/Dateien | Nach Drive-Ordner `secondmind`; Bilder werden erst beim Anzeigen geladen |

## Funktionen

Suche oben, **+** unten rechts, Logo oben links (Menü: Start, Archiv, Memory, Einstellungen) ·
Notizen mit Titel, Text, Links, Fotos/Dokumenten, Farbe, Tags, Pinnen und To-Do-Verknüpfung ·
To-Do-Vorlagen **Woche** (7 Tage, verschiebbar, Historie/Archiv/Memory), **Standard** und
**Simpel** (abgehakte Punkte verschwinden sofort) · Unterpunkte mit Fortschrittslinie ·
Wischen: links = Archiv/Memory, rechts = Löschen mit Rückfrage · Archiv nach Jahr
(Punkte landen in „*Name der Liste* – Archiv“) · Memory: eine Liste pro Jahr ·
Farbcodierung grün/gelb/rot/neutral · Einstellungen für Memory, Löschschutz, Design, Sync-Intervall,
Export/Import.

## Sicherheit

- **Ende-zu-Ende-Verschlüsselung**: Jeder Datensatz wird auf dem Gerät mit einem
  256-Bit-Hauptschlüssel (AES-256-GCM) verschlüsselt; in Google Drive liegt ausschließlich
  Chiffretext. Der Hauptschlüssel wird lokal aus dem Android-Keystore abgeleitet und
  verlässt das Gerät nur, wenn du ihn explizit über QR-Code, Netzwerk oder `.smkey`-Datei
  an ein zweites Gerät gibst – niemals über Google.
- Ohne Sicherung des Schlüssels (siehe „Schlüssel & Geräte“) sind die Daten bei einem
  Geräteverlust endgültig weg; secondmind hat keine Kopie und kann sie nicht wiederherstellen.
- OAuth-Tokens verschlüsselt im **Android-Keystore** (AES-256-GCM, Hardware-gebunden).
- Standard-Scope `drive.file` – die App sieht nur Dateien, die sie selbst angelegt hat
  (Ordner `secondmind` in deinem Drive).
- Kein Server, kein fremdes Konto – nur du und dein Google-Konto.

## Lizenz

MIT

## Siehe auch

- [secondmind-windows](https://github.com/deinwebadmin/secondmind-windows) – dieselbe App für Windows (gleiche Google-Client-ID, gleicher Drive-Ordner)

## Änderungen

### 1.0.26
- **Anmeldung läuft nicht mehr direkt in die Schlüssel-Seite**: Wurde „Mit Google anmelden" gedrückt, ohne dass eine Client-ID hinterlegt war, sprang die App trotz des Fehlers weiter zur Seite „Schlüssel & Geräte" – als wäre alles gelungen. Jetzt bleibst du auf der Begrüßungsseite und siehst, was zu tun ist
- **Client-ID direkt nachtragen**: Ohne Google Client-ID startet keine Anmeldung; es öffnet sich beim Klick ein Eingabefeld und die Anmeldung läuft nach dem Speichern automatisch weiter
- **OAuth Client-ID geht nicht mehr verloren**: Ein Einstellungs-Datensatz aus Google Drive, der die Client-ID nicht führt, überschreibt deinen lokal eingetragenen Wert nicht mehr mit dem Standard – dasselbe gilt für den Gemini-API-Key. Ein auf einem Gerät bewusst geleerter Wert wird weiterhin synchronisiert
- **Gesperrte Einstellungen bleiben unangetastet**: Ist der Geräteschlüssel verloren, schreibt secondmind seine Voreinstellungen weder über deine echten Einstellungen noch nach Google Drive – deine Client-ID und dein API-Key bleiben so erhalten und sind nach dem Schlüssel-Import sofort wieder da
- **Klarer Hinweis bei blockiertem Anmeldefenster**: Wird das Google-Fenster vom Browser blockiert, sagt secondmind das jetzt ausdrücklich, statt still zu scheitern

### 1.0.25
- **Ersteinrichtung mit Onboarding**: Nach der Installation fragt secondmind, ob du dich mit Google anmelden willst oder **nur lokal** nutzt – ohne Anmeldung bleibt alles auf dem Gerät, der Drive-Sync lässt sich jederzeit nachholen
- **Identität sichtbar**: In den Einstellungen siehst du Avatar, Name und E-Mail deines verbundenen Kontos; ein Kontowechsel verworft alte Drive-Daten, statt sie mit deinen zu vermischen
- **Abgelaufene Verbindung erkannt**: Ist die Google-Anmeldung abgelaufen, erscheint ein Banner „Verbindung abgelaufen – erneut verknüpfen“ mit einem einzigen Tipp; zuvor wird eine stille Wiederanmeldung versucht – deine Daten bleiben dabei immer erhalten
- **Ende-zu-Ende-Verschlüsselung**: Notizen, To-Dos, Einstellungen und Anhänge werden auf dem Gerät mit einem 256-Bit-Schlüssel (AES-256-GCM, PBKDF2 mit 310.000 Runden) verschlüsselt; in Google Drive liegt ausschließlich Chiffretext
- **Schlüssel in vier Schritten übergeben**: Direkt-QR-Code, Kamera-Scan, Netzwerk-Übergabe mit 6-stelligem Sicherheitscode oder .smkey-Datei – der Schlüssel verlässt das Gerät nur über diese Wege und **nie über Google** (Android: Kamera-Scan und Netzwerk-Senden laufen nativ über das Plugin)
- **Sicherungspflicht für den Schlüssel**: Eine eigene Seite mahnt, den Schlüssel auf einem zweiten Gerät oder Stick zu sichern, mit Warnhinweis bis es erledigt ist
- **Export im Klartext**: Der Export enthält alle Inhalte unverschlüsselt (inklusive Gemini-API-Key), aber **niemals** Anmelde-Token, Geräteschlüssel oder Passwörter; der Import stellt alles 1:1 wieder her, auch offline
- **Ehrliches Schutzziel**: Die Einstellungen erklären, was der Schlüssel schützt (Inhalte auf dem Gerät und in Drive) und was nicht (Zugriff auf dein Benutzerkonto)

### 1.0.24
- **Modell-Dropdown richtig gefärbt**: Das Auswahlfeld für das KI-Modell ist jetzt wie alle anderen Eingabefelder gefüllt dargestellt – Android zeigte vorher das native, ungefüllte Widget; jetzt mit eigenem Pfeil und angepasster Auswahlliste

### 1.0.23
- **KI-Modell frei wählbar**: Neues Dropdown in den Einstellungen mit den aktuellen Gemini-Modellen – Standard ist **Gemini 3.8 Flash** (u. a. 3.7/3.6/3.5 Flash, Lite-Varianten, Pro-Vorschau); das veraltete `gemini-2.5-flash` entfällt ersatzlos
- **Inaktive Buttons deutlicher**: Alle inaktiven Button-Icons in den Punktzeilen (Abhaken, Verschiebe-Griff, Farbe, Kalender, Büroklammer, Archiv, Wiederherstellen) sind jetzt Orange wie das Tag-Icon
- **Kontrast zum Hintergrund**: Auf farbigen Karten weicht das Icon automatisch auf einen Ton mit hohem Kontrast zum Hintergrund aus (mind. ~3:1), wenn Orange der Hintergrundfarbe zu ähnlich wäre
- **Verständlicher Fehler beim Modellwechsel**: Lässt sich ein gewähltes Modell bei Google nicht abrufen, rät secondmind, in den Einstellungen ein anderes auszuwählen

### 1.0.22
- **In-App-Update funktioniert jetzt wirklich**: Der APK-Download läuft im Hintergrund (App bleibt bedienbar), der System-Installer wird sauber geöffnet – **scheitert er, öffnet automatisch der Browser mit der APK** (aus Benachrichtigungen/Downloads installieren)
- **Klare Rückmeldungen**: Jeder Schritt meldet jetzt, was passiert („Installer geöffnet – auf „Installieren“ tippen“)

### 1.0.21
- **Freigabe-Button immer sichtbar**: Der Gemini-Sparkle-Button neben dem Pin erscheint jetzt auf jeder Listen- und Notizkarte (Modus „Nur exponierte") – auch wenn die KI noch nicht eingerichtet ist; grau = nicht freigegeben, farbig = freigegeben
- **Topbar-Button korrekt ausblenden**: Bei deaktiviertem Gemini verschwindet der Chat-Button oben rechts jetzt zuverlässig

### 1.0.20
- **Kalender zum Abhaken**: jeder Eintrag (geplant oder ♻️) hat einen kleinen Kreis – ein Tipp hakt direkt im Kalender ab, erneuter Tipp macht es wieder offen
- **Wiederholungen mit Zeit & Dauer**: ♻️-Punkte optional mit Uhrzeit und Dauer (neue Felder im Intervall-Sheet) – positioniert im Stundenraster, gemeinsam mit normalen Terminen
- **Notiz dabei**: steht eine Notiz am wiederkehrenden Punkt, zeigt der Kalendereintrag den Notiztitel (📝) – Tippen öffnet die Notiz
- **Verschieben mit Rückfrage**: Ziehen einer Wiederholung in einen Zeitslot fragt „Für alle Wiederholungen" oder „Nur für diesen Tag" – Einzeltermine werden als Ausnahme gespeichert
- **Seitenbereich ↔ Kalender**: normale Punkte lassen sich beidseitig ziehen – vom Seitenbereich ins Raster und zurück (Termin herausgenommen, Dauer bleibt)

### 1.0.19
- **Stabile Reihenfolge**: Punkte in der Wiederkehrend-Liste ändern beim Aktivieren/Deaktivieren nie ihre Position – nur on/off
- **Alles über Buttons in der Zeile**: Farbwahl (🎨, Farbe wird in den Kalender übernommen), Intervall (♻️), An/Aus-Schalter – kein Menü mehr beim Anklicken, Text direkt editierbar
- **Wischen wie gewohnt**: nach rechts löschen (mit Rückfrage), nach links pausieren/aktivieren
- **„Repetitiv" als neue Vorlage** beim Plus-Button

### 1.0.18
- **Neue Liste „Wiederkehrend“ (♻️)**: wird automatisch angelegt – dort lebst du To-Dos, die sich wiederholen
- **Täglich / wöchentlich / monatlich** – mit Intervall („alle n Tage/Wochen/Monate“), Wochentagswahl und Monatstag
- **An- und ausschaltbar**: jeder Punkt hat einen Schalter zum Pausieren (ohne Löschen)
- **Kalender-Verknüpfung**: wiederkehrende Aufgaben erscheinen als ♻️-Einträge in der Wochenübersicht; Tippen öffnet die Bearbeitung

### 1.0.17
- **Optionale Gemini-KI-Integration**: in den Einstellungen an-/abschaltbar, API-Key wird lokal eingetragen (Modell `gemini-2.5-flash`)
- **Zugriffskontrolle**: Standard „Nur exponierte" – pro Karte (Liste/Notiz) ein Gemini-Sparkle-Button **neben dem Pin**, der diese Karte für die KI freigibt; alternativ „Alle Einträge" auf ausdrückliche Wahl
- **Gemini-Chat**: Sparkle-Button in der Topbar (sichtbar, sobald eingerichtet) sowie in den Einstellungen – Anfragen enthalten ausschließlich die freigegebenen Inhalte

### 1.0.16
- **Anpinnen**: Der Pin-Button zeigt immer das 📌-Icon – **grau solange nicht angepinnt, farbig wenn aktiv** (gilt für die Karten auf der Startseite sowie die Pins im Listen- und Notiz-Detail; 📍 ist verschwunden)

### 1.0.15
- **Dauer-Wahlrad**: Der Zeit-Button (📅) in der Zeile öffnet ein Dauer-Sheet mit iOS-artigem **Wahlrad** (15 Min bis 4 Std) – so lassen sich **Punkte und Unterpunkte schon vor dem Einplanen mit einer Dauer versehen** (Chip „⏱ 45 Min" in der Zeile, erneutes Tippen öffnet das Rad wieder; „Ohne Dauer" zum Zurücknehmen)
- **Kalender: Seitenbereiche zum Hineinziehen**: Punkte mit zugewiesener Dauer, aber noch ohne Termin, erscheinen **links und rechts** neben dem Wochenraster – per Fingerzug direkt ins Raster legen (Ziel-Spalte leuchtet auf, Vorschau mit 15-Minuten-Snap, die Dauer bleibt erhalten); Tippen auf den Chip öffnet das Termin-Sheet
- **Termin-Sheet**: Die Dauer wird ebenfalls per Wahlrad gewählt (statt Segmentschaltern), Datum und Uhrzeit bleiben unverändert

### 1.0.14
- **Pfeil-Button „›" in den Punktezeilen entfernt** – das Tippen auf die Zeile öffnet die Punkt-Optionen weiterhin

### 1.0.13
- Filterleiste: „Chrono" heißt jetzt **„Neuste"**, **„Alle"-Button entfernt**, „Dringend"/„Neuste" als unabhängig zuschaltbare Kipptaster (nochmal drücken = aus, beliebig kombinierbar)

### 1.0.12
- **Chrono zeigt die neuesten Karten zuerst** (vorher älteste zuerst)

### 1.0.11
- **Menü aufgeräumt**: „Start" und „Kalender" entfernt (Rückweg über Logo bzw. Kalender-Button in der Topbar)
- **Filterleiste auf der Startseite**: nach Labels (Tag-Chips mit Farbpunkt), Dringlichkeit (nur Listen mit rot/gelben Punkten, rote zuerst) und Chronologie (nach Erstellungsdatum, älteste zuerst) – plus „Alle" zum Zurücksetzen und Hinweis mit Reset bei 0 Treffern

### 1.0.10
- **Kalender: To-Do per Tipp einplanen**: Tipp auf ein leeres Zeitfenster im Wochenraster öffnet den Punktewähler mit Datum/Uhrzeit der Fläche vorausgefüllt (auch zum Verschieben bereits eingeplanter Punkte); der Button „＋ To-Do einplanen" unter dem Raster ist entfallen

### 1.0.9
- **Kein lila Punkt mehr oben rechts**: Der Sync-Status-Punkt ist unsichtbar, solange es nichts zu melden ist (Start) oder Google Drive nicht verbunden ist; grün/orange/rot für synchronisiert/synchronisieren/Fehler bleiben erhalten, ein Tipp auf die Stelle öffnet weiterhin die Einstellungen

### 1.0.8
- **Archiv-Button trifft immer das Archiv**: Der Archiv-Button in der Punktzeile verschiebt den Punkt mit allen Unterpunkten jetzt wirklich ins Archiv – unabhängig von der Wisch-Aktion, die je nach Vorlage auch ins Memory führt
- **Verschieben per Griff**: Jede Punktzeile (Haupt- und Unterpunkte) hat links ein 6-Punkte-Symbol – Halten und Ziehen sortiert die Reihenfolge neu (ersetzt das bisherige Long-Press-Ziehen); in Wochenlisten landet ein gezogener Punkt weiterhin automatisch auf dem Ziel-Tag

### 1.0.7
- **In-App-Updates**: secondmind prüft alle 30 Minuten (und manuell in den Einstellungen) nach neuen Versionen und fragt „Jetzt aktualisieren oder Später?“. Beim Bestätigen lädt die App die neue APK und öffnet den Android-Installer – ein Tipp auf „Installieren“ genügt (Android verlangt diese Bestätigung bei Sideloading). Einträge, Einstellungen und die Drive-Verknüpfung bleiben vollständig erhalten
- **Einstellungen synchronisieren**: Theme, Kalender-/Memory-Schalter, OAuth-Client-ID usw. liegen jetzt als eigener Datensatz in der Drive-Datei. Ein zweites Gerät übernimmt nach dem Verbinden automatisch alle Einstellungen des ersten Geräts (Tags und Tag-Farben lieferten ohnehin schon die Listen-/Notiz-Datensätze)

### 1.0.6
- **Archiv-Button an jedem Punkt**: Direkt in der Punktzeile (neben Farbe, Kalender und Büroklammer) – verschiebt den Punkt mit einem Tipp ins Memory (bzw. ins Archiv bei Simpel-Listen), inklusive Rückgängig-Toast
- **Unterpunkte wandern mit**: Wird ein Hauptpunkt ins Memory/Archiv verschoben, gehen alle seine Unterpunkte mit – nichts verschwindet mehr
- **Memory zeigt Gruppen vollständig**: Hauptpunkte erscheinen mit allen eingerückten Unterpunkten, inklusive Notiz-Rückverweis, Termin und Quelle/Datum; Wiederherstellen (↺) holt immer die ganze Gruppe zurück
- Dasselbe Gruppen-Layout im Archiv für Simpel-Listen

### 1.0.5
- **Intensivere Hintergrundfarben**: Die 15 Hintergrundtöne sind jetzt kräftig gesättigt (Gelb, Orange, Rot, Pink, Violett, Blau, Türkis, Grün, Braun, Grau) – die Kontraste sind klar erkennbar
- **Automatische Schriftfarbe überall**: Helle Töne bekommen dunkle Schrift, kräftige/dunkle Töne weiße Schrift – auch im Titel der Listen-Detailansicht (war vorher immer schwarz)

### 1.0.4
- **Kalender als echtes Wochenraster**: 24-Stunden-Zeitachse links, 7 Tagesspalten nebeneinander – Termine stehen positionsgenau auf ihrem Wochentag und ihrer Uhrzeit; die Ansicht springt direkt zur aktuellen Zeit
- **Termine per Drag & Drop verschieben**: Eintrag anfassen und auf einen anderen Tag ziehen (Ziel-Spalte leuchtet auf) oder höher/tiefer schieben – die Uhrzeit rastet im 15-Minuten-Raster ein; Tippen öffnet weiterhin das Termin-Sheet
- **Reihenfolge in To-Do-Listen ändern**: Hauptpunkte und Unterpunkte lassen sich nach kurzem Drücken verschieben – die Zeile hebt ab, die Nachbarn machen Platz; in Wochenlisten landet ein gezogener Punkt automatisch auf dem Ziel-Tag
- Überlagernde Termine teilen sich die Tagesspalte nebeneinander statt sich zu verdecken

### 1.0.3
- **Kalender** (optional, in den Einstellungen zuschaltbar): Termine mit Datum, Uhrzeit und Dauer direkt am Punkt planen – Kalender-Button in jeder Punktzeile, neue Wochenansicht (‹ ›, „Heute", Wischgeste) über dem Menü-Button in der Topbar, Einträge antippen zum Verschieben, „＋ To-Do einplanen" aus dem Kalender heraus
- **Tags bekommen eigene Farben**: Tag-Sheet mit Farbpalette (7 Farben), farbige Chips auf Karten und in den Detailansichten; Tag-Button (🏷) oben rechts auf jeder Startseiten-Karte
- **Mehr Hintergrundfarben**: das Farben-Sheet bietet jetzt 15 Töne statt 5
- **Drei-Punkte-Menü in den Detailansichten entfernt**: Listen und Notizen haben jetzt sechs einzelne Icon-Buttons (Anpinnen, Titel, Farbe, Tags, Archiv, Löschen – bei Notizen plus Verknüpfen)
- **Bugfix**: die Archiv-Seite rendert nach dem Wiederherstellen des letzten Eintrags wieder korrekt (Leerzustand statt veralteter Ansicht)

### 1.0.2
- **Anpinnen direkt auf der Startseite** – Pin-Button auf jeder Karte (Liste & Notiz)
- **Startseite zeigt Vorschauen**: die ersten 5 Hauptpunkte einer Liste, die ersten 5 Zeilen einer Notiz
- **Farbcodierung färbt den ganzen Punkt bzw. Unterpunkt** ein (statt nur eines Punktes am Rand)
- **Farb- und Büroklammer-Buttons direkt in der Punktzeile** – Notiz verknüpfen geht jetzt ohne Umweg über das Menü
- **Unterpunkte sind einzeln bedienbar**: eigene Wischgesten (Links = Löschen, Rechts = Memory) plus eigene Zeilen-Buttons
- **Leere Buttons unten entfernt**: Das Logo führt direkt auf die Startseite, daneben öffnet ein Menü-Button ( drei Striche ) den Drawer; die „＋ Neu“-Buttons an den Abschnittsüberschriften entfallen
- **Memory funktioniert wirklich**: nach Memory/Archiv verschobene Punkte verschwinden aus der Liste und tauchen in der jeweiligen Ansicht auf

### 1.0.1
- „＋ Unterpunkt“ ist jetzt in **allen Vorlagen** (Woche, Standard, Simpel) direkt am Hauptpunkt sichtbar
- Notiz ↔ To-Do-Verknüpfungen zeigen **beidseitig** aufeinander: Chip am Punkt, Liste in der Notiz, Lösen per ×
- Konto-Wechsel abgesichert: bei einem anderen Google-Konto werden alte Drive-IDs verworfen und fehlende Dateien automatisch neu angelegt
- Export/Import enthält jetzt auch die Einstellungen (praktisch für ein zweites Gerät)

### 1.0.0
- Erstveröffentlichung
