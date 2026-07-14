package com.lucoris.pulse.ingest.primary;

import com.lucoris.pulse.ingest.primary.robots.FetchIntent;
import com.lucoris.pulse.ingest.primary.robots.RobotsGate;
import com.lucoris.pulse.ingest.primary.robots.SourceNotPermittedException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dekorator um den {@link AdapterDispatcher}: prüft VOR dem Abruf die Erlaubnis (robots.txt +
 * TDM-Vorbehalt) und protokolliert die Entscheidung mit Zeitstempel.
 *
 * <p>Sitzt bewusst VOR dem Dispatcher und nicht IM {@code GenericRssAdapter}: so ist jeder künftige
 * Handler ({@code sec_edgar}, {@code html_index}, ...) automatisch abgedeckt und niemand kann das
 * Gate versehentlich umgehen, indem er einen neuen Adapter schreibt.
 *
 * <p>Ein Verbot wirft {@link SourceNotPermittedException} — eine verbotene Quelle sähe sonst aus
 * wie ein leerer Feed. Der Ingest fängt pro Quelle ab, die übrigen laufen weiter.
 */
public final class RobotsGatedAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(RobotsGatedAdapter.class);

    private final SourceAdapter delegate;
    private final RobotsGate gate;
    private final Clock clock;

    public RobotsGatedAdapter(SourceAdapter delegate, RobotsGate gate, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<PrimaryEvent> fetch(IngestSource source) {
        URI url = URI.create(source.access().url());
        RobotsGate.Decision decision = gate.check(new FetchIntent(
                source.id(), url, source.access().type(), source.expressInvitation()));
        Instant zeitpunkt = clock.instant();

        if (!decision.allowed()) {
            // Beweislast: Zustand + Zeitstempel protokollieren (docs/ingest-and-sources.md).
            log.error("ABRUF VERWEIGERT ({}): id={} url={} grund=\"{}\" zeitpunkt={} — Quelle in der "
                            + "Registry auf enabled:false setzen",
                    decision.verdict(), source.id(), url, decision.reason(), zeitpunkt);
            throw new SourceNotPermittedException(
                    "Abruf von '" + source.id() + "' (" + url + ") untersagt: " + decision.reason());
        }

        if (decision.verdict() == RobotsGate.Verdict.ALLOW_BY_INVITATION) {
            auditInvitation(decision.evidence(), url, zeitpunkt);
        } else {
            log.info("Abruf erlaubt: id={} legal_class={} confidence={} url={} grund=\"{}\" zeitpunkt={}",
                    source.id(), source.legalClass(), source.confidence(), url, decision.reason(), zeitpunkt);
        }
        return delegate.fetch(source);
    }

    /**
     * Die Beweislast-Zeile für jeden Abruf, der sich auf eine Einladung beruft (ADR 24).
     *
     * <p>Auf WARN, nicht INFO: das ist die Ausnahme von der Regel und soll im Log auffallen. Sie
     * enthält alles, was ein Dritter bräuchte, um die Entscheidung nachzuvollziehen — und um sie zu
     * widerlegen: das getroffene Muster, die UA-Gruppe, die Fundstelle und den wörtlichen Satz.
     */
    private void auditInvitation(RobotsGate.InvitationEvidence e, URI url, Instant zeitpunkt) {
        log.warn("ALLOW_BY_INVITATION: id={} url={} muster=\"{}\" ua_gruppe=\"{}\" page_url={} "
                        + "retrieved={} scope=\"{}\" wording=\"{}\" zeitpunkt={}",
                e.sourceId(), url, e.pattern(), e.userAgentGroup(), e.pageUrl(),
                e.retrieved(), e.scope(), e.wording(), zeitpunkt);
    }
}
