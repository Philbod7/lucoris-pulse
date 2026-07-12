-- ============================================================================
--  V2 — URL-Index für Fact-Check über alternative Quellen.
--  Reiner, append-only URL<->Event-Index: eine flache Zeile je Quell-URL mit
--  ihrer global_event_id. Zweck: eine (z.B. von Perplexity gelieferte) URL, die
--  wegen robots.txt/TDM-Vorbehalt nicht gelesen werden darf, auf ihr Ereignis
--  abbilden und darüber ALLE anderen Quell-URLs desselben Events finden.
--
--  BEWUSST OHNE PRIMARY KEY / UNIQUE: Dubletten sind erlaubt und erwünscht
--  (Performance: keine Konfliktprüfung/Dedup-Kosten beim Firehose-Insert). Die
--  Zeilenidentität übernimmt Hibernate rein mapping-seitig über einen
--  zusammengesetzten @IdClass (kein Surrogat, keine Sequence). Konsumenten
--  deduplizieren bei Bedarf per DISTINCT.
--
--  source_flag:  'P' = primär  (gdelt_events.source_url)
--                'S' = sekundär (gdelt_mentions.mention_identifier)
--  Bewusst 1 Zeichen und erweiterbar (aktuell nur P/S).
-- ============================================================================
CREATE TABLE url_index (
    global_event_id bigint  NOT NULL,   -- Ereignis-Klammer (aus Event bzw. Mention)
    url             text    NOT NULL,   -- Artikel-URL (Lookup-Ziel)
    source_flag     char(1) NOT NULL    -- 'P' = primär (Event), 'S' = sekundär (Mention)
);
CREATE INDEX ix_url_index_event ON url_index (global_event_id);  -- Event -> alle URLs
CREATE INDEX ix_url_index_url   ON url_index (url);              -- URL -> Event(s)
