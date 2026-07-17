package com.lucoris.pulse.ingest.primary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapter für den Handler {@code sec_edgar}: liest die EDGAR-submissions-API
 * ({@code data.sec.gov/submissions/CIK##########.json}) und normalisiert neue 8-K-Einreichungen
 * (US-Ad-hoc) zu {@link FeedItem}.
 *
 * <p><b>Warum ausgerechnet dieser Endpunkt.</b> Der naheliegende globale Echtzeit-Strom
 * ({@code browse-edgar?action=getcurrent}) liegt unter {@code /cgi-bin} — und die robots.txt der SEC
 * sperrt genau diesen Zweig ({@code Disallow: /cgi-bin}), während sie die Daten ausdrücklich erlaubt
 * ({@code Allow: /Archives/edgar/data}). Die Volltextsuche ({@code efts.sec.gov}) beantwortet ihre
 * robots.txt mit 403, was fail-closed ebenfalls sperrt. Bleibt die submissions-API: sie ist erlaubt
 * (kein robots.txt = keine Einschränkung, RFC 9309) und echtzeitnah — der Preis ist, dass sie PER
 * FIRMA adressiert ist und deshalb eine Watchlist braucht ({@link SecEdgarCikLoader}). Was außerhalb
 * der Watchlist passiert, fängt {@link SecEdgarDailyIndexAdapter} am Abend auf.
 *
 * <p>POJO ohne Spring-Annotationen. Der HTTP-Abruf steckt hinter {@link FeedFetcher} — dieser Adapter
 * kennt keinen {@code HttpClient} und kann daher im Unit-Test gar kein Netz erreichen. Die Erlaubnis
 * hat der {@link RobotsGatedAdapter} vor diesem Aufruf geprüft.
 *
 * <p>Die SEC lässt 10 Requests/s pro IP zu und drosselt darüber mit 403. Der Sweep hält deshalb
 * {@code pacing} zwischen den Firmen ein. Eine einzelne nicht abrufbare Firma überspringt der Adapter
 * — sie darf den restlichen Sweep nicht mitreißen (dieselbe Philosophie wie beim GDELT-Pfad).
 */
public final class SecEdgarAdapter implements SourceAdapter {

    /** Der Handler-Schlüssel im Routing-Manifest, den dieser Adapter bedient. */
    public static final String HANDLER = "sec_edgar";

    private static final Logger log = LoggerFactory.getLogger(SecEdgarAdapter.class);

    /** 8-K = US-Ad-hoc; die Berichtigung (8-K/A) gehört dazu, sonst fehlten Korrekturen. */
    private static final Set<String> FORMS = Set.of("8-K", "8-K/A");

    private final FeedFetcher fetcher;
    private final SecEdgarCikLoader watchlist;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration pacing;
    private final Duration lookback;

    public SecEdgarAdapter(FeedFetcher fetcher, SecEdgarCikLoader watchlist, ObjectMapper mapper,
            Clock clock, Duration pacing, Duration lookback) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pacing = Objects.requireNonNull(pacing, "pacing");
        this.lookback = Objects.requireNonNull(lookback, "lookback");
    }

    @Override
    public List<FeedItem> fetch(IngestSource source) {
        Instant fetchedAt = clock.instant();
        Instant cutoff = fetchedAt.minus(lookback);
        String base = basisUrl(source);
        List<SecEdgarCik> firmen = watchlist.load();

        List<FeedItem> items = new ArrayList<>();
        int nichtAbrufbar = 0;
        for (int i = 0; i < firmen.size(); i++) {
            if (i > 0 && !pause()) {
                log.warn("Quelle {}: Sweep unterbrochen nach {} von {} Firmen",
                        source.id(), i, firmen.size());
                break;
            }
            SecEdgarCik firma = firmen.get(i);
            Optional<FeedFetcher.FeedResponse> response =
                    fetcher.fetch(URI.create(base + "CIK" + firma.cik() + ".json"));
            if (response.isEmpty()) {
                nichtAbrufbar++;
                continue;
            }
            items.addAll(parse(response.get().body(), firma, source, fetchedAt, cutoff));
        }

        if (nichtAbrufbar > 0) {
            // Einmal pro Sweep, nicht pro Firma — sonst Log-Spam, wenn die SEC gerade drosselt.
            log.warn("Quelle {}: {} von {} Firmen nicht abrufbar — übersprungen",
                    source.id(), nichtAbrufbar, firmen.size());
        }
        log.info("Quelle {}: {} Einreichungen aus {} Firmen gelesen (Fenster {})",
                source.id(), items.size(), firmen.size(), lookback);
        return List.copyOf(items);
    }

    private List<FeedItem> parse(byte[] body, SecEdgarCik firma, IngestSource source,
            Instant fetchedAt, Instant cutoff) {
        Submissions doc;
        try {
            doc = mapper.readValue(body, Submissions.class);
        } catch (JacksonException e) {
            log.warn("Quelle {}: Antwort für CIK {} nicht parsbar ({}) — übersprungen",
                    source.id(), firma.cik(), e.toString());
            return List.of();
        }

        Recent recent = doc.filings() == null ? null : doc.filings().recent();
        if (recent == null || recent.form() == null || recent.form().isEmpty()) {
            return List.of();
        }

        int n = recent.form().size();
        // Die Felder sind PARALLELE Arrays — ihre Zuordnung hängt allein am Index. Geht die Zählung
        // nicht auf, wäre jede Meldung stillschweigend falsch zusammengesetzt: lieber die Firma
        // verwerfen als Titel und Zeitstempel verschiedener Einreichungen zu vermischen.
        if (groesse(recent.accessionNumber()) != n || groesse(recent.acceptanceDateTime()) != n) {
            log.warn("Quelle {}: CIK {} liefert inkonsistente parallele Arrays (form={}, accession={},"
                            + " acceptance={}) — Firma übersprungen",
                    source.id(), firma.cik(), n, groesse(recent.accessionNumber()),
                    groesse(recent.acceptanceDateTime()));
            return List.of();
        }

        String institution = doc.name() != null && !doc.name().isBlank() ? doc.name() : firma.name();
        List<FeedItem> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String form = recent.form().get(i);
            if (form == null || !FORMS.contains(form)) {
                continue;
            }
            Optional<Instant> acceptedAt = FeedDates.parse(recent.acceptanceDateTime().get(i));
            if (acceptedAt.isEmpty() || acceptedAt.get().isBefore(cutoff)) {
                continue; // ausserhalb des Fensters oder ohne verwertbaren Zeitpunkt
            }
            String accession = recent.accessionNumber().get(i);
            if (!SecEdgarUrls.isAccession(accession)) {
                continue;
            }

            items.add(new FeedItem(
                    source.id(),
                    source.institution(),
                    form + " - " + institution,
                    SecEdgarUrls.filingPermalink(firma.cik(), accession),
                    accession,
                    acceptedAt.get(),
                    zusammenfassung(recent, i),
                    "en",
                    fetchedAt,
                    source.legalClass(),
                    source.attribution()));
        }
        return items;
    }

    /**
     * Der Rohtext der Meldung: die 8-K-Item-Codes ({@code "2.02,9.01"} = Quartalszahlen + Anlagen)
     * tragen die eigentliche Aussage. {@code primaryDocDescription} ist meist nur „8-K" und damit
     * wertlos — deshalb nur als Notnagel.
     */
    private static String zusammenfassung(Recent recent, int i) {
        String items = wert(recent.items(), i);
        return items != null ? items : wert(recent.primaryDocDescription(), i);
    }

    /** Basis-URL der Quelle, immer mit Schrägstrich am Ende (das Manifest führt das Präfix). */
    private static String basisUrl(IngestSource source) {
        String url = source.access().url();
        return url.endsWith("/") ? url : url + "/";
    }

    /**
     * Hält den Abstand zum Ratenlimit der SEC ein.
     *
     * @return {@code false}, wenn der Thread unterbrochen wurde — dann bricht der Sweep ab
     */
    private boolean pause() {
        if (pacing.isZero() || pacing.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(pacing.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Status wiederherstellen, nicht schlucken
            return false;
        }
    }

    private static int groesse(List<String> list) {
        return list == null ? -1 : list.size();
    }

    /** Ein optionales Parallel-Array darf kürzer sein oder fehlen — dann gibt es den Wert nicht. */
    private static String wert(List<String> list, int i) {
        if (list == null || i >= list.size()) {
            return null;
        }
        String value = list.get(i);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // --- Nur die Felder, die wir lesen; der Rest der Antwort ist gross und geht uns nichts an. ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Submissions(String cik, String name, Filings filings) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Filings(Recent recent) {}

    /**
     * {@code filings.recent} ist spaltenweise abgelegt: parallele Arrays statt einer Liste von
     * Objekten. Der Index verbindet sie.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recent(
            List<String> accessionNumber,
            List<String> form,
            List<String> acceptanceDateTime,
            List<String> filingDate,
            List<String> items,
            List<String> primaryDocDescription) {}
}
