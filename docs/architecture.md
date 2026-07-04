# Architektur — lucoris-pulse

## Rolle
`lucoris-pulse` ist ein eigenständiges Vorsystem, das Lucoris (und perspektivisch andere
Abnehmer) mit aufbereiteten News-Ereignissen versorgt. Es ersetzt teure, quellen-unvollständige
LLM-Suche (Perplexity) durch eine eigene, kontrollierte, EU-gehostete Pipeline. Der Dienst ist
bewusst von Lucoris entkoppelt und dient zugleich als Erprobungsfeld für einen hochlastfähigen
Java-Stack, der später ein Lucoris-Kern-Backend tragen könnte.

## Drei fachliche Aufgaben (bestimmen das ganze Design)
1. Welches Ereignis ist global relevant?             -> Signifikanz-Ranking (event_significance)
2. Welches ist marktrelevant UND wichtig genug für   -> theme.is_market_relevant + Signifikanz-Schwelle
   den allgemeinen Aktien-News-Feed?
3. (Fast) alle News zu einer Portfolio-Position       -> company -> organization -> article_organization
   anzeigen (max. Recall).

## Zwei komplementäre Ingest-Kanäle
- GDELT als "Router": Ereignis-Erkennung, Signifikanz, Quellen-Cluster, Themen-/Company-Bezug.
  GDELT liefert Metadaten + Quell-URLs, NICHT den Artikel-Volltext.
- Primäre Feeds als eigene Spur: Institutionen (Bundesbank, EZB, BaFin, Destatis), Pflicht-
  meldungen (Ad-hoc/DGAP/EQS), IR. Diese sind autoritativ, fast immer erlaubt und oft die
  Originalquelle. GDELT indexiert sie nicht zuverlässig -> eigener Abruf.
- Verknüpfung beider über das Ereignis; KI-Text stützt sich auf die (Primär-)Quelle, nie auf
  GDELTs Kodierung allein (Halluzinationsrisiko).

## Pipeline
poll `lastupdate.txt` (15 Min) -> Slice laden/entpacken/tab-parsen -> auf Wirtschaft/Politik
filtern (GKG-Themen) -> Entitäten auflösen -> normalisiert speichern -> Signifikanz aktualisieren
-> über REST (schlanke DTO-Projektionen) an Lucoris ausliefern.

## Deployment & Betrieb
- Ein Deployment (kleines System). Ingest hinter Profil `ingest`, REST-API immer aktiv.
  Packages trennbar gehalten -> spätere Auftrennung ist eine Deployment-, keine Umbau-Entscheidung.
- OCI-Image (Temurin-JRE, per Buildpacks). Läuft identisch auf IONOS-VPS-Docker und AWS Fargate.
- Start/Stop/Restart übernimmt die Plattform (systemd bzw. Fargate); Health über Actuator
  Liveness/Readiness; Monitoring über Micrometer (-> Prometheus/Grafana bzw. CloudWatch).

## Latenz-Prinzip (kritisch)
Backend und PostgreSQL MÜSSEN beim selben Anbieter, in derselben Region/AZ, über ein privates
Netz laufen — nie Cross-Cloud. Request-Latenz = RTT/Hop x Anzahl Round-Trips; deshalb zusätzlich
Round-Trips in Hibernate minimieren (Batch-Fetch, gezielte Joins, DTO-Projektionen, kein N+1)
und HikariCP als persistenten Pool nutzen.

## Empfohlene Hosting-Optionen
- Primär (EU-souverän, günstig, ein Ökosystem): IONOS Cloud VPS (JVM via Docker/systemd) +
  IONOS DBaaS PostgreSQL, gleiches Rechenzentrum, privates LAN.
- Alternative (max. Skalierungsreserve): AWS single-VPC, single-AZ — ECS Fargate + RDS/Aurora.
- Frontend (später, Vite/React): IONOS Deploy Now (kann KEIN Java-Backend hosten).
