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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Reiner Unit-Test des EDGAR-Tagesindex-Adapters — ohne Spring, Netz oder DB.
 *
 * <p>Die Fixture {@code sec-edgar-daily-index-master.idx} ist ein ECHTER Ausschnitt der Datei
 * {@code master.20260715.idx} (abgerufen am 2026-07-16): der Kopf ist unverändert, die Datenzeilen
 * sind auf einen Querschnitt gekürzt — 8-K, 8-K/A und 424B2, damit der Form-Filter etwas zu tun hat.
 */
class SecEdgarDailyIndexAdapterTest {

    /** 2026-07-16, 14:00 UTC = 10:00 in New York -> der Adapter fragt den Index vom 16.07. an. */
    private static final Instant FETCHED_AT = Instant.parse("2026-07-16T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);

    private static final String BASE = "https://www.sec.gov/Archives/edgar/daily-index/";
    private static final String FIXTURE = "feeds/sec-edgar-daily-index-master.idx";
    private static final URI HEUTE = URI.create(BASE + "2026/QTR3/master.20260716.idx");
    private static final URI GESTERN = URI.create(BASE + "2026/QTR3/master.20260715.idx");

    private final ClasspathFeedFetcher fetcher = new ClasspathFeedFetcher(Map.of(HEUTE, FIXTURE));

    /** Letzter Erfolg heute -> Fenster = 1 Tag: für die Fälle, die genau die heutige Datei betrachten. */
    private static final LastSuccessLookup ERFOLG_HEUTE = id -> Optional.of(FETCHED_AT);
    /** Kein Zustand bekannt -> volle Rückschau (Kaltstart, Probe, validate-sources). */
    private static final LastSuccessLookup KEIN_ERFOLG = id -> Optional.empty();

    private final SecEdgarDailyIndexAdapter adapter =
            new SecEdgarDailyIndexAdapter(fetcher, ERFOLG_HEUTE, CLOCK, 7);

    @Test
    void theDatePathIsDerivedFromTheSecBusinessDayAndOnlyEightKsSurvive() {
        List<FeedItem> items = adapter.fetch(source());

        // Von zehn Datenzeilen bleiben die fünf 8-K und das eine 8-K/A; die vier 424B2 fallen raus.
        // Die ersten beiden sind DIESELBE Einreichung unter zwei Mit-Anmeldern (siehe unten).
        assertThat(items).extracting(FeedItem::guid).containsExactly(
                "0000100517-26-000135",
                "0000100517-26-000135",
                "0001437749-26-023636",
                "0001104659-26-083743",
                "0001031203-26-000117",
                "0001140361-26-028564");
        assertThat(items).extracting(FeedItem::title).contains(
                "8-K - United Airlines Holdings, Inc.",
                "8-K/A - GYRE THERAPEUTICS, INC.");
    }

    @Test
    void aFilingWithCoRegistrantsHasSeveralValidPermalinksButOneIdentity() {
        // DER Fall, der die permalink-basierte Dedup zerlegt hat: 0000100517-26-000135 steht im
        // echten Tagesindex ZWEIMAL — einmal unter der Holding (CIK 100517), einmal unter der
        // Tochter (CIK 319687). Beide URLs sind gültig und liefern dasselbe Dokument (live geprüft,
        // HTTP 200). Über den Permalink dedupliziert läge das Filing zweimal in der Datenbank.
        List<FeedItem> united = adapter.fetch(source()).stream()
                .filter(i -> "0000100517-26-000135".equals(i.guid()))
                .toList();

        assertThat(united).hasSize(2);
        assertThat(united).extracting(FeedItem::url).containsExactly(
                "https://www.sec.gov/Archives/edgar/data/100517/000010051726000135/0000100517-26-000135-index.htm",
                "https://www.sec.gov/Archives/edgar/data/319687/000010051726000135/0000100517-26-000135-index.htm");

        // Verschiedene Permalinks — EIN Schlüssel. Das ist der Punkt.
        assertThat(united).extracting(FeedItem::url).doesNotHaveDuplicates();
        assertThat(united).extracting(DedupKeys::keyFor)
                .containsOnly("sec-edgar:accession:0000100517-26-000135");
    }

    @Test
    void aFilingIsMappedWithConstructedPermalinkAndDayPrecisionTimestamp() {
        FeedItem first = adapter.fetch(source()).getFirst();

        assertThat(first.sourceId()).isEqualTo("sec-edgar-daily-index");
        assertThat(first.institution()).isEqualTo("US SEC (EDGAR)");
        assertThat(first.title()).isEqualTo("8-K - United Airlines Holdings, Inc.");
        assertThat(first.language()).isEqualTo("en");
        assertThat(first.fetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(first.legalClass()).isEqualTo("A");

        // Der Tagesindex führt keine Item-Codes — anders als die submissions-API.
        assertThat(first.rawSummary()).isNull();

        // 'Date Filed' ist ein TAG, kein Zeitpunkt: Tagesbeginn UTC, bewusste Ungenauigkeit.
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-07-15T00:00:00Z"));

        // Accession aus dem Dateinamen, Permalink daraus konstruiert (CIK ohne führende Nullen).
        assertThat(first.guid()).isEqualTo("0000100517-26-000135");
        assertThat(first.url()).isEqualTo(
                "https://www.sec.gov/Archives/edgar/data/100517/000010051726000135/0000100517-26-000135-index.htm");
    }

    @Test
    void bothEdgarHandlersProduceTheSameDedupKeyEvenViaDifferentCiks() {
        // DER Vertrag zwischen den beiden Handlern: dieselbe Accession ⇒ derselbe dedup_key ⇒ die
        // Meldung wird EINMAL gespeichert, egal welcher Pfad sie zuerst sieht. Bewusst über die
        // Tochter-CIK gegengeprüft: der Echtzeit-Pfad kennt die Holding (CIK 100517), der Tagesindex
        // liefert die Zeile auch unter 319687. Über den Permalink wäre das zweierlei.
        FeedItem ueberTochter = adapter.fetch(source()).stream()
                .filter(i -> i.url().contains("/data/319687/"))
                .findFirst()
                .orElseThrow();

        String schluesselDesEchtzeitpfads = SecEdgarUrls.dedupKey("0000100517-26-000135");

        assertThat(ueberTochter.url())
                .isNotEqualTo(SecEdgarUrls.filingPermalink("0000100517", "0000100517-26-000135"));
        assertThat(DedupKeys.keyFor(ueberTochter)).isEqualTo(schluesselDesEchtzeitpfads);
    }

    @Test
    void duringTheDayTheStillMissingTodayFileFallsBackToEarlierDays() {
        // DER Fall, an dem eine „nur heute"-Fassung scheitern würde: die Datei des laufenden Tages
        // erscheint erst gegen 22:00 ET (davor antwortet die SEC mit 403). Läse der Adapter nur
        // „heute", lieferte er 22 von 24 Stunden am Tag nichts — ein Neustart in dem kurzen Fenster
        // verlöre den Tag still. Hier gibt es NUR die Datei von gestern.
        ClasspathFeedFetcher nurGestern = new ClasspathFeedFetcher(Map.of(GESTERN, FIXTURE));

        List<FeedItem> items =
                new SecEdgarDailyIndexAdapter(nurGestern, KEIN_ERFOLG, CLOCK, 3).fetch(source());

        assertThat(items).hasSize(6);
    }

    @Test
    void severalDaysAreMergedAndTheirOverlapIsLeftToTheDedupKey() {
        // Beide Tage liefern (hier: dieselbe Fixture). Der Adapter mischt sie roh — das Entdoppeln
        // ist NICHT seine Aufgabe, sondern die von DedupKeys/dem Usecase. Genau deshalb ist das
        // Mehrtage-Fenster billig zu haben.
        ClasspathFeedFetcher beide =
                new ClasspathFeedFetcher(Map.of(HEUTE, FIXTURE, GESTERN, FIXTURE));

        List<FeedItem> items =
                new SecEdgarDailyIndexAdapter(beide, KEIN_ERFOLG, CLOCK, 2).fetch(source());

        // Roh gemischt: 12 Meldungen (2 Tage x 6 Zeilen), aber nur 5 verschiedene Schlüssel — die
        // Tages-Überlappung UND die Mit-Anmelder-Dublette fallen zusammen. Beides ist ERWÜNSCHT: das
        // Entdoppeln passiert eine Schicht höher (IngestPrimarySourcesUsecase kollabiert batch-intern
        // über DedupKeys, der UNIQUE-Index ist das Netz darunter).
        assertThat(items).hasSize(12);
        assertThat(items.stream().map(DedupKeys::keyFor).distinct().toList()).hasSize(5);
    }

    @Test
    void noDailyFileAtAllYieldsEmptyListInsteadOfThrowing() {
        FeedFetcher tot = url -> Optional.empty();

        assertThat(new SecEdgarDailyIndexAdapter(tot, KEIN_ERFOLG, CLOCK, 3).fetch(source())).isEmpty();
    }

    @Test
    void aBodyWithoutDataRowsYieldsEmptyListInsteadOfThrowing() {
        FeedFetcher muell = url -> Optional.of(
                new FeedResponse("das ist kein Index".getBytes(StandardCharsets.ISO_8859_1)));

        assertThat(new SecEdgarDailyIndexAdapter(muell, ERFOLG_HEUTE, CLOCK, 7).fetch(source())).isEmpty();
    }

    // --- Adaptives Rückschau-Fenster ---

    @Test
    void aColdStartWithoutAnyRecordedSuccessReadsTheFullWindow() {
        // Kein letzter Erfolg -> volle Rückschau (hier maxDays=7): der erste Lauf nach einem Deploy
        // muss die ganze Spanne aufholen, nicht nur heute.
        List<URI> geholt = new ArrayList<>();
        FeedFetcher zaehlend = url -> { geholt.add(url); return Optional.empty(); };

        new SecEdgarDailyIndexAdapter(zaehlend, KEIN_ERFOLG, CLOCK, 7).fetch(source());

        assertThat(geholt).hasSize(7);
        assertThat(geholt.getFirst().toString()).endsWith("master.20260716.idx"); // heute zuerst
        assertThat(geholt.getLast().toString()).endsWith("master.20260710.idx"); // 6 Tage zurück
    }

    @Test
    void aSuccessEarlierTheSameDayReadsOnlyToday() {
        List<URI> geholt = new ArrayList<>();
        FeedFetcher zaehlend = url -> { geholt.add(url); return Optional.empty(); };
        // 6 Stunden vor FETCHED_AT, aber am selben SEC-Kalendertag.
        LastSuccessLookup vor6h = id -> Optional.of(FETCHED_AT.minus(Duration.ofHours(6)));

        new SecEdgarDailyIndexAdapter(zaehlend, vor6h, CLOCK, 7).fetch(source());

        assertThat(geholt).extracting(URI::toString).containsExactly(
                BASE + "2026/QTR3/master.20260716.idx");
    }

    @Test
    void aSuccessYesterdayReadsTodayAndYesterday() {
        List<URI> geholt = new ArrayList<>();
        FeedFetcher zaehlend = url -> { geholt.add(url); return Optional.empty(); };
        LastSuccessLookup gestern = id -> Optional.of(FETCHED_AT.minus(Duration.ofDays(1)));

        new SecEdgarDailyIndexAdapter(zaehlend, gestern, CLOCK, 7).fetch(source());

        assertThat(geholt).extracting(URI::toString).containsExactly(
                BASE + "2026/QTR3/master.20260716.idx",
                BASE + "2026/QTR3/master.20260715.idx");
    }

    @Test
    void aLongOutageIsCappedAtTheMaximumWindow() {
        List<URI> geholt = new ArrayList<>();
        FeedFetcher zaehlend = url -> { geholt.add(url); return Optional.empty(); };
        LastSuccessLookup vor30Tagen = id -> Optional.of(FETCHED_AT.minus(Duration.ofDays(30)));

        new SecEdgarDailyIndexAdapter(zaehlend, vor30Tagen, CLOCK, 7).fetch(source());

        // Gedeckelt: 30 Tage Ausfall, aber nur 7 werden geholt. Ältere sind endgültig verloren
        // (der Adapter loggt das als WARN).
        assertThat(geholt).hasSize(7);
    }

    private static IngestSource source() {
        return new IngestSource(
                "sec-edgar-daily-index", "US SEC (EDGAR)", "company_primary", "US", 1, List.of(),
                new Access("api", BASE, "idx"),
                SecEdgarDailyIndexAdapter.HANDLER, new Poll("interval", 3600, null),
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
