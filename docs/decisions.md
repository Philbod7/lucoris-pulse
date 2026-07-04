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
