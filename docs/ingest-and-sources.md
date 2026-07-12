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
- Der Theme-Filter greift auf GKG (nur GKG trägt Themen). Events/Mentions werden daran GEKOPPELT
  (`lucoris.ingest.gdelt.filter-linked-events-and-mentions`, Default an): pro Slice läuft die
  Reihenfolge GKG → Mentions → Events; eine Mention bleibt nur, wenn ihr `mention_identifier` auf
  einen behaltenen Artikel zeigt (`= document_identifier`), ein Event nur, wenn seine
  `global_event_id` von einer behaltenen Mention referenziert wird. So kommen nicht-relevante
  Events/Mentions gar nicht ins System. Bewusste Intra-Slice-Näherung (kein slice-übergreifender
  Join): wenige relevante Zeilen mit Artikel/Event in einem anderen Slice fallen weg; fehlt ein
  GKG-Slice (404), werden dessen Events/Mentions verworfen. `=false` schreibt Events/Mentions
  ungefiltert.
- Statistik: je GKG-File wird geloggt, wie viele Artikel geparst, wie viele als marktrelevant
  behalten und wie viele verworfen wurden. Zusätzlich wird am Ende eines Tageslaufs eine
  aggregierte Themen-Statistik über ALLE vorgekommenen Codes geloggt (Häufigkeit + Markierung, ob
  bereits marktrelevant) — Diagnose zum Kuratieren des Sets, abschaltbar über
  `lucoris.ingest.gdelt.log-theme-histogram`.

## Filter-Ablauf im Detail (pro 15-Min-Slice, zwei Phasen)
Bei aktivierter Kopplung (`filter-linked-events-and-mentions`, Default an) läuft jeder Slice in zwei
Phasen; Events werden erst in Phase 2 geschrieben. Siehe `decisions.md` ADR 17.

Phase 1 (committen):
0. Ist der GKG-Slice laut `ingest_log` bereits eingelesen (`isFileProcessed`), wird Phase 1
   ÜBERSPRUNGEN — verhindert doppeltes Speichern von GKG/Mentions (Constraint-Verletzung). Phase 2
   läuft trotzdem (idempotent).
1. GKG laden → parsen → Marktrelevanz-Filter (Themen-Präfix-Schnitt). Behaltene Artikel bilden mit
   ihren URLs (`document_identifier`) das Set `relevantUrls` DIESES Slices.
2. Mentions laden → parsen → behalten, wenn `mention_identifier ∈ relevantUrls`.
3. Relevante GKG + gekoppelte Mentions + je ein `ingest_log`-Eintrag (GKG-Datei, Mentions-Datei)
   werden in EINER Transaktion geschrieben und committed (atomar). So sieht Phase 2 die Mentions und
   der `ingest_log`-Vermerk existiert nur, wenn die Daten committed sind. Ein fehlender GKG-Slice
   (404) wird NICHT vermerkt (späterer Lauf kann ihn erneut versuchen).

Phase 2 (Events auflösen):
3. Über die committeten Mentions dieses Slices werden per SQL (`not exists` gegen `gdelt_events`,
   eingegrenzt über `mention_time_date`) die fehlenden Events ermittelt und nach ihrem
   Herkunfts-Slice (`eventTimeDate` = DATEADDED-Slice) gebündelt.
4. Je Herkunfts-Slice EINMAL geladen: der aktuelle Slice wird nur einmal geholt (kein erneuter
   Abruf); ältere Slices mit bis zu `event-backfill-retries` (Default 3) Versuchen. Die benötigten
   Events werden herausgezogen und geschrieben.
5. Wird ein Event in seinem Slice nicht gefunden oder ist der Slice nach den Retries nicht lesbar,
   wird ein Stub-Event angelegt (`global_event_id` + `date_added = eventTimeDate`, keine weiteren
   Felder). Das sichert Konsistenz, verhindert wiederholtes Suchen; ein Housekeeping-Job kann später
   das echte Event nachladen.

### Szenario: Event zuerst nicht vorhanden, später relevanter Bezug
Frage: Ein Event wird in Slice T zunächst nicht gespeichert (der referenzierende Artikel ist noch
nicht relevant/da); in einem späteren Slice T+k erwähnt ein relevanter Artikel dieses Event. Wird
es dann gezogen?
- ANTWORT: Ja. GDELT liefert jedes Event zwar nur EINMAL (im `DATEADDED`-Slice), aber jede Mention
  trägt `eventTimeDate` = genau diesen Slice. Phase 2 lädt daraus die Export-Datei des Events und
  schreibt es nach. Ist der Slice (auch nach Retries) nicht lesbar oder das Event nicht enthalten,
  entsteht ein Stub-Event (später per Housekeeping ersetzbar). So bleibt keine behaltene Mention
  ohne Event-Zeile.

### Kommt ein Event „jedes Mal" in den Dateien mit?
Nein. Events: EINMALIG (im `DATEADDED`-Slice). Mentions: bei JEDER Erwähnung eine neue Zeile (Event
1:N über die Zeit) — der wachsende Stream. GKG: je Artikel-Dokument einmalig. Deshalb wird ein Event
gezielt aus seinem `eventTimeDate`-Slice nachgeladen, statt es im Vorwärts-Stream zu erwarten.

### Wird ein bereits vorhandenes Event aktualisiert, oder gibt es keine veränderten Daten?
- GDELT-seitig: keine veränderten Event-Daten. Die Zähler im Event
  (`num_mentions`/`num_sources`/`num_articles`) sind der Stand des Erst-Slices; die weitere
  Amplifikation steckt allein im Mentions-Stream.
- Ingest-seitig: KEIN Upsert/Update. Der Firehose schreibt reine `INSERT`s. Phase 2 fügt über die
  `not exists`-Ermittlung nur ECHT fehlende Events ein → keine Doppel-Inserts, idempotent bei
  Wiederholung. Ein Stub-Event bleibt bestehen, bis ein Housekeeping-Job es durch das echte ersetzt
  (bräuchte ein gezieltes Update). Für GKG (natürlicher PK `gkg_record_id`/`seen_date`) und Mentions
  (Surrogat-PK `mention_seq`) ist der Schutz gegen Republish/Re-Run noch offen — Datei-Dedup über
  `ingest_log` ist vorgesehen, aber noch nicht aktiv.

## Dedup & Idempotenz
- `ingest_log(filename PK, dataset, md5, row_count, processed_at)` verhindert Doppelverarbeitung.
  Je verarbeiteter Slice-Datei ein Eintrag (Dateiname = `<stamp><suffix>`, z.B.
  `20260710000000.gkg.csv.zip`). Der Eintrag wird INNERHALB derselben schreibenden Transaktion wie
  die Nutzdaten geschrieben (siehe Phase 1 oben; `insertAtomic`), damit Vermerk und Daten
  konsistent sind. `processed_at` setzt die DB (`DEFAULT now()`; Entity `insertable=false`).
- Vor der Verarbeitung eines Slices prüft `isFileProcessed(gkg-Datei)`; ist der Slice vermerkt, wird
  Phase 1 übersprungen → GKG/Mentions werden nicht erneut gespeichert (verhindert die
  Constraint-Verletzung auf dem natürlichen GKG-PK). Das macht Läufe wiederhol-/fortsetzbar.
- `md5` wird derzeit nicht befüllt (Feld für spätere Republish-/Integritätsprüfung). Bei deaktivierter
  Kopplung wird analog die Events-Datei vermerkt und übersprungen.
- Jede ausgelassene Datei/Slice wird pro Fall im Log vermerkt (INFO): „bereits eingelesen
  (ingest_log)" bzw. „fehlt/nicht abrufbar — ausgelassen". Die Endzusammenfassung führt zusätzlich
  die Zähler `bereitsEingelesen` und `übersprungeneSlices`.

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
