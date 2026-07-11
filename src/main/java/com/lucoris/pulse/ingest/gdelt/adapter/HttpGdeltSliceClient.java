package com.lucoris.pulse.ingest.gdelt.adapter;

import com.lucoris.pulse.ingest.config.GdeltProperties;
import com.lucoris.pulse.ingest.gdelt.GdeltDataset;
import com.lucoris.pulse.ingest.gdelt.GdeltSliceClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * HTTP-Adapter für den direkten, kostenlosen GDELT-V2-Bezug. Baut die case-sensitive Slice-URL,
 * lädt das ZIP per JDK-{@link HttpClient} (streamt den Body), entpackt den einen Eintrag und
 * splittet jede Zeile an TAB.
 *
 * <p>HTTP 404 (Slice nicht publiziert) und transiente Fehler ergeben {@link Optional#empty()},
 * damit ein einzelner fehlender Slice den Tageslauf nicht abbricht.
 */
@Component
@Profile("ingest")
public class HttpGdeltSliceClient implements GdeltSliceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpGdeltSliceClient.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final HttpClient httpClient;
    private final GdeltProperties props;

    public HttpGdeltSliceClient(GdeltProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(props.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Optional<List<String[]>> download(GdeltDataset dataset, LocalDateTime sliceStartUtc) {
        String stamp = sliceStartUtc.atOffset(ZoneOffset.UTC).format(STAMP);
        String url = dataset.url(props.getBaseUrl(), stamp);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", props.getUserAgent())
                .timeout(props.getRequestTimeout())
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status == 404) {
                    log.debug("GDELT-Slice fehlt (404): {}", url);
                    return Optional.empty();
                }
                if (status != 200) {
                    log.warn("GDELT-Slice {} liefert HTTP {} — übersprungen", url, status);
                    return Optional.empty();
                }
                return Optional.of(unzipAndSplit(body));
            }
        } catch (IOException e) {
            log.warn("GDELT-Slice {} nicht abrufbar ({}) — übersprungen", url, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GDELT-Abruf unterbrochen: " + url, e);
        }
    }

    /** Entpackt den einzigen ZIP-Eintrag und splittet jede nichtleere Zeile an TAB. */
    private static List<String[]> unzipAndSplit(InputStream body) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(body)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) {
                return List.of();
            }
            // Reader NICHT schließen — das würde den ZipInputStream/Body schließen (per try-with-resources).
            BufferedReader reader = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
            List<String[]> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                rows.add(line.split("\t", -1)); // -1: leere Trailing-Felder behalten
            }
            return rows;
        }
    }
}
