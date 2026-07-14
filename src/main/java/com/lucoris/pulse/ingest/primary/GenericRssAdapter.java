package com.lucoris.pulse.ingest.primary;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter für den Handler {@code generic_rss}: liest RSS 2.0 UND Atom über Romes gemeinsames
 * {@code SyndFeed}-Modell und normalisiert beides zu {@link FeedItem}.
 *
 * <p>POJO ohne Spring-Annotationen. Der HTTP-Abruf steckt hinter {@link FeedFetcher} — dieser
 * Adapter kennt keinen {@code HttpClient} und kann daher im Unit-Test gar kein Netz erreichen.
 *
 * <p>Tolerant gegenüber der Realität der Feeds: der ECB-Feed hat keine XML-Deklaration und liefert
 * keine {@code description}, der Fed-Feed beginnt mit einem UTF-8-BOM. Einträge ohne Link oder ohne
 * parsbares Datum werden verworfen (beides ist Pflicht am {@link FeedItem}) und am Ende
 * gezählt gemeldet — bewusst KEIN Notbehelf „Abrufzeit als Veröffentlichungszeit", der stillschweigend
 * falsche Zeitachsen erzeugen würde.
 */
public final class GenericRssAdapter implements SourceAdapter {

    /** Der Handler-Schlüssel im Routing-Manifest, den dieser Adapter bedient. */
    public static final String HANDLER = "generic_rss";

    private static final Logger log = LoggerFactory.getLogger(GenericRssAdapter.class);
    private static final Namespace DC = Namespace.getNamespace("http://purl.org/dc/elements/1.1/");

    private final FeedFetcher fetcher;
    private final Clock clock;

    public GenericRssAdapter(FeedFetcher fetcher, Clock clock) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<FeedItem> fetch(IngestSource source) {
        URI url = URI.create(source.access().url());
        Instant fetchedAt = clock.instant();

        // Die Erlaubnis (robots.txt/TDM) hat der RobotsGatedAdapter VOR diesem Aufruf geprüft, den
        // TDM-Header der Antwort prüft der TdmAwareFeedFetcher — dieser Adapter parst nur noch.
        Optional<FeedFetcher.FeedResponse> response = fetcher.fetch(url);
        if (response.isEmpty()) {
            log.warn("Quelle {} nicht abrufbar ({}) — übersprungen", source.id(), url);
            return List.of();
        }
        return parse(response.get().body(), source, fetchedAt);
    }

    private List<FeedItem> parse(byte[] body, IngestSource source, Instant fetchedAt) {
        SyndFeed feed;
        List<String> rawDates;
        String language;
        try {
            // XmlReader löst BOM, fehlende XML-Deklaration und Zeichensatz auf — deshalb Bytes
            // statt String. Das Document wird selbst gebaut, um an die Roh-Datumsangaben zu kommen.
            Document document = hardenedBuilder().build(new XmlReader(new ByteArrayInputStream(body)));
            feed = new SyndFeedInput().build(document);
            rawDates = rawDates(document);
            language = language(feed, document);
        } catch (IOException | JDOMException | FeedException | IllegalArgumentException e) {
            log.warn("Quelle {}: Feed nicht parsbar ({}) — übersprungen", source.id(), e.toString());
            return List.of();
        }

        List<SyndEntry> entries = feed.getEntries();

        // Die Roh-Datumsangaben sind über die Dokumentreihenfolge an die Einträge gekoppelt. Geht die
        // Zählung nicht auf, wäre die Zuordnung stillschweigend falsch -> Fallback lieber abschalten.
        boolean datesAligned = rawDates.size() == entries.size();
        if (!datesAligned && !entries.isEmpty()) {
            log.warn("Quelle {}: {} Roh-Datumsangaben zu {} Einträgen — Datums-Fallback abgeschaltet",
                    source.id(), rawDates.size(), entries.size());
        }

        List<FeedItem> items = new ArrayList<>(entries.size());
        int ohneLink = 0;
        int ohneDatum = 0;

        for (int i = 0; i < entries.size(); i++) {
            SyndEntry entry = entries.get(i);

            String url = link(entry);
            if (url == null) {
                ohneLink++;
                continue;
            }
            Optional<Instant> publishedAt =
                    publishedAt(entry, datesAligned ? rawDates.get(i) : null);
            if (publishedAt.isEmpty()) {
                ohneDatum++;
                continue;
            }

            items.add(new FeedItem(
                    source.id(),
                    source.institution(),
                    trimToNull(entry.getTitle()),
                    url,
                    trimToNull(entry.getUri()),
                    publishedAt.get(),
                    summary(entry),
                    language,
                    fetchedAt,
                    source.legalClass(),
                    source.attribution()));
        }

        if (ohneLink > 0 || ohneDatum > 0) {
            // Einmal pro Feed, nicht pro Eintrag — sonst Log-Spam bei einem kaputten Feed.
            log.warn("Quelle {}: {} von {} Einträgen verworfen ({} ohne Link, {} ohne parsbares Datum)",
                    source.id(), ohneLink + ohneDatum, entries.size(), ohneLink, ohneDatum);
        }
        log.info("Quelle {}: {} Meldungen gelesen", source.id(), items.size());
        return List.copyOf(items);
    }

    /**
     * Sprache des Feeds. Rome füllt {@code SyndFeed.getLanguage()} nur aus dem RSS-Element
     * {@code <language>} — bei Atom steht die Sprache im Attribut {@code xml:lang} der Wurzel und
     * bliebe sonst immer {@code null}.
     */
    private static String language(SyndFeed feed, Document document) {
        String language = trimToNull(feed.getLanguage());
        if (language != null) {
            return language;
        }
        return trimToNull(document.getRootElement().getAttributeValue("lang", Namespace.XML_NAMESPACE));
    }

    /** Deep-Link auf das Original; {@code link} vor {@code guid}/{@code id}. */
    private static String link(SyndEntry entry) {
        String link = trimToNull(entry.getLink());
        return link != null ? link : trimToNull(entry.getUri());
    }

    /**
     * Veröffentlichungszeitpunkt: was Rome erkannt hat ({@code pubDate}/{@code published}/
     * {@code dc:date}, sonst Atoms {@code updated}), erst danach die tolerante Fallback-Kette auf
     * der Rohangabe.
     */
    private static Optional<Instant> publishedAt(SyndEntry entry, String rawDate) {
        if (entry.getPublishedDate() != null) {
            return Optional.of(entry.getPublishedDate().toInstant());
        }
        if (entry.getUpdatedDate() != null) {
            return Optional.of(entry.getUpdatedDate().toInstant());
        }
        return FeedDates.parse(rawDate);
    }

    /** Rohtext: {@code content:encoded} bzw. Atoms {@code content} vor {@code description}/{@code summary}. */
    private static String summary(SyndEntry entry) {
        for (SyndContent content : entry.getContents()) {
            String value = trimToNull(content.getValue());
            if (value != null) {
                return value;
            }
        }
        SyndContent description = entry.getDescription(); // null bei den ECB-Items — NPE-Falle
        return description == null ? null : trimToNull(description.getValue());
    }

    /**
     * Die Roh-Datumsangaben in Dokumentreihenfolge — Auffangnetz für Formate, an denen Romes
     * Datums-Parser scheitert. Ein Listenplatz kann {@code null} sein (Eintrag ohne jede Datumsangabe).
     */
    private static List<String> rawDates(Document document) {
        Element root = document.getRootElement();
        Namespace ns = root.getNamespace();
        List<String> dates = new ArrayList<>();

        if ("feed".equals(root.getName())) { // Atom
            for (Element entry : root.getChildren("entry", ns)) {
                String published = entry.getChildText("published", ns);
                dates.add(published != null ? published : entry.getChildText("updated", ns));
            }
            return dates;
        }

        Element channel = root.getChild("channel", ns); // RSS 2.0
        if (channel == null) {
            return List.of();
        }
        for (Element item : channel.getChildren("item", ns)) {
            String pubDate = item.getChildText("pubDate", ns);
            dates.add(pubDate != null ? pubDate : item.getChildText("date", DC));
        }
        return dates;
    }

    /**
     * Romes eigene XXE-Härtung greift nur, wenn Rome selbst parst ({@code build(Reader)}). Wir bauen
     * das Document hier selbst — also müssen Doctypes und externe Entities selbst abgestellt werden.
     */
    private static SAXBuilder hardenedBuilder() {
        SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
        builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
        builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        builder.setExpandEntities(false);
        return builder;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
