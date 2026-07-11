# Ingest & Quellen — lucoris-pulse

## GDELT-Abruf
- Polling-Endpunkt: `http://data.gdeltproject.org/gdeltv2/lastupdate.txt` (alle 15 Min).
  Liefert 3 Zeilen `groesse md5 url` (export=Events, mentions, gkg).
- Backfill: `.../masterfilelist.txt` (vollständige Historie seit Feb 2015).
- URL-Schema je Slice: `.../YYYYMMDDHHMMSS.export.CSV.zip` (UTC, :00/:15/:30/:45).
- Drei Dateien je Slice: `.export.CSV.zip` (Events), `.mentions.CSV.zip`, `.gkg.csv.zip`.
- STOLPERFALLEN: Groß-/Kleinschreibung inkonsistent (.CSV bei Events/Mentions, .csv bei GKG,
  URLs case-sensitive); Dateien sind TAB-getrennt trotz .csv (Delimiter explizit auf Tab);
  ZIP mit genau einer Datei — erst entpacken.
- Primärdatensatz für Lucoris: GKG (V2Themes, V2Tone, V2Organizations, DocumentIdentifier).
  Events/Mentions nur für die Ereignis-Klammer (global_event_id).

## Marktrelevanz-Filter (Ingest)
- Ziel: DB-Menge klein halten — nur marktrelevante Artikel werden gespeichert. Der Filter greift
  NACH dem Parsen der GKG-Datei und VOR dem Schreiben; nicht relevante Artikel werden verworfen,
  bevor irgendeine Entität geschrieben wird.
- Regel: Ein GKG-Artikel ist relevant, wenn seine V2Themes mit dem Marktrelevanz-Set einen
  nicht-leeren Schnitt bilden. Das Set steht in `application.yml`
  (`lucoris.ingest.gdelt.market-relevant-theme-prefixes`); Einträge wirken als Präfix
  (`ECON_` deckt die ganze Familie ab). Vorschlag (Wirtschaft/Politik): `ECON_`, `EPU_`.
- Der Filter ist GKG-scoped (nur GKG trägt Themen). Events/Mentions werden derzeit ungefiltert
  geschrieben — eine spätere Kopplung an die relevanten Artikel liefe über die URL-Brücke
  (`mention_identifier = document_identifier`) bzw. `global_event_id`.
- Statistik: je GKG-File wird geloggt, wie viele Artikel geparst, wie viele als marktrelevant
  behalten und wie viele verworfen wurden. Zusätzlich wird am Ende eines Tageslaufs eine
  aggregierte Themen-Statistik über ALLE vorgekommenen Codes geloggt (Häufigkeit + Markierung, ob
  bereits marktrelevant) — Diagnose zum Kuratieren des Sets, abschaltbar über
  `lucoris.ingest.gdelt.log-theme-histogram`.

## Dedup & Idempotenz
- ingest_log(filename PK, md5, ...) verhindert Doppelverarbeitung; GDELT republisht Slices
  gelegentlich -> zuletzt verarbeiteten Dateinamen tracken, md5 verifizieren.

## Auflösung pro Entitätstyp (Ingest)
- THEMA:  Code splitten -> UPSERT theme -> article_theme (FK = Code).
- ORT:    Geo-Felder parsen -> UPSERT location per natürlichem Schlüssel -> FK.
- ORG:    norm_name(roh) -> LOOKUP organization_alias; Treffer -> id; sonst organization+Alias
          neu (is_reviewed=false) -> article_organization (FK + raw_name fürs Audit).
- PERSON: analog über person_alias, konservativ.
- Tab-Parsing: univocity-parsers. Firehose-Insert: StatelessSession + Batch (siehe CLAUDE.md).

## Text-Pipeline (falls KI-Zusammenfassung)
GDELT selektiert -> erlaubte Quelle wird gefetcht -> KI fasst QUELLENGEBUNDEN zusammen ->
Fakt (aus Quelle) und Einordnung (didaktischer Rahmen) getrennt -> KEINE Handlungsempfehlung
(Finanzbildung, nicht Anlageberatung unter KWG/WpHG). Nie aus GDELT-Metadaten allein
"beschreiben" (Halluzinationsrisiko am höchsten, wo Vertrauen am wichtigsten ist).

## Quellen-Erlaubnis (rechtlich) — VOR jedem Abruf prüfen
- GDELT-Lizenz: freie kommerzielle Weiterverbreitung, ABER Attributionspflicht (Verweis +
  Link auf gdeltproject.org) muss propagieren (API-Response/Terms).
- Artikel-Volltext ist NICHT von der GDELT-Lizenz gedeckt -> Verlags-Urheberrecht.
- EU/DE: §44b UrhG (TDM) erlaubt kommerzielles Mining rechtmäßig zugänglicher Werke, SOFERN
  kein maschinenlesbarer Nutzungsvorbehalt vorliegt.
  - Paywall = "rechtmäßig zugänglich?" -> nie Zugangssperren umgehen; harte Paywalls skippen.
  - robots.txt / TDM-Rep (/.well-known/tdmrep.json, Header, Meta) = Vorbehaltskanal.
  - Beweislast liegt beim Data Miner -> pro Abruf robots/TDM-Zustand + Zeitstempel LOGGEN.
- KONSERVATIVE REGEL (verbindlich): Sperrt eine Seite die gängigen KI-Crawler oder hat sie
  einen TDM-Vorbehalt, gilt sie als verboten — auch wenn unser eigener User-Agent nicht
  namentlich genannt ist. Namenslücke NICHT ausnutzen. Kein User-Agent-Spoofing.
- Output: nur eigene Zusammenfassung, keine Passagen reproduzieren, keinen Volltext archivieren
  (Löschpflicht nach TDM). Höflich crawlen (ehrlicher UA, Rate-Limit, Caching).

## Quellenstrategie (DACH)
- Kommerzielle Premium-Qualitätspresse (Spiegel, SZ, Zeit, FAZ, Welt, Handelsblatt ...) sperrt
  überwiegend + paywalled -> nicht einplanen. War über GDELT ohnehin nie im Volltext verfügbar.
- Tragfähig & erlaubt: Primär-/Institutionsquellen, Pflichtmeldungen (Ad-hoc/IR), öffentlich-
  rechtliche (tagesschau/ZDF/DLF — je Domain prüfen), dpa-gespeiste Regionalpresse, mehrsprachige
  Abdeckung. Kuratierte Allowlist statt Open-Web-Crawling.
- Werkzeuge: `lucoris_quellen_de.csv` (Startliste) + `check_robots.mjs` (robots/TDM-Prüfung,
  liefert das zeitgestempelte Erlaubnis-Register für die Beweislast).
