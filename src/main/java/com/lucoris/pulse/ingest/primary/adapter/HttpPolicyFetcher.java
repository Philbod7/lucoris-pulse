package com.lucoris.pulse.ingest.primary.adapter;

import com.lucoris.pulse.ingest.config.PrimarySourceProperties;
import com.lucoris.pulse.ingest.primary.robots.PolicyFetcher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * HTTP-Adapter des {@link PolicyFetcher}-Ports: holt {@code robots.txt} und
 * {@code /.well-known/tdmrep.json}.
 *
 * <p>Reicht den HTTP-Status durch, statt Fehler zu {@code Optional.empty()} zu verrühren — die
 * Fail-closed-Regel lebt genau von diesem Unterschied (404 = keine Einschränkung, 503 = unbekannt
 * = gesperrt). Ein Netzfehler wird zu {@link PolicyFetcher.Response#networkError()}, nicht zu einer
 * Exception: die Entscheidung darüber trifft das Gate, nicht der Transport.
 */
@Component
@Profile({"ingest", "validate-sources"})
public class HttpPolicyFetcher implements PolicyFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpPolicyFetcher.class);

    private final HttpClient httpClient;
    private final PrimarySourceProperties props;

    public HttpPolicyFetcher(PrimarySourceProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(props.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Response get(URI url) {
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("User-Agent", props.getUserAgent())
                .timeout(props.getRequestTimeout())
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("Erlaubnis-Dokument {} -> HTTP {}", url, response.statusCode());
            // Header mitgeben: eine Einladungsseite kann selbst einen TDM-Reservation-Header tragen.
            return new Response(response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            log.warn("Erlaubnis-Dokument {} nicht erreichbar ({})", url, e.toString());
            return Response.networkError();
        } catch (IllegalArgumentException e) {
            // Bot-Manager (z.B. Radware vor bundesfinanzministerium.de) antworten mit einem Redirect,
            // dessen Location-URL den User-Agent UNKODIERT enthält — Leerzeichen und Klammern
            // inklusive. Der JDK-HttpClient kann sie dann nicht parsen und wirft (unchecked!) beim
            // Folgen des Redirects. Das ist kein Erlaubnis-Nachweis -> fail-closed, nicht abstürzen.
            log.warn("Erlaubnis-Dokument {} nicht auswertbar (kaputter Redirect: {})", url, e.getMessage());
            return Response.networkError();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Auch hier kein Werfen: ein Abbruch ist kein Erlaubnis-Nachweis -> fail-closed.
            return Response.networkError();
        }
    }
}
