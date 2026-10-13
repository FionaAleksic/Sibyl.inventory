# Core-Kompatibilität und Release-Browser

**Addon:** `Sibyl.inventory` (Inventar)  
**Typ:** `feature`  
**Aktuelle veröffentlichte Vorabversion:** `v0.1.0-rc.4` (Stand 13.10.2026)

Dieses Addon gehört zum **Sibyl System** und wird künftig über den geschützten Addon-Browser des zentralen Core entdeckt. Der Browser ruft die veröffentlichten GitHub-Releases **serverseitig** ab; keine GitHub-Tokens oder AD-/Addon-Credentials gehören in den Browser.

## Vertragsregeln

- Eindeutige Addon-ID `Sibyl.inventory`, eine aktive Version pro Addon-ID.
- Release-Quelle: `FionaAleksic/Sibyl.inventory`, veröffentlichtes Release-Asset `Sibyl.inventory-v0.1.0-rc.4.zip`, SHA-256-Integritätswert aus den Release-Asset-Metadaten. Ein Hash ist **keine digitale Signatur**.
- Installieren erst nach expliziter Admin-Freigabe, Prüfung des `module.json` und `deployment.json` sowie der Abhängigkeiten. Das Anzeigen von Releases installiert keine Addons.
- Der Core kontrolliert Authentifizierung, Rechte, Datenhaltung, Addon-Versionen und API-Zugriff. Die Addons dürfen das Core-Berechtigungsmodell nicht umgehen.
- Abhängigkeiten und Service-Accounts ausschließlich über dokumentierte Core-Schnittstellen und geschützte Konfiguration. Keine Passwörter, Keys oder Tokens ins Repository oder Frontend.
- Dies ist ein **Pre-Release**. Das Vorhandensein eines Release-ZIPs bestätigt nicht, dass alle Funktionen auf einer produktiven Sibyl-Installation abgenommen wurden. Keine pauschale Produktionsfreigabe ohne Integrationstest.

## Einheitliches Webdesign

Die allgemeinen Nutzer-Designregeln gelten auch für die Moduloberfläche: **minimalistisch, 0 px Eckenradius, keine dekorativen Verläufe oder aufwendigen Schatten, ruhig und funktional**; dezente, absichtliche Asymmetrie ist okay, aber nicht auf Kosten der Bedienbarkeit. Grundlage: [Sibyl Design Principles](https://github.com/FionaAleksic/Sibyl_System/blob/feature/deck-inspired-v2/docs/DESIGN-PRINCIPLES.md).

## Zuständigkeitsgrenzen

- `test` ist der Entwicklungsbranch. Bestehende Releases und ihre ZIPs bleiben unverändert.
- Source-Code, Manifest, Release-Paket und Deploy-Operationen werden vor einem neuen Release separat geprüft.
- Das Bereitstellen dieses Dokuments installiert **kein** Addon und verändert **keine** Live-Daten.
