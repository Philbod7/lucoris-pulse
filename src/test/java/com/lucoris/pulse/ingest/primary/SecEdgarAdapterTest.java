package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.ingest.primary.FeedFetcher.FeedResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reiner Unit-Test des EDGAR-submissions-Adapters — ohne Spring, Netz oder DB. Der
 * {@link FeedFetcher}-Port ist ein Fake, der die Bytes aus dem Test-Classpath liest; der Adapter
 * kennt konstruktionsbedingt keinen HTTP-Client und kann hier gar kein Netz erreichen.
 *
 * <p>Die Fixture {@code sec-edgar-submissions-CIK0000320193.json} ist eine ECHTE Antwort von
 * {@code data.sec.gov} (Apple, abgerufen am 2026-07-16). Gekürzt sind nur die Längen der parallelen
 * Arrays — Feldnamen, Schachtelung und Werte sind unverändert. Sie enthält bewusst 4 Nicht-8-K
 * (Form 4, SD), damit der Form-Filter etwas zu tun hat.
 *
 * <p>{@code pacing = ZERO}: das Ratenlimit-Warten der SEC gehört in den Betrieb, nicht in den Test.
 */
class SecEdgarAdapterTest {

    /** Abrufzeit fixiert — so sind fetchedAt UND das Lookback-Fenster prüfbar. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);

    private static final String BASE = "https://data.sec.gov/submissions/";
    private static final URI APPLE = URI.create(BASE + "CIK0000320193.json");

    private final ClasspathFeedFetcher fetcher = new ClasspathFeedFetcher(
            Map.of(APPLE, "feeds/sec-edgar-submissions-CIK0000320193.json"));

    /** Test-Watchlist: Apple (mit Fixture) + eine CIK ohne Fixture (= nicht abrufbar). */
    private final SecEdgarCikLoader watchlist = new SecEdgarCikLoader(
            JsonMapper.builder().build(), "primary-sources/test-sec-edgar-ciks.json");

    private SecEdgarAdapter adapter(Duration lookback) {
        return new SecEdgarAdapter(fetcher, watchlist, JsonMapper.builder().build(), CLOCK,
                Duration.ZERO, lookback);
    }

    @Test
    void onlyEightKsInsideTheLookbackWindowBecomeFeedItems() {
        // Fenster 7 Tage ab 2026-05-01 -> Grenze 2026-04-24. Von den vier 8-K der Fixture liegt nur
        // das vom 30.04. darin; Form 4 und SD fallen ohnehin durch den Form-Filter.
        List<FeedItem> items = adapter(Duration.ofDays(7)).fetch(source());

        assertThat(items).hasSize(1);

        FeedItem first = items.getFirst();
        assertThat(first.sourceId()).isEqualTo("sec-edgar");
        assertThat(first.institution()).isEqualTo("US SEC (EDGAR)");
        // Der Firmenname kommt aus der API-Antwort, nicht aus der Watchlist.
        assertThat(first.title()).isEqualTo("8-K - Apple Inc.");
        assertThat(first.language()).isEqualTo("en");
        assertThat(first.fetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(first.legalClass()).isEqualTo("A");
        assertThat(first.attribution()).isNull();

        // acceptanceDateTime, nicht filingDate: der exakte Zeitpunkt der Entgegennahme.
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-04-30T20:30:41Z"));

        // Die Item-Codes tragen die Aussage (2.02 = Quartalszahlen, 9.01 = Anlagen).
        assertThat(first.rawSummary()).isEqualTo("2.02,9.01");

        // guid = Accession (nicht URL-förmig) -> DedupKeys fällt auf den konstruierten Permalink
        // zurück. Genau dieser Permalink muss auch aus dem Tagesindex entstehen, sonst gäbe es die
        // Meldung zweimal.
        assertThat(first.guid()).isEqualTo("0000320193-26-000011");
        assertThat(first.url()).isEqualTo(
                "https://www.sec.gov/Archives/edgar/data/320193/000032019326000011/0000320193-26-000011-index.htm");
        assertThat(DedupKeys.keyFor(first)).isEqualTo(first.url());
    }

    @Test
    void aWiderWindowYieldsEveryEightKButStillNoOtherForm() {
        List<FeedItem> items = adapter(Duration.ofDays(365)).fetch(source());

        // Alle vier 8-K der Fixture, in Dokumentreihenfolge (neueste zuerst) — kein Form 4, kein SD.
        assertThat(items).extracting(FeedItem::guid).containsExactly(
                "0000320193-26-000011",
                "0001140361-26-015711",
                "0001140361-26-006577",
                "0000320193-26-000005");
        assertThat(items).allSatisfy(item -> assertThat(item.title()).startsWith("8-K"));
    }

    @Test
    void anUnreachableCompanyIsSkippedAndDoesNotAbortTheSweep() {
        // Die zweite CIK der Test-Watchlist hat keine Fixture -> der Fake antwortet wie ein 404.
        // Apple muss trotzdem geliefert werden.
        List<FeedItem> items = adapter(Duration.ofDays(365)).fetch(source());

        assertThat(items).isNotEmpty();
        assertThat(items).allSatisfy(item -> assertThat(item.url()).contains("/data/320193/"));
    }

    @Test
    void inconsistentParallelArraysSkipTheCompanyInsteadOfMisalignedItems() {
        // form hat 2 Einträge, accessionNumber nur 1: der Index verbindet die Spalten — geht die
        // Zählung nicht auf, wäre jede Meldung still falsch zusammengesetzt.
        String kaputt = """
                {"cik":"0000320193","name":"Apple Inc.","filings":{"recent":{
                  "form":["8-K","8-K"],
                  "accessionNumber":["0000320193-26-000011"],
                  "acceptanceDateTime":["2026-04-30T20:30:41.000Z","2026-04-29T20:30:41.000Z"]
                }}}""";
        FeedFetcher fake = url -> Optional.of(new FeedResponse(kaputt.getBytes(StandardCharsets.UTF_8)));

        List<FeedItem> items = new SecEdgarAdapter(fake, watchlist, JsonMapper.builder().build(),
                CLOCK, Duration.ZERO, Duration.ofDays(365)).fetch(source());

        assertThat(items).isEmpty();
    }

    @Test
    void unparsableBodyYieldsEmptyListInsteadOfThrowing() {
        FeedFetcher muell = url -> Optional.of(
                new FeedResponse("das ist kein JSON".getBytes(StandardCharsets.UTF_8)));

        assertThat(new SecEdgarAdapter(muell, watchlist, JsonMapper.builder().build(), CLOCK,
                Duration.ZERO, Duration.ofDays(365)).fetch(source())).isEmpty();
    }

    @Test
    void unreachableSourceYieldsEmptyListInsteadOfThrowing() {
        FeedFetcher tot = url -> Optional.empty();

        assertThat(new SecEdgarAdapter(tot, watchlist, JsonMapper.builder().build(), CLOCK,
                Duration.ZERO, Duration.ofDays(365)).fetch(source())).isEmpty();
    }

    private static IngestSource source() {
        return new IngestSource(
                "sec-edgar", "US SEC (EDGAR)", "company_primary", "US", 1, List.of(),
                new Access("api", BASE, "json"),
                SecEdgarAdapter.HANDLER, new Poll("interval", 120, null),
                false, "verify_endpoint", "A", null, null, null);
    }

    /** Fake-Fetcher: liefert Fixtures aus dem Test-Classpath, sonst „nicht abrufbar". */
    private static final class ClasspathFeedFetcher implements FeedFetcher {

        private final Map<URI, String> resourceByUrl;

        ClasspathFeedFetcher(Map<URI, String> resourceByUrl) {
            this.resourceByUrl = resourceByUrl;
        }

        @Override
        public Optional<FeedResponse> fetch(URI url) {
            String resource = resourceByUrl.get(url);
            if (resource == null) {
                return Optional.empty(); // wie ein 404
            }
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new AssertionError("Fixture fehlt im Test-Classpath: " + resource);
                }
                return Optional.of(new FeedResponse(in.readAllBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
