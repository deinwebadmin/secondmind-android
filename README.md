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
│   ├── keystore/secondmind.keystore   Signaturschlüssel für Updates
│   └── app/build.gradle          signingConfig (Passwort: secondmind)
└── capacitor.config.json
```

## APK installieren (Sideload)

1. [`secondmind-1.0.6.apk`](https://github.com/deinwebadmin/secondmind-android/releases/download/v1.0.6/secondmind-1.0.6.apk)
   aus dem Release herunterladen (oder selbst bauen, siehe unten).
2. Auf dem Gerät: **Einstellungen → Apps → Installieren unbekannter Apps** → Browser/File-Manager erlauben.
3. APK antippen und installieren.
4. App starten → Google Drive verknüpfen (siehe unten).

> **Keystore beachten:** App-Updates müssen mit demselben Schlüssel signiert sein
> (`android/keystore/secondmind.keystore`, Alias `secondmind`, Passwort `secondmind`).
> Keystore-Datei und Passwort deshalb sicher aufbewahren.

## Google Drive einrichten (einmalig)

1. <https://console.cloud.google.com> → **Neues Projekt** anlegen (z. B. `secondmind`).
2. **APIs & Dienste → Bibliothek** → „Google Drive API“ → **Aktivieren**.
3. **OAuth-Bestätigungs-Bildschirm**: Nutzertyp **External**, App-Name `secondmind`,
   Scopes `.../auth/drive.file`, `openid`, `email`, `profile`, eigene Adresse als **Testuser**.
4. **Zugangsdaten → OAuth-Client-ID** → Anwendungstyp **Desktop-App** (wichtig!).
5. Client-ID in secondmind eintragen: **Einstellungen → Google Drive → OAuth Client-ID**.
6. **„Mit Google verknüpfen“** → System-Browser öffnet sich → Anmeldung → App übernimmt den Code automatisch.

**Wichtig:** Im Status **„Testing“** läuft das Refresh-Token nach 7 Tagen ab. Für dauerhaftes
Anmelden den Bildschirm **„In Veröffentlichung bringen“**.

Der Flow funktioniert über einen lokalen Loopback-Server (`http://127.0.0.1:PORT`) in der App –
deshalb reicht eine einzige Desktop-Client-ID für Windows *und* Android, ohne SHA-1-Registrierung.
Falls der Browser den Redirect nicht erreicht: in der App erscheint ein Eingabefeld, in das du
die angezeigte `http://127.0.0.1:…`-Adresse manuell einfügen kannst.

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

- OAuth-Tokens verschlüsselt im **Android-Keystore** (AES-256-GCM, Hardware-gebunden).
- Standard-Scope `drive.file` – die App sieht nur Dateien, die sie selbst angelegt hat
  (Ordner `secondmind` in deinem Drive).
- Kein Server, kein fremdes Konto – nur du und dein Google-Konto.

## Lizenz

MIT

## Siehe auch

- [secondmind-windows](https://github.com/deinwebadmin/secondmind-windows) – dieselbe App für Windows (gleiche Google-Client-ID, gleicher Drive-Ordner)

## Änderungen

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
