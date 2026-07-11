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
(nur GKG trägt Themen); Events/Mentions bleiben vorerst ungefiltert (spätere Kopplung über die
URL-Brücke bzw. `global_event_id` möglich). Logik im POJO `MarketRelevanceFilter`, aufgerufen
vom Usecase (siehe ADR 14).
