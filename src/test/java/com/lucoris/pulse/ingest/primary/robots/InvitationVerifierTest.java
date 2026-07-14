package com.lucoris.pulse.ingest.primary.robots;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reiner Unit-Test der Einladungs-Re-Validierung — ohne Spring, Netz oder DB.
 *
 * <p>Der Kern ist die Unterscheidung zwischen „widerlegt" und „nicht nachprüfbar". Beides als
 * Fehlschlag zu behandeln wäre bequem, aber falsch: eine bot-blockierte Seite als „veraltet" zu
 * führen wäre eine Falschaussage im Audit-Trail.
 */
class InvitationVerifierTest {

    private static final String WORDING =
            "Kopieren Sie den Link der RSS-Datei, diese kopierte Adresse können Sie dann in Ihren "
                    + "RSS-Reader einfügen.";

    private static final ExpressInvitation EINLADUNG = new ExpressInvitation(
            "https://bmf.example/Web/DE/Service/Abonnements/Rss/rss.html", WORDING, "2026-07-13", null);

    private static InvitationVerifier verifierDer(int status, String body) {
        return verifierDer(status, body, Map.of());
    }

    private static InvitationVerifier verifierDer(
            int status, String body, Map<String, List<String>> headers) {
        return new InvitationVerifier(url -> new PolicyFetcher.Response(status, body, headers));
    }

    private static InvitationVerifier.Result pruefe(InvitationVerifier verifier) {
        return verifier.verify("bmf-presse", EINLADUNG);
    }

    @Test
    void wordingOnThePageConfirmsTheInvitation() {
        InvitationVerifier.Result r = pruefe(verifierDer(200,
                "<html><body><h1>RSS</h1><p>" + WORDING + "</p></body></html>"));

        assertThat(r.verdict()).isEqualTo(RobotsGate.Verdict.ALLOW_BY_INVITATION);
    }

    @Test
    void readablePageWithoutTheWordingIsStaleEvidence() {
        // Der ernste Fall: die Registry behauptet etwas, das nicht (mehr) dasteht.
        InvitationVerifier.Result r = pruefe(verifierDer(200,
                "<html><body><p>Wir bieten keine Feeds mehr an.</p></body></html>"));

        assertThat(r.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED_STALE_INVITATION);
        assertThat(r.detail()).contains("wording");
    }

    @ParameterizedTest(name = "HTTP {0} -> unverifizierbar, NICHT veraltet")
    @ValueSource(ints = {302, 403, 401, 503, PolicyFetcher.Response.NETWORK_ERROR})
    void anUnreadablePageIsUnverifiableNotStale(int status) {
        // GENAU der BMF-Fall: Radware antwortet Bots mit 302 auf validate.perfdrive.com. Wir haben
        // die Seite nicht gesehen — das ist kein Beweis dafür, dass sich das wording geändert hat.
        InvitationVerifier.Result r = pruefe(verifierDer(status, ""));

        assertThat(r.verdict()).isEqualTo(RobotsGate.Verdict.INVITATION_UNVERIFIABLE);
        assertThat(r.verdict()).isNotEqualTo(RobotsGate.Verdict.BLOCKED_STALE_INVITATION);
    }

    @Test
    void whitespaceInHtmlDoesNotBreakTheMatch() {
        // Die eigentliche Falle sind nicht die Wörter, sondern die Zwischenräume: HTML bricht Zeilen
        // um, setzt &nbsp; (U+00A0) und schmale Leerzeichen (U+202F).
        String zerpflueckt = "<p>Kopieren Sie den Link\n  der RSS-Datei,\tdiese kopierte Adresse\n"
                + "        können Sie dann in Ihren RSS-Reader einfügen.</p>";

        InvitationVerifier.Result r = pruefe(verifierDer(200, zerpflueckt));

        assertThat(r.verdict()).isEqualTo(RobotsGate.Verdict.ALLOW_BY_INVITATION);
    }

    @Test
    void aTdmMetaTagOnTheInvitationPageBlocksEverything() {
        // Widerspricht sich die Seite selbst — lädt zum Abonnieren ein UND erklärt einen
        // TDM-Vorbehalt —, gewinnt der Vorbehalt.
        InvitationVerifier.Result r = pruefe(verifierDer(200,
                "<html><head><meta name=\"tdm-reservation\" content=\"1\"></head>"
                        + "<body><p>" + WORDING + "</p></body></html>"));

        assertThat(r.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
        assertThat(r.detail()).contains("TDM-Vorbehalt");
    }

    @Test
    void aTdmHeaderOnTheInvitationPageBlocksEverything() {
        InvitationVerifier.Result r = pruefe(verifierDer(200,
                "<html><body><p>" + WORDING + "</p></body></html>",
                Map.of("TDM-Reservation", List.of("1"))));

        assertThat(r.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
    }

    @Test
    void anIncompleteInvitationNeedsNoPageFetchAtAll() {
        InvitationVerifier verifier = new InvitationVerifier(url -> {
            throw new AssertionError("Bei unvollständiger Evidenz darf gar nichts geholt werden: " + url);
        });

        InvitationVerifier.Result r = verifier.verify("x",
                new ExpressInvitation("https://example.org/x", null, "2026-07-13", null));

        assertThat(r.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED_STALE_INVITATION);
    }

    @Test
    void noInvitationIsHandledGracefully() {
        assertThat(pruefeOhneEinladung().verdict())
                .isEqualTo(RobotsGate.Verdict.BLOCKED_STALE_INVITATION);
    }

    private static InvitationVerifier.Result pruefeOhneEinladung() {
        InvitationVerifier verifier =
                new InvitationVerifier(url -> new PolicyFetcher.Response(200, ""));
        return verifier.verify("x", null);
    }

    /** Nur damit der Import von URI nicht ungenutzt ist — die Fakes bekommen die URL herein. */
    @Test
    void theVerifierAsksForThePageUrlFromTheRegistry() {
        java.util.List<URI> gefragt = new java.util.ArrayList<>();
        InvitationVerifier verifier = new InvitationVerifier(url -> {
            gefragt.add(url);
            return new PolicyFetcher.Response(200, WORDING);
        });

        verifier.verify("bmf-presse", EINLADUNG);

        assertThat(gefragt).containsExactly(URI.create(EINLADUNG.pageUrl()));
    }
}
