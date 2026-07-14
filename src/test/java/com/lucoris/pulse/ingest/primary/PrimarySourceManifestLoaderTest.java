package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reiner Unit-Test des Manifest-Loaders — ohne Spring, Netz oder DB. Gelesen wird bewusst die ECHTE
 * Registry aus {@code src/main/resources} (im Test-Classpath), nicht eine Fixture-Kopie: so bricht
 * dieser Test, sobald jemand die Registry unvereinbar ändert.
 */
class PrimarySourceManifestLoaderTest {

    private static final String MANIFEST = "primary-sources/lucoris-pulse-primary-sources.json";

    private final PrimarySourceManifestLoader loader =
            new PrimarySourceManifestLoader(JsonMapper.builder().build(), MANIFEST);

    @Test
    void manifestLoadsAndExposesExactlyTheTwoEnabledSources() {
        Manifest manifest = loader.load();

        assertThat(manifest.version()).isNotBlank();
        assertThat(manifest.ingestSources()).isNotEmpty();

        assertThat(loader.enabledSources())
                .extracting(IngestSource::id)
                .containsExactly("ecb-press", "fed-monetary");
    }

    @Test
    void ecbPressIsMappedCompletelyIncludingTheAttributionBlock() {
        IngestSource ecb = source("ecb-press");

        assertThat(ecb.institution()).isEqualTo("European Central Bank (EZB)");
        assertThat(ecb.category()).isEqualTo("central_bank");
        assertThat(ecb.region()).isEqualTo("EA");
        assertThat(ecb.tier()).isEqualTo(1);
        assertThat(ecb.originates()).contains("monetary_policy_decision", "speech");
        assertThat(ecb.handler()).isEqualTo("generic_rss");
        assertThat(ecb.enabled()).isTrue();
        assertThat(ecb.confidence()).isEqualTo("verified");
        assertThat(ecb.notes()).isNotBlank();

        // snake_case -> camelCase (explizites @JsonProperty, keine globale Naming-Strategie).
        assertThat(ecb.legalClass()).isEqualTo("A");

        assertThat(ecb.access().type()).isEqualTo("rss");
        assertThat(ecb.access().format()).isEqualTo("rss2.0");
        assertThat(ecb.access().url()).isEqualTo("https://www.ecb.europa.eu/rss/press.xml");

        assertThat(ecb.poll().mode()).isEqualTo("interval");
        assertThat(ecb.poll().seconds()).isEqualTo(300);
        assertThat(ecb.poll().ref()).isNull();

        assertThat(ecb.attribution()).isNotNull();
        assertThat(ecb.attribution().required()).isTrue();
        assertThat(ecb.attribution().formula()).isEqualTo("Quelle: Europaeische Zentralbank, [Titel/Datum]");
        assertThat(ecb.attribution().modifiedNote()).isFalse();
    }

    @Test
    void fedMonetaryHasNoAttributionBlockAndDeserialisesToNull() {
        // Regressionsschutz: ein Kompakt-Konstruktor mit Null-Check auf attribution würde genau
        // diese Quelle beim Deserialisieren sprengen.
        IngestSource fed = source("fed-monetary");

        assertThat(fed.enabled()).isTrue();
        assertThat(fed.legalClass()).isEqualTo("A");
        assertThat(fed.access().url()).isEqualTo("https://www.federalreserve.gov/feeds/press_monetary.xml");
        assertThat(fed.attribution()).isNull();
    }

    @Test
    void destatisAttributionCarriesTheModifiedNoteFlag() {
        // dl-de/by-2.0: bei Bearbeitung ist ein Veränderungshinweis Pflicht — modified_note = true.
        Attribution destatis = source("destatis-press").attribution();

        assertThat(destatis).isNotNull();
        assertThat(destatis.required()).isTrue();
        assertThat(destatis.modifiedNote()).isTrue();
    }

    @Test
    void calendarSourceHasRefButNoIntervalSeconds() {
        // seconds ist Integer, nicht int: das fehlende Feld muss null bleiben und darf nicht
        // stillschweigend zu 0 ("Intervall 0 Sekunden") werden.
        Poll poll = source("us-bls").poll();

        assertThat(poll.mode()).isEqualTo("calendar");
        assertThat(poll.seconds()).isNull();
        assertThat(poll.ref()).isEqualTo("https://www.bls.gov/schedule/news_release/");
    }

    @Test
    void unknownTopLevelBlocksAndFieldsAreIgnored() {
        // Die Registry enthält schema, relevance_gate, verification_lookup, blocked, planned und
        // fallback_for_uncovered. Keiner dieser Blöcke wird gemappt — sie dürfen nicht brechen.
        // Zusätzlich ein unbekanntes Feld INNERHALB einer Quelle.
        String json = """
                {
                  "version": "test",
                  "schema": { "foo": "bar" },
                  "relevance_gate": { "v1_defaults_to_calibrate": { "T_domains": 25 } },
                  "verification_lookup": [ { "id": "x" } ],
                  "blocked": [ { "id": "y", "reason": "..." } ],
                  "planned": [ { "id": "z", "expected": "2027-07-10" } ],
                  "fallback_for_uncovered": { "id": "w" },
                  "voellig_unbekannt": [1, 2, 3],
                  "ingest_sources": [
                    {
                      "id": "a", "institution": "I", "category": "c", "region": "r", "tier": 1,
                      "originates": ["x"],
                      "access": { "type": "rss", "url": "https://example.org/f.xml", "format": "rss2.0" },
                      "handler": "generic_rss", "poll": { "mode": "interval", "seconds": 60 },
                      "enabled": true, "confidence": "verified", "legal_class": "A",
                      "kuenftiges_feld": "egal"
                    }
                  ]
                }
                """;

        Manifest manifest = JsonMapper.builder().build().readValue(json, Manifest.class);

        assertThat(manifest.version()).isEqualTo("test");
        assertThat(manifest.ingestSources()).singleElement()
                .satisfies(s -> {
                    assertThat(s.id()).isEqualTo("a");
                    assertThat(s.legalClass()).isEqualTo("A");
                    assertThat(s.attribution()).isNull();
                });
    }

    @Test
    void missingManifestFailsLoudly() {
        PrimarySourceManifestLoader missing =
                new PrimarySourceManifestLoader(JsonMapper.builder().build(), "primary-sources/gibt-es-nicht.json");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(missing::load)
                .withMessageContaining("gibt-es-nicht.json");
    }

    private IngestSource source(String id) {
        List<IngestSource> sources = loader.load().ingestSources();
        return sources.stream()
                .filter(s -> id.equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quelle fehlt in der Registry: " + id));
    }
}
