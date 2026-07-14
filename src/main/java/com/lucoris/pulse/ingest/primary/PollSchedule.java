package com.lucoris.pulse.ingest.primary;

import com.lucoris.pulse.core.domain.PrimarySourceState;
import com.lucoris.pulse.ingest.primary.robots.RobotsGate;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entscheidet, ob eine Quelle JETZT abzurufen ist. Fällig ist eine Intervall-Quelle, wenn sie noch
 * nie versucht wurde oder ihr letzter Versuch länger als das effektive Intervall zurückliegt —
 * {@code lastAttemptAt} kommt aus dem persistierten Quellen-Zustand, das Intervall aus dem
 * Manifest (nie aus der DB: eine Manifest-Änderung wirkt sofort).
 *
 * <p>Das effektive Intervall ist {@code max(poll.seconds, robots Crawl-delay)}: wer sich beim
 * Abruf auf das Wohlwollen des Herausgebers beruft (ADR 24), kann nicht zugleich dessen
 * Abrufgrenze verletzen.
 *
 * <p>{@code mode=calendar} (z.B. destatis-press) ist für DIESEN Poller nie fällig —
 * Kalender-Polling ist ein späteres Increment; der Einmal-Lauf ({@code runAll()}) ruft solche
 * Quellen weiterhin ab. Ein Intervall-Modus ohne {@code seconds} wäre ein 0s-Hot-Loop und wird
 * als Fehlkonfiguration sichtbar gemacht statt gepollt.
 *
 * <p>Pures POJO; einmalige Warnungen je Quelle, damit der 30s-Tick das Log nicht flutet.
 */
public final class PollSchedule {

    private static final Logger log = LoggerFactory.getLogger(PollSchedule.class);

    private final RobotsGate gate;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public PollSchedule(RobotsGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** Ist die Quelle bei {@code now} fällig? {@code state} ist {@code null}, wenn nie versucht. */
    public boolean isDue(IngestSource source, PrimarySourceState state, Instant now) {
        Poll poll = source.poll();
        if (!"interval".equals(poll.mode())) {
            warnOnce(source.id(), "calendar".equals(poll.mode())
                    ? "poll.mode=calendar — Kalender-Polling ist ein späteres Increment, Quelle wird vom Intervall-Poller übersprungen"
                    : "poll.mode=" + poll.mode() + " ist unbekannt — Quelle wird übersprungen");
            return false;
        }
        if (poll.seconds() == null || poll.seconds() <= 0) {
            warnOnce(source.id(), "poll.mode=interval ohne brauchbare seconds — Quelle wird übersprungen");
            return false;
        }
        if (state == null || state.getLastAttemptAt() == null) {
            return true;
        }

        long effectiveSeconds = Math.max(
                poll.seconds(),
                gate.crawlDelaySeconds(URI.create(source.access().url())).orElse(0));
        return !state.getLastAttemptAt().plusSeconds(effectiveSeconds).isAfter(now);
    }

    private void warnOnce(String sourceId, String message) {
        if (warned.add(sourceId)) {
            log.warn("Quelle {}: {}", sourceId, message);
        }
    }
}
