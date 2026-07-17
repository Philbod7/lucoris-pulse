package com.lucoris.pulse.ingest.primary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ein Eintrag der kuratierten CIK-Watchlist ({@code sec-edgar-ciks.json}).
 *
 * <p>Die submissions-API von EDGAR ist per Firma adressiert — sie kennt keinen globalen Strom. Wer
 * sie abruft, muss also sagen, WESSEN Einreichungen ihn interessieren. Diese Liste ist genau das,
 * und sie ist bewusst von Hand kuratiert statt aus dem vollen Registranten-Universum (~10k Ticker)
 * abgeleitet: der Ingest soll marktrelevante Emittenten beobachten, nicht jede Briefkastenfirma.
 *
 * @param cik    CIK, 10-stellig zero-gepaddet (so verlangt es der submissions-Endpunkt im Dateinamen)
 * @param name   Klarname — nur für Lesbarkeit der Liste und Log-Ausgaben; der Anzeigename einer
 *               Meldung kommt aus der API-Antwort, nicht von hier
 * @param ticker Börsenkürzel; {@code null} möglich (nicht jeder Filer ist notiert)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecEdgarCik(String cik, String name, String ticker) {}
