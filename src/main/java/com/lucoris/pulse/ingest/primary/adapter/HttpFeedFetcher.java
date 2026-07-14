package com.lucoris.pulse.ingest.primary.adapter;

import com.lucoris.pulse.ingest.config.PrimarySourceProperties;
import com.lucoris.pulse.ingest.primary.FeedFetcher;
import com.lucoris.pulse.ingest.primary.FeedFetcher.FeedResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * HTTP-Adapter des {@link FeedFetcher}-Ports — spiegelt {@code HttpGdeltSliceClient}: JDK-HttpClient,
 * Timeouts aus der Konfiguration, ehrlicher User-Agent, und eine nicht abrufbare Quelle ist kein
 * Fehler, sondern {@link Optional#empty()}.
 *
 * <p>Liefert bewusst die unangetasteten Bytes: der Fed-Feed beginnt mit einem UTF-8-BOM, der
 * ECB-Feed hat keine XML-Deklaration — die Zeichensatz-Auflösung gehört in den XML-Parser, nicht
 * hierher.
 */
@Component
@Profile({"ingest", "validate-sources"})
public class HttpFeedFetcher implements FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFeedFetcher.class);

    private final HttpClient httpClient;
    private final PrimarySourceProperties props;

    public HttpFeedFetcher(PrimarySourceProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(props.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Optional<FeedResponse> fetch(URI url) {
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("User-Agent", props.getUserAgent())
                .timeout(props.getRequestTimeout())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                // 403 kommt bei Bot-Abwehr (z.B. federalreserve.gov) vor — kein Grund, den Lauf
                // abzubrechen; die Quelle fällt für diesen Durchgang einfach aus.
                log.warn("Feed {} liefert HTTP {} — übersprungen", url, status);
                return Optional.empty();
            }
            // Header mitgeben: der TDM-Vorbehalt kann als TDM-Reservation-Header kommen und wird
            // vom TdmAwareFeedFetcher ausgewertet, bevor jemand die Bytes anfasst.
            return Optional.of(new FeedResponse(response.body(), response.headers().map()));
        } catch (IOException e) {
            log.warn("Feed {} nicht abrufbar ({}) — übersprungen", url, e.toString());
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            // Bot-Manager antworten mit einem Redirect, dessen Location-URL den User-Agent
            // UNKODIERT enthält (Leerzeichen/Klammern). Der JDK-HttpClient wirft dann beim Folgen
            // des Redirects — unchecked. Eine unerreichbare Quelle darf den Lauf nicht abbrechen.
            log.warn("Feed {} nicht abrufbar (kaputter Redirect: {}) — übersprungen", url, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feed-Abruf unterbrochen: " + url, e);
        }
    }
}
