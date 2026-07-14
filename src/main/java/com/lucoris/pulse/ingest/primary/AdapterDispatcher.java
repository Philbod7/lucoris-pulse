package com.lucoris.pulse.ingest.primary;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Routet eine Quelle anhand ihres {@code handler}-Felds an den zuständigen {@link SourceAdapter} —
 * das ist der Zweck des Manifests: „der Importer liest dieses Manifest einheitlich und routet jede
 * Quelle über 'handler' an die konkrete Adapter-Klasse".
 *
 * <p>Heute ist nur {@code generic_rss} verdrahtet. Ein noch nicht gebauter Handler
 * ({@code sec_edgar}, {@code html_index}, ...) führt bewusst zu einer
 * {@link UnsupportedOperationException} statt zu einem stillen Überspringen: eine Quelle, die im
 * Manifest auf {@code enabled} steht, aber niemand abruft, wäre ein unsichtbares Datenloch.
 */
public final class AdapterDispatcher implements SourceAdapter {

    private final Map<String, SourceAdapter> adapterByHandler;

    public AdapterDispatcher(Map<String, SourceAdapter> adapterByHandler) {
        this.adapterByHandler = Map.copyOf(Objects.requireNonNull(adapterByHandler, "adapterByHandler"));
    }

    @Override
    public List<FeedItem> fetch(IngestSource source) {
        String handler = source.handler();
        SourceAdapter adapter = adapterByHandler.get(handler);
        if (adapter == null) {
            throw new UnsupportedOperationException(
                    "Handler '" + handler + "' der Quelle '" + source.id()
                            + "' ist noch nicht implementiert. Verdrahtete Handler: "
                            + new TreeSet<>(adapterByHandler.keySet()));
        }
        return adapter.fetch(source);
    }

    /** Die verdrahteten Handler-Schlüssel — für Diagnose und Log-Ausgaben. */
    public java.util.Set<String> handlers() {
        return new TreeSet<>(adapterByHandler.keySet());
    }
}
