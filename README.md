# Sibyl.inventory

Pre-Release 0.1.0-rc.1. Eigenständiger Inventar-Domänenkern (Java 21) mit vollständiger IPAM-Auflistung, DHCP-Lease/Reservierungsstatus und ICMP-Aktualitätsprüfung. Nicht nur vergebene IP-Adressen werden aufgeführt. Frühere Implementierung: [Niko Test-Addon](https://github.com/FionaAleksic/Niko_Web-System/tree/Test/Addons/inventory), [ursprüngliche Inventar-Oberfläche](https://github.com/FionaAleksic/Niko_Web-System/tree/Test/frontend-next/src/modules/inventory).

## Build
`bash scripts/build-release.sh` (JDK 21, Python 3, zip). Tests laufen vor dem Release.

## Status und Einschränkung
Dies ist eine **echte testbare Domänenbibliothek**, aber **noch kein aktiviertes Inventar-Modul auf Sibyl Core RC.7**. Der neue Core kann den ZIP-Release laden und die Manifestdaten prüfen, verfügt jedoch noch nicht über den Plugin-Lifecycle / Routing / Daten-API-Adapter. Agenten, DHCP- und ICMP-Collector werden noch nicht gestartet. Für Funktionsparität sind die bisherigen UI-/Backend-Verträge zu portieren, ausschließlich auf Testservern zu testen und erst dann freizugeben. AD-Integration `Sibyl.ad` ist verpflichtende Dependency, direkte AD-Zugriffe durch dieses Addon sind nicht vorgesehen.
