package com.lucoris.pulse.ingest.primary;

import com.lucoris.pulse.core.domain.PrimaryFeedItem;

/**
 * Mappt die Adapter-Ausgabe {@link FeedItem} auf die Entity {@link PrimaryFeedItem}:
 * Dedup-Schlüssel aus {@link DedupKeys}, Attribution flach in die drei Spalten.
 * Pures POJO — direkt unit-testbar.
 */
final class FeedItemMapper {

    private FeedItemMapper() {}

    static PrimaryFeedItem toEntity(FeedItem item) {
        PrimaryFeedItem row = new PrimaryFeedItem();
        row.setDedupKey(DedupKeys.keyFor(item));
        row.setSourceId(item.sourceId());
        row.setInstitution(item.institution());
        row.setTitle(item.title());
        row.setUrl(item.url());
        row.setGuid(item.guid());
        row.setPublishedAt(item.publishedAt());
        row.setRawSummary(item.rawSummary());
        row.setLanguage(item.language());
        row.setFetchedAt(item.fetchedAt());
        row.setLegalClass(item.legalClass());
        Attribution attribution = item.attribution();
        if (attribution != null) {
            row.setAttributionRequired(attribution.required());
            row.setAttributionFormula(attribution.formula());
            row.setAttributionModifiedNote(attribution.modifiedNote());
        }
        return row;
    }
}
