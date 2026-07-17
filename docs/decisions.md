# Architektur-Entscheidungen (ADR-Log) — lucoris-pulse

Kurzformat je Entscheidung: Kontext -> Entscheidung -> Begründung -> Konsequenz.

## 1. Eigenständiger Adapter statt Lucoris-Modul
Lucoris braucht relevante Ereignisse mit allen Quellen, aktuell, nach Thema/Company. ->
Separates Vorsystem mit eigener REST-API. -> Isolation, unabhängig skalierbar/deploybar,
optional als eigener Dienst vermarktbar. -> Kopplung an Lucoris nur über REST.

## 2. Java/Spring Boot/Hibernate statt Supabase/TypeScript (wie im Lucoris-Kern)
Bewährter, bekannter Stack; zugleich Erprobungsfeld für ein späteres Hochlast-Backend von
Lucoris. -> Bewusst NICHT der Supabase/TS-Welt folgen. -> Hochlast-Bausteine (Batch-Writer,
ModelManager) als wiederverwendbare, GDELT-unabhängige Komponenten bauen. Migration des
Lucoris-Backends bliebe dennoch ein Großprojekt (Auth/RLS/Realtime/RevenueCat).

## 3. Spring Boot 4.1, nicht 3.5
3.5 hat OSS-EOL am 30.06.2026 erreicht. -> Greenfield auf 4.1 (Spring Framework 7, Java 17+,
Java-25-tauglich). Java 21 LTS (Temurin). -> Keine Migration, CRA-konform (unterstützte Version).

## 4. Flyway statt Liquibase
Nur PostgreSQL, SQL-lastig, partitioniertes Schema mit Sequenzen/generierten Spalten. ->
Flyway (versioniertes SQL). -> Liquibase-Abstraktion verpufft bei diesem Postgres-DDL; Rollback
in einem Ingest-System praktisch irrelevant (immer vorwärts).

## 5. Caffeine (via JCache) statt EhCache
Resolver-Lookup (alias_norm -> id) hat stark schiefe Verteilung. -> Caffeine (W-TinyLFU) für
bessere Hit-Rate; angebunden über JCache (JSR-107). -> Eine Engine für App-Cache UND späteren
Hibernate-2LC ("ein Cache-System"). Redis/Valkey erst bei mehreren Instanzen.

## 6. Sequenzen statt IDENTITY
Firehose braucht JDBC-Batching; IDENTITY verhindert es. -> Alle Surrogate über Sequenzen
INCREMENT 50 = allocationSize 50, pooled-lo. -> Batch-fähig; DEFAULT nextval macht auch Roh-/
Test-Inserts robust.

## 7. Hybride Persistenz
Normalisiertes Domänenmodell + hoher Schreibdurchsatz. -> JPA/Hibernate für Domäne/Resolver/
Read-Model, StatelessSession + Batch für den Firehose. -> Kein Persistence-Context-Ballast beim
Massen-Insert; hbm2ddl=validate (Flyway besitzt das Schema).

## 8. Entitäten voll ausmodelliert (keine Trennzeichen-Listen)
Companies/Personen etc. sauber als Entitäten. -> theme/location kanonisch, org/person Surrogat +
Alias-Resolver; article_* als FK-Link-Tabellen. -> Suchen über Integer-FKs; Matching nur beim Ingest.

## 9. Ein Deployment, Profile für ingest/api
Kleines System, Solo-Betrieb. -> Ein Artefakt; Ingest hinter Profil `ingest`, API immer aktiv;
Packages trennbar. -> Spätere Auftrennung ist Deployment-Schalter, kein Umbau. Signal zum
Trennen: News-Abrufe werden während eines Ingest-Laufs spürbar träger.

## 10. Hosting: App + DB co-located
Dringend geringe Latenz Java<->Postgres. -> Beide beim selben Anbieter, selbe Region/AZ,
privates Netz. Primär IONOS Cloud (VPS + DBaaS), Alternative AWS single-VPC. Frontend später
IONOS Deploy Now (hostet kein Java). -> Nie Cross-Cloud (entwertet auch Supabase-DB als Option).

## 11. Entity-first ab V2, Schema-first bei V1
V1-Schema (Partitionierung/Sequenzen/generierte Spalten) ist nicht aus Entities ableitbar. ->
V1 = handgeschriebenes SQL, Entities folgen (validate). Ab V2: Entity-Änderung + handgeschriebene
Migration = ein Commit, grünes mvn verify Pflicht. -> Kein Auto-Generieren; Flyway führt nur aus.

## 12. Quellen: GDELT als Router + Primär-Feeds; kuratierte Allowlist
GDELT liefert Metadaten/URLs, nicht Volltext; Premium-Presse gesperrt/paywalled; §44b-Pflichten.
-> GDELT für Selektion/Signifikanz, Primär-/Institutionsquellen für Inhalt; Allowlist mit
robots/TDM-Prüfung (konservative Regel) und Beweislast-Logging.

## 13. Spring Boot 4 Modularisierung — Abhängigkeiten & Build (bei Grundgerüst festgestellt)
SB 4 (verwendet: 4.1.0, Hibernate ORM 7.4.1, JPA 3.2) hat die monolithische
`spring-boot-autoconfigure` in technologie-spezifische Module aufgetrennt; die 4.1-BOM zieht
zudem Testcontainers 2.x. -> Konkret nötige Abweichungen vom „klassischen" SB-3-Setup:
- **`spring-boot-starter-webmvc`** statt `spring-boot-starter-web` (REST-API immer aktiv; das alte
  Starter-Artefakt existiert nur noch als deprecated „classic starter").
- **`spring-boot-flyway`** als explizite Abhängigkeit — die Flyway-Autokonfiguration ist NICHT mehr
  in `spring-boot-autoconfigure`; ohne dieses Modul läuft die Migration beim Start stillschweigend
  gar nicht (Schema bleibt leer). `flyway-database-postgresql` bleibt separat, `flyway-core` kommt
  transitiv.
- **Testcontainers 2.x**: Modul-Artefakte heißen jetzt `testcontainers-postgresql` und
  `testcontainers-junit-jupiter` (2.x-Namensschema, alle BOM-verwaltet, versionslos).
- **`maven-failsafe-plugin`** explizit an `integration-test`/`verify` gebunden; sonst werden die
  `*IT`-Integrationstests stumm übersprungen (BUILD SUCCESS ohne einen einzigen Testlauf).
-> Begründung: Ohne diese Module baut/startet der Dienst nicht wie erwartet, die Fehler sind aber
still (leeres Schema, nicht laufende Tests) statt laut. Konsequenz: Bei neuen SB-4-Features prüfen,
ob ein eigenes `spring-boot-<x>`-Autoconfig-Modul nötig ist; Actuator/Data-JPA sind über ihre
Starter bereits abgedeckt. Lokaler Testlauf (Rancher Desktop) braucht maschinenspezifische
Env-Variablen (`DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`) — bewusst NICHT im Repo;
die IT-Basisklasse pollt zusätzlich den Host-Port (Rancher etabliert Port-Forwarding verzögert).

## 14. Usecase-POJOs + Hibernate ohne Spring-Data-Repositories
Geschäftslogik soll ohne Spring/Netz testbar und klar von Infrastruktur getrennt sein; der
Firehose-Pfad braucht ohnehin `StatelessSession` statt Repository-Abstraktion. -> Persistenz
ausschließlich über Hibernate (`EntityManager`/`StatelessSession`), KEINE Spring-Data-Repositories.
Geschäftslogik in eigenständigen Usecase-POJOs (annotationsfrei), die von dünnen `@Service`-
Fassaden aufgerufen werden; Infrastruktur (HTTP/Entpacken/JDBC) hinter Ports, nur Adapter sind
`@Component`. -> Usecases + Mapper sind reine POJOs -> deterministische Unit-Tests ohne Container;
Services bleiben triviale Delegatoren; der `@Scheduled`-Poller kann später denselben Service nutzen.
Erstanwendung: GDELT-Ingest (`com.lucoris.pulse.ingest`).

## 15. Marktrelevanz-Filter beim Ingest (GKG-Themen)
GDELT liefert weit mehr Artikel als für ein Markt-/Finanz-News-System nutzbar; die DB soll klein
und relevant bleiben. -> Ein Filter greift NACH dem Parsen der GKG-Datei und VOR dem Schreiben:
ein Artikel wird nur gespeichert, wenn seine V2Themes einen nicht-leeren Schnitt mit dem
Marktrelevanz-Set haben (Präfix-Match, Set in `application.yml`; Vorschlag Wirtschaft/Politik:
`ECON_`, `EPU_`). -> Nicht relevante Artikel werden verworfen, bevor irgendeine Entität entsteht;
je File wird eine Statistik (geparst/behalten/verworfen) geloggt. Der Filter ist GKG-scoped
(nur GKG trägt Themen); Events/Mentions werden daran gekoppelt (siehe ADR 16). Logik im POJO
`MarketRelevanceFilter`, aufgerufen vom Usecase (siehe ADR 14).

## 16. Events/Mentions an marktrelevante Artikel koppeln
Der Theme-Filter reduziert nur GKG; Events/Mentions (die keine Themen tragen, aber das größte
Volumen ausmachen) sollen ebenfalls nicht mit nicht-relevanten Daten fluten. -> Kopplung am Ingest
über die vorhandenen GDELT-Brücken, pro 15-Min-Slice in der Reihenfolge GKG → Mentions → Events:
Mention bleibt nur bei `mention_identifier = document_identifier` eines behaltenen Artikels.
Standardmäßig an, abschaltbar (`filter-linked-events-and-mentions`). -> Nicht-relevante Mentions
entstehen gar nicht erst; DB bleibt klein. Bewusste Intra-Slice-Näherung für die Mention-Kopplung
(kein slice-übergreifender Join). Die zunächst geplante Intra-Slice-Event-Filterung wurde durch die
Zwei-Phasen-Event-Auflösung ERSETZT (ADR 17), weil GDELT jedes Event nur EINMAL liefert (im
DATEADDED-Slice) und später relevante Events sonst fehlten. Logik im Usecase
(`IngestGdeltDayUsecase`, siehe ADR 14).

## 17. Zwei-Phasen-Event-Auflösung (statt Intra-Slice-Event-Filter)
GDELT liefert jedes Event nur EINMAL im Events-File (im DATEADDED-Slice); spätere Erwähnungen kommen
nur als neue Mention-Zeilen. Eine reine Intra-Slice-Kopplung verlöre Events, deren relevanter
Artikel erst später kommt. -> Je Slice zwei Phasen: Phase 1 schreibt relevante GKG + gekoppelte
Mentions und committet sie; Phase 2 ermittelt über diese Mentions per SQL (`not exists` gegen
`gdelt_events`, über `mention_time_date` auf den Slice eingegrenzt) die fehlenden Events, lädt sie
aus ihrem `eventTimeDate`-Slice (aktueller Slice nur einmal geladen; ältere gebündelt, jeder einmal,
bis zu `event-backfill-retries` Versuche) und schreibt sie. Nicht auffindbare Events werden als Stub
angelegt (`global_event_id` + `date_added = eventTimeDate`). -> Nach jedem Slice hat jede behaltene
Mention ihr Event (echt oder Stub); Konsistenz auch für später relevante/ältere Events. `not exists`
macht das Nachladen idempotent; Stubs verhindern wiederholtes Suchen und sind später per
Housekeeping durch echte Events ersetzbar. Reads über Hibernate `StatelessSession`-HQL (kein
Spring-Data). Details in `ingest-and-sources.md` („Filter-Ablauf im Detail").

## 18. ingest_log als Slice-Dedup, transaktional mit den Nutzdaten
Wird ein bereits eingelesener Slice erneut verarbeitet (Wiederanlauf, Überlappung), scheitern
GKG-Inserts am natürlichen PK (Constraint-Verletzung); Mentions würden dupliziert. -> Je
verarbeiteter Slice-Datei ein `ingest_log`-Eintrag (Dateiname als PK), der INNERHALB DERSELBEN
Transaktion wie die Nutzdaten geschrieben wird (Phase 1 schreibt relevante GKG + gekoppelte Mentions
+ beide `ingest_log`-Einträge atomar über `insertAtomic`). Vor der Verarbeitung prüft
`isFileProcessed(gkg-Datei)` und überspringt bereits eingelesene Slices (Phase 2 läuft weiter, ist
idempotent). -> Läufe sind wiederhol-/fortsetzbar ohne Doppelspeicherung; der Vermerk existiert nur
bei committeten Daten (atomar). `processed_at` setzt die DB (`DEFAULT now()`, Entity
`insertable=false`); `md5` bleibt vorerst leer (spätere Republish-/Integritätsprüfung). Ein
fehlender Slice (404) wird nicht vermerkt (Retry in späterem Lauf möglich).

## 19. URL-Index (url_index) — append-only, ohne Primary Key
Lucoris bekommt (z.B. von Perplexity) eine Quell-URL; darf sie wegen robots.txt/TDM-Vorbehalt
nicht gelesen werden, braucht Lucoris ANDERE Artikel zum selben Ereignis (Fact-Check über
alternative Quellen). Die URL-Spalten liegen verstreut über `gdelt_events.source_url`,
`gdelt_mentions.mention_identifier`, `gdelt_gkg.document_identifier`. -> Eine flache Tabelle
`url_index (global_event_id, url, source_flag)` mit je einem btree-Index auf `global_event_id`
und `url` macht den Pivot „URL → global_event_id → alle anderen URLs des Events" zu einem
indexgestützten Zugriff. Befüllt INNERHALB der zwei bestehenden Ingest-Transaktionen (keine dritte):
die Events-Transaktion schreibt Primär-Zeilen (`'P'`, aus `source_url`), die Slice-Transaktion
Sekundär-Zeilen (`'S'`). -> Weil `gdelt_gkg` KEINE `global_event_id` trägt, kommen die S-Zeilen aus
den Mentions (`mention_identifier` + echte `global_event_id`) — im Kopplungsmodus sind das genau
die marktrelevanten GKG-Artikel-URLs, nur mit Event-ID; `global_event_id` bleibt so `NOT NULL`.
Die Tabelle hat BEWUSST keinen Primary Key / kein Unique: Dubletten sind erlaubt (Performance —
keine Konfliktprüfung/Dedup-Kosten am Firehose, kein `ON CONFLICT`, kein Batch-Rollback). Hibernate
verlangt für `StatelessSession.insert` zwar ein `@Id`; das erfüllt ein zusammengesetzter, nur
mapping-seitiger `@IdClass` über die drei Spalten (assigned, kein Surrogat/keine Sequence). Da
`session.insert` keine Identity-Map hat und kein SELECT-before-insert macht und die Tabelle keinen
Unique/PK trägt, entstehen bei gleichen Werten schlicht mehrere Zeilen; Konsumenten deduplizieren
per `DISTINCT`. `source_flag char(1)`, erweiterbar (aktuell nur P/S). Konsequenz/Trade-off:
Nicht partitioniert, append-only -> unbegrenztes Wachstum (~ Anzahl Mentions + Events); spätere
Partitionierung oder ein Dedup-/Housekeeping-Job bleiben Option. `insertEvents` wird produktiv
nicht mehr genutzt (Events-Schreiben läuft jetzt über `insertAtomic`, damit die P-Zeilen atomar
mit den Events committen), bleibt aber Teil des Firehose-Ports.

## 20. Primärquellen-Ingest: Manifest-Routing, Adapter je `handler`, ein `PrimaryEvent`
Der Primärquellen-Kanal (Nr. 12) soll wachsen können, ohne dass jede neue Quelle den Ingest umbaut.
-> Das Routing-Manifest (`src/main/resources/primary-sources/lucoris-pulse-primary-sources.json`)
ist die einzige Quelle der Wahrheit darüber, WAS abgerufen wird (`enabled`, `access.url`,
`legal_class`, `attribution`); der Code entscheidet nur das WIE. Jede Quelle nennt ihren `handler`;
der `AdapterDispatcher` routet darüber an genau eine Adapter-Klasse. Alle Adapter emittieren
denselben Typ `PrimaryEvent`. -> Eine neue Quelle ist entweder ein Registry-Eintrag (bei
`generic_rss`: keine Zeile Code) oder genau eine neue Adapter-Klasse.
- **Unbekannter `handler` wirft** (`UnsupportedOperationException`) statt still zu überspringen: eine
  Quelle, die auf `enabled` steht und die niemand abruft, wäre ein unsichtbares Datenloch. Der
  Usecase fängt pro Quelle ab, damit eine defekte Quelle die übrigen nicht mitreißt.
- **`PrimaryEvent` trägt `legal_class` und `attribution` mit** (aus der Quelle durchgereicht), damit
  das Rendering die Quellzeile (Institution + Datum + Deep-Link, ggf. Pflichtformel) bauen kann,
  ohne erneut in die Registry zu greifen. `eventType` fehlt bewusst — Klassifikation ist Routing,
  nicht Einlesen.
- **Kein Auto-Start**: `IngestPrimarySourcesUsecase` ist weder `ApplicationRunner` noch `@Scheduled`.
  Der Poller (`poll.mode`/`seconds`) kommt später und ruft ihn auf.

## 21. RSS/Atom über Rome, hinter einem `FeedFetcher`-Port
Feeds sind in der Praxis unsauber: der Fed-Feed beginnt mit einem UTF-8-BOM, der ECB-Feed hat gar
keine XML-Deklaration und liefert keine `<description>`. -> Rome (`com.rometools:rome`, Version
explizit — die Boot-BOM verwaltet sie NICHT) liest RSS 2.0 und Atom über dasselbe `SyndFeed`-Modell.
- **Der Port liefert `byte[]`, nicht `String`.** Ein `new String(bytes, UTF_8)` vor dem Parser
  scheitert an „Content is not allowed in prolog"; die Zeichensatz-/BOM-Auflösung gehört in Romes
  `XmlReader`.
- **`FeedFetcher` als eigener Port** (Adapter: `HttpFeedFetcher`, spiegelt `HttpGdeltSliceClient`):
  `GenericRssAdapter` kennt dadurch keinen `HttpClient` — ein Netz-Zugriff aus einem Unit-Test ist
  damit kompiliertechnisch unmöglich, nicht bloß ungetestet. Das ist die Zusicherung, dass der
  Standard-Build offline bleibt.
- **Datum**: Rome zuerst (`pubDate`/`published`/`dc:date`, dann Atoms `updated`), danach eine
  tolerante Formatkette (`FeedDates`). Formate ohne Zonenangabe werden als UTC gelesen — eine
  Annahme, aber die einzige, die nicht von der Server-Zeitzone abhängt. **Kein parsbares Datum oder
  kein Link => Eintrag wird verworfen**, nie mit der Abrufzeit aufgefüllt (das erzeugte stillschweigend
  falsche Zeitachsen).
- **XXE**: Romes Härtung greift nur, wenn Rome selbst parst. Wir bauen das JDOM-`Document` selbst
  (um an die Roh-Datumsangaben zu kommen) und stellen Doctypes/externe Entities daher selbst ab.
- Romes `SyndFeed.getLanguage()` füllt sich nur aus dem RSS-Element `<language>`; bei Atom steht die
  Sprache im Wurzel-Attribut `xml:lang` und wird separat gelesen.

## 22. Profil `validate-sources` für die Load-Validierung der Registry
Die Registry führt `confidence` (`verified` | `verify_endpoint` | `landing_only`) — eine Behauptung,
die veraltet. -> `SourceLoadValidator` ruft jede aktivierte Quelle über den ECHTEN Ingest-Pfad
(Dispatcher) ab und meldet Mismatches: als `verified` geführt, liefert aber nichts (URL umgezogen /
403) bzw. funktioniert, ist aber noch nicht als geprüft eingetragen. -> Nur unter Profil
`validate-sources` (`mvn spring-boot:run -Dspring-boot.run.profiles=validate-sources`); die Beans des
Primärquellen-Pfads stehen unter `@Profile({"ingest","validate-sources"})` (ODER-Semantik).
Der Validator ist der einzige `ApplicationRunner` — deshalb erreicht kein Standard-Test das Netz.
Die `Clock` wird hier direkt gesetzt statt als Bean injiziert: `IngestConfig` definiert `ingestClock`
nur unter Profil `ingest`, ein zweites Clock-Bean würde bei beiden aktiven Profilen kollidieren.

## 23. Kandidatenquellen werden angetippt, nicht aktiviert
`SourceLoadValidator` und `PrimaryRssLiveIT` iterieren `enabledSources()`. Eine Quelle mit
`confidence: verify_endpoint` ließe sich damit nur prüfen, indem man sie aktiviert — dann wäre das
Aktivieren selbst der Test, und eine unerreichbare oder umgezogene URL landete im Ingest, bevor sie
je jemand abgerufen hat (bei `bmf-presse` war die hinterlegte URL ein 404). -> `PrimarySourceProbeIT`
zieht eine Quelle per ID aus `load().ingestSources()` (also auch `enabled: false`) und ruft sie über
denselben `AdapterDispatcher` ab, den der Ingest benutzt — nicht über einen zweiten Parser-Pfad.
- **Doppelt gegatet**: `PRIMARY_LIVE_IT=true` UND `-Dprimary.source=<id>`. Ohne beides deaktiviert
  JUnit die Klasse, bevor ein Socket aufgeht; der Standard-Build bleibt offline.
- **Ohne Spring und ohne DB** (wie `PrimaryRssLiveIT`): ein RSS-Abruf braucht kein Postgres.
- **Zusicherungen quellunabhängig**: `legal_class` wird gegen das Manifest geprüft, nicht gegen ein
  hartes `"A"` — die Klasse muss auch für Klasse-B-Kandidaten taugen.
- Reihenfolge beim Einbau einer Quelle: robots/TDM prüfen -> URL antippen -> Probe grün ->
  `confidence: verified` -> erst danach `enabled: true`. `enabled` bleibt das Tor zum Ingest.

## 23. RobotsGate: robots.txt + TDM-Vorbehalt als Sicherheitsnetz, fail-closed
Die kuratierte Allowlist ist eine Handprüfung — und Handprüfungen irren. Beim Bauen genau
nachgewiesen: `bmf-presse` war von Hand als „Feed-Pfade nicht disallowed" freigegeben worden,
tatsächlich sperrt die `*`-Gruppe der BMF-robots.txt mit `Disallow: */SiteGlobals` genau den Zweig,
in dem der Feed liegt. -> Ein maschinelles Gate prüft VOR jedem Abruf und verweigert ihn im Zweifel.
- **Drei Verbotsgründe**: (1) robots.txt verbietet unserem Token den Pfad; (2) robots.txt sperrt
  einen gängigen KI-Crawler (GPTBot, ClaudeBot, CCBot, Google-Extended, ...) für **denselben Pfad**
  — KONSERVATIVE REGEL aus CLAUDE.md, die Namenslücke wird nicht ausgenutzt; (3) TDM-Vorbehalt in
  `/.well-known/tdmrep.json` für den Pfad. Bewusst pfadgenau statt domainweit: ein GPTBot-Disallow
  auf `/shop/` darf den Abruf von `/rss/` nicht blockieren (kein Fehlalarm).
- **Fail-closed**: robots.txt mit 5xx/401/403 oder gar nicht erreichbar => VERBOTEN. Kein Nachweis
  der Erlaubnis = kein Abruf; die Beweislast liegt beim Data Miner. Nur ein sauberes 404/410 heißt
  „keine robots.txt, also keine Einschränkung" (RFC 9309).
  Ausnahme mit Grund: eine nicht erreichbare `tdmrep.json` hebt eine GÜLTIGE robots-Auskunft nicht
  auf. Ihr Fehlen ist der Normalfall; würde ein 5xx dort sperren, wäre praktisch jede Domain gesperrt.
- **Sitz des Gates**: Dekorator (`RobotsGatedAdapter`) VOR dem `AdapterDispatcher`, nicht im
  RSS-Adapter. Damit ist jeder künftige Handler (`sec_edgar`, ...) zwangsläufig abgedeckt und
  niemand umgeht das Gate versehentlich, indem er einen neuen Adapter schreibt. Auch die Live- und
  Probe-ITs laufen durch dasselbe Gate — gerade die Probe, denn dort wird eine noch UNGEPRÜFTE
  Quelle zum ersten Mal angefasst.
- **Verbot wirft** (`SourceNotPermittedException`) statt eine leere Liste zu liefern: eine verbotene
  Quelle sähe sonst aus wie ein leerer Feed. Der Ingest fängt pro Quelle ab, der Validator meldet
  sie gesondert.
- **Kein Kill-Switch.** Ein Ausschalter würde genau das Netz entwerten, für das er da ist.
- **Cache je Host** (Caffeine, `maximumSize` gesetzt): bei 300 s Poll würde robots.txt sonst 288-mal
  am Tag je Quelle geholt. Erfolge 24 h, Fehlschläge nur 5 min — fail-closed sperrt die Quelle,
  solange die Auskunft fehlt, und ein kurzer 503 darf sie nicht einen ganzen Tag aussperren.
  Der Cache steckt direkt in Caffeine, NICHT hinter Springs `@Cacheable`: das Gate ist ein POJO und
  muss ohne Spring-Kontext testbar bleiben (die JCache/`@Cacheable`-Schicht aus CLAUDE.md bleibt für
  die Spring-verwalteten Caches des REST-Read-Models).
- **Bot-Manager-Fallstrick** (beim Bauen gefunden): Radware vor `bundesfinanzministerium.de`
  antwortet mit einem 302, dessen Location-URL den User-Agent UNKODIERT enthält (Leerzeichen,
  Klammern). Der JDK-`HttpClient` wirft dann beim Folgen des Redirects `IllegalArgumentException` —
  **unchecked**, also nicht vom `catch (IOException)` erfasst. Beide HTTP-Adapter fangen das jetzt
  ab; im Gate führt es (richtigerweise) zu fail-closed statt zum Absturz.
- **Grenze**: der `TDM-Reservation`-HTTP-Header auf der Feed-Antwort selbst wird noch nicht geprüft
  (dafür müsste der Feed-Port seine Header durchreichen). robots.txt und `tdmrep.json` sind
  abgedeckt.

## 24. express_invitation: ausdrückliche Abo-Einladung schlägt ein GENERISCHES robots-Disallow
Der RobotsGate (ADR 23) sperrte `bmf-presse`, weil die BMF-robots.txt in der `*`-Gruppe
`Disallow: */SiteGlobals` führt — den CMS-Zweig, in dem Formulare, Skripte und Stylesheets liegen und
in dem der RSS-Feed zufällig auch liegt. Dass das Kollateralschaden ist und keine Absage, ist
BELEGT: Destatis nutzt dasselbe CMS mit derselben Pauschalsperre, hat aber
`Allow: /SiteGlobals/Functions/RSSFeed/DE/` ergänzt. BMF hat JavaScript, CSS, Buttons und
SocialBookmarks freigeschaltet — und RSSFeed vergessen. Gleichzeitig lädt das BMF auf einer eigenen
Seite ausdrücklich zum Abonnieren ein. Ein Feed ist zum Abonnieren gemacht; eine Zweig-Regel, die
ihn nur nebenbei erfasst, ist keine Absage an genau diese Nutzung.
-> Optionales Registry-Feld `express_invitation` (page_url, wording, retrieved, scope) und ein
neuer Gate-Zustand `ALLOW_BY_INVITATION`. **Kein Override-/Force-Flag, keine Sonderbehandlung
einzelner Quellen im Code.** Die Evidenz kommt ausschließlich von Hand aus der Registry — der Code
erfindet sie nie.

Ein Disallow wird NUR dann zur Einladung, wenn ALLE gelten:
- **(a)** `access.type == rss` — nur ein Feed kann „zum Abonnieren angeboten" sein.
- **(b)** Evidenz vollständig (page_url, wording, retrieved) UND nicht veraltet
  (`lucoris.ingest.primary.invitation-max-age`, Default 180 Tage). Evidenz altert: eine Feststellung
  von 2026 trägt einen Abruf 2031 nicht. Sonst `BLOCKED_STALE_INVITATION`.
- **(c)** Das TREFFENDE Muster erfasst den Feed nur beiläufig (`PatternScope`). Nennt es
  `rss`/`feed`/`atom`/`xml`, endet es auf `$`, zielt es auf eine Datei — oder ist es ein Total-Bann
  (`/`, `*`, `/*`) —, bleibt es BLOCKED. **Der Total-Bann ist bewusst ausgenommen**: ein pauschales
  „keine Bots, nirgends" erfasst den Feed nicht nebenbei, sondern absichtlich; sonst hebelte eine
  Einladung jede Komplettsperre aus. Die Klassifikation ist absichtlich ÜBERSTRENG — ein falsch als
  „gezielt" eingestuftes Muster kostet einen Abruf (harmlos), ein falsch als „beiläufig" eingestuftes
  bricht den erkennbaren Willen des Herausgebers (nicht harmlos).
- **(d)** Das treffende Disallow steht in der `*`-Gruppe. Nennt uns die Seite BEIM NAMEN und sperrt
  uns, ist das eine gezielte Absage, keine Namenslücke. Jede benannte KI-Crawler-Gruppe gewinnt ebenso.
- **(e)** TDM-Kanäle sind clean — sie gewinnen IMMER.

**(d) und (e) laufen STRUKTURELL vor der Einladungs-Leiter**, damit niemand sie später versehentlich
dahinter schiebt und die konservative Regel still aushebelt. Dafür gibt es Reihenfolge-Tests.

Jede `ALLOW_BY_INVITATION`-Entscheidung schreibt eine Beweislast-Zeile auf WARN (sourceId, Muster,
UA-Gruppe, page_url, retrieved, scope, wording, Zeitstempel). Die Evidenz hängt als Record am
`Decision` — die Tests prüfen den Record, nicht den Logger.

### Der Nebenbefund, ohne den das Feature tot gewesen wäre
`blockedAiCrawlers()` rief `allows()` auf, und das fällt bei fehlender KI-Gruppe auf `*` zurück. Bei
BMF sperrt nur `*` -> es meldete **alle 20 KI-Crawler als gesperrt**, obwohl die Datei keinen einzigen
nennt. Die konservative Regel hätte die Einladung immer vorher erschlagen — und die Liste war
obendrein eine Falschaussage. -> Nur noch KI-Crawler zählen, die durch eine EIGENE, BENANNTE Gruppe
gesperrt sind; implementiert über `match()` + `group().equals(crawler)`, NICHT über `allows()` (sonst
kippt die Regel still, sobald jemand die Fallback-Logik anfasst). **Das schwächt nichts**: eine Site
mit `User-agent: * / Disallow: /` bleibt über `allows()==false` gesperrt. Zwei Wächter-Tests halten
das fest (`wildcardDisallowIsNotAnAiReservation`, `siteWideDisallowStillBlocksUs`).

### TDM-Header: post-fetch statt Gate-Vorbedingung
Der `TDM-Reservation`-Header ist eine Aussage über die AUSGELIEFERTE RESSOURCE und steht
definitionsgemäß erst in der Antwort. Ein HEAD im Gate wäre genau dort blind, wo man ihn braucht
(hinter einem Bot-Manager bekommt er 302) und wäre ein Extra-Request gegen eine Site mit
`Crawl-delay: 180`. -> `TdmAwareFeedFetcher` als Dekorator um den Fetcher (wie der RobotsGatedAdapter
um den Dispatcher): Header gesetzt => `SourceNotPermittedException`, BEVOR ein Handler die Bytes
sieht. 0 Extra-Requests, schützt alle Quellen, schließt die in ADR 23 dokumentierte Lücke. Rechtlich
sauber: § 44b UrhG verbietet das *Mining*, nicht den Abruf — der Abruf war durch robots.txt gedeckt.

### Grenzen, die sichtbar bleiben müssen
- **Der BMF-Fall ist maschinell NICHT verifizierbar.** Die Einladungsseite ist für Bots durch einen
  Radware-Bot-Manager gesperrt (5/5 Abrufe -> HTTP 302 auf validate.perfdrive.com). Der
  `InvitationVerifier` (nur Profil `validate-sources`) meldet daher dauerhaft
  `INVITATION_UNVERIFIABLE` — bewusst NICHT `BLOCKED_STALE_INVITATION`: „nicht gesehen" ist kein
  Beweis für „geändert", und eine unlesbare Seite als veraltet zu führen wäre eine Falschaussage im
  Audit-Trail. Die Erlaubnis ruht damit allein auf der HANDAUFZEICHNUNG in der Registry. Wer daraus
  stillschweigend „geprüft" macht, hat die Beweislast auf null gesetzt.
- **`scope` ist Prosa, kein Pfad-Präfix** — kein String-Matcher. Seine Aussage („nur die konkret
  verlinkten Feed-XMLs, nicht pauschal für den Pfad") wird STRUKTURELL eingehalten: die Evidenz hängt
  an EINER Quelle, das Gate entscheidet über DEREN Feed-URL. Eine Einladung kann konstruktionsbedingt
  nie einen ganzen Pfad freigeben. Dass die verlinkten XMLs nicht gegengeprüft werden können, ist die
  Folge der Bot-Sperre oben.
- **Die Einladung erlaubt den ABRUF, sie ist KEINE Lizenz.** `legal_class: B` bleibt B: nur Fakten
  extrahieren, eigene Formulierung, Link. Nichts an dieser Entscheidung darf als Nutzungsrecht an der
  Ausdrucksform gelesen werden.
- **`enabled` bleibt das Tor.** `ALLOW_BY_INVITATION` schaltet `bmf-presse` NICHT scharf; das bleibt
  eine getrennte menschliche Entscheidung.
- **Crawl-delay:** BMF setzt 180 s. Sich auf das Wohlwollen eines Herausgebers zu berufen und
  zugleich seine Abrufgrenze zu missachten, wäre der peinlichste denkbare Widerspruch. Der
  `SourceLoadValidator` warnt jetzt, wenn `poll.seconds < Crawl-delay` (BMF: 900 > 180, hält).

## 25. Feed-Meldungen persistieren: `primary_feed_item`, Dedup über guid/Link, „FeedItem" statt „Event"
Die vom Primärquellen-Ingest gelieferten Meldungen mussten gespeichert werden (V3) — mit der
Anforderung, dass dieselbe Meldung aus überlappenden Feeds nur einmal landet und wiederholte
Läufe idempotent sind.
- **Benennung:** Der Typ heißt jetzt `FeedItem` (vorher `PrimaryEvent`), Tabelle
  `primary_feed_item`. Gespeichert wird die MELDUNG (das RSS/Atom-Item), nicht das reale
  Ereignis: mehrere Meldungen zum selben Ereignis bleiben getrennte Zeilen — ihre
  Zusammenführung (auch mit GDELT über `url_index` ↔ `global_event_id`) ist eine spätere
  Resolver-Entität. „Event" bleibt dafür und für GDELT reserviert; die GDELT-Seite ist vom
  Rename unberührt.
- **Dedup-Schlüssel** (`DedupKeys`, pures POJO): URL-förmige Feed-guid normalisiert, sonst
  normalisierter Link. NICHT `source_id + url` — bmf-presse und bmf-finanzmarkt liefern dieselbe
  Meldung mit derselben guid (= Artikel-URL). Opake guids nie roh (zwei Herausgeber können
  „12345" vergeben). Normalisierung entfernt nur nachweislich Identitäts-irrelevantes
  (utm_*/fbclid/... , Fragment, Host-Case, Default-Ports); Pfad und übrige Query bleiben.
  Rohe guid + rohe URL werden mitgespeichert (Audit, Re-Keying).
- **Mechanik: select-then-insert** in einer StatelessSession-Transaktion je Quelle, kein
  `ON CONFLICT`: Instanz ist single, Poller sequenziell — kein Insert-Race; exakte
  neu/dedupliziert-Zähler fallen gratis ab; konsistent mit dem GDELT-Muster (`isFileProcessed`,
  not-exists-Backfill, ADR 18/19). Der UNIQUE-Index auf `dedup_key` ist das harte Sicherheitsnetz:
  schlägt er je an, scheitert nur der Batch dieser Quelle (als Quellen-Fehler verbucht), der
  nächste Lauf heilt es.
- **Attribution flach** (3 Spalten) statt jsonb; Surrogat-Sequence INCREMENT 50; keine
  Partitionierung bei diesem Volumen. Schema: `docs/data-model.md`.

## 26. Periodischer Quellen-Poller: EIN fixedDelay-Tick, Property-Gate default AUS, Zustand je Quelle
Die enabled-Quellen sollen gemäß ihrem Manifest-`poll`-Intervall automatisch abgerufen werden —
abschaltbar, und in Tests darf NIE von selbst Netz angefasst werden.
- **Ein einziger `@Scheduled(fixedDelay)`-Tick** (Default 30s, `PrimaryPollingConfig`) statt
  per-Quelle-Trigger: 6 Quellen brauchen keine Parallelität, sequenziell ist die natürliche
  Höflichkeit, und die Fälligkeit (`PollSchedule`) rechnet aus persistiertem `last_attempt_at`
  + Manifest-Intervall — überlebt damit Restarts (kein Hämmern bei jedem Deploy). `fixedDelay`
  statt `fixedRate`: Läufe überlappen nie.
- **Doppelt verriegelt:** `@Profile("ingest")` UND `@ConditionalOnProperty(poll.enabled,
  default AUS)`. Profil-Gating allein genügt NICHT — mehrere ITs aktivieren `ingest`
  (`GdeltIngestContextIT`, `FeedItemStoreIT`, ...). `@EnableScheduling` lebt NUR auf dieser
  konditionalen Klasse: ohne Property existiert kein Scheduler-Thread. Produktion:
  `LUCORIS_INGEST_PRIMARY_POLL_ENABLED=true`.
- **Effektives Intervall = max(poll.seconds, robots Crawl-delay)** über
  `RobotsGate.crawlDelaySeconds`: wer sich auf das Wohlwollen eines Herausgebers beruft
  (ADR 24), verletzt nicht seine Abrufgrenze.
- **`poll.mode=calendar` (destatis-press) ist für den Intervall-Poller nie fällig** —
  Kalender-Polling ist ein späteres Increment; der explizite Einmal-Lauf (`runAll()`) ruft
  solche Quellen weiterhin ab. `interval` ohne `seconds` wird als Fehlkonfiguration geloggt
  statt als 0s-Hot-Loop gepollt.
- **Fehlerisolation + Buchführung im Usecase, nicht im Poller:** `runSource` wirft nie, schreibt
  Erfolg/Fehler nach `primary_source_state` (`consecutive_failures`, `last_error`) — Einmal-Lauf
  und Poller teilen dieselbe Buchführung; eine tote Quelle stoppt den Tick nie und ist in der DB
  sichtbar statt nur im Log.


## 27. EDGAR-Anbindung: submissions-API (Echtzeit, Watchlist) + Tagesindex (Voll-Abgleich)
Die im Manifest reservierte Quelle `sec-edgar` wird als erste API-Primärquelle gebaut: neue
**8-K/8-K-A** (US-Ad-hoc, höchstes Marktsignal) als `FeedItem`, ohne Schema-Änderung, über das
bestehende Muster (Adapter-POJO → Dispatcher → RobotsGate → `primary_feed_item`).

**Der erste Anlauf war unzulässig — und das Gate hat es gefangen.** Gebaut war der globale
Echtzeit-Strom `browse-edgar?action=getcurrent&output=atom`; die Live-Probe wurde verweigert. Zu
Recht: `sec.gov/robots.txt` führt `Disallow: /cgi-bin`. Die SEC sperrt ihre alten CGI-Skripte und
erlaubt mit `Allow: /Archives/edgar/data` ausdrücklich die Daten — nicht die Quelle war blockiert,
nur die URL. `enabled:false` wäre der falsche Fix gewesen. Der Feed hätte funktioniert und wäre
trotzdem unzulässig gewesen; genau dafür existiert das Gate.

**Endpunktwahl (alle Kandidaten am 2026-07-16 live geprüft, nicht angenommen):**

| Endpunkt | robots | Echtzeit | Watchlist | Zeitstempel |
|---|---|---|---|---|
| `/cgi-bin` getcurrent | **Disallow** | ja | nein | exakt |
| `efts.sec.gov` (Volltextsuche) | robots.txt **403** → fail-closed | ~min | nein | Datum |
| `/Archives/edgar/daily-index` | keine Regel trifft → erlaubt | **End-of-Day ~22:00 ET** | nein | **nur Datum** |
| `data.sec.gov/submissions` | robots.txt **404** = frei (RFC 9309) | **ja (<1s)** | **ja** | **exakt** |

Es gibt also **keinen erlaubten globalen Echtzeit-Strom**. Daraus folgen zwei Handler statt einem:
- **`sec_edgar` (Echtzeit)** über `data.sec.gov/submissions/CIK##########.json`. Die API ist per
  Firma adressiert ⇒ **kuratierte Watchlist** `sec-edgar-ciks.json` (90 CIKs, aus der maßgeblichen
  SEC-Quelle `files/company_tickers.json` übernommen, nicht getippt). Bewusst eine Ressourcendatei
  statt einer Tabelle: `company` trägt `ticker`/`isin`, aber **keine `cik`** — ein Universum gäbe es
  nur mit Migration + Sync-Job, und der Ingest soll marktrelevante Emittenten beobachten, nicht jede
  Briefkastenfirma. Erweitern = Eintrag ergänzen, kein Code.
- **`sec_edgar_daily` (Voll-Abgleich)** über den Tagesindex — das Netz unter der Watchlist, das auch
  unbeobachtete Firmen auffängt. Dafür erst am Abend und **nur mit Datum**: `publishedAt` =
  Tagesbeginn UTC. Bewusste Ungenauigkeit — es ist die einzige Angabe der Quelle; sie zu erfinden
  wäre schlimmer.
  **Er liest die letzten `dailyIndexDays` (Default 3) Kalendertage, nicht nur „heute".** Das kam aus
  der Live-Probe: die Datei des laufenden Tages erscheint erst gegen 22:00 ET (davor antwortet die SEC
  mit 403). Eine „nur heute"-Fassung lieferte also nur zwischen 22:00 ET und Mitternacht überhaupt
  etwas — ein Neustart in diesem Fenster verlöre den Tag stillschweigend, und ein Sicherheitsnetz mit
  einem Zwei-Stunden-Fangfenster ist keines. 3 Tage überbrücken zusätzlich ein Wochenende. Die
  Überlappung ist gratis: der Adapter mischt roh, das Entdoppeln macht ohnehin `DedupKeys`.

**Beide Handler erzeugen denselben `dedup_key`** — das ist der Vertrag zwischen ihnen und der Grund,
warum die Doppel-Abdeckung keine Doppel-Zeilen erzeugt: `SecEdgarUrls.filingPermalink` konstruiert
aus CIK (ohne führende Nullen) + Accession denselben Link
(`/Archives/edgar/data/{cik}/{accOhneStriche}/{acc}-index.htm`), `guid` = Accession (nicht
URL-förmig) ⇒ `DedupKeys` fällt auf ebendiesen Permalink zurück. Wer zuerst liefert, gewinnt; das
ist fast immer der Echtzeit-Pfad mit dem exakten `acceptanceDateTime`. Der Permalink wird
**konstruiert, nicht übernommen**: die Links, die EDGAR selbst mitgibt, zeigen firmenweit auf
`getcompany` und würden alle Filings einer Firma zu einer Meldung kollabieren.

- **`acceptanceDateTime`, nicht `filingDate`**: nur ersteres ist ein Zeitpunkt. `filings.recent` ist
  spaltenweise abgelegt (parallele Arrays); stimmen die Längen nicht überein, wird die Firma
  verworfen statt Titel und Zeitstempel verschiedener Einreichungen zu vermischen.
- **Lookback-Fenster (7 Tage)**: `recent` führt bis ~1000 Einträge (rund ein Jahr). Ohne Fenster
  liefe die ganze Historie jeder Firma bei jedem Tick durch die Dedup-Prüfung.
- **Pacing (120 ms ≈ 8 Req/s)**: die SEC lässt 10 Req/s zu und drosselt darüber mit 403. Abstand
  halten statt die Grenze ausreizen. Sweep über 90 CIKs ≈ 11 s bei 120 s Poll-Intervall.
- **Grenze, bewusst in Kauf genommen:** `access.url` im Manifest ist das **Präfix**, das der Gate
  prüft; die Adapter fächern darunter auf (CIK-Dateien bzw. Tagesdatei). Beide Präfixe tragen heute
  keine tieferen robots-Regeln (geprüft) — eine später ergänzte tiefere Regel sähe der Gate nicht.
  Der Gate je Einzel-URL zu befragen würde die Dekorator-Architektur umkehren; das ist es hier nicht wert.
- **KEIN `express_invitation`** (obwohl naheliegend): eine Einladung kann laut `CachingRobotsGate`
  nur ein *generisches* `'*'`-Disallow bei `access.type: rss` aufwiegen. Beide Endpunkte sind schlicht
  erlaubt — es gibt nichts zu heilen. **Keine UA-Änderung**: der bestehende UA mit Kontakt-URL liefert
  von `data.sec.gov` nachweislich HTTP 200; eine Kontakt-E-Mail anzuhängen wäre eine Änderung an allen
  Quellen ohne belegten Nutzen.
- **OFFEN:** weitere Formulartypen (10-K/10-Q/Form 4/13F/13D/13G); Volumen-/Partitionierungsfrage für
  `primary_feed_item` bei einem Massen-Handler (`docs/data-model.md`).

## 28. robots.txt: nur 200 wird geparst — 429 und unerwartete Codes sperren fail-closed
Beim EDGAR-Einbau aufgefallen: `PolicyFetcher.Response.unknown()` kannte **429 nicht**. Ein
gedrosseltes robots.txt fiel damit in den `parse()`-Zweig, dessen Fehlerkörper keine
`User-agent:`-Gruppen enthält → leere Regeln → „keine Regel trifft" → **erlaubt**. Ein Fail-open im
Sicherheitsnetz, ausgerechnet bei Hosts mit hartem Ratenlimit.
- **429 zählt zu `unknown()`** (RFC 9309 § 2.3.1.4 wertet es als „unavailable" = vollständiges
  Verbot): wer uns drosselt, hat uns die Hausordnung nicht gezeigt.
- **Whitelist statt Restmenge** in `CachingRobotsGate.load()`: NUR ein gelesenes Dokument (200) wird
  geparst, nur ein sauberes „gibt es nicht" (404/410) heißt „keine Einschränkung". Alles andere —
  400, 204, ein nicht gefolgter Redirect — ist keine robots.txt und sperrt fail-closed, statt aus
  einem Fehlerkörper einen Freibrief zu machen.
- Die 404-Regel bleibt unangetastet: sie ist RFC-konform und trägt `data.sec.gov`, das gar keine
  robots.txt hat. Nebenbefund: die SEC drosselt real mit **403**, das griff schon vorher.
