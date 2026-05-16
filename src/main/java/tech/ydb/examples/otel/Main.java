package tech.ydb.examples.otel;

import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.core.metrics.OpenTelemetryMeter;
import tech.ydb.core.tracing.OpenTelemetryTracer;
import tech.ydb.query.QueryClient;
import tech.ydb.query.tools.SessionRetryContext;


/**
 * Точка входа приложения-примера интеграции YDB Java SDK с OpenTelemetry.
 *
 * <p>При старте:
 * <ol>
 *   <li>Инициализирует OpenTelemetry SDK через {@link OpenTelemetrySetup} (трейсы + метрики → OTLP).</li>
 *   <li>Создаёт {@link GrpcTransport} с {@link OpenTelemetryTracer} и {@link QueryClient}
 *       с {@link OpenTelemetryMeter}.</li>
 *   <li>Запускает {@link YandexDatabaseUtils#generateLoad()} — бесконечный цикл операций,
 *       порождающий трейсы и метрики.</li>
 * </ol>
 *
 * <p>Аргументы командной строки (необязательны):
 * <pre>
 *   args[0] — строка подключения к YDB, по умолчанию {@code grpc://localhost:2136/?database=/local}
 *   args[1] — OTLP-эндпоинт,по умолчанию {@code http://localhost:4317}
 * </pre>
 */
public class Main implements AutoCloseable, Runnable {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String BASE_OTLP_ENDPOINT = "http://localhost:4317";
    private static final String BASE_YDB_CONNECTION_STRING = "grpc://localhost:2136/?database=/local";

    private final GrpcTransport transport;
    private final QueryClient queryClient;
    private final SessionRetryContext retryCtx;

    private final OpenTelemetryMeter ydbMeter;
    private final OpenTelemetryTracer ydbTracer;

    public Main(String connectionString, String otlpEndpoint) {
        OpenTelemetry openTelemetry = OpenTelemetrySetup.create(otlpEndpoint, "ydb-java-otel-examples");

        this.ydbTracer = OpenTelemetryTracer.fromOpenTelemetry(openTelemetry);
        this.ydbMeter = OpenTelemetryMeter.fromOpenTelemetry(openTelemetry, parseDatabase(connectionString),
                parseHost(connectionString));

        this.transport = GrpcTransport.forConnectionString(connectionString).withTracer(ydbTracer).build();
        this.queryClient = QueryClient.newClient(transport).withMeter(ydbMeter).build();
        this.retryCtx = SessionRetryContext.create(queryClient).withMeter(ydbMeter).build();
    }

    private static String parseHost(String connectionString) {
        String s = connectionString.replaceFirst("grpcs?://", "");
        int slash = s.indexOf('/');

        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');

        return colon >= 0 ? s.substring(0, colon) : s;
    }

    private static String parseDatabase(String connectionString) {
        int idx = connectionString.indexOf("database=");

        if (idx >= 0) {
            String rest = connectionString.substring(idx + "database=".length());
            int amp = rest.indexOf('&');
            return amp >= 0 ? rest.substring(0, amp) : rest;
        }

        int slash = connectionString.indexOf("/?");

        return slash >= 0 ? connectionString.substring(slash + 1) : "/local";
    }

    @Override
    public void close() throws Exception {
        queryClient.close();
        transport.close();
    }

    @Override
    public void run() {
        var ydb = new YandexDatabaseUtils(queryClient, retryCtx);

        ydb.createTables();
        ydb.upsertTablesData();
        try {
            ydb.generateLoad();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        final String connectionString = (args != null && args.length > 0) ? args[0] : BASE_YDB_CONNECTION_STRING;
        final String otlpEndpoint = (args != null && args.length > 1) ? args[1] : BASE_OTLP_ENDPOINT;

        try (Main app = new Main(connectionString, otlpEndpoint)) {
            app.run();
        } catch (Exception e) {
            log.error("app problem", e);
        }
    }
}
