select count(*) from gdelt_events ;
select count(*) from gdelt_mentions;
select count(*) from gdelt_gkg;
select count(*) from ingest_log;
select source_common_name , count(*) from gdelt_gkg group by source_common_name order by source_common_name;
select source_flag, count(*) from url_index group by source_flag ;
select count(*) from url_index where url like 'https://www.yahoo.com/%';


select * from gdelt_events ge ;
select * from gdelt_mentions gm;
select * from gdelt_gkg gg ;
select * from ingest_log;
select * from url_index;

select * from article a;

select * from url_index a where a.url like '%reuters.com%'; -- '%wallstreet-online.de%';
select * from url_index a where a.url like '%21075420%';
select * from url_index a where a.url = 'https://reuters.com/lifestyle/netflix-nods-nostalgia-with-new-little-house-prairie-tv-series-2026-07-06';
select * from gdelt_events ge where ge.source_url like '%reuters.com%';
select * from gdelt_gkg gg where gg.document_identifier like '%reuters.com%';
select * from url_index where url like '%yahoo%';

select * from gdelt_events ge where ge.source_url  like '%finanznachrichten.de%';
select count(*) from gdelt_events ge where ge.source_url  like '%finanznachrichten.de%';
select source_flag, count(*) from url_index where url like '%finanznachrichten.de%' group by source_flag ;


