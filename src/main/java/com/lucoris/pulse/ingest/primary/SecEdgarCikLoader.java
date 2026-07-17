package com.lucoris.pulse.ingest.primary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * Lädt die kuratierte CIK-Watchlist für den {@code sec_edgar}-Handler vom Classpath.
 *
 * <p>Baugleich zu {@link PrimarySourceManifestLoader} und aus demselben Grund mit eigenem
 * {@link ObjectMapper}: Springs Mapper-Bean ist über Boot-Properties fremdveränderbar, das Parsen
 * dieser Ressource soll in Test und Produktion identisch sein. Einmal gelesen, dann gehalten — die
 * Liste ist eine statische Ressource im Artefakt, keine Laufzeit-Konfiguration.
 *
 * <p>Ein fehlerhafter oder leerer Eintrag wird beim Laden LAUT abgelehnt, nicht still übersprungen:
 * eine Firma, die stillschweigend aus der Watchlist fällt, wäre ein unsichtbares Datenloch — dieselbe
 * Haltung wie beim {@link AdapterDispatcher} gegenüber unbekannten Handlern.
 */
public final class SecEdgarCikLoader {

    private final ObjectMapper mapper;
    private final String classpathLocation;
    private volatile List<SecEdgarCik> cached;

    public SecEdgarCikLoader(ObjectMapper mapper, String classpathLocation) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.classpathLocation = Objects.requireNonNull(classpathLocation, "classpathLocation");
    }

    /** Die beobachteten Firmen (beim ersten Aufruf gelesen). */
    public List<SecEdgarCik> load() {
        List<SecEdgarCik> local = cached;
        if (local == null) {
            local = read();
            cached = local;
        }
        return local;
    }

    private List<SecEdgarCik> read() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = SecEdgarCikLoader.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException(
                        "CIK-Watchlist nicht im Classpath gefunden: " + classpathLocation);
            }
            // Jackson 3 wirft unchecked (JacksonException extends RuntimeException) — kein Catch nötig.
            CikManifest manifest = mapper.readValue(in, CikManifest.class);
            if (manifest.ciks() == null || manifest.ciks().isEmpty()) {
                throw new IllegalStateException(
                        "CIK-Watchlist ohne Block 'ciks' (oder leer): " + classpathLocation);
            }
            manifest.ciks().forEach(this::validate);
            return List.copyOf(manifest.ciks());
        } catch (IOException e) {
            throw new IllegalStateException("CIK-Watchlist nicht lesbar: " + classpathLocation, e);
        }
    }

    /**
     * Die CIK MUSS 10-stellig zero-gepaddet sein: der submissions-Endpunkt heißt wörtlich
     * {@code CIK##########.json}. Eine ungepaddete CIK ergäbe stumm einen 404 und damit eine Firma,
     * die nie Meldungen liefert — der Fehler soll beim Laden auffallen, nicht im Betrieb.
     */
    private void validate(SecEdgarCik eintrag) {
        String cik = eintrag.cik();
        if (cik == null || cik.length() != 10 || !cik.chars().allMatch(Character::isDigit)) {
            throw new IllegalStateException(
                    "CIK-Watchlist " + classpathLocation + ": '" + cik + "' (" + eintrag.name()
                            + ") ist keine 10-stellig zero-gepaddete CIK");
        }
    }

    /** Nur die Hülle der Ressourcendatei. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CikManifest(String version, String purpose, List<SecEdgarCik> ciks) {}
}
