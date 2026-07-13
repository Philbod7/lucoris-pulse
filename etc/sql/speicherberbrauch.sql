SELECT
    pg_size_pretty(SUM(pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename)))) AS gesamt
FROM pg_tables
WHERE schemaname = 'public';

SELECT
    tablename,
    pg_size_pretty(pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename))) AS total,
    pg_size_pretty(pg_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename))) AS nur_daten,
    pg_size_pretty(pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename))
                   - pg_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename))) AS indizes_toast
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(tablename)) DESC;

3 Tage -> 526 MB -> 30 Tage 6 GB
-- in ingest_log die IDs aller 3 Quell-Tabellen speichern darüber dann löschen
