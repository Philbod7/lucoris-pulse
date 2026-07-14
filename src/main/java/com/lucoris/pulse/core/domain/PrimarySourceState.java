package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Betriebszustand einer Primärquelle — eine Zeile je Manifest-Quelle, bei jedem Lauf
 * überschrieben. Hält den Fehlerzustand persistent sichtbar ({@code consecutiveFailures},
 * {@code lastError}) und liefert dem Poller den restart-festen Fälligkeitsanker
 * ({@code lastAttemptAt}); das Poll-Intervall selbst bleibt im Manifest.
 */
@Entity
@Table(name = "primary_source_state")
public class PrimarySourceState {

    /** Manifest-id der Quelle, natürlicher Primärschlüssel — keine Sequence. */
    @Id
    @Column(name = "source_id")
    private String sourceId;

    /** Letzter Abrufversuch (Erfolg ODER Fehler) — Fälligkeitsanker des Pollers. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** Letzter erfolgreicher Lauf. */
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    /** Fehler in Folge; 0 nach Erfolg. */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /** Letzte Fehlermeldung; {@code null} nach Erfolg. */
    @Column(name = "last_error")
    private String lastError;

    /** Zeitpunkt des letzten Fehlers. */
    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    /** Zähler des letzten Erfolgslaufs: Einträge im Feed. */
    @Column(name = "last_fetched")
    private Integer lastFetched;

    /** ... davon neu gespeichert. */
    @Column(name = "last_new")
    private Integer lastNew;

    /** ... davon Dubletten (bekannt oder batch-intern). */
    @Column(name = "last_deduped")
    private Integer lastDeduped;

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public void setLastErrorAt(Instant lastErrorAt) {
        this.lastErrorAt = lastErrorAt;
    }

    public Integer getLastFetched() {
        return lastFetched;
    }

    public void setLastFetched(Integer lastFetched) {
        this.lastFetched = lastFetched;
    }

    public Integer getLastNew() {
        return lastNew;
    }

    public void setLastNew(Integer lastNew) {
        this.lastNew = lastNew;
    }

    public Integer getLastDeduped() {
        return lastDeduped;
    }

    public void setLastDeduped(Integer lastDeduped) {
        this.lastDeduped = lastDeduped;
    }
}
