-- ============================================================================
--  V3 — Primärquellen-Persistenz: primary_feed_item + primary_source_state.
--
--  primary_feed_item: eine Zeile je Feed-MELDUNG (RSS/Atom-Item) — bewusst nicht
--  "event": das reale Ereignis wird später von einer Resolver-Entität abgebildet,
--  die mehrere Meldungen clustert und über url_index (V2) mit GDELT verbindet.
--  Quellenübergreifend dedupliziert über dedup_key (URL-förmige guid normalisiert,
--  sonst normalisierter Link): dieselbe Meldung aus überlappenden Feeds
--  (z.B. bmf-presse und bmf-finanzmarkt, guid = Artikel-URL) wird EINMAL gespeichert.
--
--  primary_source_state: eine Zeile je Quelle des Routing-Manifests — Betriebs-
--  zustand des Abrufens (Fehler in Folge, letzter Versuch für die restart-feste
--  Fälligkeit des Pollers, Zähler des letzten Erfolgslaufs).
--
--  Additive Migration; keine Partitionierung (6 Feeds ergeben Zehner-Zeilen/Tag —
--  bei einem Massen-Handler wie sec_edgar neu bewerten).
-- ============================================================================

-- Surrogat: INCREMENT 50 = Hibernate allocationSize (pooled-lo), wie alle Surrogate (V1).
-- Der dedup_key ist als langer Text kein brauchbarer PK.
CREATE SEQUENCE primary_feed_item_seq INCREMENT BY 50 START WITH 1 MINVALUE 1;

CREATE TABLE primary_feed_item (
    primary_feed_item_id bigint PRIMARY KEY DEFAULT nextval('primary_feed_item_seq'),
    dedup_key            text        NOT NULL,  -- guid (URL-förmig: normalisiert), sonst normalisierter Link — s. DedupKeys
    source_id            text        NOT NULL,  -- Manifest-id der ERSTEN Quelle, die die Meldung lieferte
    institution          text        NOT NULL,  -- Klarname des Herausgebers (Quellzeile)
    title                text,                  -- Titel des Eintrags (kann fehlen)
    url                  text        NOT NULL,  -- Deep-Link, ROH und unnormalisiert (Join-Anker für den späteren url_index-Resolver)
    guid                 text,                  -- Roh-guid aus dem Feed; Audit-Spur der Schlüsselberechnung, erlaubt Re-Keying
    published_at         timestamptz NOT NULL,  -- Veröffentlichung (UTC-normalisiert)
    raw_summary          text,                  -- content:encoded bzw. description/summary; NULL möglich
    language             text,                  -- Sprache laut Feed; NULL möglich
    fetched_at           timestamptz NOT NULL,  -- Zeitpunkt unseres Abrufs
    legal_class          text        NOT NULL,  -- 'A' | 'B', aus der Quelle durchgereicht
    -- Attribution flach statt jsonb: fixes 3-Felder-Schema, abfragbar ohne Sondertypen.
    attribution_required      boolean NOT NULL DEFAULT false,  -- Attributionspflicht der Quelle
    attribution_formula       text,                            -- vorgegebene Formel; NULL = keine
    attribution_modified_note boolean NOT NULL DEFAULT false   -- Veränderungshinweis nötig (dl-de/by-2.0)
);

-- DER Dedup-Anker: quellenübergreifend eindeutig. Hartes Sicherheitsnetz unter dem
-- select-then-insert des Stores und zugleich dessen Lookup-Index.
CREATE UNIQUE INDEX ux_primary_feed_item_dedup_key ON primary_feed_item (dedup_key);
-- Read-Model: neueste Meldungen, gesamt und je Quelle.
CREATE INDEX ix_primary_feed_item_published        ON primary_feed_item (published_at DESC);
CREATE INDEX ix_primary_feed_item_source_published ON primary_feed_item (source_id, published_at DESC);

-- Je Quelle eine Zustandszeile. next_due_at wird BEWUSST NICHT persistiert: ableitbar
-- aus last_attempt_at + Manifest-Intervall — das Manifest bleibt die einzige Quelle
-- des Rhythmus, eine Intervall-Änderung wirkt sofort.
CREATE TABLE primary_source_state (
    source_id            text PRIMARY KEY,           -- natürlicher Schlüssel (Manifest-id), KEINE Sequence
    last_attempt_at      timestamptz,                -- letzter Abrufversuch (Erfolg ODER Fehler) — Fälligkeitsanker des Pollers
    last_success_at      timestamptz,                -- letzter erfolgreicher Lauf
    consecutive_failures integer NOT NULL DEFAULT 0, -- Fehler in Folge; 0 nach Erfolg
    last_error           text,                       -- letzte Fehlermeldung; NULL nach Erfolg
    last_error_at        timestamptz,                -- Zeitpunkt des letzten Fehlers
    last_fetched         integer,                    -- Zähler des letzten Erfolgslaufs: Einträge im Feed
    last_new             integer,                    -- ... davon neu gespeichert
    last_deduped         integer                     -- ... davon Dubletten (bekannt oder batch-intern)
);
