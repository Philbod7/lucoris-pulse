package com.lucoris.pulse.ingest.primary.robots;

/**
 * Prüft VOR jedem Abruf, ob eine URL geholt werden darf (robots.txt + TDM-Vorbehalt).
 *
 * <p>Das ist das Sicherheitsnetz unter der kuratierten Allowlist: trägt jemand versehentlich eine
 * Quelle ein, die maschinellen Zugriff untersagt, verweigert das Gate den Abruf — statt sich auf
 * die manuelle Prüfung zu verlassen.
 */
public interface RobotsGate {

    Decision check(FetchIntent intent);

    /**
     * Der {@code Crawl-delay}, den die robots.txt dieses Hosts für uns setzt (Sekunden).
     *
     * <p>Für den {@code SourceLoadValidator}: pollen wir häufiger als der Herausgeber erlaubt,
     * verletzen wir seine Abrufgrenze — und das ausgerechnet dort, wo wir uns unter Umständen auf
     * sein Wohlwollen berufen ({@code ALLOW_BY_INVITATION}). Der peinlichste denkbare Widerspruch.
     *
     * <p>Reine Diagnose, deshalb mit Default: {@link #check} bleibt die eine Methode, die ein Gate
     * beherrschen muss (und die Test-Fakes bleiben Lambdas).
     */
    default java.util.Optional<Integer> crawlDelaySeconds(java.net.URI url) {
        return java.util.Optional.empty();
    }

    /** Der Ausgang der Prüfung. */
    enum Verdict {

        /** robots.txt erlaubt den Pfad; kein KI-/TDM-Vorbehalt. Der Normalfall. */
        ALLOWED,

        /**
         * robots.txt verbietet den Pfad — aber das treffende Muster erfasst den Feed nur beiläufig,
         * und der Herausgeber lädt ausdrücklich zum Abonnieren ein (ADR 24). Die Ausnahme, nicht die
         * Regel: jede solche Entscheidung wird auditiert.
         */
        ALLOW_BY_INVITATION,

        /** Verboten: robots.txt, benannter KI-Vorbehalt, TDM-Vorbehalt, oder fail-closed. */
        BLOCKED,

        /**
         * Es gibt eine Einladung, aber sie trägt nicht: unvollständig, unlesbares Datum, veraltet —
         * oder die Re-Validierung fand das {@code wording} auf der Seite nicht mehr (dann lügt die
         * Registry). Kein Abruf.
         */
        BLOCKED_STALE_INVITATION,

        /**
         * Nur aus dem {@code InvitationVerifier} (Profil {@code validate-sources}): die
         * Einladungsseite war nicht lesbar (Bot-Manager, 302/403), das {@code wording} konnte also
         * weder bestätigt noch widerlegt werden.
         *
         * <p>Bewusst NICHT {@link #BLOCKED_STALE_INVITATION}: „nicht gesehen" ist kein Beweis für
         * „geändert", und eine unlesbare Seite als veraltet zu führen wäre eine Falschaussage im
         * Audit-Trail. Es bleibt aber ein Warnzeichen — die Evidenz ruht dann allein auf der
         * Handaufzeichnung in der Registry.
         */
        INVITATION_UNVERIFIABLE
    }

    /**
     * @param verdict  der Ausgang
     * @param reason   Begründung im Klartext — geht so in die Beweislast-Zeile
     * @param evidence nur bei {@link Verdict#ALLOW_BY_INVITATION} gesetzt; sonst {@code null}
     */
    record Decision(Verdict verdict, String reason, InvitationEvidence evidence) {

        /** Darf abgerufen werden? Eine getragene Einladung ist eine Erlaubnis. */
        public boolean allowed() {
            return verdict == Verdict.ALLOWED || verdict == Verdict.ALLOW_BY_INVITATION;
        }

        public static Decision allow(String reason) {
            return new Decision(Verdict.ALLOWED, reason, null);
        }

        public static Decision deny(String reason) {
            return new Decision(Verdict.BLOCKED, reason, null);
        }

        public static Decision stale(String reason) {
            return new Decision(Verdict.BLOCKED_STALE_INVITATION, reason, null);
        }

        public static Decision unverifiable(String reason) {
            return new Decision(Verdict.INVITATION_UNVERIFIABLE, reason, null);
        }

        public static Decision byInvitation(String reason, InvitationEvidence evidence) {
            return new Decision(Verdict.ALLOW_BY_INVITATION, reason, evidence);
        }
    }

    /**
     * Was in die Audit-Zeile geht, wenn wir uns auf eine Einladung berufen. Alles, was ein Dritter
     * bräuchte, um die Entscheidung nachzuvollziehen — und um sie zu widerlegen.
     */
    record InvitationEvidence(
            String sourceId,
            String path,
            String pattern,
            String userAgentGroup,
            String pageUrl,
            String wording,
            String retrieved,
            String scope) {}
}
