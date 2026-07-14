package com.lucoris.pulse.ingest.primary.robots;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Der TDM-Vorbehalt einer Domain aus {@code /.well-known/tdmrep.json} (TDM Reservation Protocol) —
 * reines POJO, kein Netz.
 *
 * <p>Das ist der zweite Vorbehaltskanal neben robots.txt. Format: eine Liste von Einträgen mit
 * {@code location} (Pfad-Präfix), {@code tdm-reservation} ({@code 1} = vorbehalten) und optionaler
 * {@code tdm-policy}. Der längste passende {@code location}-Präfix gewinnt.
 */
public final class TdmReservation {

    /** Ein Eintrag aus tdmrep.json. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String location,
            @JsonProperty("tdm-reservation") int reservation,
            @JsonProperty("tdm-policy") String policy) {}

    private static final TypeReference<List<Entry>> ENTRY_LIST = new TypeReference<>() {};

    private final List<Entry> entries;

    private TdmReservation(List<Entry> entries) {
        this.entries = entries;
    }

    /** Keine tdmrep.json vorhanden = kein erklärter Vorbehalt über diesen Kanal. */
    public static TdmReservation none() {
        return new TdmReservation(List.of());
    }

    /** Unlesbares/kaputtes JSON wird wie „kein Eintrag" behandelt — robots.txt bleibt maßgeblich. */
    public static TdmReservation parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return none();
        }
        try {
            List<Entry> entries = mapper.readValue(json, ENTRY_LIST);
            return new TdmReservation(entries == null ? List.of() : entries);
        } catch (RuntimeException e) { // Jackson 3 wirft unchecked
            return none();
        }
    }

    /** Ist für diesen Pfad ein Vorbehalt erklärt? Längster passender {@code location}-Präfix gewinnt. */
    public boolean isReservedFor(String path) {
        Entry best = null;
        for (Entry entry : entries) {
            String location = entry.location();
            if (location == null || !path.startsWith(location)) {
                continue;
            }
            if (best == null || location.length() > best.location().length()) {
                best = entry;
            }
        }
        return best != null && best.reservation() >= 1;
    }

    /** Die zum Pfad passende Policy-URL, falls angegeben — für die Beweislast-Zeile. */
    public String policyFor(String path) {
        Entry best = null;
        for (Entry entry : entries) {
            String location = entry.location();
            if (location == null || !path.startsWith(location)) {
                continue;
            }
            if (best == null || location.length() > best.location().length()) {
                best = entry;
            }
        }
        return best == null ? null : best.policy();
    }
}
