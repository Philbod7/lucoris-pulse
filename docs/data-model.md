# Datenmodell — lucoris-pulse

Verbindliche Quelle des Schemas ist die Flyway-V1-Migration (aus `lucoris_gdelt_schema.sql`).
Dieses Dokument erklärt Aufbau und Begründung. Bei Änderungen: hier UND in `decisions.md` nachziehen.

## Fünf Schichten
- A ROH:      GDELT-Original 1:1 (gdelt_events, gdelt_mentions, gdelt_gkg) — Fidelity/Resale/
             Reprocessing. Die semikolon-Rohlisten (Themen/Orgs/...) leben NUR hier.
- B ABFRAGE:  article — Artikel-Hub, dedupliziert pro URL.
- C ENTITÄT:  theme / location / organization / person + article_* Link-Tabellen (per FK).
             KEINE Trennzeichen-Listen mehr — alles ausmodelliert.
- D AUFLÖSUNG: company (verweist auf organization), portfolio, portfolio_holding.
- E SIGNAL:   event_significance, theme_volume_daily.

## Die drei GDELT-Rohdatensätze (Schicht A)
GDELT (Global Database of Events, Language, and Tone) liefert je 15-Min-Slice drei Dateien:
- EVENTS (gdelt_events): ereignis-zentriert. Ein CAMEO-kodiertes Akteur–Aktion–Akteur-Ereignis je
  Zeile, global eindeutig über global_event_id. Beantwortet „WAS ist passiert".
- MENTIONS (gdelt_mentions): je Erwähnung eines Events in einem Artikel eine Zeile (Event 1:N),
  verbindet Ereignis und Artikel-URL (mention_identifier).
- GKG (gdelt_gkg): GKG = "Global Knowledge Graph". ARTIKEL-/dokument-zentriert — eine Zeile je von
  GDELT verarbeitetem Artikel (gkg_record_id, Quelle, document_identifier = URL). Beantwortet
  „WORUM geht es im Artikel": extrahierte Themen (V2Themes), Personen, Organisationen, Orte, Tonwert
  und Namen als semikolon-getrennte Rohlisten. KEINE global_event_id (nicht ereignisgebunden).
  Primärdatensatz für Lucoris: die Themen steuern Marktrelevanz-Filter und Entitäts-Auflösung; die
  Rohlisten leben nur hier und werden beim Ingest in Schicht C aufgelöst. Brücke zur Ereignis-Welt
  über die URL (mentions.mention_identifier = gkg.document_identifier).

## Klassendiagramm: beim Ingest geschriebene Entities
Beim Einlesen schreibt der Firehose die drei Roh-Entities der Schicht A (`GdeltEvent`,
`GdeltMention`, `GdeltGkg`) plus ihre `@IdClass`-Schlüsselklassen, den Dedup-Ledger `IngestLog`
(siehe ADR 18) und den `UrlIndex` (URL↔Event-Index, siehe unten und ADR 19). Die aufgelösten
Schichten (Article, Theme, Location, Organization, Person …) werden beim Ingest NICHT geschrieben
(kein Resolver implementiert).

```mermaid
classDiagram
    direction LR

    class GdeltEvent {
        +Long globalEventId
        +Instant dateAdded
        +LocalDate day
        +String actor1Code
        +String actor1Name
        +String actor1CountryCode
        +String actor1Type1Code
        +String actor2Code
        +String actor2Name
        +String actor2CountryCode
        +String actor2Type1Code
        +Boolean rootEvent
        +String eventCode
        +String eventBaseCode
        +String eventRootCode
        +Short quadClass
        +BigDecimal goldsteinScale
        +Integer numMentions
        +Integer numSources
        +Integer numArticles
        +BigDecimal avgTone
        +Short actionGeoType
        +String actionGeoFullname
        +String actionGeoCountryCode
        +BigDecimal actionGeoLat
        +BigDecimal actionGeoLong
        +String sourceUrl
    }

    class GdeltMention {
        +Long mentionId
        +Instant mentionTimeDate
        +Long globalEventId
        +Instant eventTimeDate
        +Short mentionType
        +String mentionSourceName
        +String mentionIdentifier
        +Integer sentenceId
        +Short confidence
        +BigDecimal mentionDocTone
    }

    class GdeltGkg {
        +String gkgRecordId
        +Instant seenDate
        +String sourceCommonName
        +String documentIdentifier
        +String v2Themes
        +String v2Locations
        +String v2Persons
        +String v2Organizations
        +String v2AllNames
        +String v2Tone
        +BigDecimal tone
    }

    class MentionId {
        <<IdClass>>
        +Long mentionId
        +Instant mentionTimeDate
    }

    class GkgId {
        <<IdClass>>
        +String gkgRecordId
        +Instant seenDate
    }

    class UrlIndex {
        +Long globalEventId
        +String url
        +String sourceFlag
    }

    class UrlIndexId {
        <<IdClass>>
        +Long globalEventId
        +String url
        +String sourceFlag
    }

    GdeltEvent "1" --> "0..*" GdeltMention : global_event_id
    GdeltMention "0..*" ..> "0..1" GdeltGkg : gleiche Artikel-URL
    GdeltMention ..> MentionId : IdClass
    GdeltGkg ..> GkgId : IdClass
    GdeltEvent "1" ..> "0..*" UrlIndex : source_url (P)
    GdeltMention "1" ..> "0..1" UrlIndex : mention_identifier (S)
    UrlIndex ..> UrlIndexId : IdClass
```

Schlüssel & Verknüpfungen (Legende zum Diagramm):
- GdeltEvent: PK = `globalEventId` (natürlich, von GDELT vergeben, KEINE Sequence); nicht partitioniert.
- GdeltMention: PK = (`mentionId`, `mentionTimeDate`); `mentionId` aus `mention_seq`,
  `mentionTimeDate` = Partitionsschlüssel; `@IdClass(MentionId)`.
- GdeltGkg: PK = (`gkgRecordId`, `seenDate`); `seenDate` = Partitionsschlüssel; `@IdClass(GkgId)`.
- Die Verknüpfungen sind LOGISCH (keine DB-Fremdschlüssel, unabhängig geladen): Event 1:N Mention
  über `global_event_id`; Mention → GKG über die Artikel-URL (`mention_identifier` =
  `document_identifier`). GKG hat KEINE `global_event_id`.

## Die vier Entitätstypen (unterschiedliche Kanonizität — Kernentscheidung)
- THEMA:  GDELT-Themencode ist bereits kanonisch -> theme.theme_code als PK, kein Resolver.
- ORT:    geokodiert (FeatureID/FIPS/ADM1/LatLong) -> Auflösung beim Ingest über Geo-Felder.
- ORG:    roher NER-Text, KEIN GDELT-Identifikator -> Surrogat + organization_alias-Resolver.
- PERSON: wie ORG (Namensgleichheit gefährlicher -> konservativ auflösen, im Zweifel nicht mergen).
Gleiches Muster (Entität + Artikel-FK), typ-spezifischer Schlüssel. String-Matching passiert
EINMAL beim Ingest, nie bei der Suche (Suchen laufen über Integer-FKs).

## Company vs. Organization
Nicht jede Organisation ist ein Wertpapier (Behörden, NGOs, Notenbanken). company verweist per
FK auf organization. Portfolio-Recall: portfolio_holding -> company -> article_organization ->
article, rein über organization_id, ohne Laufzeit-String-Matching. Aliasse braucht nur der Ingest.
Ticker->Firmenname-Mapping (inkl. Aliasse, Kollisionsschutz via is_ambiguous) wird gepflegt.

## Schlüssel (Sequenzen, batch-optimiert)
Alle surrogaten IDs über Sequenzen mit INCREMENT 50 (mention_seq, article_seq, location_seq,
organization_seq, person_seq, company_seq, portfolio_seq). Entities: @SequenceGenerator(
allocationSize = 50), pooled-lo. Grund: IDENTITY verhindert JDBC-Batching am Firehose.

## Partitionierung
article, gdelt_mentions, gdelt_gkg sind monatlich RANGE-partitioniert (Zeitfenster-Queries +
Retention per Partition-Drop). Zusammengesetzter PK (id, seen_date) macht die FKs der Link-
Tabellen partitionierungssicher; seen_date ist in die Link-Tabellen denormalisiert, damit
24h-Filter über den Index prunen. Partitions-Rollover per pg_partman/Cron, NICHT in Migrationen.

## Signifikanz
event_significance.significance_score ist eine GENERATED-Spalte (gewichtete Mischung aus
Coverage-Menge, Domain-/Länder-Diversität, spike_ratio, Goldstein-Intensität). Der Ingest-Job
füllt die Metrik-Spalten; spike_ratio kommt aus dem Vergleich mit theme_volume_daily (Baseline).
Die Gewichte sind tunebar — das ist die editoriale Logik ("Zusammenhänge erkennen"), der Moat.

## Ereignis-Klammer
"Ein Ereignis, alle Quellen" hält GDELT über global_event_id (Events 1 : N Mentions). GKG hat
KEINE global_event_id (artikel-zentriert, verbunden über gemeinsame Themen/Entitäten). Brücke:
mentions.mention_identifier = gkg.document_identifier (URL). Dedup nicht nur über ID (GDELT hat
~20% Redundanz) — near-duplicate Events zusätzlich zusammenführen.

## URL-Index (url_index) — Fact-Check über alternative Quellen
Zweck: Lucoris bekommt (z.B. von Perplexity) eine Quell-URL; darf sie wegen robots.txt/TDM-
Vorbehalt nicht gelesen werden, sollen ANDERE Artikel zum selben Ereignis gefunden werden
(alternative, lesbare Quellen). Dafür bündelt `url_index` die über drei Tabellen verstreuten
URL↔Event-Bezüge in einer flachen Tabelle `(global_event_id, url, source_flag)` mit je einem
btree-Index auf `global_event_id` und `url`. Der Pivot ist damit ein indexgestützter Self-Join:
```sql
SELECT u2.url, u2.source_flag
FROM url_index u1
JOIN url_index u2 USING (global_event_id)
WHERE u1.url = :url AND u2.url <> u1.url;
```
- `source_flag` (char(1), erweiterbar): `'P'` = primär (`gdelt_events.source_url`, eine repräsentative
  URL je Event), `'S'` = sekundär (`gdelt_mentions.mention_identifier`, korroborierende Artikel).
- Die S-Zeilen kommen aus den Mentions (nicht aus GKG — GKG hat keine `global_event_id`); im
  Kopplungsmodus sind das genau die marktrelevanten GKG-Artikel-URLs, nur mit Event-ID.
- Befüllt innerhalb der zwei bestehenden Ingest-Transaktionen: Slice-Transaktion → S, Events-
  Transaktion → P (Stub-Events ohne `source_url` liefern keine P-Zeile).
- BEWUSST ohne Primary Key / Unique: Dubletten erlaubt (append-only, keine Dedup-Kosten am
  Firehose). Der `@IdClass(UrlIndexId)` über die drei Spalten ist nur mapping-seitige Identität
  für `StatelessSession.insert`, erzwingt DB-seitig KEINE Eindeutigkeit. Konsumenten dedupen per
  `DISTINCT`. Nicht partitioniert. Details/Trade-offs: ADR 19.

## Primärquellen-Schicht (primary_feed_item, primary_source_state) — V3

Persistenz der zweiten Ingest-Spur (Primärquellen, `docs/ingest-and-sources.md`). Zwei Tabellen:

**`primary_feed_item`** — eine Zeile je Feed-MELDUNG (RSS/Atom-Item). Bewusst nicht „event":
das reale Ereignis wird später von einer übergreifenden Resolver-Entität abgebildet, die mehrere
Meldungen clustert und über `url_index` (URL ↔ `global_event_id`) mit GDELT verbindet — dafür
werden die rohe `url` und der normalisierte `dedup_key` gespeichert (Join-Anker).
- **Dedup quellenübergreifend** über `dedup_key` (UNIQUE): URL-förmige Feed-guid normalisiert,
  sonst normalisierter Link (Tracking-Parameter entfernt, Fragment weg, Scheme/Host lowercase —
  Regeln in `DedupKeys`). NICHT `source_id + url`: dieselbe Meldung erscheint in überlappenden
  Feeds (bmf-presse/bmf-finanzmarkt, guid = Artikel-URL) und wird EINMAL gespeichert; `source_id`
  nennt die ERSTE liefernde Quelle. Opake guids („12345", `tag:`/`urn:`) werden nie roh verwendet
  (Kollisionsgefahr zwischen Herausgebern) — der Link dedupliziert dann.
- Rohe `guid` als eigene Spalte: Audit-Spur der Schlüsselberechnung, erlaubt Re-Keying bei
  geänderten Normalisierungsregeln.
- Surrogat-PK aus `primary_feed_item_seq` (INCREMENT 50, pooled-lo — wie alle Surrogate);
  der lange Text-`dedup_key` taugt nicht als PK.
- Attribution FLACH (`attribution_required/formula/modified_note`) statt jsonb: fixes
  3-Felder-Schema, abfragbar ohne Sondertypen.
- KEINE Partitionierung: 6 Feeds ergeben Zehner-Zeilen/Tag; bei einem Massen-Handler
  (sec_edgar) neu bewerten.

**`primary_source_state`** — eine Zeile je Manifest-Quelle (natürlicher PK `source_id`, keine
Sequence), bei jedem Lauf überschrieben: Betriebszustand des Abrufens.
- `consecutive_failures`, `last_error`, `last_error_at`: Fehlerzustand persistent sichtbar.
- `last_attempt_at`: restart-fester Fälligkeitsanker des Pollers. `next_due_at` wird BEWUSST
  NICHT persistiert — ableitbar aus `last_attempt_at` + Manifest-Intervall; das Manifest bleibt
  die einzige Quelle des Rhythmus, eine Intervall-Änderung wirkt sofort.
- `last_fetched/new/deduped`: Zähler des letzten Erfolgslaufs — eine leise gestorbene Quelle
  (plötzlich 0) fällt auf.

Geschrieben werden beide über StatelessSession-Adapter (`StatelessSessionFeedItemStore`,
`StatelessSessionSourceStateStore`); Dedup-Mechanik ist select-then-insert, der UNIQUE-Index das
harte Sicherheitsnetz. Details/Trade-offs: ADR 25/26.
