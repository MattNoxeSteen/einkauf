# Einkauf

Einkaufsliste für Android (Pixel 9). WhatsApp-Einkaufszettel teilen, Produkte erscheinen nach Laufweg sortiert, Antippen = erledigt.

## Funktionen
- **Teilen aus WhatsApp:** Nachricht gedrückt halten → Teilen → *Einkauf*. Die Produkte landen direkt auf der Liste.
- **Text markieren → „Zur Einkaufsliste“** in jeder App.
- Einfügen aus der Zwischenablage, Liste oder Fließtext, Deutsch und Polnisch, Läden wie „edeka:“ werden erkannt.
- Sortierung nach Laufweg (Reihenfolge der Bereiche einstellbar), Zuordnung aus den eigenen Kassenbons.
- Schnellauswahl „Häufig gekauft“, Rückgängig, Liste leeren mit Doppeltipp.

## Bauen
Jeder Push auf `main` baut die APK per GitHub Actions und legt sie unter **Releases** ab (`Einkauf.apk`).

Hinweis: Der Signaturschlüssel `app/einkauf.keystore` liegt im Repo, damit Updates sich über die alte Version installieren lassen. Repository deshalb **privat** halten.
