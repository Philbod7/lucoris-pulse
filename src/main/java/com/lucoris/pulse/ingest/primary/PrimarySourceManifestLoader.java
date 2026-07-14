package com.lucoris.pulse.ingest.primary;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * Lädt das Routing-Manifest der Primärquellen vom Classpath.
 *
 * <p>POJO ohne Spring-Annotationen (verdrahtet in {@code PrimarySourcesConfig}) und mit einem
 * eigenen {@link ObjectMapper} statt Springs Bean: dessen Konfiguration (Naming-Strategie, Module,
 * Fail-on-unknown) ist über Boot-Properties fremdveränderbar, das Manifest-Parsing soll aber in
 * Test und Produktion identisch und deterministisch sein.
 *
 * <p>Das Manifest wird einmalig gelesen und gehalten — es ist eine statische Ressource im
 * Artefakt, keine Laufzeit-Konfiguration.
 */
public final class PrimarySourceManifestLoader {

    private final ObjectMapper mapper;
    private final String classpathLocation;
    private volatile Manifest cached;

    /**
     * @param mapper            Jackson-Mapper (Jackson 3: {@code tools.jackson.databind})
     * @param classpathLocation Pfad im Classpath, OHNE {@code classpath:}-Präfix und ohne
     *                          führenden Schrägstrich
     */
    public PrimarySourceManifestLoader(ObjectMapper mapper, String classpathLocation) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.classpathLocation = Objects.requireNonNull(classpathLocation, "classpathLocation");
    }

    /** Liest das Manifest (beim ersten Aufruf) und liefert es. */
    public Manifest load() {
        Manifest local = cached;
        if (local == null) {
            local = read();
            cached = local;
        }
        return local;
    }

    /** Die Quellen mit {@code enabled: true} — nur diese werden abgerufen. */
    public List<IngestSource> enabledSources() {
        return load().ingestSources().stream().filter(IngestSource::enabled).toList();
    }

    private Manifest read() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = PrimarySourceManifestLoader.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Routing-Manifest nicht im Classpath gefunden: " + classpathLocation);
            }
            // Jackson 3 wirft unchecked (JacksonException extends RuntimeException) — kein Catch nötig.
            Manifest manifest = mapper.readValue(in, Manifest.class);
            if (manifest.ingestSources() == null) {
                throw new IllegalStateException(
                        "Routing-Manifest ohne Block 'ingest_sources': " + classpathLocation);
            }
            return manifest;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Routing-Manifest nicht lesbar: " + classpathLocation, e);
        }
    }
}
