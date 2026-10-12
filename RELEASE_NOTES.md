# Sibyl.inventory v0.1.0-rc.4

Geräte, Netzwerkgeräte und USB-/HID-Peripherie über die generische Core-Records-API dauerhaft speichern und archivieren. Listenansicht mit Suche, IPAM mit vollständiger IPv4-Adresszählung (/16 bis /30), DHCP-Lease-Overlay und Reservierungen, 200 Adressen pro Seite. Kein fingierter ICMP-Status: tatsächliche AD-, DHCP-, ICMP- und Windows-Agent-Collector sind weiterhin nicht angebunden. Die Legacy-UI wurde als Grundlage berücksichtigt, ist aber noch nicht 1:1 umgesetzt.

Dependency: Sibyl.ad v0.1.0-rc.4. Für Persistenz Core RC.14 oder höher.

Abhängigkeit auf `Sibyl.ad v0.1.0-rc.4` aktualisiert. AD-Import bleibt bis zum sicheren Connector-Runner ausdrücklich deaktiviert.
