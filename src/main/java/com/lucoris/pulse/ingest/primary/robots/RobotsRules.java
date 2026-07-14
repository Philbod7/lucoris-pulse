package com.lucoris.pulse.ingest.primary.robots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Geparste {@code robots.txt} einer Domain (RFC 9309) — reines POJO, kein Netz.
 *
 * <p>Umgesetzt: Gruppen über {@code User-agent}, {@code Allow}/{@code Disallow}, Wildcard {@code *}
 * und End-Anker {@code $}, längster Pfad-Match gewinnt, bei Gleichstand gewinnt {@code Allow}.
 * Unbekannte Direktiven ({@code Sitemap}, {@code Crawl-delay}, ...) werden ignoriert.
 */
public final class RobotsRules {

    /**
     * Die gängigen KI-/TDM-Crawler. Grundlage der KONSERVATIVEN REGEL aus {@code CLAUDE.md}: sperrt
     * eine Seite einen von ihnen für den Pfad, den wir holen wollen, gilt sie als verboten — auch
     * wenn unser eigener User-Agent nicht namentlich genannt ist. Die Namenslücke wird NICHT
     * ausgenutzt.
     */
    public static final List<String> AI_CRAWLERS = List.of(
            "gptbot", "chatgpt-user", "oai-searchbot",
            "claudebot", "anthropic-ai", "claude-web",
            "ccbot",
            "google-extended",
            "applebot-extended",
            "meta-externalagent", "facebookbot",
            "bytespider",
            "perplexitybot",
            "amazonbot",
            "cohere-ai",
            "diffbot",
            "omgili", "omgilibot",
            "timpibot",
            "img2dataset");

    /** Der Name der Fallback-Gruppe {@code User-agent: *}. */
    public static final String WILDCARD_AGENT = "*";

    /** Eine Regel: erlaubend oder verbietend, mit ihrem Pfadmuster. */
    private record Rule(boolean allow, String pattern, Pattern regex) {}

    /**
     * Die Regel, die für ein {@code (agentToken, path)}-Paar gewonnen hat.
     *
     * <p>Nicht nur „darf ich?", sondern auch „warum" — das Muster und die UA-Gruppe, aus der es
     * stammt. Beides braucht die Einladungs-Prüfung ({@code PatternScope}, Bedingungen (c) und (d))
     * und die Beweislast-Zeile.
     *
     * @param allow   erlaubt die Regel den Abruf?
     * @param pattern das Pfadmuster, wie es in der robots.txt steht (z.B. ein Wildcard-Muster auf
     *                einen ganzen Zweig) — unverändert, damit es in die Beweislast-Zeile kann
     * @param group   die UA-Gruppe, aus der die Regel stammt: {@link #WILDCARD_AGENT} oder das
     *                kleingeschriebene Produkt-Token einer benannten Gruppe
     */
    public record Match(boolean allow, String pattern, String group) {}

    private final Map<String, List<Rule>> rulesByAgent;
    private final Map<String, Integer> crawlDelayByAgent;

    private RobotsRules(Map<String, List<Rule>> rulesByAgent, Map<String, Integer> crawlDelayByAgent) {
        this.rulesByAgent = rulesByAgent;
        this.crawlDelayByAgent = crawlDelayByAgent;
    }

    /** Keine robots.txt vorhanden (HTTP 404) = keine Einschränkung. */
    public static RobotsRules unrestricted() {
        return new RobotsRules(Map.of(), Map.of());
    }

    public static RobotsRules parse(String robotsTxt) {
        if (robotsTxt == null || robotsTxt.isBlank()) {
            return unrestricted();
        }
        Map<String, List<Rule>> byAgent = new LinkedHashMap<>();
        Map<String, Integer> crawlDelays = new LinkedHashMap<>();
        List<String> currentAgents = new ArrayList<>();
        boolean collectingAgents = false;

        for (String rawLine : robotsTxt.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue; // keine Direktive
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            switch (key) {
                case "user-agent" -> {
                    // Aufeinanderfolgende User-agent-Zeilen bilden EINE Gruppe. Kam davor eine
                    // Regel, beginnt hier eine neue Gruppe.
                    if (!collectingAgents) {
                        currentAgents = new ArrayList<>();
                        collectingAgents = true;
                    }
                    if (!value.isEmpty()) {
                        currentAgents.add(value.toLowerCase(Locale.ROOT));
                    }
                }
                case "allow", "disallow" -> {
                    collectingAgents = false;
                    // "Disallow:" ohne Wert bedeutet ausdrücklich KEINE Einschränkung -> keine Regel.
                    if (value.isEmpty() || currentAgents.isEmpty()) {
                        continue;
                    }
                    Rule rule = new Rule("allow".equals(key), value, toRegex(value));
                    for (String agent : currentAgents) {
                        byAgent.computeIfAbsent(agent, a -> new ArrayList<>()).add(rule);
                    }
                }
                case "crawl-delay" -> {
                    collectingAgents = false;
                    try {
                        int sekunden = Integer.parseInt(value);
                        for (String agent : currentAgents) {
                            crawlDelays.put(agent, sekunden);
                        }
                    } catch (NumberFormatException e) {
                        // Unlesbarer Wert: ignorieren, nicht die ganze Datei verwerfen.
                    }
                }
                default -> collectingAgents = false; // Sitemap, Host, ... : ignorieren
            }
        }
        return new RobotsRules(byAgent, crawlDelays);
    }

    /**
     * Darf {@code agentToken} den {@code path} abrufen?
     *
     * @param agentToken das Produkt-Token, z.B. {@code LucorisNewsBot} (nicht der volle User-Agent)
     * @param path       Pfad inkl. Query, z.B. {@code /rss/press.xml}
     */
    public boolean allows(String agentToken, String path) {
        return match(agentToken, path).map(Match::allow).orElse(true);
    }

    /**
     * Die gewinnende Regel für {@code (agentToken, path)}, oder {@link Optional#empty()}, wenn keine
     * greift (= erlaubt).
     *
     * <p>Auswahl nach RFC 9309: eine eigene, benannte Gruppe ERSETZT die {@code *}-Gruppe (sie
     * ergänzt sie nicht). Innerhalb der Gruppe gewinnt der längste Pfad-Match; bei gleicher Länge
     * gewinnt {@code Allow}.
     */
    public Optional<Match> match(String agentToken, String path) {
        String own = agentToken.toLowerCase(Locale.ROOT);
        String group = rulesByAgent.containsKey(own) ? own : WILDCARD_AGENT;
        List<Rule> rules = rulesByAgent.get(group);
        if (rules == null || rules.isEmpty()) {
            return Optional.empty(); // keine Gruppe für uns und kein '*' -> keine Einschränkung
        }

        Rule best = null;
        for (Rule rule : rules) {
            if (!rule.regex().matcher(path).find()) {
                continue;
            }
            if (best == null
                    || rule.pattern().length() > best.pattern().length()
                    // Gleich spezifisch: Allow gewinnt (RFC 9309).
                    || (rule.pattern().length() == best.pattern().length() && rule.allow())) {
                best = rule;
            }
        }
        return best == null
                ? Optional.empty()
                : Optional.of(new Match(best.allow(), best.pattern(), group));
    }

    /** Nennt die robots.txt diesen Agenten mit einer EIGENEN Gruppe? */
    public boolean namesAgent(String agentToken) {
        return rulesByAgent.containsKey(agentToken.toLowerCase(Locale.ROOT));
    }

    /**
     * Der {@code Crawl-delay} der für uns geltenden Gruppe, in Sekunden.
     *
     * <p>Keine RFC-9309-Direktive, aber weit verbreitet — das BMF setzt 180. Wer sich auf das
     * Wohlwollen eines Herausgebers beruft (siehe {@code ExpressInvitation}) und zugleich seine
     * Abrufgrenze ignoriert, widerspricht sich selbst. Der {@code SourceLoadValidator} vergleicht
     * den Wert mit {@code poll.seconds}.
     */
    public Optional<Integer> crawlDelaySeconds(String agentToken) {
        String own = agentToken.toLowerCase(Locale.ROOT);
        String group = crawlDelayByAgent.containsKey(own) ? own : WILDCARD_AGENT;
        return Optional.ofNullable(crawlDelayByAgent.get(group));
    }

    /**
     * Die KONSERVATIVE REGEL: welche gängigen KI-Crawler sind für diesen Pfad durch eine EIGENE,
     * NAMENTLICHE Gruppe gesperrt? Nicht leer = die Seite hat einen erkennbaren KI-Vorbehalt und
     * gilt damit als verboten — auch wenn unser Token nicht genannt ist (Namenslücke nicht ausnutzen).
     *
     * <p><strong>Bewusst NICHT über den {@code *}-Fallback.</strong> Ein Disallow in der
     * {@code *}-Gruppe ist keine Aussage über KI-Crawler, sondern die allgemeine Hausordnung — und
     * die sperrt UNS bereits über {@link #allows}. Zählte man den Fallback mit, meldete jede Seite
     * mit irgendeinem {@code *}-Disallow auf unserem Pfad ALLE Crawler dieser Liste als gesperrt
     * (bundesfinanzministerium.de, 2026-07-14: 20 von 20 — obwohl die Datei keinen einzigen nennt).
     * Die Liste wäre eine Falschaussage und die Regel bloß eine zweite Zählung von {@code allows()}.
     * Ein Vorbehalt ist erst dann ein KI-Vorbehalt, wenn der Betreiber den Crawler BEIM NAMEN nennt.
     *
     * <p>Absichtlich über {@link #match} statt über {@link #allows} implementiert: über
     * {@code allows()} stimmte das Ergebnis nur zufällig, und die Regel kippte still, sobald jemand
     * die Fallback-Logik dort anfasst.
     */
    public List<String> blockedAiCrawlers(String path) {
        return AI_CRAWLERS.stream()
                .filter(crawler -> match(crawler, path)
                        .filter(m -> m.group().equals(crawler)) // aus SEINER Gruppe, nicht aus '*'
                        .filter(m -> !m.allow())
                        .isPresent())
                .toList();
    }

    /**
     * Das robots.txt-Produkt-Token aus einem vollen User-Agent: aus
     * {@code "LucorisNewsBot/1.0 (+https://...)"} wird {@code "LucorisNewsBot"}.
     */
    public static String productToken(String userAgent) {
        String token = userAgent.trim();
        int cut = token.indexOf('/');
        if (cut < 0) {
            cut = token.indexOf(' ');
        }
        return cut < 0 ? token : token.substring(0, cut);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /** Pfadmuster -> Regex. {@code *} = beliebige Folge, {@code $} am Ende = Ende des Pfads. */
    private static Pattern toRegex(String pattern) {
        String body = pattern;
        boolean anchorEnd = body.endsWith("$");
        if (anchorEnd) {
            body = body.substring(0, body.length() - 1);
        }
        StringBuilder regex = new StringBuilder("^");
        for (char c : body.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (anchorEnd) {
            regex.append('$');
        }
        return Pattern.compile(regex.toString());
    }
}
