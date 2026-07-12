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
