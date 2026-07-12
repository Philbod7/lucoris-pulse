package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Ingest-Buchführung: dedupliziert GDELT-Republishes über den Dateinamen ({@code filename} als
 * natürlicher Primärschlüssel) und die MD5-Prüfsumme, um Doppelverarbeitung zu verhindern.
 */
@Entity
@Table(name = "ingest_log")
public class IngestLog {

    /** Dateiname des GDELT-Slice, Schlüssel gegen Doppelverarbeitung. */
    @Id
    @Column(name = "filename")
    private String filename;

    /** Datensatztyp: {@code 'events'} | {@code 'mentions'} | {@code 'gkg'}. */
    @Column(name = "dataset", nullable = false)
    private String dataset;

    /** MD5-Prüfsumme (Integritäts-/Dublettenprüfung). */
    @Column(name = "md5")
    private String md5;

    /** Anzahl importierter Zeilen (Kontrolle). */
    @Column(name = "row_count")
    private Integer rowCount;

    /** Zeitpunkt der Verarbeitung. */
    @Column(name = "processed_at", nullable = false, insertable = false, updatable = false)
    private Instant processedAt;

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getDataset() {
        return dataset;
    }

    public void setDataset(String dataset) {
        this.dataset = dataset;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
