package com.lucoris.pulse.ingest.primary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Das Routing-Manifest der Primärquellen (Wurzel-Objekt).
 *
 * <p>Es wird bewusst nur {@code ingest_sources} gemappt. Die übrigen Blöcke des Manifests
 * ({@code schema}, {@code relevance_gate}, {@code verification_lookup}, {@code blocked},
 * {@code planned}, {@code fallback_for_uncovered}) sind für diesen Ingest ohne Bedeutung und werden
 * dank {@code ignoreUnknown} folgenlos übergangen — sie dürfen die Deserialisierung nicht brechen.
 *
 * @param version       Stand des Manifests, z.B. {@code 2026-07-13c}
 * @param ingestSources die gepollten Quellen (auch die noch nicht aktivierten)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(
        String version,
        @JsonProperty("ingest_sources") List<IngestSource> ingestSources) {}
