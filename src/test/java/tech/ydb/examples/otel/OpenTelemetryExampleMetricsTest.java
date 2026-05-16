package tech.ydb.examples.otel;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.*;

import tech.ydb.auth.TokenAuthProvider;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.core.metrics.OpenTelemetryMeter;
import tech.ydb.query.QueryClient;
import tech.ydb.query.QuerySession;
import tech.ydb.query.tools.SessionRetryContext;
import tech.ydb.test.junit4.YdbHelperRule;

/**
 * Тесты, проверяющие что {@link OpenTelemetryMeter} корректно регистрирует метрики YDB SDK.
 */
public class OpenTelemetryExampleMetricsTest {

    /**
     * Все метрики, регистрируемые {@link OpenTelemetryMeter}.
     * Соответствует методам интерфейса {@link tech.ydb.core.metrics.Meter}.
     */
    public static final List<String> ALL_METRIC_NAMES = List.of(
            "ydb.client.operation.duration",
            "ydb.client.operation.failed",
            "ydb.query.session.create_time",
            "ydb.query.session.pending_requests",
            "ydb.query.session.timeouts",
            "ydb.query.session.count",
            "ydb.query.session.min",
            "ydb.query.session.max",
            "ydb.client.retry.duration",
            "ydb.client.retry.attempts"
    );

    private static final AttributeKey<String> ATTR_DATABASE = AttributeKey.stringKey("database");
    private static final AttributeKey<String> ATTR_ENDPOINT = AttributeKey.stringKey("endpoint");
    private static final AttributeKey<String> ATTR_OPERATION_NAME = AttributeKey.stringKey("operation.name");

    @ClassRule
    public static final YdbHelperRule YDB = new YdbHelperRule();

    private InMemoryMetricReader metricReader;
    private SdkMeterProvider meterProvider;
    private OpenTelemetryMeter ydbMeter;
    private GrpcTransport transport;
    private QueryClient queryClient;
    private SessionRetryContext retryCtx;
    private YandexDatabaseUtils db;

    @Before
    public void init() {
        metricReader = InMemoryMetricReader.create();
        meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();

        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();

        ydbMeter = OpenTelemetryMeter.fromOpenTelemetry(openTelemetry, YDB.database(), YDB.endpoint());

        transport = GrpcTransport.forEndpoint(YDB.endpoint(), YDB.database())
                .withAuthProvider(new TokenAuthProvider(YDB.authToken()))
                .build();

        queryClient = QueryClient.newClient(transport).withMeter(ydbMeter).build();
        retryCtx = SessionRetryContext.create(queryClient).withMeter(ydbMeter).build();

        db = new YandexDatabaseUtils(queryClient, retryCtx);
        db.createTables();
        db.upsertTablesData();
    }

    @After
    public void close() throws IOException {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            for (String table : List.of("Marks", "Plan", "Students", "Lecturers", "Courses", "Groups")) {
                session.createQuery("DROP TABLE IF EXISTS " + table, TxMode.NONE).execute().join();
            }
        }

        queryClient.close();
        transport.close();
        meterProvider.close();
        metricReader.close();
    }

    // Проверка наличия всех метрик
    @Test
    public void testAllMetricsArePresent() {
        db.getStudents();
        db.getStudentsWithRetry(1);
        db.selectBadQuery();

        try (QueryClient limitedClient = QueryClient.newClient(transport)
                .withMeter(ydbMeter)
                .sessionPoolMaxSize(1)
                .build()) {
            QuerySession held = limitedClient.createSession(Duration.ofSeconds(5)).join().getValue();
            limitedClient.createSession(Duration.ofMillis(1)).join();
            limitedClient.createSession(Duration.ofSeconds(5));
            held.close();
        }

        Set<String> names = getAllPresentMetricNames();
        Assert.assertTrue(
                "No metrics with names : " + ALL_METRIC_NAMES.stream()
                        .filter(m -> !names.contains(m))
                        .collect(Collectors.toList()) + " found",
                names.containsAll(ALL_METRIC_NAMES)
        );
    }

    // `ydb.client.operation.duration` имеет положительное значение
    @Test
    public void testOperationDurationSumIsPositive() {
        db.getStudents();
        Assert.assertTrue(getHistogramSum("ydb.client.operation.duration") > 0);
    }

    // гистограмма `ydb.client.operation.duration` растёт с каждым вызовом
    @Test
    public void testOperationDurationCountGrowsWithEachCall() {
        db.getStudents();
        long countAfterOne = findDurationMetricByOperationName("ExecuteQuery");

        db.getStudents();
        long countAfterTwo = findDurationMetricByOperationName("ExecuteQuery");

        Assert.assertTrue(countAfterTwo > countAfterOne);
    }

    // метрика-гистограмма `ydb.client.operation.duration` содержит атрибут database со значением из конфигурации
    @Test
    public void testOperationDurationHasDatabaseAttribute() {
        db.getStudents();
        MetricData metric = findMetric("ydb.client.operation.duration").orElseThrow();

        boolean hasDatabase = metric.getHistogramData().getPoints().stream()
                .anyMatch(p -> YDB.database().equals(p.getAttributes().get(ATTR_DATABASE)));

        boolean hasEndpoint = metric.getHistogramData().getPoints().stream()
                .anyMatch(p -> YDB.endpoint().equals(p.getAttributes().get(ATTR_ENDPOINT)));

        Assert.assertTrue(hasDatabase);
        Assert.assertTrue(hasEndpoint);
    }

    // операция ExecuteQuery записывается с корректным именем
    @Test
    public void testExecuteQueryOperationNameIsRecorded() {
        db.getStudents();
        Assert.assertTrue(findDurationMetricByOperationName("ExecuteQuery") > 0);
    }

    // операция Commit записывается после успешной транзакции
    @Test
    public void testCommitOperationNameIsRecorded() {
        db.upsertMarkWithCommit(1, 1, 5);
        Assert.assertTrue(findDurationMetricByOperationName("Commit") > 0);
    }

    // операция Rollback записывается после отката транзакции
    @Test
    public void testRollbackOperationNameIsRecorded() {
        db.upsertMarkWithRollback(1);
        Assert.assertTrue(findDurationMetricByOperationName("Rollback") > 0);
    }

    // метрика-счетчик `ydb.client.operation.failed` положительна после ошибочного запроса
    @Test
    public void testOperationFailedCounterIsPositive() {
        db.selectBadQuery();
        Assert.assertTrue(getCounterTotal("ydb.client.operation.failed") > 0);
    }

    // метрика-счётчик `ydb.client.operation.failed` растёт пропорционально числу упавших операций
    @Test
    public void testOperationFailedCounterIncrementsPerFailure() {
        db.selectBadQuery();
        long afterOne = getCounterTotal("ydb.client.operation.failed");

        db.selectBadQuery();
        db.selectBadQuery();
        long afterThree = getCounterTotal("ydb.client.operation.failed");

        Assert.assertTrue(afterThree > afterOne);
    }

    // время создания сессий записывается в метрику `ydb.query.session.create_time` и имеет положительную сумму значений
    @Test
    public void testSessionCreateTimeSumIsPositive() {
        db.getStudents();
        Assert.assertTrue(getHistogramSum("ydb.query.session.create_time") > 0);
    }

// Проверяет наличие gauge-метрик пула сессий (`ydb.query.session.count / min / max`)
    @Test
    public void testSessionPoolGaugesAreRegistered() {
        Set<String> presentMetricNames = getAllPresentMetricNames();

        Assert.assertTrue(presentMetricNames.contains("ydb.query.session.count"));
        Assert.assertTrue(presentMetricNames.contains("ydb.query.session.min"));
        Assert.assertTrue(presentMetricNames.contains("ydb.query.session.max"));
    }

    // минимальный размер пула равен 0 по умолчанию
    @Test
    public void testSessionPoolMinIsZeroByDefault() {
        MetricData metric = findMetric("ydb.query.session.min").orElseThrow();
        long minValueSessionPool = metric.getLongGaugeData().getPoints().stream()
                .mapToLong(LongPointData::getValue)
                .findFirst()
                .orElse(-1);

        Assert.assertEquals(0, minValueSessionPool);
    }

    // метрика-счётчик `ydb.query.session.pending_requests` положителен когда все сессии пула заняты
    @Test
    public void testSessionPendingRequestsCounterIsPositive() {
        try (QueryClient limitedClient = QueryClient.newClient(transport)
                .withMeter(ydbMeter)
                .sessionPoolMaxSize(1)
                .build()) {
            QuerySession held = limitedClient.createSession(Duration.ofSeconds(5)).join().getValue();
            var pending = limitedClient.createSession(Duration.ofSeconds(5));
            held.close();
            pending.join().getValue().close();
        }

        Assert.assertTrue(getCounterTotal("ydb.query.session.pending_requests") > 0);
    }

    // метрика-счётчик `ydb.query.session.timeouts` положительна после истечения таймаута ожидания сессии
    @Test
    public void testSessionTimeoutsCounterIsPositive() {
        try (QueryClient limitedClient = QueryClient.newClient(transport)
                .withMeter(ydbMeter)
                .sessionPoolMaxSize(1)
                .build()) {
            QuerySession held = limitedClient.createSession(Duration.ofSeconds(5)).join().getValue();
            limitedClient.createSession(Duration.ofMillis(1)).join();
            held.close();
        }

        Assert.assertTrue(getCounterTotal("ydb.query.session.timeouts") > 0);
    }

    // длительность retry-операций записывается в метрику-гистограммму `ydb.client.retry.duration` и имеет
    // положительную сумму, содержит корректное имя операции
    @Test
    public void testRetryDurationSumIsPositive() {
        db.getStudentsWithRetry(1);
        Assert.assertTrue(getHistogramSum("ydb.client.retry.duration") > 0);

        MetricData metric = findMetric("ydb.client.retry.duration").orElseThrow();
        boolean hasRunWithRetry = metric.getHistogramData().getPoints().stream()
                .anyMatch(p -> "ydb.RunWithRetry".equals(p.getAttributes().get(ATTR_OPERATION_NAME)));
        Assert.assertTrue(hasRunWithRetry);
    }

    // метрика-гистограмма `ydb.client.retry.attempts` записывается и >= 1 после запроса retry
    @Test
    public void testRetryAttemptsMinIsAtLeastOne() {
        db.getStudentsWithRetry(1);
        MetricData metric = findMetric("ydb.client.retry.attempts").orElseThrow();
        HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
        Assert.assertTrue(point.getMin() >= 1);
    }

    // Вызовы к YDB записывают метрику ExecuteQuery
    @Test
    public void testRecordsExecuteQuery_1() {
        db.getStudents();
        db.getGroups();
        db.getCourses();
        db.getLecturers();

        Assert.assertTrue(findDurationMetricByOperationName("ExecuteQuery") >= 4);
    }

    // Вызовы к YDB записывают метрику ExecuteQuery
    @Test
    public void testRecordsExecuteQuery_2() {
        db.getStudentsByGroup(1);
        db.getAvgMarkByCourse(1);
        db.getMarksForStudent(1);
        db.getPlanForGroup(1);
        db.getStudentsWithAvgMarkByGroup(1);

        Assert.assertTrue(findDurationMetricByOperationName("ExecuteQuery") >= 5);
    }

    // upsertMark записывает и ExecuteQuery, и Commit
    @Test
    public void testUpsertMarkRecordsExecuteQueryAndCommit() {
        db.upsertMarkWithCommit(1, 1, 5);
        Assert.assertTrue(findDurationMetricByOperationName("ExecuteQuery") > 0);
        Assert.assertTrue(findDurationMetricByOperationName("Commit") > 0);
    }

    // upsertMarkWithRollback записывает и ExecuteQuery, и Rollback
    @Test
    public void testUpsertMarkWithRollbackRecordsExecuteQueryAndRollback() {
        db.upsertMarkWithRollback(1);
        Assert.assertTrue(findDurationMetricByOperationName("ExecuteQuery") > 0);
        Assert.assertTrue(findDurationMetricByOperationName("Rollback") > 0);
    }

    // getStudentsWithRetry записывает retry-метрики
    @Test
    public void testGetStudentsWithRetryRecordsRetryMetrics() {
        db.getStudentsWithRetry(1);
        Assert.assertTrue(getHistogramSum("ydb.client.retry.duration") > 0);
        Assert.assertTrue(getHistogramCount("ydb.client.retry.attempts") > 0);
    }

    // счётчик ExecuteQuery растёт после 5 последовательных запросов
    @Test
    public void testMultipleOperationsAccumulateInHistogram() {
        for (int i = 0; i < 5; i++) {
            db.getStudents();
        }

        Assert.assertTrue(findDurationMetricByOperationName("ExecuteQuery") >= 5);
    }

    // счётчик упавших операций накапливается при нескольких ошибках
    @Test
    public void testMultipleFailuresAccumulateInCounter() {
        for (int i = 0; i < 3; i++) {
            db.selectBadQuery();
        }

        Assert.assertTrue(getCounterTotal("ydb.client.operation.failed") >= 3);

    }

    // retry-метрики накапливаются при нескольких вызовах через SessionRetryContext
    @Test
    public void testMultipleRetryCallsAccumulate() {
        for (int i = 0; i < 3; i++) {
            db.getStudentsWithRetry(i + 1);
        }

        Assert.assertTrue(getHistogramCount("ydb.client.retry.attempts") >= 3);
    }

    private Set<String> getAllPresentMetricNames() {
        return metricReader.collectAllMetrics().stream()
                .map(MetricData::getName)
                .collect(Collectors.toSet());
    }

    private Optional<MetricData> findMetric(String name) {
        return metricReader.collectAllMetrics().stream()
                .filter(m -> m.getName().equals(name))
                .findFirst();
    }

    private double getHistogramSum(String name) {
        return findMetric(name)
                .map(m -> m.getHistogramData().getPoints().stream()
                        .mapToDouble(HistogramPointData::getSum)
                        .sum())
                .orElse(0.0);
    }

    private long getHistogramCount(String name) {
        return findMetric(name)
                .map(m -> m.getHistogramData().getPoints().stream()
                        .mapToLong(HistogramPointData::getCount)
                        .sum())
                .orElse(0L);
    }

    private long findDurationMetricByOperationName(String operationName) {
        return findMetric("ydb.client.operation.duration")
                .map(m -> m.getHistogramData().getPoints().stream()
                        .filter(p -> operationName.equals(p.getAttributes().get(ATTR_OPERATION_NAME)))
                        .mapToLong(HistogramPointData::getCount)
                        .sum())
                .orElse(0L);
    }

    private long getCounterTotal(String name) {
        return findMetric(name)
                .map(m -> m.getLongSumData().getPoints().stream()
                        .mapToLong(LongPointData::getValue)
                        .sum())
                .orElse(0L);
    }
}
