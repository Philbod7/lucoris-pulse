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
select * from ingest_log order by filename ;
select * from url_index;

select * from article a;

select * from url_index a where a.url like '%reuters.com%'; -- '%wallstreet-online.de%';
select * from url_index a where a.url like '%21075420%';
select * from url_index a where a.url = 'https://reuters.com/lifestyle/netflix-nods-nostalgia-with-new-little-house-prairie-tv-series-2026-07-06';
select * from gdelt_events ge where ge.source_url like '%reuters.com%';
select * from gdelt_gkg gg where gg.document_identifier like '%reuters.com%';
select * from url_index where url like '%yahoo%';

select * from gdelt_events ge where ge.source_url  like '%finanznachrichten.de%';
select * from gdelt_events ge where ge.source_url  like '%finanznachrichten.de%' and ge.num_articles > 8;
select count(*) from gdelt_mentions gm where gm.global_event_id = 1312450498;

select ge.global_event_id, ge.source_url , ge.num_articles, count(*) 
from gdelt_events ge join gdelt_mentions gm on gm.global_event_id = ge.global_event_id 
where ge.source_url  like '%finanznachrichten.de%' and ge.num_articles > 8
group by ge.global_event_id, ge.source_url, ge.num_articles ;

select count(*) from gdelt_events ge where ge.source_url  like '%finanznachrichten.de%';
select source_flag, count(*) from url_index where url like '%finanznachrichten.de%' group by source_flag ;

select * from primary_feed_item pfi order by pfi.published_at desc;
select * from primary_source_state pss ;


