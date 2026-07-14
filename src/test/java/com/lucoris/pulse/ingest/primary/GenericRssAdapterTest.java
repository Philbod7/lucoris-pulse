package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import com.lucoris.pulse.ingest.primary.FeedFetcher.FeedResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reiner Unit-Test des RSS-/Atom-Adapters — ohne Spring, Netz oder DB. Der {@link FeedFetcher}-Port
 * ist ein Fake, der die Bytes aus dem Test-Classpath liest; der Adapter kennt konstruktionsbedingt
 * keinen HTTP-Client und kann hier gar kein Netz erreichen.
 *
 * <p>Die Fixtures {@code ecb-press-rss20.xml} und {@code fed-monetary-rss20.xml} sind BYTE-GENAUE
 * Aufzeichnungen der echten Feeds — mit all ihren Macken (Fed: UTF-8-BOM; ECB: keine
 * XML-Deklaration und keine {@code description}). Genau die sollen sie testen; ein Editor, der sie
 * „aufräumt", entwertet den Test still.
 */
class GenericRssAdapterTest {

    /** Abrufzeit fixiert — so ist fetchedAt an jedem Ereignis prüfbar. */
    private static final Instant FETCHED_AT = Instant.parse("2026-07-14T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);

    private static final URI ECB = URI.create("https://www.ecb.europa.eu/rss/press.xml");
    private static final URI FED = URI.create("https://www.federalreserve.gov/feeds/press_monetary.xml");
    private static final URI ATOM = URI.create("https://example.org/atom.xml");
    private static final URI EDGE = URI.create("https://example.org/edge.xml");

    private final ClasspathFeedFetcher fetcher = new ClasspathFeedFetcher(Map.of(
            ECB, "feeds/ecb-press-rss20.xml",
            FED, "feeds/fed-monetary-rss20.xml",
            ATOM, "feeds/atom-sample.xml",
            EDGE, "feeds/rss20-edge-cases.xml"));

    private final GenericRssAdapter adapter = new GenericRssAdapter(fetcher, CLOCK);

    /** Die echten Quellen kommen aus der echten Registry — so ist der Durchreich-Pfad mitgetestet. */
    private final PrimarySourceManifestLoader registry = new PrimarySourceManifestLoader(
            JsonMapper.builder().build(), "primary-sources/lucoris-pulse-primary-sources.json");

    @Test
    void ecbFeedWithoutXmlDeclarationParsesAndNormalisesToUtc() {
        List<FeedItem> events = adapter.fetch(source("ecb-press"));

        assertThat(events).hasSize(15);

        FeedItem first = events.getFirst();
        assertThat(first.sourceId()).isEqualTo("ecb-press");
        assertThat(first.institution()).isEqualTo("European Central Bank (EZB)");
        assertThat(first.title()).isEqualTo("Piero Cipollone: Interview with Jornal de Negocios");
        assertThat(first.url())
                .isEqualTo("https://www.ecb.europa.eu//press/inter/date/2026/html/ecb.in260713~d928475792.en.html");
        assertThat(first.language()).isEqualTo("en");
        assertThat(first.fetchedAt()).isEqualTo(FETCHED_AT);

        // Die guid kommt WÖRTLICH an (hier: guid = Artikel-URL) — sie ist die Dedup-Grundlage.
        assertThat(first.guid())
                .isEqualTo("https://www.ecb.europa.eu//press/inter/date/2026/html/ecb.in260713~d928475792.en.html");

        // pubDate ist "Mon, 13 Jul 2026 19:00:00 +0200" -> nach UTC verschoben, nicht bloß übernommen.
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-07-13T17:00:00Z"));

        // ECB-Items haben KEIN <description> (nur der Channel hat eins) -> null, keine NPE.
        assertThat(events).allSatisfy(e -> assertThat(e.rawSummary()).isNull());

        // Jedes Ereignis trägt Pflichtfelder und die Rechtslage der Quelle.
        assertThat(events).allSatisfy(e -> {
            assertThat(e.url()).isNotBlank();
            assertThat(e.publishedAt()).isNotNull();
            assertThat(e.legalClass()).isEqualTo("A");
        });
    }

    @Test
    void ecbAttributionIsCarriedIntoEveryEventForTheLaterSourceLine() {
        // Das Rendering muss später "Quelle: Europaeische Zentralbank, ..." bauen können, ohne
        // erneut in die Registry zu greifen.
        List<FeedItem> events = adapter.fetch(source("ecb-press"));

        assertThat(events).allSatisfy(e -> {
            assertThat(e.attribution()).isNotNull();
            assertThat(e.attribution().required()).isTrue();
            assertThat(e.attribution().formula()).isEqualTo("Quelle: Europaeische Zentralbank, [Titel/Datum]");
            assertThat(e.attribution().modifiedNote()).isFalse();
        });
    }

    @Test
    void fedFeedWithUtf8BomParsesAndHasNoAttribution() {
        List<FeedItem> events = adapter.fetch(source("fed-monetary"));

        // Der Feed beginnt mit EF BB BF. Wäre er als String statt als Bytes durchgereicht worden,
        // stünde hier "Content is not allowed in prolog" und die Liste wäre leer.
        assertThat(events).hasSize(15);

        FeedItem first = events.getFirst();
        assertThat(first.sourceId()).isEqualTo("fed-monetary");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-07-09T19:00:00Z"));
        assertThat(first.url())
                .isEqualTo("https://www.federalreserve.gov/newsevents/pressreleases/monetary20260709a.htm");
        // Anders als ECB liefert der Fed-Feed eine description.
        assertThat(first.rawSummary()).isNotBlank();

        assertThat(events).allSatisfy(e -> {
            assertThat(e.legalClass()).isEqualTo("A");
            assertThat(e.attribution()).isNull(); // fed-monetary hat keinen attribution-Block
            // Der Fed-Feed hat KEIN <guid> — Rome füllt die uri dann aus dem Link. Für die
            // Dedup ist das gleichwertig: guid und url normalisieren auf denselben Schlüssel.
            assertThat(e.guid()).isEqualTo(e.url());
        });
    }

    @Test
    void atomFeedIsReadThroughTheSameAdapter() {
        List<FeedItem> events = adapter.fetch(syntheticSource("atom-demo", ATOM));

        assertThat(events).hasSize(3);
        assertThat(events).extracting(FeedItem::title).containsExactly("Atom A", "Atom B", "Atom C");
        assertThat(events).allSatisfy(e -> assertThat(e.language()).isEqualTo("de")); // xml:lang

        FeedItem a = events.get(0);
        assertThat(a.publishedAt()).isEqualTo(Instant.parse("2026-07-13T17:00:00Z")); // published, nicht updated
        assertThat(a.rawSummary()).isEqualTo("<p>Volltext A</p>"); // aus <content>
        // Atoms <id> des EINTRAGS (nicht des Feeds) kommt als guid an — auch wenn sie opak ist.
        assertThat(a.guid()).isEqualTo("urn:uuid:aaaaaaaa-0000-4000-8000-000000000000");

        FeedItem b = events.get(1);
        assertThat(b.publishedAt()).isEqualTo(Instant.parse("2026-07-12T08:30:00Z")); // updated springt ein
        assertThat(b.rawSummary()).isEqualTo("Zusammenfassung B"); // aus <summary>

        FeedItem c = events.get(2);
        assertThat(c.rawSummary()).isNull(); // weder content noch summary
    }

    @Test
    void contentEncodedWinsOverDescriptionAndExoticDatesAreCaught() {
        List<FeedItem> events = adapter.fetch(syntheticSource("edge-demo", EDGE));

        // Vier Items, zwei davon unbrauchbar -> zwei Ereignisse.
        assertThat(events).hasSize(2);

        FeedItem withContent = events.get(0);
        assertThat(withContent.rawSummary()).isEqualTo("<p>Volltext aus content:encoded</p>");

        FeedItem exoticDate = events.get(1);
        // "2026-07-10 06:15:00" — daran scheitert Romes Parser, die FeedDates-Kette fängt es auf.
        assertThat(exoticDate.publishedAt()).isEqualTo(Instant.parse("2026-07-10T06:15:00Z"));
        assertThat(exoticDate.rawSummary()).isEqualTo("Nur description");
    }

    @Test
    void entriesWithoutDateOrWithoutLinkAreDroppedNotFakedUp() {
        List<FeedItem> events = adapter.fetch(syntheticSource("edge-demo", EDGE));

        // Das Item ohne Datum darf NICHT mit der Abrufzeit aufgefüllt werden — das erzeugte eine
        // falsche Zeitachse. Und das Item ohne Link/guid hat keinen Deep-Link, ist also wertlos.
        assertThat(events).extracting(FeedItem::title)
                .doesNotContain("Ohne jedes Datum", "Ohne Link");
        assertThat(events).noneSatisfy(e -> assertThat(e.publishedAt()).isEqualTo(FETCHED_AT));
    }

    @Test
    void unreachableFeedYieldsEmptyListInsteadOfThrowing() {
        // Eine tote Quelle (404/403/Timeout) darf den Lauf nicht abbrechen — dieselbe Philosophie
        // wie beim GDELT-Pfad, der einen fehlenden Slice als Optional.empty() behandelt.
        IngestSource tot = syntheticSource("tot", URI.create("https://example.org/gibt-es-nicht.xml"));

        assertThat(adapter.fetch(tot)).isEmpty();
    }

    @Test
    void unparsableBodyYieldsEmptyListInsteadOfThrowing() {
        FeedFetcher muell = url -> Optional.of(
                new FeedFetcher.FeedResponse("das ist kein XML".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(new GenericRssAdapter(muell, CLOCK).fetch(syntheticSource("muell", ATOM))).isEmpty();
    }

    private IngestSource source(String id) {
        return registry.enabledSources().stream()
                .filter(s -> id.equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quelle fehlt in der Registry: " + id));
    }

    /** Quelle für die synthetischen Fixtures — nicht in der Registry, nur hier. */
    private static IngestSource syntheticSource(String id, URI url) {
        return new IngestSource(
                id, "Beispiel-Institution", "central_bank", "EA", 1, List.of(),
                new Access("rss", url.toString(), "rss2.0"),
                GenericRssAdapter.HANDLER, new Poll("interval", 300, null),
                true, "verified", "A", null, null, null);
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
