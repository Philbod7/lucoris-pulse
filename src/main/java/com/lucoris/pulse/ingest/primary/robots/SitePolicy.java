package com.lucoris.pulse.ingest.primary.robots;

/**
 * Die Erlaubnislage einer Domain — das, was je Host gecacht wird.
 *
 * @param reachable ob die Erlaubnis überhaupt festgestellt werden konnte. {@code false} = weder
 *                  „erlaubt" noch „verboten", sondern „unbekannt" — und unbekannt heißt fail-closed
 *                  verboten.
 * @param problem   Klartext-Grund, warum die Auskunft fehlt; {@code null}, wenn sie vorliegt
 * @param robots    geparste robots.txt (bei HTTP 404: uneingeschränkt)
 * @param tdm       TDM-Vorbehalt aus tdmrep.json (fehlt die Datei: keiner)
 */
public record SitePolicy(boolean reachable, String problem, RobotsRules robots, TdmReservation tdm) {

    public static SitePolicy of(RobotsRules robots, TdmReservation tdm) {
        return new SitePolicy(true, null, robots, tdm);
    }

    /** Die Erlaubnis ließ sich nicht feststellen — jeder Abruf gegen diesen Host ist gesperrt. */
    public static SitePolicy unreachable(String problem) {
        return new SitePolicy(false, problem, RobotsRules.unrestricted(), TdmReservation.none());
    }
}
