package com.lucoris.pulse.ingest.primary;

import java.util.List;

/**
 * Port: liest eine Quelle des Routing-Manifests und liefert deren Einträge als {@link PrimaryEvent}.
 *
 * <p>Je {@code handler} im Manifest gibt es genau eine Implementierung; der
 * {@link AdapterDispatcher} wählt sie aus. Ein Adapter wirft nicht, wenn eine Quelle gerade nicht
 * abrufbar ist — er liefert dann eine leere Liste (dieselbe Philosophie wie der GDELT-Pfad, der
 * einen fehlenden Slice als {@code Optional.empty()} behandelt).
 */
public interface SourceAdapter {

    /**
     * @param source die abzurufende Quelle (bereits als {@code enabled} erkannt)
     * @return die Einträge der Quelle; leer, wenn die Quelle nicht abrufbar oder leer ist
     */
    List<PrimaryEvent> fetch(IngestSource source);
}
