package com.lucoris.pulse.ingest.gdelt;

import java.time.Instant;

/**
 * Verweis auf ein von einer behaltenen Mention referenziertes, aber (noch) nicht gespeichertes
 * Event. {@code eventTimeDate} ist zugleich der DATEADDED-Slice, in dem das Event in der
 * GDELT-Export-Datei liegt — daraus wird die Backfill-URL gebaut.
 */
public record MissingEventRef(Long globalEventId, Instant eventTimeDate) {
}
