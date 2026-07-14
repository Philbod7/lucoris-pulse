package com.lucoris.pulse.ingest.primary;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load-Validierung der Registry: ruft jede aktivierte Quelle EINMAL über den echten Ingest-Pfad ab
 * und prüft, ob die im Manifest hinterlegte {@code confidence} noch der Wirklichkeit entspricht.
 *
 * <p>Kein Teil des Ingests und kein Teil des Standard-Builds — ein Diagnose-Werkzeug, das nur unter
 * dem Profil {@code validate-sources} startet:
 *
 * <pre>mvn spring-boot:run -Dspring-boot.run.profiles=validate-sources</pre>
 *
 * <p>Bewusst über den {@link AdapterDispatcher}, nicht über einen eigenen Parser-Pfad: geprüft
 * werden soll, was der Ingest tatsächlich tut — nicht eine zweite, womöglich abweichende Meinung
 * darüber.
 */
public final class SourceLoadValidator {

    private static final Logger log = LoggerFactory.getLogger(SourceLoadValidator.class);
    private static final String VERIFIED = "verified";

    private final PrimarySourceManifestLoader registry;
    private final SourceAdapter dispatcher;
    private final Clock clock;

    public SourceLoadValidator(
            PrimarySourceManifestLoader registry, SourceAdapter dispatcher, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Ergebnis je Quelle. {@code problem} ist {@code null}, wenn alles stimmt. */
    public record SourceCheck(
            String id, String handler, String url, String confidence, int events, String problem) {

        public boolean ok() {
            return problem == null;
        }
    }

    /** Prüft alle aktivierten Quellen und protokolliert das Ergebnis. */
    public List<SourceCheck> validate() {
        List<IngestSource> sources = registry.enabledSources();
        Instant zeitpunkt = clock.instant();
        log.info("Load-Validierung startet ({} aktivierte Quelle(n), Zeitpunkt {})",
                sources.size(), zeitpunkt);

        List<SourceCheck> checks = new ArrayList<>(sources.size());
        for (IngestSource source : sources) {
            checks.add(check(source));
        }

        long fehlerhaft = checks.stream().filter(c -> !c.ok()).count();
        log.info("Load-Validierung fertig: {} von {} Quelle(n) in Ordnung",
                checks.size() - fehlerhaft, checks.size());
        return List.copyOf(checks);
    }

    private SourceCheck check(IngestSource source) {
        String url = source.access().url();
        int events;
        try {
            events = dispatcher.fetch(source).size();
        } catch (RuntimeException e) {
            // Z.B. ein noch nicht gebauter Handler an einer aktivierten Quelle.
            String problem = "Abruf fehlgeschlagen: " + e;
            log.error("FEHLER   {} ({}) — {}", source.id(), url, problem);
            return new SourceCheck(source.id(), source.handler(), url, source.confidence(), 0, problem);
        }

        if (events == 0) {
            // Als 'verified' geführt, liefert aber nichts: entweder ist die URL umgezogen, der Feed
            // ist leer, oder der Herausgeber blockt uns (403). So oder so: die Registry lügt.
            String problem = VERIFIED.equals(source.confidence())
                    ? "als '" + VERIFIED + "' geführt, liefert aber KEINE Einträge — URL/Zugang prüfen"
                    : "liefert keine Einträge";
            log.warn("MISMATCH {} ({}) — {}", source.id(), url, problem);
            return new SourceCheck(source.id(), source.handler(), url, source.confidence(), 0, problem);
        }

        if (!VERIFIED.equals(source.confidence())) {
            // Umgekehrter Fall: funktioniert, ist aber noch nicht als geprüft eingetragen.
            log.info("HINWEIS  {} ({}) — {} Treffer, confidence='{}' kann auf '{}' hochgestuft werden",
                    source.id(), url, events, source.confidence(), VERIFIED);
        } else {
            log.info("OK       {} ({}) — {} Treffer", source.id(), url, events);
        }
        return new SourceCheck(source.id(), source.handler(), url, source.confidence(), events, null);
    }
}
