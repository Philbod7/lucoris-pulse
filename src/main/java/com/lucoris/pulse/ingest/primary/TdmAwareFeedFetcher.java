package com.lucoris.pulse.ingest.primary;

import com.lucoris.pulse.ingest.primary.robots.SourceNotPermittedException;
import com.lucoris.pulse.ingest.primary.robots.TdmHeader;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dekorator um den {@link FeedFetcher}: erklärt die Antwort einen TDM-Vorbehalt im Header, wird das
 * Dokument verworfen, BEVOR ein Handler die Bytes zu sehen bekommt.
 *
 * <p>Das schließt den dritten Vorbehaltskanal. robots.txt und {@code /.well-known/tdmrep.json}
 * prüft das Gate VOR dem Abruf; der Header {@code TDM-Reservation} ist dagegen eine Aussage über die
 * ausgelieferte Ressource und steht definitionsgemäß erst in der Antwort. Ihn zu lesen und das
 * Dokument daraufhin wegzuwerfen, ist genau der vorgesehene Ablauf: § 44b UrhG verbietet das
 * <em>Mining</em>, nicht den Abruf — und der Abruf selbst war durch robots.txt gedeckt.
 *
 * <p>Als Dekorator (wie der {@code RobotsGatedAdapter} um den Dispatcher), damit kein Adapter den
 * Kanal versehentlich umgehen kann. Er wirft, statt still eine leere Liste zu liefern: ein
 * Vorbehalt sähe sonst aus wie ein kaputter Feed.
 */
public final class TdmAwareFeedFetcher implements FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(TdmAwareFeedFetcher.class);

    private final FeedFetcher delegate;

    public TdmAwareFeedFetcher(FeedFetcher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<FeedResponse> fetch(URI url) {
        Optional<FeedResponse> response = delegate.fetch(url);
        if (response.isEmpty()) {
            return response;
        }

        FeedResponse feed = response.get();
        if (TdmHeader.isReserved(feed.headers())) {
            String policy = TdmHeader.policy(feed.headers()).orElse(null);
            log.error("ABRUF VERWEIGERT (TDM-Header): url={} tdm-reservation gesetzt{} — Dokument "
                            + "verworfen, Quelle in der Registry auf enabled:false setzen",
                    url, policy == null ? "" : ", Policy: " + policy);
            throw new SourceNotPermittedException(
                    "TDM-Vorbehalt im Antwort-Header von " + url
                            + (policy == null ? "" : " (Policy: " + policy + ")"));
        }
        return response;
    }
}
