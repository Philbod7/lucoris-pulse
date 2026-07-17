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
- ZEIT-CUTOFF: Der Tageslauf stoppt beim ersten Slice, dessen Startzeit NACH der aktuellen UTC-Zeit
  liegt (`Instant.now(clock)`) — es wird nichts für die Zukunft abgerufen. Da Slices chronologisch
  aufsteigen, bricht die Schleife dort ab. Relevant beim Einlesen des laufenden Tages; für
  vergangene Tage greift der Cutoff nie. Die `Clock` ist ein Bean (`ingestClock`), in Tests fixierbar.

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

## Primärquellen-Abruf (zweite Ingest-Spur)

Die zweite Spur neben GDELT: primäre Ausgabestellen (Notenbanken, Statistikämter, Regulatoren)
liefern den Fakt selbst, nicht nur den Hinweis darauf. Details: `docs/decisions.md` Nr. 20–26.

- **Routing-Manifest** `src/main/resources/primary-sources/lucoris-pulse-primary-sources.json` —
  die einzige Quelle der Wahrheit darüber, WAS abgerufen wird. Der Code entscheidet nur das WIE.
  Eine Quelle aktivieren = `enabled: true` setzen; bei `handler: generic_rss` ohne eine Zeile Code.
- **Routing**: `AdapterDispatcher` wählt anhand von `handler` die Adapter-Klasse. Heute verdrahtet:
  `generic_rss` (RSS 2.0 + Atom via Rome), `sec_edgar` und `sec_edgar_daily` (EDGAR-Filings, siehe
  unten). Ein unbekannter Handler wirft — eine aktivierte Quelle, die niemand abruft, wäre ein
  unsichtbares Datenloch.
- **Ausgabe**: alle Adapter emittieren `FeedItem` — die Feed-MELDUNG, bewusst nicht „Event"
  (ADR 25) — inkl. `guid`, `legal_class` und `attribution` aus der Quelle, damit Dedup und
  Rendering ohne erneuten Registry-Griff auskommen.
- **Persistenz & Dedup**: `IngestPrimarySourcesUsecase.runSource()` speichert nach
  `primary_feed_item`, quellenübergreifend dedupliziert über die guid (Fallback: normalisierter
  Link, `DedupKeys`); wiederholte Läufe sind idempotent. Betriebszustand je Quelle (Fehler in
  Folge, letzte Zähler) in `primary_source_state`. Nur Feed-Daten — KEIN Volltext-Abruf der
  verlinkten Seiten (ContentFetcher ist ein späteres Increment mit eigenen Regeln).
  Schema: `docs/data-model.md`, Entscheidung: ADR 25.
- **Kein Auto-Start**: `IngestPrimarySourcesUsecase` wird aufgerufen, er startet nicht von
  selbst — außer der Poller (unten) ist per Property freigeschaltet.
- **Aktiv**: welche Quellen `enabled: true` tragen, sagt allein das Manifest — die Anzahl ändert
  sich laufend. Konsistenzregel statt fixer Liste: jede enabled-Quelle hat einen implementierten
  Handler (`PrimarySourceManifestLoaderTest`).

### EDGAR — zwei Handler, weil es keinen erlaubten Echtzeit-Gesamtstrom gibt
8-K/8-K-A (US-Ad-hoc) aus EDGAR. Der naheliegende globale Strom
(`browse-edgar?action=getcurrent`) ist **unzulässig**: `sec.gov/robots.txt` führt
`Disallow: /cgi-bin` und erlaubt mit `Allow: /Archives/edgar/data` ausdrücklich nur die Daten. Das
Gate hat den ersten scharfen Lauf zu Recht verweigert. Die Volltextsuche (`efts.sec.gov`) beantwortet
ihre robots.txt mit **403** → fail-closed ebenfalls gesperrt. Es bleiben zwei Wege, die sich ergänzen:

| Handler | Endpunkt | Abdeckung | Frische | Zeitstempel |
|---|---|---|---|---|
| `sec_edgar` | `data.sec.gov/submissions/CIK*.json` | nur Watchlist | Echtzeit (<1s) | exakt (`acceptanceDateTime`) |
| `sec_edgar_daily` | `/Archives/edgar/daily-index/.../master.{yyyyMMdd}.idx` | **alle** Firmen | End-of-Day (~22:00 ET) | nur Datum |

- **`sec_edgar`** ist per Firma adressiert und braucht deshalb die kuratierte Watchlist
  `src/main/resources/primary-sources/sec-edgar-ciks.json` (90 CIKs, aus der SEC-Quelle
  `files/company_tickers.json` übernommen). Erweitern = Eintrag ergänzen, kein Code.
  Pacing 120 ms (~8 Req/s) hält Abstand zum SEC-Limit von 10 Req/s (darüber drosselt sie mit 403);
  Lookback 7 Tage hält den Batch klein, weil `filings.recent` bis ~1000 Einträge führt.
- **`sec_edgar_daily`** ist das Netz darunter: der Tagesindex führt ALLE Einreichungen eines Tages,
  auch von Firmen außerhalb der Watchlist — dafür erst abends und ohne Uhrzeit (`publishedAt` =
  Tagesbeginn UTC, bewusste Ungenauigkeit). Er liest die letzten `dailyIndexDays` (Default 3)
  Kalendertage und mischt sie: die Datei des laufenden Tages gibt es erst ~22:00 ET (davor 403), und
  ein Netz, das nur zwischen 22:00 ET und Mitternacht fängt, wäre keines. Die Überlappung entdoppelt
  `DedupKeys`.
- **Beide erzeugen denselben `dedup_key`**: `SecEdgarUrls.filingPermalink` konstruiert aus CIK +
  Accession denselben Link (`/Archives/edgar/data/{cik}/{accOhneStriche}/{acc}-index.htm`),
  `guid` = Accession → `DedupKeys` fällt darauf zurück. Kein Doppel-Insert; wer zuerst liefert,
  gewinnt (fast immer der Echtzeit-Pfad mit dem exakten Zeitstempel). Der Permalink wird
  **konstruiert, nicht übernommen** — EDGARs eigene Links zeigen firmenweit auf `getcompany`.
- **`access.url` ist das Präfix**, das der Gate prüft; die Adapter fächern darunter auf. Beide
  Präfixe tragen keine tieferen robots-Regeln (geprüft) — Grenze bewusst in Kauf genommen (ADR 27).
- `legal_class: A` (gemeinfrei, 17 U.S.C. § 105); Anzeige-Regel Institution+Datum+Deep-Link gilt
  trotzdem. Kein `express_invitation` nötig (es gibt kein Disallow zu heilen), keine UA-Änderung
  (der bestehende UA bekommt von `data.sec.gov` HTTP 200).
- **`enabled` bleibt `false`** (`confidence: verify_endpoint`) bis zur grünen Live-Probe:
  ```
  PRIMARY_LIVE_IT=true mvn -Dit.test=PrimarySourceProbeIT -Dprimary.source=sec-edgar verify
  PRIMARY_LIVE_IT=true mvn -Dit.test=PrimarySourceProbeIT -Dprimary.source=sec-edgar-daily-index verify
  ```
  Der Tagesindex ist tagsüber erwartungsgemäß leer (Datei erscheint ~22:00 ET). Details: ADR 27.

### Periodischer Poller
`PrimaryPollingConfig` (Profil `ingest`) startet EINEN `@Scheduled`-Tick (fixedDelay,
`poll.tick-interval`, Default 30s), der fällige Quellen sequenziell über `runSource()` abruft.
Fällig = `last_attempt_at` (aus `primary_source_state`) + Manifest-`poll.seconds` erreicht;
das effektive Intervall respektiert zusätzlich den robots-`Crawl-delay` (max von beidem).
`poll.mode=calendar` (destatis-press) überspringt der Intervall-Poller — Kalender-Polling ist
ein späteres Increment; der explizite `runAll()` ruft solche Quellen weiterhin ab.

**Default AUS.** Profil-Gating allein genügt nicht (mehrere ITs aktivieren `ingest`); erst die
Property erzeugt den Scheduler samt `@EnableScheduling`:

```
LUCORIS_INGEST_PRIMARY_POLL_ENABLED=true
```

Fehler einer Quelle (Netz, Parse, robots-Verbot, fehlender Handler) stoppen die übrigen nicht;
der Zustand steht danach in `primary_source_state` (`consecutive_failures`, `last_error`).
Details: ADR 26.

### Load-Validierung der Registry
`confidence` im Manifest ist eine Behauptung, die veraltet (URL zieht um, Herausgeber blockt).
Der `SourceLoadValidator` prüft sie gegen die Wirklichkeit — über den echten Ingest-Pfad:

```
mvn spring-boot:run -Dspring-boot.run.profiles=validate-sources
```

Er meldet: als `verified` geführt, liefert aber nichts (URL/Zugang prüfen) bzw. funktioniert, ist
aber noch nicht als geprüft eingetragen (`confidence` hochstufbar). Läuft NIE im Standard-Build.

### Live-Tests gegen die echten Feeds
Vom `mvn verify` per Default ausgeschlossen (wie die GDELT-Live-ITs, per Env-Variable):

```
# Fetch-only (ohne Spring/DB): robots erlaubt jede enabled-Quelle, jeder Feed liefert
PRIMARY_LIVE_IT=true mvn -Dit.test=PrimaryRssLiveIT verify

# Voller Persistenz-Pfad (Testcontainers-DB): zwei Läufe, Summary je Quelle
# (gefetcht/neu/dedupliziert/Fehler), zweiter Lauf beweist die Idempotenz
PRIMARY_LIVE_IT=true mvn -Dit.test=PrimaryIngestLiveIT verify
```

### Kandidaten-Probe: eine benannte Quelle antippen
`SourceLoadValidator` und `PrimaryRssLiveIT` sehen nur `enabled: true`-Quellen. Ein Kandidat mit
`confidence: verify_endpoint` muss aber abgerufen werden, BEVOR er aktiviert wird — sonst wäre das
Aktivieren selbst der Test. Dafür `PrimarySourceProbeIT`: zieht eine Quelle per ID aus dem
Manifest (auch `enabled: false`) und ruft sie über den echten Dispatcher-Pfad ab.

```
PRIMARY_LIVE_IT=true mvn -Dit.test=PrimarySourceProbeIT -Dprimary.source=bmf-presse verify
```

Doppelt gegatet (Env-Variable UND Quell-ID), ohne Spring und ohne Datenbank; läuft NIE im
Standard-Build. Liefert die Quelle nichts, ist der Test rot — das ist die Aussage. Reihenfolge beim
Einbau: robots/TDM prüfen → URL antippen → Probe grün → `confidence: verified` → erst danach
`enabled: true`.

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

### Prüfprotokoll (manuell, bis der `RobotsGate` steht)
Jede Domain wird VOR dem ersten Abruf geprüft; Befund mit Datum hier festhalten (Beweislast).

| Domain | geprüft am | robots.txt | TDM-Vorbehalt | Ergebnis |
|---|---|---|---|---|
| `bundesfinanzministerium.de` | 2026-07-14 | `User-agent: *` erlaubt, `Crawl-delay: 180`; ~40 namentlich gesperrte SEO-/Scraper-Bots; keine KI-Crawler (GPTBot/CCBot/ClaudeBot) gesperrt; Feed-Pfade nicht disallowed | keiner gefunden | Abruf zulässig. Feed führt „Copyright by BMF. Alle Rechte vorbehalten" → `legal_class: B`. Vorgelagerter Radware-Bot-Manager (HTML → 302 auf `validate.perfdrive.com`), den Feed hat er `LucorisNewsBot/1.0` ausgeliefert (Probe: 20 Einträge). |

### Der RobotsGate (maschinell, fail-closed)
Die Allowlist ist eine Handprüfung — und Handprüfungen irren. Deshalb prüft der `RobotsGate` VOR
jedem Abruf maschinell und verweigert im Zweifel. Details: `docs/decisions.md` Nr. 23.

Verboten wird, wenn **einer** dieser drei Gründe greift:
1. robots.txt verbietet `LucorisNewsBot` den Pfad;
2. robots.txt sperrt einen gängigen KI-Crawler (GPTBot, ClaudeBot, CCBot, Google-Extended, ...) für
   **denselben Pfad** — die konservative Regel oben; die Namenslücke wird nicht ausgenutzt;
3. `/.well-known/tdmrep.json` erklärt für den Pfad einen TDM-Vorbehalt.

**Fail-closed**: robots.txt mit 5xx/401/403 oder gar nicht erreichbar => kein Abruf. Nur ein
sauberes HTTP 404/410 heißt „keine robots.txt, also keine Einschränkung" (RFC 9309). Jede
Entscheidung wird mit Zeitstempel geloggt (Beweislast). Es gibt **keinen Ausschalter**.

Das Gate sitzt als Dekorator VOR dem Dispatcher — jeder Handler (RSS wie `sec_edgar`) ist damit
zwangsläufig abgedeckt, und auch `PrimarySourceProbeIT` läuft hindurch: gerade dort wird eine noch
ungeprüfte Quelle zum ersten Mal angefasst.

Ein Verbot wirft `SourceNotPermittedException` (keine stille leere Liste). Der Ingest fängt es pro
Quelle ab, der Validator meldet es als `VERBOTEN`.

**Beleg, dass es trägt:** `bmf-presse` war von Hand als unbedenklich freigegeben. Der Gate hat den
Abruf verweigert — die BMF-robots.txt sperrt mit `Disallow: */SiteGlobals` genau den Zweig, in dem
der Feed liegt. Die Quelle bleibt `enabled: false`.

**Der dritte Kanal (seit ADR 24 geschlossen):** Der `TDM-Reservation`-HTTP-Header auf der Feed-Antwort
wird vom `TdmAwareFeedFetcher` geprüft — einem Dekorator um den Fetcher. Ist er gesetzt, wird das
Dokument verworfen, BEVOR ein Parser es sieht (`SourceNotPermittedException`). Das kostet keinen
zusätzlichen Request und schützt alle Quellen.

### ALLOW_BY_INVITATION (ADR 24)
Ein **generisches** robots-Disallow, das einen Feed nur als Nebenwirkung erfasst, kann von einer
**ausdrücklichen Abo-Einladung des Herausgebers** aufgewogen werden. Belegfall: BMF sperrt pauschal
den CMS-Zweig `*/SiteGlobals` (dort liegen Formulare, Skripte, CSS) und hat — anders als Destatis im
selben CMS — vergessen, den RSS-Zweig per `Allow` wieder freizugeben; zugleich lädt es auf einer
eigenen Seite ausdrücklich zum Abonnieren ein.

Die Evidenz steht als optionales Feld `express_invitation` in der Registry und wird **von Hand**
eingetragen (`page_url`, `wording`, `retrieved`, `scope`). **Es gibt kein Override-Flag und keine
Sonderbehandlung einzelner Quellen im Code.** Alle fünf Bedingungen müssen gelten — Details und
Begründung in `docs/decisions.md` Nr. 24:

| | |
|---|---|
| **a** | `access.type == rss` |
| **b** | Evidenz vollständig **und** nicht veraltet (`invitation-max-age`, Default 180 Tage) |
| **c** | das treffende Muster erfasst den Feed nur beiläufig — nennt es `rss`/`feed`/`atom`, zielt es auf eine Datei, oder ist es ein Total-Bann (`Disallow: /`), bleibt es gesperrt |
| **d** | das Disallow steht in der `*`-Gruppe; jede benannte UA-Gruppe (auch KI-Crawler), die sperrt, gewinnt |
| **e** | TDM-Kanäle sind clean — sie gewinnen immer |

Jede solche Entscheidung schreibt eine Beweislast-Zeile auf WARN (Muster, UA-Gruppe, Fundstelle,
wörtlicher Satz, Zeitstempel).

**Grenze, die sichtbar bleiben muss:** Die BMF-Einladungsseite ist für Bots gesperrt (Bot-Manager,
302). Die Re-Validierung im Profil `validate-sources` meldet daher dauerhaft
`INVITATION_UNVERIFIABLE` — nicht `BLOCKED_STALE_INVITATION`, denn „nicht gesehen" ist kein Beweis
für „geändert". Die Erlaubnis ruht damit allein auf der Handaufzeichnung in der Registry.

Und: die Einladung erlaubt den **Abruf**. Sie ist **keine Lizenz** — `legal_class: B` bleibt B.

## Quellenstrategie (DACH)
- Kommerzielle Premium-Qualitätspresse (Spiegel, SZ, Zeit, FAZ, Welt, Handelsblatt ...) sperrt
  überwiegend + paywalled -> nicht einplanen. War über GDELT ohnehin nie im Volltext verfügbar.
- Tragfähig & erlaubt: Primär-/Institutionsquellen, Pflichtmeldungen (Ad-hoc/IR), öffentlich-
  rechtliche (tagesschau/ZDF/DLF — je Domain prüfen), dpa-gespeiste Regionalpresse, mehrsprachige
  Abdeckung. Kuratierte Allowlist statt Open-Web-Crawling.
- Werkzeuge: `lucoris_quellen_de.csv` (Startliste) + `check_robots.mjs` (robots/TDM-Prüfung,
  liefert das zeitgestempelte Erlaubnis-Register für die Beweislast).
