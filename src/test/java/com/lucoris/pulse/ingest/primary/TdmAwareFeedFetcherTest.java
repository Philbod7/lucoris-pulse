package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lucoris.pulse.ingest.primary.robots.SourceNotPermittedException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reiner Unit-Test des dritten Vorbehaltskanals — ohne Spring, Netz oder DB.
 *
 * <p>robots.txt und tdmrep.json prüft das Gate VOR dem Abruf. Der {@code TDM-Reservation}-Header ist
 * dagegen eine Aussage über die ausgelieferte Ressource und steht erst in der Antwort — er kann nur
 * hier greifen, und er muss greifen, bevor der Parser die Bytes sieht.
 */
class TdmAwareFeedFetcherTest {

    private static final URI FEED = URI.create("https://example.org/rss.xml");
    private static final byte[] BODY = "<rss/>".getBytes(StandardCharsets.UTF_8);

    private static FeedFetcher liefert(Map<String, List<String>> headers) {
        return url -> Optional.of(new FeedFetcher.FeedResponse(BODY, headers));
    }

    @Test
    void aReservedResponseIsDiscardedBeforeAnyoneSeesTheBytes() {
        FeedFetcher fetcher = new TdmAwareFeedFetcher(
                liefert(Map.of(
                        "TDM-Reservation", List.of("1"),
                        "TDM-Policy", List.of("https://example.org/tdm-policy"))));

        assertThatExceptionOfType(SourceNotPermittedException.class)
                .isThrownBy(() -> fetcher.fetch(FEED))
                .withMessageContaining("TDM-Vorbehalt")
                .withMessageContaining("https://example.org/tdm-policy");
    }

    @ParameterizedTest(name = "Header-Schreibweise ''{0}'' wird erkannt")
    @ValueSource(strings = {"TDM-Reservation", "tdm-reservation", "TDM-RESERVATION", "Tdm-Reservation"})
    void headerNamesAreCaseInsensitive(String name) {
        // HTTP-Header-Namen sind case-insensitiv (RFC 9110) — der Server schreibt sie, wie er mag.
        FeedFetcher fetcher = new TdmAwareFeedFetcher(liefert(Map.of(name, List.of("1"))));

        assertThatExceptionOfType(SourceNotPermittedException.class)
                .isThrownBy(() -> fetcher.fetch(FEED));
    }

    @Test
    void reservationZeroMeansExplicitlyNoReservation() {
        // "0" ist eine ausdrückliche Freigabe, kein Vorbehalt — sie darf den Abruf nicht sperren.
        FeedFetcher fetcher =
                new TdmAwareFeedFetcher(liefert(Map.of("tdm-reservation", List.of("0"))));

        assertThat(fetcher.fetch(FEED)).isPresent();
    }

    @Test
    void withoutTheHeaderTheBytesPassThroughUnchanged() {
        // Der Normalfall — und der reale BMF-Fall: der Feed trägt keinen TDM-Header.
        FeedFetcher fetcher = new TdmAwareFeedFetcher(liefert(Map.of()));

        assertThat(fetcher.fetch(FEED)).get()
                .extracting(FeedFetcher.FeedResponse::body)
                .isEqualTo(BODY);
    }

    @Test
    void anUnreachableFeedStaysEmptyInsteadOfThrowing() {
        FeedFetcher fetcher = new TdmAwareFeedFetcher(url -> Optional.empty());

        assertThat(fetcher.fetch(FEED)).isEmpty();
    }
}
