package com.lucoris.pulse.ingest.primary;

import java.util.List;

/**
 * Zusammenfassung eines Laufs über mehrere Quellen — ein {@link SourceRunResult} je Quelle,
 * in Manifest-Reihenfolge.
 */
public record PrimaryIngestReport(List<SourceRunResult> results) {

    public int totalFetched() {
        return results.stream().mapToInt(SourceRunResult::fetched).sum();
    }

    public int totalNew() {
        return results.stream().mapToInt(SourceRunResult::newItems).sum();
    }

    public int totalDeduped() {
        return results.stream().mapToInt(SourceRunResult::deduped).sum();
    }

    public List<SourceRunResult> failures() {
        return results.stream().filter(SourceRunResult::failed).toList();
    }
}
