package com.lucoris.pulse;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Basisklasse aller Integrationstests: bootet den Spring-Context gegen ein echtes PostgreSQL
 * (Testcontainers, {@code postgres:17}), kein H2 und kein DB-Mocking.
 *
 * <p>Der Container ist {@code static} und wird als Singleton über alle erbenden Testklassen
 * geteilt (einmalig gestartet, nie gestoppt — der Ryuk-Reaper räumt am Session-Ende auf).
 * {@code @ServiceConnection} verdrahtet die Spring-Datasource automatisch mit dem Container;
 * beim Context-Start läuft Flyway und danach Hibernate mit {@code ddl-auto=validate}.
 *
 * <p>Nach {@code start()} wird der gemappte Host-Port aktiv abgewartet: manche Docker-Backends
 * (z.&nbsp;B. Rancher Desktop) etablieren das Port-Forwarding auf den Host erst wenige Sekunden
 * nach Container-Start. Ohne dieses Warten verbindet sich Spring zu früh und läuft in
 * "Connection refused". Auf Standard-Docker kehrt die Prüfung sofort zurück (unschädlich).
 */
@SpringBootTest
public abstract class AbstractPostgresIT {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    static {
        POSTGRES.start();
        awaitHostPortReachable(POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), Duration.ofSeconds(60));
    }

    /** Pollt den Host-Port, bis eine TCP-Verbindung möglich ist, oder wirft nach {@code timeout}. */
    private static void awaitHostPortReachable(String host, int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                return; // Forwarding steht, DB ist vom Test-JVM aus erreichbar.
            } catch (Exception retry) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Warten auf DB-Host-Port unterbrochen", interrupted);
                }
            }
        }
        throw new IllegalStateException(
                "PostgreSQL-Host-Port %s:%d nach %s nicht erreichbar".formatted(host, port, timeout));
    }
}
