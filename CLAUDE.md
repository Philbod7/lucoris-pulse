# lucoris-pulse

GDELT-Adapter / Vorsystem für Lucoris: liest GDELT (und später primäre Feeds) ein,
speichert normalisiert in PostgreSQL und stellt aufbereitete News-Ereignisse über REST bereit.
Eigenständiger, von Lucoris entkoppelter Dienst — NICHT Teil des Lucoris-Kernprodukts.

Vertiefte Architektur und Begründungen: siehe `docs/`. Diese Datei ist die verbindliche
Arbeitsanweisung; bei Konflikt gilt sie.

## Tech-Stack (fixiert)
- Java 21 (Temurin), Maven (Single-Modul)
- Spring Boot 4.1.x (Spring Framework 7)
- PostgreSQL (Ziel-DB), HikariCP
- Persistenz: JPA/Hibernate + hybrider Firehose-Pfad über StatelessSession
- Migrationen: Flyway  (KEIN Liquibase, KEIN Hibernate-DDL)
- Cache: JCache (JSR-107) + Caffeine
- Tests: JUnit 5 + Testcontainers gegen echtes PostgreSQL
- Betrieb: Spring-Boot-Buildpacks -> Temurin-JRE-OCI-Image
- Package-Basis: `com.lucoris.pulse`

## Harte Regeln (immer einhalten)

### Schema & Migrationen
- Flyway besitzt das Schema. `hibernate.hbm2ddl.auto=validate` (nie create/update).
- V1 ist HANDGESCHRIEBENES SQL — Partitionierung, Sequenzen, generierte Spalten und
  GIN-Indizes sind nicht aus Entities ableitbar. Quelle: `docs/data-model.md`.
- Ab V2 gilt Entity-first: JEDE Entity-Änderung + zugehörige handgeschriebene Flyway-Migration
  = EIN Commit. Kein Commit ohne grünes `mvn verify` (Testcontainers).
- Migrationen werden nach dem Ausführen NIE editiert. Immer vorwärts (V+1).
- Additive Migration (ADD COLUMN/TABLE/INDEX): Test gegen leeres Schema genügt.
  Verändernde Migration (RENAME/TYPE/NOT NULL/DROP): mit Testdaten testen (Backfill prüfen).

### Schlüssel
- Alle surrogaten Primärschlüssel über Sequenzen mit `INCREMENT 50`.
- Entity-Mapping: `@SequenceGenerator(allocationSize = 50)`, pooled-lo Optimizer.
  allocationSize MUSS gleich dem INCREMENT der DB-Sequence (50) sein. Nie IDENTITY für Surrogate.
- Natürliche/zusammengesetzte Schlüssel (global_event_id, alias_norm, theme_code, filename,
  Composite-PKs der Link-Tabellen) haben KEINE Sequence.

### Persistenz
- JPA/Hibernate für Domäne, Resolver und REST-Read-Model.
- Persistenz ausschließlich über Hibernate — KEINE Spring-Data-Repositories
  (kein `JpaRepository`/`CrudRepository`). Zugriff über `EntityManager`/`StatelessSession`.
- Firehose (Massen-Insert: article, mentions, Link-Tabellen) über Hibernate `StatelessSession`
  + JDBC-Batch — NICHT über den Persistence-Context.
- Generierte Spalten (z.B. significance_score) lese-nur mappen (insertable/updatable=false).

### Geschäftslogik & Schichtung
- Geschäftslogik lebt in eigenständigen Usecase-POJOs (KEINE Spring-Annotationen), die von
  dünnen `@Service`-Klassen aufgerufen werden. Services enthalten keine Logik — nur Delegation
  und Verdrahtung. Usecases werden per Konstruktor mit Ports verdrahtet (`@Configuration`/`@Bean`).
- Infrastruktur (HTTP, Entpacken, JDBC/StatelessSession) hinter Ports; nur Adapter sind
  `@Component`. So bleiben Usecases und Mapper ohne Spring/Netz unit-testbar.

### Cache
- Springs `@Cacheable` über JCache (JSR-107) mit Caffeine als Provider.
- Jeder Cache mit `maximumSize`. Hibernate 2nd-Level-Cache vorbereitet, aber AUS.
- Redis/Valkey-Slot bleibt für den Mehr-Instanzen-Fall reserviert (jetzt nicht nutzen).

### Tests
- Integrationstests bis in echtes PostgreSQL via Testcontainers (postgres:17, @ServiceConnection).
- Kein DB-Mocking, kein H2. Geteilter statischer Container in einer Basisklasse.
- StatelessSession-Batch-Tests: explizit TRUNCATE im Teardown (Transaktions-Rollback greift dort nicht).

### Betrieb & Sicherheit
- Ein Deployment. Ingest hinter Spring-Profil `ingest`, REST-API immer aktiv.
  Packages `core` / `ingest` / `api` sauber getrennt (spätere Auftrennung ohne Umbau).
- Virtual Threads (Java 21) aktiviert.
- Actuator: Liveness/Readiness (Readiness inkl. DB-Check), Micrometer-Metriken (Ingest-Lag,
  Zeilen/Slice, Resolver-Trefferquote). Logging als JSON nach stdout.
- Container-Speicher: `-XX:MaxRAMPercentage` setzen; Caches begrenzt halten.
- Secrets NIE ins Repo — Konfiguration ausschließlich über Umgebungsvariablen.
- Externe Quellen: robots.txt / TDM-Vorbehalt VOR dem Abruf prüfen und mit Zeitstempel loggen.
  Konservative Regel: erkennbarer KI-/TDM-Vorbehalt = nicht abrufen. Siehe `docs/ingest-and-sources.md`.

## Befehle
- Bauen & testen:            `mvn verify`
- Lokal starten (alle):      `mvn spring-boot:run`
- Nur Ingest / nur API:      `-Dspring-boot.run.profiles=ingest`  bzw.  `api`
- OCI-Image bauen:           `mvn spring-boot:build-image`

## Arbeitsweise für den Agent
- Inkrementell: kleiner Schritt -> `mvn verify` grün -> dem Nutzer zum Commit übergeben. Claude
  committet NIE selbst (kein `git commit`/`add`/`push`); der Nutzer committet alles — siehe globale
  Regel in `~/.claude/CLAUDE.md`. Nie mehrere unverifizierte Änderungen bündeln.
- Bei jeder schema-relevanten Änderung `docs/data-model.md` und `docs/decisions.md` aktualisieren.
- Details, die hier nicht stehen, stehen in `docs/`. Erst dort nachsehen, dann fragen.
