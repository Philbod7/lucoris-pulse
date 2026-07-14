package com.lucoris.pulse.ingest.primary.robots;

import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-Validierung einer {@link ExpressInvitation}: steht das in der Registry hinterlegte
 * {@code wording} noch auf der genannten Seite?
 *
 * <p>Nur im Profil {@code validate-sources}. Der Ingest-Pfad ruft die Einladungsseite bewusst NICHT
 * ab — er würde sonst bei jedem Poll eine HTML-Seite mitziehen, und ein Bot-Manager könnte ihn
 * stören. Die Evidenz ist per Konstruktion eine menschliche Feststellung; dieser Verifier ist der
 * Stolperdraht, der meldet, wenn sie nicht mehr stimmt.
 *
 * <p>Der Unterschied zwischen „widerlegt" und „nicht nachprüfbar" ist der Kern:
 * <ul>
 *   <li>Seite lesbar, {@code wording} da → {@link RobotsGate.Verdict#ALLOW_BY_INVITATION}</li>
 *   <li>Seite lesbar, {@code wording} FEHLT → {@link RobotsGate.Verdict#BLOCKED_STALE_INVITATION} —
 *       die Registry behauptet etwas, das nicht (mehr) dasteht</li>
 *   <li>Seite nicht lesbar (Bot-Manager, 302/403) → {@link RobotsGate.Verdict#INVITATION_UNVERIFIABLE}
 *       — „nicht gesehen" ist kein Beweis für „geändert"; eine unlesbare Seite als veraltet zu führen
 *       wäre eine Falschaussage im Audit-Trail</li>
 *   <li>Seite erklärt einen TDM-Vorbehalt per HTML-Meta → {@link RobotsGate.Verdict#BLOCKED}</li>
 * </ul>
 */
public final class InvitationVerifier {

    private static final Logger log = LoggerFactory.getLogger(InvitationVerifier.class);

    private final PolicyFetcher fetcher;

    public InvitationVerifier(PolicyFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /**
     * @param verdict was die Prüfung ergeben hat
     * @param detail  Klartext für den Bericht
     */
    public record Result(RobotsGate.Verdict verdict, String detail) {}

    public Result verify(String sourceId, ExpressInvitation invitation) {
        if (invitation == null || !invitation.complete()) {
            return new Result(RobotsGate.Verdict.BLOCKED_STALE_INVITATION,
                    "Einladung fehlt oder ist unvollständig");
        }

        PolicyFetcher.Response response = fetcher.get(URI.create(invitation.pageUrl()));

        if (!response.ok()) {
            // Genau der BMF-Fall: Radware antwortet Bots mit 302 auf validate.perfdrive.com.
            String grund = response.status() == PolicyFetcher.Response.NETWORK_ERROR
                    ? "nicht erreichbar"
                    : "HTTP " + response.status() + " (Bot-Manager/Weiterleitung?)";
            log.warn("INVITATION_UNVERIFIABLE {}: Einladungsseite {} {} — das wording konnte weder "
                            + "bestätigt noch widerlegt werden. Die Evidenz ruht damit allein auf der "
                            + "Handaufzeichnung vom {} in der Registry.",
                    sourceId, invitation.pageUrl(), grund, invitation.retrieved());
            return new Result(RobotsGate.Verdict.INVITATION_UNVERIFIABLE,
                    "Einladungsseite nicht lesbar (" + grund + ")");
        }

        // Der TDM-Vorbehalt kann auch auf der Einladungsseite stehen — als Header oder HTML-Meta.
        if (TdmHeader.isReserved(response.headers()) || declaresTdmMeta(response.body())) {
            log.error("VERBOTEN {}: die Einladungsseite {} erklärt selbst einen TDM-Vorbehalt",
                    sourceId, invitation.pageUrl());
            return new Result(RobotsGate.Verdict.BLOCKED,
                    "Einladungsseite erklärt einen TDM-Vorbehalt");
        }

        if (!containsWording(response.body(), invitation.wording())) {
            // Die Registry behauptet etwas, das nicht (mehr) dasteht. Das ist der ernste Fall.
            log.error("BLOCKED_STALE_INVITATION {}: das wording steht NICHT mehr auf {} — Einladung "
                            + "erneut feststellen oder Quelle abschalten",
                    sourceId, invitation.pageUrl());
            return new Result(RobotsGate.Verdict.BLOCKED_STALE_INVITATION,
                    "wording nicht mehr auf der Einladungsseite gefunden");
        }

        log.info("Einladung bestätigt {}: wording steht auf {} (festgestellt {})",
                sourceId, invitation.pageUrl(), invitation.retrieved());
        return new Result(RobotsGate.Verdict.ALLOW_BY_INVITATION, "wording bestätigt");
    }

    /**
     * Substring-Match nach Normalisierung.
     *
     * <p>Die eigentlichen Fallen sind nicht die Wörter, sondern die Zwischenräume: HTML bricht Zeilen
     * um, setzt geschützte Leerzeichen ({@code &nbsp;}, U+00A0), schmale Leerzeichen (U+202F) und
     * Tabs. Deshalb: Unicode-NFC, dann JEDE Whitespace-Klasse auf ein einzelnes Leerzeichen.
     * Anführungszeichen werden bewusst NICHT normalisiert — die gehören nicht ins {@code wording}.
     */
    private static boolean containsWording(String html, String wording) {
        return normalise(html).contains(normalise(wording));
    }

    private static String normalise(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace(' ', ' ') // geschütztes Leerzeichen
                .replace(' ', ' ') // schmales geschütztes Leerzeichen
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** {@code <meta name="tdm-reservation" content="1">} — der dritte TDM-Kanal auf HTML-Seiten. */
    private static boolean declaresTdmMeta(String html) {
        String normalisiert = html.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalisiert.matches(".*<meta[^>]*name=[\"']?tdm-reservation[\"']?[^>]*content=[\"']?[1-9].*");
    }
}
