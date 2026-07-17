package com.lucoris.pulse.ingest.primary;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter für den Handler {@code sec_edgar_daily}: liest den EDGAR-Tagesindex
 * ({@code /Archives/edgar/daily-index/{jahr}/QTR{q}/master.{yyyyMMdd}.idx}) und normalisiert die
 * 8-K-Zeilen zu {@link FeedItem}.
 *
 * <p><b>Wozu, wenn es {@link SecEdgarAdapter} gibt.</b> Die submissions-API ist echtzeitnah, aber per
 * Firma adressiert — sie sieht ausschließlich die Watchlist. Der Tagesindex ist das Netz darunter:
 * er führt ALLE Einreichungen eines Tages, auch von Firmen, die niemand auf dem Schirm hatte. Der
 * Preis steht in den Daten: die Datei erscheint erst am Abend (~22:00 ET), und sie führt nur das
 * Datum, keine Uhrzeit.
 *
 * <p><b>Warum mehrere Tage je Lauf.</b> Die Datei des laufenden Tages gibt es tagsüber schlicht noch
 * nicht (die SEC antwortet dann mit 403). Läse dieser Adapter nur „heute", lieferte er nur zwischen
 * 22:00 ET und Mitternacht überhaupt etwas — ein Neustart in diesem Fenster verlöre den Tag
 * stillschweigend, und genau das darf ein Sicherheitsnetz nicht. Er liest deshalb die letzten
 * {@code indexDays} Kalendertage (Default 3, überbrückt auch ein Wochenende) und mischt sie. Die
 * Überlappung kostet nichts: {@link DedupKeys} kollabiert alles bereits Gespeicherte.
 *
 * <p><b>Die Ungenauigkeit ist bewusst.</b> {@code Date Filed} ist ein Tag, kein Zeitpunkt — daraus
 * wird der Tagesbeginn UTC. Das ist nachweislich zu früh, aber es ist die einzige Angabe, die die
 * Quelle macht; sie zu erfinden wäre schlimmer. Im Regelfall ist das folgenlos: dieselbe Einreichung
 * kommt über den Echtzeit-Pfad mit exaktem {@code acceptanceDateTime} und erzeugt via
 * {@link SecEdgarUrls#filingPermalink} denselben {@code dedup_key} — wer zuerst da ist, gewinnt, und
 * das ist fast immer der schnellere Pfad. Nur Firmen außerhalb der Watchlist landen mit
 * Mitternachts-Zeit in der Datenbank.
 *
 * <p>POJO ohne Spring-Annotationen; kennt keinen {@code HttpClient} und wirft nie.
 */
public final class SecEdgarDailyIndexAdapter implements SourceAdapter {

    /** Der Handler-Schlüssel im Routing-Manifest, den dieser Adapter bedient. */
    public static final String HANDLER = "sec_edgar_daily";

    private static final Logger log = LoggerFactory.getLogger(SecEdgarDailyIndexAdapter.class);

    /** 8-K = US-Ad-hoc; die Berichtigung (8-K/A) gehört dazu, sonst fehlten Korrekturen. */
    private static final Set<String> FORMS = Set.of("8-K", "8-K/A");

    /** EDGAR rechnet in der Zeitzone der SEC — der „heutige" Index ist der US-Börsentag. */
    private static final ZoneId SEC_ZONE = ZoneId.of("America/New_York");

    private static final DateTimeFormatter DATEI_DATUM =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    /** {@code CIK|Company Name|Form Type|Date Filed|File Name} */
    private static final int SPALTEN = 5;

    private final FeedFetcher fetcher;
    private final Clock clock;
    private final int indexDays;

    public SecEdgarDailyIndexAdapter(FeedFetcher fetcher, Clock clock, int indexDays) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (indexDays < 1) {
            throw new IllegalArgumentException("indexDays muss >= 1 sein, war: " + indexDays);
        }
        this.indexDays = indexDays;
    }

    @Override
    public List<FeedItem> fetch(IngestSource source) {
        Instant fetchedAt = clock.instant();
        LocalDate heute = LocalDate.ofInstant(fetchedAt, SEC_ZONE);

        List<FeedItem> items = new ArrayList<>();
        int gelesen = 0;
        for (int zurueck = 0; zurueck < indexDays; zurueck++) {
            LocalDate tag = heute.minusDays(zurueck);
            URI url = URI.create(tagesIndexUrl(source, tag));
            Optional<FeedFetcher.FeedResponse> response = fetcher.fetch(url);
            if (response.isEmpty()) {
                // Normalfall, kein Fehler: die Datei des laufenden Tages erscheint erst am Abend,
                // und Wochenendtage haben nie eine.
                log.debug("Quelle {}: Tagesindex {} (noch) nicht abrufbar", source.id(), url);
                continue;
            }
            gelesen++;
            items.addAll(parse(response.get().body(), source, fetchedAt, tag));
        }

        if (gelesen == 0) {
            log.info("Quelle {}: keiner der letzten {} Tagesindizes abrufbar — übersprungen",
                    source.id(), indexDays);
        }
        return List.copyOf(items);
    }

    /** {@code .../daily-index/2026/QTR3/master.20260715.idx} */
    private static String tagesIndexUrl(IngestSource source, LocalDate tag) {
        String base = source.access().url();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        int quartal = (tag.getMonthValue() - 1) / 3 + 1;
        return base + tag.getYear() + "/QTR" + quartal + "/master." + DATEI_DATUM.format(tag) + ".idx";
    }

    private List<FeedItem> parse(byte[] body, IngestSource source, Instant fetchedAt, LocalDate tag) {
        // Die .idx-Dateien sind ASCII; ISO-8859-1 bildet jedes Byte ab und kann nie werfen.
        String text = new String(body, StandardCharsets.ISO_8859_1);

        List<FeedItem> items = new ArrayList<>();
        int verworfen = 0;
        for (String zeile : text.split("\n")) {
            String[] spalten = zeile.split("\\|");
            // Kopfzeilen brauchen kein Sonderwissen: sie haben entweder nicht 5 Felder, oder ihr
            // erstes Feld ist keine CIK ("CIK|Company Name|..."). Damit ist der Parser gegen einen
            // umformulierten Kopf unempfindlich.
            if (spalten.length != SPALTEN || !istZiffern(spalten[0].trim())) {
                continue;
            }
            String form = spalten[2].trim();
            if (!FORMS.contains(form)) {
                continue;
            }

            String cik = spalten[0].trim();
            String firma = spalten[1].trim();
            String accession = SecEdgarUrls.accessionFromArchivePath(spalten[4].trim());
            Optional<Instant> publishedAt = tagesBeginn(spalten[3].trim());
            if (accession == null || publishedAt.isEmpty()) {
                verworfen++;
                continue;
            }

            items.add(new FeedItem(
                    source.id(),
                    source.institution(),
                    form + " - " + firma,
                    SecEdgarUrls.filingPermalink(cik, accession),
                    accession,
                    publishedAt.get(),
                    null, // der Tagesindex führt keine Item-Codes und keinen Beschreibungstext
                    "en",
                    fetchedAt,
                    source.legalClass(),
                    source.attribution()));
        }

        if (verworfen > 0) {
            // Einmal pro Datei, nicht pro Zeile.
            log.warn("Quelle {}: Tagesindex {} — {} 8-K-Zeilen ohne Accession/Datum verworfen",
                    source.id(), tag, verworfen);
        }
        log.info("Quelle {}: Tagesindex {} — {} Einreichungen gelesen", source.id(), tag, items.size());
        return items;
    }

    /** {@code 20260715} -> Tagesbeginn UTC. Die Quelle nennt keine Uhrzeit (siehe Klassendoc). */
    private static Optional<Instant> tagesBeginn(String yyyymmdd) {
        try {
            return Optional.of(LocalDate.parse(yyyymmdd, DATEI_DATUM).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static boolean istZiffern(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }
}
