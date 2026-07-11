package com.lucoris.pulse.ingest.gdelt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Port (Infrastruktur): lädt einen GDELT-Slice, entpackt das ZIP und liefert die TAB-getrennten
 * Rohzeilen. Kein Domänenwissen — die Spalten->Entity-Abbildung übernehmen die Row-Mapper im
 * Usecase.
 */
public interface GdeltSliceClient {

    /**
     * @param dataset       Events/Mentions/GKG
     * @param sliceStartUtc Slice-Beginn in UTC (00:00/00:15/... des Tages)
     * @return die Rohzeilen (jede als TAB-gesplittetes {@code String[]}), oder
     *         {@link Optional#empty()}, wenn der Slice fehlt/nicht abrufbar ist (z.B. HTTP 404).
     */
    Optional<List<String[]>> download(GdeltDataset dataset, LocalDateTime sliceStartUtc);
}
