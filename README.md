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

1. [`secondmind-1.0.1.apk`](https://github.com/deinwebadmin/secondmind-android/releases/download/v1.0.1/secondmind-1.0.1.apk)
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

### 1.0.1
- „＋ Unterpunkt“ ist jetzt in **allen Vorlagen** (Woche, Standard, Simpel) direkt am Hauptpunkt sichtbar
- Notiz ↔ To-Do-Verknüpfungen zeigen **beidseitig** aufeinander: Chip am Punkt, Liste in der Notiz, Lösen per ×
- Konto-Wechsel abgesichert: bei einem anderen Google-Konto werden alte Drive-IDs verworfen und fehlende Dateien automatisch neu angelegt
- Export/Import enthält jetzt auch die Einstellungen (praktisch für ein zweites Gerät)

### 1.0.0
- Erstveröffentlichung
