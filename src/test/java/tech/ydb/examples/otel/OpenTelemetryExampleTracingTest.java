package tech.ydb.examples.otel;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.*;

import tech.ydb.auth.TokenAuthProvider;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.core.metrics.NoopMeter;
import tech.ydb.core.tracing.OpenTelemetryTracer;
import tech.ydb.query.QueryClient;
import tech.ydb.query.QuerySession;
import tech.ydb.query.tools.SessionRetryContext;
import tech.ydb.test.junit4.YdbHelperRule;

/**
 * Тесты, проверяющие что {@link OpenTelemetryTracer} корректно записывает трейсы YDB SDK.
 */
public class OpenTelemetryExampleTracingTest {

    /**
     * Все span-имена, создаваемые SDK при работе с YDB.
     */
    public static final List<String> ALL_SPAN_NAMES = List.of(
            "ydb.ExecuteQuery",
            "ydb.Commit",
            "ydb.Rollback",
            "ydb.CreateSession",
            "ydb.RunWithRetry",
            "ydb.Try"
    );

    private static final AttributeKey<String> ATTR_DB_RESPONSE_STATUS = AttributeKey.stringKey("db.response.status_code");
    private static final AttributeKey<String> ATTR_ERROR_TYPE = AttributeKey.stringKey("error.type");

    @ClassRule
    public static final YdbHelperRule YDB = new YdbHelperRule();

    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;

    private OpenTelemetryTracer ydbTracer;
    private GrpcTransport transport;
    private QueryClient queryClient;
    private SessionRetryContext retryCtx;

    private YandexDatabaseUtils db;

    @Before
    public void init() {
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        ydbTracer = OpenTelemetryTracer.fromOpenTelemetry(openTelemetry);

        transport = GrpcTransport.forEndpoint(YDB.endpoint(), YDB.database())
                .withAuthProvider(new TokenAuthProvider(YDB.authToken()))
                .withTracer(ydbTracer)
                .build();

        queryClient = QueryClient.newClient(transport)
                .withMeter(NoopMeter.INSTANCE)
                .build();

        retryCtx = SessionRetryContext.create(queryClient).build();

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
        tracerProvider.close();
        spanExporter.close();
    }

    // Проверяет наличие всех ожидаемых span-имён после выполнения различных операций
    @Test
    public void testAllSpanNamesArePresent() {
        db.getStudents();
        db.getStudentsWithRetry(1);
        db.upsertMarkWithCommit(1, 1, 5);
        db.upsertMarkWithRollback(1);

        Set<String> names = getAllPresentSpanNames();
        Assert.assertTrue(
                "Missing spans: " + ALL_SPAN_NAMES.stream()
                        .filter(s -> !names.contains(s))
                        .collect(Collectors.toList()),
                names.containsAll(ALL_SPAN_NAMES)
        );
    }

    // span ydb.ExecuteQuery записывается при выполнении запроса getStudents
    @Test
    public void testExecuteQuerySpanIsRecorded() {
        db.getStudents();
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // счётчик span ydb.ExecuteQuery растёт с каждым вызовом
    @Test
    public void testExecuteQuerySpanCountGrowsWithEachCall() {
        db.getStudents();
        long afterOne = countSpansByName("ydb.ExecuteQuery");

        db.getStudents();
        long afterTwo = countSpansByName("ydb.ExecuteQuery");

        Assert.assertTrue(afterTwo > afterOne);
    }

    // span ydb.Commit записывается после успешной транзакции с коммитом
    @Test
    public void testCommitSpanIsRecorded() {
        db.upsertMarkWithCommit(1, 1, 5);
        Assert.assertTrue(countSpansByName("ydb.Commit") > 0);
    }

    // span ydb.Rollback записывается после отката транзакции
    @Test
    public void testRollbackSpanIsRecorded() {
        db.upsertMarkWithRollback(1);
        Assert.assertTrue(countSpansByName("ydb.Rollback") > 0);
    }

    // span ydb.CreateSession записывается при создании сессии
    @Test
    public void testCreateSessionSpanIsRecorded() {
        db.getStudents();
        Assert.assertTrue(countSpansByName("ydb.CreateSession") > 0);
    }

    // span ydb.RunWithRetry записывается при вызове через SessionRetryContext
    @Test
    public void testRunWithRetrySpanIsRecorded() {
        db.getStudentsWithRetry(1);
        Assert.assertTrue(countSpansByName("ydb.RunWithRetry") > 0);
    }

    // span ydb.Try записывается при вызове через SessionRetryContext
    @Test
    public void testTrySpanIsRecorded() {
        db.getStudentsWithRetry(1);
        Assert.assertTrue(countSpansByName("ydb.Try") > 0);
    }

    // неудачный запрос создаёт span ydb.ExecuteQuery со статусом ERROR
    @Test
    public void testFailedSpanHasErrorStatus() {
        db.selectBadQuery();
        boolean hasErrorSpan = findSpansByName("ydb.ExecuteQuery").stream()
                .anyMatch(s -> s.getStatus().getStatusCode() == StatusCode.ERROR);
        Assert.assertTrue(hasErrorSpan);
    }

    // неудачный span содержит атрибут db.response.status_code
    @Test
    public void testFailedSpanHasStatusCodeAttribute() {
        db.selectBadQuery();
        boolean hasAttr = findSpansByName("ydb.ExecuteQuery").stream()
                .anyMatch(s -> s.getAttributes().get(ATTR_DB_RESPONSE_STATUS) != null);
        Assert.assertTrue(hasAttr);
    }

    // неудачный span содержит атрибут error.type
    @Test
    public void testFailedSpanHasErrorTypeAttribute() {
        db.selectBadQuery();
        boolean hasAttr = findSpansByName("ydb.ExecuteQuery").stream()
                .anyMatch(s -> s.getAttributes().get(ATTR_ERROR_TYPE) != null);
        Assert.assertTrue(hasAttr);
    }

    // успешный span ydb.ExecuteQuery имеет статус OK
    @Test
    public void testSuccessfulSpanHasOkStatus() {
        db.getStudents();
        boolean hasOkSpan = findSpansByName("ydb.ExecuteQuery").stream()
                .anyMatch(s -> s.getStatus().getStatusCode() == StatusCode.OK);
        Assert.assertTrue(hasOkSpan);
    }

    // span ydb.Commit после успешной транзакции имеет статус OK
    @Test
    public void testCommitSpanHasOkStatus() {
        db.upsertMarkWithCommit(1, 1, 5);
        boolean hasOkSpan = findSpansByName("ydb.Commit").stream()
                .anyMatch(s -> s.getStatus().getStatusCode() == StatusCode.OK);
        Assert.assertTrue(hasOkSpan);
    }

    // span ydb.Rollback после отката имеет статус OK
    @Test
    public void testRollbackSpanHasOkStatus() {
        db.upsertMarkWithRollback(1);
        boolean hasOkSpan = findSpansByName("ydb.Rollback").stream()
                .anyMatch(s -> s.getStatus().getStatusCode() == StatusCode.OK);
        Assert.assertTrue(hasOkSpan);
    }

    // span ydb.Try имеет родительский span ydb.RunWithRetry
    @Test
    public void testTrySpanIsChildOfRunWithRetry() {
        db.getStudentsWithRetry(1);

        Optional<SpanData> runWithRetry = findSpansByName("ydb.RunWithRetry").stream().findFirst();
        Assert.assertTrue(runWithRetry.isPresent());

        String parentSpanId = runWithRetry.get().getSpanContext().getSpanId();
        boolean hasTryChild = findSpansByName("ydb.Try").stream()
                .anyMatch(s -> s.getParentSpanContext().getSpanId().equals(parentSpanId));
        Assert.assertTrue(hasTryChild);
    }

    // у span ydb.RunWithRetry есть хотя бы один дочерний span ydb.Try
    @Test
    public void testRunWithRetryHasAtLeastOneTryChild() {
        db.getStudentsWithRetry(1);

        List<SpanData> retrySpans = findSpansByName("ydb.RunWithRetry");
        List<SpanData> trySpans = findSpansByName("ydb.Try");

        Assert.assertFalse(retrySpans.isEmpty());
        Assert.assertFalse(trySpans.isEmpty());

        String parentId = retrySpans.get(0).getSpanContext().getSpanId();
        long childCount = trySpans.stream()
                .filter(s -> s.getParentSpanContext().getSpanId().equals(parentId))
                .count();

        Assert.assertTrue(childCount >= 1);
    }

    // span ydb.RunWithRetry не имеет родительского span (является корневым)
    @Test
    public void testRunWithRetrySpanHasNoParent() {
        db.getStudentsWithRetry(1);
        boolean hasRootSpan = findSpansByName("ydb.RunWithRetry").stream()
                .anyMatch(s -> !s.getParentSpanContext().isValid());
        Assert.assertTrue(hasRootSpan);
    }

    // 5 последовательных вызовов getStudents создают не менее 5 span ydb.ExecuteQuery
    @Test
    public void testMultipleCallsAccumulateExecuteQuerySpans() {
        for (int i = 0; i < 5; i++) {
            db.getStudents();
        }
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") >= 5);
    }

    // 3 ошибочных запроса создают не менее 3 span ydb.ExecuteQuery со статусом ERROR
    @Test
    public void testMultipleFailuresAccumulateErrorSpans() {
        for (int i = 0; i < 3; i++) {
            db.selectBadQuery();
        }

        long errorCount = findSpansByName("ydb.ExecuteQuery").stream()
                .filter(s -> s.getStatus().getStatusCode() == StatusCode.ERROR)
                .count();
        Assert.assertTrue(errorCount >= 3);
    }

    // 3 вызова retry создают не менее 3 span ydb.RunWithRetry
    @Test
    public void testMultipleRetryCallsAccumulateRunWithRetrySpans() {
        for (int i = 0; i < 3; i++) {
            db.getStudentsWithRetry(i + 1);
        }
        Assert.assertTrue(countSpansByName("ydb.RunWithRetry") >= 3);
    }

    // 3 вызова retry создают не менее 3 span ydb.Try
    @Test
    public void testMultipleRetryCallsAccumulateTrySpans() {
        for (int i = 0; i < 3; i++) {
            db.getStudentsWithRetry(i + 1);
        }
        Assert.assertTrue(countSpansByName("ydb.Try") >= 3);
    }

    // getStudents создаёт span ydb.ExecuteQuery без ошибок
    @Test
    public void testGetStudentsCreatesExecuteQuerySpan() {
        db.getStudents();
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // getGroups создаёт span ydb.ExecuteQuery
    @Test
    public void testGetGroupsCreatesExecuteQuerySpan() {
        db.getGroups();
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // getCourses создаёт span ydb.ExecuteQuery
    @Test
    public void testGetCoursesCreatesExecuteQuerySpan() {
        db.getCourses();
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // getLecturers создаёт span ydb.ExecuteQuery
    @Test
    public void testGetLecturersCreatesExecuteQuerySpan() {
        db.getLecturers();
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // getStudentsByGroup создаёт span ydb.ExecuteQuery
    @Test
    public void testGetStudentsByGroupCreatesExecuteQuerySpan() {
        db.getStudentsByGroup(1);
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // getAvgMarkByCourse создаёт span ydb.ExecuteQuery
    @Test
    public void testGetAvgMarkByCourseCreatesExecuteQuerySpan() {
        db.getAvgMarkByCourse(1);
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // getMarksForStudent создаёт span ydb.ExecuteQuery
    @Test
    public void testGetMarksForStudentCreatesExecuteQuerySpan() {
        db.getMarksForStudent(1);
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // getPlanForGroup создаёт span ydb.ExecuteQuery
    @Test
    public void testGetPlanForGroupCreatesExecuteQuerySpan() {
        db.getPlanForGroup(1);
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // getStudentsWithAvgMarkByGroup создаёт span ydb.ExecuteQuery
    @Test
    public void testGetStudentsWithAvgMarkByGroupCreatesExecuteQuerySpan() {
        db.getStudentsWithAvgMarkByGroup(1);
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // upsertMarkWithCommit создаёт span ydb.ExecuteQuery и ydb.Commit
    @Test
    public void testUpsertMarkWithCommitCreatesExecuteQueryAndCommitSpans() {
        db.upsertMarkWithCommit(1, 1, 5);
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
        Assert.assertTrue(countSpansByName("ydb.Commit") > 0);
    }

    // upsertMarkWithRollback создаёт span ydb.ExecuteQuery и ydb.Rollback
    @Test
    public void testUpsertMarkWithRollbackCreatesExecuteQueryAndRollbackSpans() {
        db.upsertMarkWithRollback(1);
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
        Assert.assertTrue(countSpansByName("ydb.Rollback") > 0);
    }

    // getStudentsWithRetry создаёт span ydb.RunWithRetry и ydb.Try
    @Test
    public void testGetStudentsWithRetryCreatesRunWithRetryAndTrySpans() {
        db.getStudentsWithRetry(1);
        Assert.assertTrue(countSpansByName("ydb.RunWithRetry") > 0);
        Assert.assertTrue(countSpansByName("ydb.Try") > 0);
    }

    // 4 запроса (getStudents, getGroups, getCourses, getLecturers) создают не менее 4 span ExecuteQuery
    @Test
    public void testFourSelectsCreateAtLeastFourExecuteQuerySpans() {
        db.getStudents();
        db.getGroups();
        db.getCourses();
        db.getLecturers();
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") >= 4);
    }

    // 5 разных запросов создают не менее 5 span ydb.ExecuteQuery
    @Test
    public void testFiveSelectsCreateAtLeastFiveExecuteQuerySpans() {
        db.getStudentsByGroup(1);
        db.getAvgMarkByCourse(1);
        db.getMarksForStudent(1);
        db.getPlanForGroup(1);
        db.getStudentsWithAvgMarkByGroup(1);
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") >= 5);
    }

    // span ydb.CreateSession создаётся не более одного раза при повторном использовании сессии
    @Test
    public void testCreateSessionSpanCount() {
        db.getStudents();
        long count = countSpansByName("ydb.CreateSession");
        Assert.assertTrue(count >= 1);
    }

    // span ydb.RunWithRetry и ydb.Try лежат в одном трейсе (одинаковый traceId)
    @Test
    public void testRunWithRetryAndTryShareSameTrace() {
        db.getStudentsWithRetry(1);

        Optional<SpanData> runWithRetry = findSpansByName("ydb.RunWithRetry").stream().findFirst();
        Optional<SpanData> trySpan = findSpansByName("ydb.Try").stream().findFirst();

        Assert.assertTrue(runWithRetry.isPresent());
        Assert.assertTrue(trySpan.isPresent());

        Assert.assertEquals(
                runWithRetry.get().getSpanContext().getTraceId(),
                trySpan.get().getSpanContext().getTraceId()
        );
    }

    // после reset() экспортёра новые span снова накапливаются
    @Test
    public void testSpanExporterAccumulatesSpansAfterReset() {
        db.getStudents();
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);

        spanExporter.reset();
        Assert.assertEquals(0, spanExporter.getFinishedSpanItems().size());

        db.getStudents();
        Assert.assertTrue(countSpansByName("ydb.ExecuteQuery") > 0);
    }

    // span ydb.ExecuteQuery имеет конечное время завершения (не равен нулю)
    @Test
    public void testExecuteQuerySpanHasEndTime() {
        db.getStudents();
        boolean hasEndTime = findSpansByName("ydb.ExecuteQuery").stream()
                .anyMatch(s -> s.getEndEpochNanos() > 0);
        Assert.assertTrue(hasEndTime);
    }

    // span ydb.ExecuteQuery имеет время начала меньше времени конца
    @Test
    public void testExecuteQuerySpanStartBeforeEnd() {
        db.getStudents();
        boolean valid = findSpansByName("ydb.ExecuteQuery").stream()
                .anyMatch(s -> s.getStartEpochNanos() < s.getEndEpochNanos());
        Assert.assertTrue(valid);
    }

    // span ydb.Commit имеет конечное время завершения
    @Test
    public void testCommitSpanHasEndTime() {
        db.upsertMarkWithCommit(1, 1, 5);
        boolean hasEndTime = findSpansByName("ydb.Commit").stream()
                .anyMatch(s -> s.getEndEpochNanos() > 0);
        Assert.assertTrue(hasEndTime);
    }

    // span ydb.Rollback имеет корректные временные метки
    @Test
    public void testRollbackSpanHasValidTimestamps() {
        db.upsertMarkWithRollback(1);
        boolean valid = findSpansByName("ydb.Rollback").stream()
                .anyMatch(s -> s.getStartEpochNanos() < s.getEndEpochNanos());
        Assert.assertTrue(valid);
    }

    // span ydb.RunWithRetry имеет корректные временные метки
    @Test
    public void testRunWithRetrySpanHasValidTimestamps() {
        db.getStudentsWithRetry(1);
        boolean valid = findSpansByName("ydb.RunWithRetry").stream()
                .anyMatch(s -> s.getEndEpochNanos() > 0 && s.getStartEpochNanos() <= s.getEndEpochNanos());
        Assert.assertTrue(valid);
    }

    // span ydb.Try имеет корректные временные метки
    @Test
    public void testTrySpanHasValidTimestamps() {
        db.getStudentsWithRetry(1);
        boolean valid = findSpansByName("ydb.Try").stream()
                .anyMatch(s -> s.getStartEpochNanos() < s.getEndEpochNanos());
        Assert.assertTrue(valid);
    }

    // span ydb.RunWithRetry начинается раньше, чем span ydb.Try в том же трейсе
    @Test
    public void testRunWithRetryStartsBeforeTry() {
        db.getStudentsWithRetry(1);

        Optional<SpanData> runWithRetry = findSpansByName("ydb.RunWithRetry").stream().findFirst();
        Optional<SpanData> trySpan = findSpansByName("ydb.Try").stream().findFirst();

        Assert.assertTrue(runWithRetry.isPresent());
        Assert.assertTrue(trySpan.isPresent());

        Assert.assertTrue(runWithRetry.get().getStartEpochNanos() <= trySpan.get().getStartEpochNanos());
    }

    // span ydb.ExecuteQuery имеет уникальный spanId
    @Test
    public void testEachExecuteQuerySpanHasUniqueSpanId() {
        db.getStudents();
        db.getStudents();

        List<SpanData> spans = findSpansByName("ydb.ExecuteQuery");
        Assert.assertTrue(spans.size() >= 2);

        long uniqueIds = spans.stream()
                .map(s -> s.getSpanContext().getSpanId())
                .distinct()
                .count();

        Assert.assertEquals(spans.size(), uniqueIds);
    }

    // смешанные операции создают все ожидаемые span-имена
    @Test
    public void testMixedOperationsCreateAllExpectedSpans() {
        db.getStudents();
        db.upsertMarkWithCommit(2, 2, 4);
        db.upsertMarkWithRollback(3);
        db.getStudentsWithRetry(2);

        Set<String> names = getAllPresentSpanNames();
        Assert.assertTrue(names.contains("ydb.ExecuteQuery"));
        Assert.assertTrue(names.contains("ydb.Commit"));
        Assert.assertTrue(names.contains("ydb.Rollback"));
        Assert.assertTrue(names.contains("ydb.RunWithRetry"));
        Assert.assertTrue(names.contains("ydb.Try"));
    }

    // span ydb.CreateSession создаётся при открытии сессий (в том числе во время @Before)
    @Test
    public void testCreateSessionSpanCreatedForEverySessionCreation() {
        db.getStudents();
        db.getGroups();

        long createSessionCount = countSpansByName("ydb.CreateSession");
        Assert.assertTrue(createSessionCount >= 1);
    }

    private Set<String> getAllPresentSpanNames() {
        return spanExporter.getFinishedSpanItems().stream()
                .map(SpanData::getName)
                .collect(Collectors.toSet());
    }

    private List<SpanData> findSpansByName(String name) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .collect(Collectors.toList());
    }

    private long countSpansByName(String name) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .count();
    }
}
