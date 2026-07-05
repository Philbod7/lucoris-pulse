-- ============================================================================
--  Lucoris "pulse" — GDELT-Datenbankschema (PostgreSQL 15+)
--  v3: Alle surrogaten Schlüssel über Sequenzen (INCREMENT 50 = Hibernate
--      allocationSize, pooled-lo) => Batch-fähiges Insert am Firehose.
--      Entitäten voll ausmodelliert; keine Trennzeichen-Listen in der Abfrage-Schicht.
-- ============================================================================
--  Kernentscheidung nach GDELT-Realität der vier Entitätstypen:
--    THEMA  — kontrollierte Taxonomie => Code IST der kanonische Schlüssel.
--    ORT    — geokodiert (FeatureID/FIPS/ADM/LatLong) => nahezu kanonisch.
--    ORG    — roher NER-Text, KEIN Identifikator => Surrogat + Alias-Resolver.
--    PERSON — roher NER-Text, KEIN Identifikator => Surrogat + Alias-Resolver.
--  Gleiches Muster (Entität + Artikel-FK), aber typ-spezifischer Schlüssel.
--  String-Matching passiert EINMAL beim Ingest, nie bei der Suche.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- Fuzzy-Matching für Namen (optional)
CREATE EXTENSION IF NOT EXISTS unaccent;  -- Akzent-/Diakritika-Entfernung für Normalisierung

-- Normalisierungshelfer: kleinschreiben + Akzente entfernen + Leerraum verdichten.
CREATE OR REPLACE FUNCTION norm_name(txt text)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT nullif(btrim(regexp_replace(lower(unaccent(coalesce(txt,''))), '\s+', ' ', 'g')), '')
$$;

-- ----------------------------------------------------------------------------
--  Sequenzen für ALLE surrogaten Schlüssel.
--  KOPPLUNG: INCREMENT 50 muss der Hibernate-allocationSize (pooled-lo optimizer)
--  exakt entsprechen — sonst kollidieren/klaffen IDs oder das Batching bricht.
--  Natürliche Schlüssel (global_event_id von GDELT, alias_norm, theme_code,
--  filename) und die zusammengesetzten PKs der Link-Tabellen haben KEINE Sequence.
-- ----------------------------------------------------------------------------
CREATE SEQUENCE mention_seq      INCREMENT BY 50 START WITH 1 MINVALUE 1;  -- gdelt_mentions.mention_id
CREATE SEQUENCE article_seq      INCREMENT BY 50 START WITH 1 MINVALUE 1;  -- article.article_id
CREATE SEQUENCE location_seq     INCREMENT BY 50 START WITH 1 MINVALUE 1;  -- location.location_id
CREATE SEQUENCE organization_seq INCREMENT BY 50 START WITH 1 MINVALUE 1;  -- organization.organization_id
CREATE SEQUENCE person_seq       INCREMENT BY 50 START WITH 1 MINVALUE 1;  -- person.person_id
CREATE SEQUENCE company_seq      INCREMENT BY 50 START WITH 1 MINVALUE 1;  -- company.company_id
CREATE SEQUENCE portfolio_seq    INCREMENT BY 50 START WITH 1 MINVALUE 1;  -- portfolio.portfolio_id

-- ----------------------------------------------------------------------------
--  Ingest-Buchführung — GDELT-Republishes über (filename, md5) deduplizieren.
-- ----------------------------------------------------------------------------
CREATE TABLE ingest_log (
    filename      text PRIMARY KEY,           -- Dateiname des GDELT-Slice, Schlüssel gegen Doppelverarbeitung
    dataset       text NOT NULL,              -- Datensatztyp: 'events' | 'mentions' | 'gkg'
    md5           text,                       -- MD5-Prüfsumme (Integritäts-/Dublettenprüfung)
    row_count     integer,                    -- Anzahl importierter Zeilen (Kontrolle)
    processed_at  timestamptz NOT NULL DEFAULT now()  -- Zeitpunkt der Verarbeitung
);

-- ============================================================================
--  A. ROH-SCHICHT — GDELT-Original 1:1 (Fidelity + Resale + Reprocessing).
--     Die Trennzeichen-Listen leben NUR hier; die Abfrage-Schicht ist aufgelöst.
-- ============================================================================

CREATE TABLE gdelt_events (
    global_event_id   bigint PRIMARY KEY,     -- Global eindeutige Ereignis-ID (Klammer über alle Erwähnungen)
    date_added        timestamptz NOT NULL,   -- Erst-Sichtung durch GDELT (15-Min-Raster, DATEADDED)
    day               date,                   -- Ereignisdatum (SQLDATE)
    actor1_code       text,                   -- CAMEO-Code des ersten Akteurs
    actor1_name       text,                   -- Klarname des ersten Akteurs
    actor1_country_code text,                 -- Herkunftsland des ersten Akteurs
    actor1_type1_code text,                   -- Akteurstyp des ersten Akteurs (z.B. GOV, BUS)
    actor2_code       text,                   -- CAMEO-Code des zweiten Akteurs
    actor2_name       text,                   -- Klarname des zweiten Akteurs
    actor2_country_code text,                 -- Herkunftsland des zweiten Akteurs
    actor2_type1_code text,                   -- Akteurstyp des zweiten Akteurs
    is_root_event     boolean,                -- Kennzeichen, ob Kernereignis der Meldung
    event_code        text,                   -- CAMEO-Ereigniscode (feinste Ebene)
    event_base_code   text,                   -- CAMEO-Basiscode (mittlere Ebene)
    event_root_code   text,                   -- CAMEO-Wurzelcode (grobe Kategorie)
    quad_class        smallint,               -- Konfliktklasse: 1 verb.Koop · 2 mat.Koop · 3 verb.Konflikt · 4 mat.Konflikt
    goldstein_scale   numeric,                -- Goldstein-Skala: Stabilitätseinfluss (-10..+10)
    num_mentions      integer,                -- Anzahl Erwähnungen
    num_sources       integer,                -- Anzahl unterschiedlicher Quellen
    num_articles      integer,                -- Anzahl abdeckender Artikel
    avg_tone          numeric,                -- Durchschnittlicher Tonwert
    action_geo_type   smallint,               -- Geo-Auflösungstyp des Ereignisorts
    action_geo_fullname text,                 -- Voller Ortsname des Ereignisorts
    action_geo_country_code text,             -- Ländercode des Ereignisorts
    action_geo_lat    numeric,                -- Breitengrad des Ereignisorts
    action_geo_long   numeric,                -- Längengrad des Ereignisorts
    source_url        text                    -- URL des repräsentativen Quellartikels (nur EINE pro Ereignis)
);
CREATE INDEX ix_events_date_added ON gdelt_events (date_added DESC);
CREATE INDEX ix_events_root_code  ON gdelt_events (event_root_code);

CREATE TABLE gdelt_mentions (
    mention_id         bigint DEFAULT nextval('mention_seq'),  -- Surrogat aus mention_seq (Hibernate: pooled-lo, allocationSize 50)
    global_event_id    bigint NOT NULL,       -- Verweis auf das Ereignis (Klammer über Erwähnungen)
    mention_time_date  timestamptz NOT NULL,  -- Zeitpunkt dieser Erwähnung (Partitionsschlüssel)
    event_time_date    timestamptz,           -- Zeitpunkt des zugrunde liegenden Ereignisses
    mention_type       smallint,              -- Quellentyp der Erwähnung (kodiert)
    mention_source_name text,                 -- Name der erwähnenden Quelle
    mention_identifier text,                  -- Artikel-URL dieser Erwähnung (Join-Schlüssel zu GKG/article)
    sentence_id        integer,               -- Satznummer, in der das Ereignis auftaucht
    confidence         smallint,              -- GDELT-Konfidenz der Extraktion (Prozent)
    mention_doc_tone   numeric,               -- Tonwert des erwähnenden Dokuments
    PRIMARY KEY (mention_id, mention_time_date)
) PARTITION BY RANGE (mention_time_date);
CREATE INDEX ix_mentions_event ON gdelt_mentions (global_event_id);
CREATE INDEX ix_mentions_url   ON gdelt_mentions (mention_identifier);

CREATE TABLE gdelt_gkg (
    gkg_record_id       text NOT NULL,        -- Eindeutige GKG-Datensatz-ID
    seen_date           timestamptz NOT NULL, -- Erfassungszeitpunkt (V2.1DATE, Partitionsschlüssel)
    source_common_name  text,                 -- Quellenname (Domain/Publikation)
    document_identifier text,                 -- Artikel-URL (Join-Schlüssel zu Mentions/article)
    v2_themes           text,                 -- Rohliste Themen (';'-getrennt) — nur Fidelity, wird aufgelöst
    v2_locations        text,                 -- Rohliste Orte (geokodiert, '#'/';'-Felder) — wird aufgelöst
    v2_persons          text,                 -- Rohliste Personen (';'-getrennt) — wird aufgelöst
    v2_organizations    text,                 -- Rohliste Organisationen (';'-getrennt) — wird aufgelöst
    v2_all_names        text,                 -- Rohliste aller Eigennamen
    v2_tone             text,                 -- Roh-Tontupel (mehrere Kennzahlen)
    tone                numeric,              -- Geparster Haupttonwert (erstes Feld aus v2_tone)
    PRIMARY KEY (gkg_record_id, seen_date)
) PARTITION BY RANGE (seen_date);
CREATE INDEX ix_gkg_url ON gdelt_gkg (document_identifier);

-- ============================================================================
--  B. ABFRAGE-SCHICHT — Artikel-Hub (aufgelöste Verknüpfungen folgen in C).
-- ============================================================================
CREATE TABLE article (
    article_id          bigint DEFAULT nextval('article_seq'),  -- Surrogat aus article_seq (Hibernate: pooled-lo, allocationSize 50)
    seen_date           timestamptz NOT NULL, -- Erfassungszeitpunkt (Partitionsschlüssel, Basis für 24h-Filter)
    url                 text NOT NULL,        -- Eindeutige Artikel-URL (Deduplizierungsschlüssel)
    source_common_name  text,                 -- Quellenname (Domain/Publikation)
    source_country      text,                 -- Herkunftsland der Quelle
    language            text,                 -- Sprache des Artikels
    tone                numeric,              -- Tonwert des Artikels
    gkg_record_id       text,                 -- Rückverweis auf den GKG-Rohdatensatz
    PRIMARY KEY (article_id, seen_date),
    UNIQUE (url, seen_date)
) PARTITION BY RANGE (seen_date);
CREATE INDEX ix_article_seen ON article (seen_date DESC);

-- ============================================================================
--  C. ENTITÄTS-SCHICHT — vier Typen, gleiches Muster, typ-spezifischer Schlüssel.
-- ============================================================================

-- --- THEMA -------------------------------------------------------------------
-- Kanonisch: der GDELT-Themencode selbst. Kein Resolver nötig. Zugleich deine
-- redaktionelle Kontrolle darüber, was marktrelevant zählt.
CREATE TABLE theme (
    theme_code         text PRIMARY KEY,      -- GKG-Themencode aus GDELTs kontrollierter Taxonomie (bereits kanonisch)
    category           text,                  -- Fachliche Kategorie: 'economics' | 'politics' | 'geopolitics' | ...
    is_market_relevant boolean NOT NULL DEFAULT false,  -- Marktbewegend-Flag (Zinsen, Wahlen, Energie, Regulierung ...)
    label              text                   -- Optionale Klartextbezeichnung des Themas
);
CREATE TABLE article_theme (
    article_id  bigint NOT NULL,              -- Verweis auf den Artikel
    seen_date   timestamptz NOT NULL,         -- Erfassungszeitpunkt (denormalisiert für Zeitfilter)
    theme_code  text NOT NULL REFERENCES theme (theme_code),  -- FK auf das kanonische Thema
    salience    real,                         -- Optionale Gewichtung/Häufigkeit des Themas im Artikel
    PRIMARY KEY (article_id, seen_date, theme_code),
    FOREIGN KEY (article_id, seen_date) REFERENCES article (article_id, seen_date) ON DELETE CASCADE
);
CREATE INDEX ix_atheme_code_time ON article_theme (theme_code, seen_date DESC);

-- --- ORT ---------------------------------------------------------------------
-- Nahezu kanonisch: GDELT geokodiert Orte. Auflösung beim Ingest über die Geo-Felder.
CREATE TABLE location (
    location_id    bigint DEFAULT nextval('location_seq') PRIMARY KEY,  -- Surrogat aus location_seq (Hibernate: pooled-lo, allocationSize 50)
    feature_id     text,                      -- GDELT/GeoNames-FeatureID (geografischer Identifikator)
    location_type  smallint,                  -- Auflösungstyp (Land, Bundesstaat, Stadt ...)
    full_name      text,                      -- Voller Ortsname
    country_code   text,                      -- FIPS-Ländercode
    adm1_code      text,                      -- ADM1-Verwaltungscode
    latitude       numeric,                   -- Breitengrad
    longitude      numeric,                   -- Längengrad
    UNIQUE (location_type, full_name, country_code, adm1_code)  -- natürliche Geo-Identität
);
CREATE TABLE article_location (
    article_id  bigint NOT NULL,              -- Verweis auf den Artikel
    seen_date   timestamptz NOT NULL,         -- Erfassungszeitpunkt (denormalisiert für Zeitfilter)
    location_id bigint NOT NULL REFERENCES location (location_id),  -- FK auf den aufgelösten Ort
    PRIMARY KEY (article_id, seen_date, location_id),
    FOREIGN KEY (article_id, seen_date) REFERENCES article (article_id, seen_date) ON DELETE CASCADE
);
CREATE INDEX ix_aloc_loc_time ON article_location (location_id, seen_date DESC);

-- --- ORGANISATION ------------------------------------------------------------
-- KEIN GDELT-Identifikator => surrogater Schlüssel + Alias-Resolver (der Kernfall).
CREATE TABLE organization (
    organization_id bigint DEFAULT nextval('organization_seq') PRIMARY KEY,  -- Surrogat aus organization_seq (GDELT liefert keinen; Hibernate: pooled-lo, allocationSize 50)
    canonical_name  text NOT NULL,            -- Gewählter Anzeigename der Entität
    org_norm        text NOT NULL UNIQUE,     -- Normalisierter Primärname (eindeutig)
    first_seen      timestamptz,              -- Erste Sichtung dieser Organisation
    is_reviewed     boolean NOT NULL DEFAULT false  -- Ob die Auflösung manuell bestätigt wurde
);
-- Der Resolver: normalisierte Schreibvarianten -> Organisation. Ingest matcht hiergegen.
CREATE TABLE organization_alias (
    alias_norm      text PRIMARY KEY,         -- Normalisierte Schreibweise (Lookup-Ziel des Ingest)
    organization_id bigint NOT NULL REFERENCES organization (organization_id) ON DELETE CASCADE,  -- Zugehörige Organisation
    raw_example     text,                     -- Beispiel-Rohschreibweise aus GDELT
    alias_type      text,                     -- Art des Alias: 'legal' | 'brand' | 'short' | ...
    is_ambiguous    boolean NOT NULL DEFAULT false  -- Flag für kollisionsgefährdete Allerweltsnamen
);
CREATE INDEX ix_orgalias_org ON organization_alias (organization_id);
CREATE INDEX ix_orgalias_trgm ON organization_alias USING gin (alias_norm gin_trgm_ops);  -- Fuzzy-Fallback
CREATE TABLE article_organization (
    article_id      bigint NOT NULL,          -- Verweis auf den Artikel
    seen_date       timestamptz NOT NULL,     -- Erfassungszeitpunkt (denormalisiert für Zeitfilter)
    organization_id bigint NOT NULL REFERENCES organization (organization_id),  -- FK auf die aufgelöste Organisation
    raw_name        text,                     -- Rohschreibweise wie im Artikel (Audit/Nachvollziehbarkeit)
    char_offset     integer,                  -- Optionaler Zeichenoffset im Dokument (Proximity)
    PRIMARY KEY (article_id, seen_date, organization_id),
    FOREIGN KEY (article_id, seen_date) REFERENCES article (article_id, seen_date) ON DELETE CASCADE
);
CREATE INDEX ix_aorg_org_time ON article_organization (organization_id, seen_date DESC);

-- --- PERSON ------------------------------------------------------------------
-- Wie Organisation: freier NER-Text, kein Identifikator => Surrogat + Alias.
CREATE TABLE person (
    person_id     bigint DEFAULT nextval('person_seq') PRIMARY KEY,  -- Surrogat aus person_seq (Hibernate: pooled-lo, allocationSize 50)
    canonical_name text NOT NULL,             -- Gewählter Anzeigename der Person
    person_norm    text NOT NULL UNIQUE,      -- Normalisierter Primärname (eindeutig)
    is_reviewed    boolean NOT NULL DEFAULT false  -- Ob die Auflösung manuell bestätigt wurde
);
CREATE TABLE person_alias (
    alias_norm  text PRIMARY KEY,             -- Normalisierte Schreibweise (Lookup-Ziel des Ingest)
    person_id   bigint NOT NULL REFERENCES person (person_id) ON DELETE CASCADE,  -- Zugehörige Person
    is_ambiguous boolean NOT NULL DEFAULT false  -- Flag für Namensgleichheit/Mehrdeutigkeit
);
CREATE INDEX ix_personalias_person ON person_alias (person_id);
CREATE TABLE article_person (
    article_id  bigint NOT NULL,              -- Verweis auf den Artikel
    seen_date   timestamptz NOT NULL,         -- Erfassungszeitpunkt (denormalisiert für Zeitfilter)
    person_id   bigint NOT NULL REFERENCES person (person_id),  -- FK auf die aufgelöste Person
    raw_name    text,                         -- Rohschreibweise wie im Artikel (Audit)
    PRIMARY KEY (article_id, seen_date, person_id),
    FOREIGN KEY (article_id, seen_date) REFERENCES article (article_id, seen_date) ON DELETE CASCADE
);
CREATE INDEX ix_aperson_person_time ON article_person (person_id, seen_date DESC);

-- ============================================================================
--  D. AUFLÖSUNGS-SCHICHT — Company/Portfolio (Company IST eine Organisation).
-- ============================================================================
-- Nicht jede Organisation ist ein handelbares Wertpapier (Behörden, NGOs ...),
-- aber jede getrackte Company verweist auf genau eine Organisationsentität.
CREATE TABLE company (
    company_id      bigint DEFAULT nextval('company_seq') PRIMARY KEY,  -- Surrogat aus company_seq (Hibernate: pooled-lo, allocationSize 50)
    organization_id bigint NOT NULL UNIQUE REFERENCES organization (organization_id),  -- Verknüpfung zur Organisationsentität
    primary_name    text NOT NULL,            -- Offizieller Anzeigename des Unternehmens
    ticker          text,                     -- Börsenkürzel (z.B. AAPL), optional
    isin            text,                     -- ISIN-Wertpapierkennung, optional
    active          boolean NOT NULL DEFAULT true  -- Ob das Unternehmen aktiv getrackt wird
);

CREATE TABLE portfolio (
    portfolio_id  bigint DEFAULT nextval('portfolio_seq') PRIMARY KEY,  -- Surrogat aus portfolio_seq (Hibernate: pooled-lo, allocationSize 50)
    owner_uuid    uuid NOT NULL               -- Supabase-Auth-Nutzer (pseudonyme UUID)
);
CREATE TABLE portfolio_holding (
    portfolio_id  bigint NOT NULL REFERENCES portfolio (portfolio_id) ON DELETE CASCADE,  -- Zugehöriges Portfolio
    company_id    bigint NOT NULL REFERENCES company (company_id) ON DELETE CASCADE,       -- Enthaltenes Unternehmen (Depot-Position)
    PRIMARY KEY (portfolio_id, company_id)
);

-- ============================================================================
--  E. SIGNAL-SCHICHT — Signifikanz + Baselines.
-- ============================================================================
CREATE TABLE theme_volume_daily (
    theme_code     text NOT NULL REFERENCES theme (theme_code),  -- GKG-Themencode
    day            date NOT NULL,             -- Tag der Aggregation
    article_count  integer NOT NULL DEFAULT 0,  -- Tagesvolumen je Thema (Baseline für Spikes)
    PRIMARY KEY (theme_code, day)
);

CREATE TABLE event_significance (
    global_event_id    bigint PRIMARY KEY REFERENCES gdelt_events (global_event_id) ON DELETE CASCADE,  -- Verweis auf das Ereignis
    first_seen         timestamptz NOT NULL,  -- Erste Sichtung des Ereignisses
    last_seen          timestamptz NOT NULL,  -- Letzte Sichtung (Basis für 24h-Fenster)
    num_articles       integer NOT NULL DEFAULT 0,   -- Anzahl abdeckender Artikel
    distinct_domains   integer NOT NULL DEFAULT 0,   -- Anzahl unterschiedlicher Quell-Domains (Breite)
    distinct_countries integer NOT NULL DEFAULT 0,   -- Anzahl unterschiedlicher Länder (Streuung)
    avg_tone           numeric,               -- Durchschnittlicher Tonwert
    goldstein          numeric,               -- Goldstein-Wert des Ereignisses
    spike_ratio        numeric,               -- Verhältnis aktuelles Volumen zu Baseline (vom Job berechnet)
    is_market_relevant boolean NOT NULL DEFAULT false,  -- Marktrelevanz-Flag (vom Job aus theme abgeleitet)
    -- Automatisch berechneter Signifikanzwert; gewichtete Mischung aus Breite, Streuung, Spike, Intensität:
    significance_score numeric GENERATED ALWAYS AS (
          0.35 * ln(1 + num_articles)         -- Menge der Berichterstattung
        + 0.25 * ln(1 + distinct_domains)     -- Breite über Quellen
        + 0.20 * ln(1 + distinct_countries)   -- geografische Streuung
        + 0.10 * coalesce(spike_ratio, 0)     -- Ausschlag gegen Baseline
        + 0.10 * (abs(coalesce(goldstein, 0)) / 10.0)  -- Ereignisintensität
    ) STORED  -- Signifikanzwert (Ranking-Grundlage für globale Relevanz)
);
CREATE INDEX ix_sig_score     ON event_significance (significance_score DESC, last_seen DESC);
CREATE INDEX ix_sig_market    ON event_significance (is_market_relevant, significance_score DESC) WHERE is_market_relevant;
CREATE INDEX ix_sig_last_seen ON event_significance (last_seen DESC);

-- ============================================================================
--  PARTITIONEN — monatlich anlegen (pg_partman/Cron). Beispiel Juli/August 2026.
-- ============================================================================
CREATE TABLE article_2026_07 PARTITION OF article         FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE article_2026_08 PARTITION OF article         FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE mentions_2026_07 PARTITION OF gdelt_mentions FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE mentions_2026_08 PARTITION OF gdelt_mentions FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE gkg_2026_07 PARTITION OF gdelt_gkg           FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE gkg_2026_08 PARTITION OF gdelt_gkg           FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

-- ============================================================================
--  INGEST-AUFLÖSUNG PRO TYP (Kurzreferenz für den Worker)
-- ----------------------------------------------------------------------------
--  THEMA : Code splitten -> UPSERT theme -> article_theme (FK = Code).
--  ORT   : Geo-Felder parsen -> UPSERT location per natürlichem Schlüssel -> FK.
--  ORG   : norm_name(roh) -> LOOKUP organization_alias
--            Treffer  -> organization_id verwenden
--            kein Tr. -> organization + Alias neu anlegen (is_reviewed=false)
--          -> article_organization (FK + raw_name fürs Audit).
--  PERSON: analog zu ORG über person_alias.
-- ============================================================================

-- ============================================================================
--  BEISPIEL-QUERIES — eine je Aufgabe (jetzt über Integer-FKs statt Strings)
-- ============================================================================
--
-- AUFGABE 1 — global relevante Ereignisse, letzte 24h, gerankt:
--   SELECT e.global_event_id, e.event_root_code, s.significance_score, s.num_articles
--   FROM event_significance s
--   JOIN gdelt_events e USING (global_event_id)
--   WHERE s.last_seen >= now() - interval '24 hours'
--   ORDER BY s.significance_score DESC
--   LIMIT 50;
--
-- AUFGABE 2 — marktrelevant UND signifikant genug für den allgemeinen Aktien-Feed:
--   SELECT DISTINCT a.url, a.source_common_name, s.significance_score
--   FROM article a
--   JOIN article_theme  at ON at.article_id = a.article_id AND at.seen_date = a.seen_date
--   JOIN theme          th ON th.theme_code = at.theme_code AND th.is_market_relevant
--   JOIN gdelt_mentions  m ON m.mention_identifier = a.url
--   JOIN event_significance s ON s.global_event_id = m.global_event_id
--   WHERE a.seen_date >= now() - interval '24 hours'
--     AND s.significance_score >= 2.0            -- Schwelle tunen
--   ORDER BY s.significance_score DESC;
--
-- AUFGABE 3 — (fast) alle News zu einem Portfolio, letzte 24h (max. Recall):
--   SELECT DISTINCT a.url, a.seen_date, a.source_common_name, c.ticker
--   FROM portfolio_holding ph
--   JOIN company c ON c.company_id = ph.company_id
--   JOIN article_organization ao ON ao.organization_id = c.organization_id
--   JOIN article a ON a.article_id = ao.article_id AND a.seen_date = ao.seen_date
--   WHERE ph.portfolio_id = $1
--     AND a.seen_date >= now() - interval '24 hours'
--   ORDER BY a.seen_date DESC;
-- ============================================================================
