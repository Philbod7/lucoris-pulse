package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Reiner Unit-Test der Schlüsselberechnung — keine Abhängigkeiten, keine Seiteneffekte. */
class DedupKeysTest {

    private static final Instant WANN = Instant.parse("2026-07-13T10:00:00Z");

    @Test
    void urlFoermigeGuidGewinnt() {
        // Der BMF-Fall: guid = Artikel-URL. Zwei überlappende Feeds liefern dieselbe guid,
        // aber unterschiedliche Links (Tracking) — der Schlüssel muss identisch sein.
        FeedItem ausPresse = item("bmf-presse",
                "https://www.bmf.de/artikel.html?utm_source=rss_presse",
                "https://www.bmf.de/artikel.html");
        FeedItem ausFinanzmarkt = item("bmf-finanzmarkt",
                "https://www.bmf.de/artikel.html?utm_source=rss_finanzmarkt",
                "https://www.bmf.de/artikel.html");

        assertThat(DedupKeys.keyFor(ausPresse))
                .isEqualTo(DedupKeys.keyFor(ausFinanzmarkt))
                .isEqualTo("https://www.bmf.de/artikel.html");
    }

    @Test
    void opakeGuidFaelltAufDenLinkZurueck() {
        // "12345" könnte bei zwei Herausgebern gleichzeitig vorkommen — nie roh verwenden.
        FeedItem item = item("fed-monetary", "https://www.fed.gov/pr/2026-07.htm", "12345");

        assertThat(DedupKeys.keyFor(item)).isEqualTo("https://www.fed.gov/pr/2026-07.htm");
    }

    @Test
    void tagUriGuidFaelltAufDenLinkZurueck() {
        FeedItem item = item("ecb-press", "https://www.ecb.europa.eu/pr.html",
                "tag:ecb.europa.eu,2026:pr260713");

        assertThat(DedupKeys.keyFor(item)).isEqualTo("https://www.ecb.europa.eu/pr.html");
    }

    @Test
    void fehlendeGuidFaelltAufDenLinkZurueck() {
        FeedItem item = item("ecb-press", "https://www.ecb.europa.eu/pr.html", null);

        assertThat(DedupKeys.keyFor(item)).isEqualTo("https://www.ecb.europa.eu/pr.html");
    }

    @Test
    void trackingParameterWerdenEntferntUebrigeBleibenInReihenfolge() {
        assertThat(DedupKeys.normalizeUrl(
                "https://ex.org/a?utm_source=rss&id=7&fbclid=x&lang=de&utm_campaign=y"))
                .isEqualTo("https://ex.org/a?id=7&lang=de");
    }

    @Test
    void nurTrackingParameterErgibtKeineQuery() {
        assertThat(DedupKeys.normalizeUrl("https://ex.org/a?utm_source=rss&gclid=z"))
                .isEqualTo("https://ex.org/a");
    }

    @Test
    void fragmentSchemeHostUndDefaultPortWerdenNormalisiert() {
        assertThat(DedupKeys.normalizeUrl("HTTPS://Www.Ex.Org:443/Pfad/Artikel.html#abschnitt"))
                .isEqualTo("https://www.ex.org/Pfad/Artikel.html");
    }

    @Test
    void expliziterAndererPortBleibt() {
        assertThat(DedupKeys.normalizeUrl("https://ex.org:8443/a"))
                .isEqualTo("https://ex.org:8443/a");
    }

    @Test
    void pfadUndUebrigeQuerySemantikBleibenUnangetastet() {
        // Kein Lowercase des Pfads, kein Umsortieren der Parameter, kein Slash-Anhängen.
        assertThat(DedupKeys.normalizeUrl("https://ex.org/A/B?z=1&a=2"))
                .isEqualTo("https://ex.org/A/B?z=1&a=2");
    }

    @Test
    void unparsebareUrlWirdGetrimmtRohVerwendetUndWirftNie() {
        assertThat(DedupKeys.normalizeUrl("  ht!tp://kaputt spaces  ")).isEqualTo("ht!tp://kaputt spaces");
    }

    @Test
    void relativeUrlBleibtRoh() {
        // Ohne Host keine Normalform — roh verwenden statt raten.
        assertThat(DedupKeys.normalizeUrl("/nur/pfad.html")).isEqualTo("/nur/pfad.html");
    }

    @Test
    void einVomAdapterErklaerterSchluesselSchlaegtGuidUndLink() {
        // Der EDGAR-Fall: dieselbe Einreichung ist unter JEDER beteiligten CIK erreichbar, also über
        // mehrere gültige Permalinks. Der Link ist dort kein Schlüssel, sondern Darstellung — der
        // Adapter kennt die kanonische Kennung (die Accession) und erklärt sie selbst.
        FeedItem ueberHolding = itemMitSchluessel("sec-edgar",
                "https://www.sec.gov/Archives/edgar/data/100517/000010051726000135/0000100517-26-000135-index.htm",
                "0000100517-26-000135", "sec-edgar:accession:0000100517-26-000135");
        FeedItem ueberTochter = itemMitSchluessel("sec-edgar-daily-index",
                "https://www.sec.gov/Archives/edgar/data/319687/000010051726000135/0000100517-26-000135-index.htm",
                "0000100517-26-000135", "sec-edgar:accession:0000100517-26-000135");

        assertThat(DedupKeys.keyFor(ueberHolding))
                .isEqualTo(DedupKeys.keyFor(ueberTochter))
                .isEqualTo("sec-edgar:accession:0000100517-26-000135");
    }

    @Test
    void einLeererErklaerterSchluesselFaelltAufDieGenerischeRegelZurueck() {
        // Kein Schlüssel ist besser als ein leerer: sonst würde eine schludrige Quelle alle ihre
        // Meldungen auf denselben Schlüssel werfen und still gegenseitig verdrängen.
        FeedItem leer = itemMitSchluessel("irgendwas", "https://example.org/a.html", "12345", "   ");
        FeedItem fehlt = itemMitSchluessel("irgendwas", "https://example.org/a.html", "12345", null);

        assertThat(DedupKeys.keyFor(leer)).isEqualTo("https://example.org/a.html");
        assertThat(DedupKeys.keyFor(fehlt)).isEqualTo("https://example.org/a.html");
    }

    private static FeedItem item(String sourceId, String url, String guid) {
        return new FeedItem(sourceId, "Institution", "Titel", url, guid, WANN,
                null, "de", WANN, "A", null);
    }

    private static FeedItem itemMitSchluessel(String sourceId, String url, String guid, String dedupKey) {
        return new FeedItem(sourceId, "Institution", "Titel", url, guid, WANN,
                null, "de", WANN, "A", null, dedupKey);
    }
}
