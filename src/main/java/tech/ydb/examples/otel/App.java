package tech.ydb.examples.otel;

import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.ydb.auth.NopAuthProvider;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.opentelemetry.OpenTelemetryMeter;
import tech.ydb.opentelemetry.OpenTelemetryTracer;
import tech.ydb.query.QueryClient;

/**
 * Точка входа демо-приложения. Подключается к YDB, инициализирует схему университетской БД,
 * заполняет начальными данными и запускает генератор нагрузки.
 *
 * <p>Аргументы командной строки (опциональны):
 * <ol>
 *   <li>connectionString — строка подключения к YDB (по умолчанию grpc://localhost:2136/?database=/local)</li>
 *   <li>otlpEndpoint — адрес OTel Collector (по умолчанию http://localhost:4317)</li>
 * </ol>
 */
public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        String connectionString = (args != null && args.length > 0) ? args[0] : "grpc://localhost:2136/?database=/local";
        String otlpEndpoint = (args != null && args.length > 1) ? args[1] : "http://localhost:4317";

        String host = extractHost(connectionString);
        int port = extractPort(connectionString);
        String database = extractDatabase(connectionString);

        log.info("Connecting to YDB: {}", connectionString);
        log.info("Sending telemetry to: {}", otlpEndpoint);

        OpenTelemetry openTelemetry = OpenTelemetrySetup.create(otlpEndpoint, "ydb-java-otel-examples");

        try (GrpcTransport transport = GrpcTransport.forConnectionString(connectionString)
                .withAuthProvider(NopAuthProvider.INSTANCE)
                .withMeter(OpenTelemetryMeter.fromOpenTelemetry(openTelemetry, database, host, port))
                .withTracer(OpenTelemetryTracer.fromOpenTelemetry(openTelemetry))
                .build(); QueryClient client = QueryClient.newClient(transport).build()) {
            var db = new DatabaseUtil();
            db.createTables(client);
            db.upsertTablesData(client);

            new LoadGenerator(client).run();
        }
    }

    private static String extractHost(String connectionString) {
        String s = connectionString.replaceFirst("grpcs?://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        return colon >= 0 ? s.substring(0, colon) : s;
    }

    private static int extractPort(String connectionString) {
        String s = connectionString.replaceFirst("grpcs?://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            try {
                return Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // No operations.
            }
        }
        return 2136;
    }

    private static String extractDatabase(String connectionString) {
        int idx = connectionString.indexOf("database=");
        if (idx >= 0) {
            String rest = connectionString.substring(idx + "database=".length());
            int amp = rest.indexOf('&');
            return amp >= 0 ? rest.substring(0, amp) : rest;
        }
        int slash = connectionString.indexOf("/?");
        return slash >= 0 ? connectionString.substring(slash + 1) : "/local";
    }
}
