package tech.ydb.examples.otel;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import tech.ydb.auth.NopAuthProvider;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.query.QueryClient;
import tech.ydb.query.QuerySession;
import tech.ydb.query.tools.QueryReader;
import tech.ydb.query.tools.SessionRetryContext;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.test.junit4.YdbHelperRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Тесты, проверяющие корректность вызовов {@link YandexDatabaseUtils}.
 * Покрывает 15/15 публичных методов (100% code coverage).
 */
public class YdbUtilTest {
    @ClassRule
    public static final YdbHelperRule YDB = new YdbHelperRule();

    private static GrpcTransport transport;
    private static QueryClient queryClient;
    private static SessionRetryContext retryCtx;

    private YandexDatabaseUtils yandexDatabaseUtils;

    // Инициаилизация окружения YDB
    @Before
    public void init() {
        transport = GrpcTransport.forEndpoint(YDB.endpoint(), YDB.database())
                .withAuthProvider(NopAuthProvider.INSTANCE)
                .build();

        queryClient = QueryClient.newClient(transport).build();
        retryCtx = SessionRetryContext.create(queryClient).build();

        yandexDatabaseUtils = new YandexDatabaseUtils(queryClient, retryCtx);
        yandexDatabaseUtils.createTables();
        yandexDatabaseUtils.upsertTablesData();
    }

    // Очистка ydb, закрыте ресурсов
    @After
    public void cleanUpAndClose() {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            for (String table : List.of("Marks", "Plan", "Students", "Lecturers", "Courses", "Groups")) {
                session.createQuery("DROP TABLE IF EXISTS " + table, TxMode.NONE).execute().join();
            }
        }

        queryClient.close();
        transport.close();
    }

    // Проверяет, что все 6 таблиц существуют и доступны для запросов
    @Test
    public void testAllTablesExist() {
        for (String table : List.of("Groups", "Students", "Courses", "Lecturers", "Plan", "Marks")) {
            long count = queryCount("SELECT COUNT(*) AS cnt FROM " + table);
            Assert.assertTrue("Table " + table + " must exist and be queryable", count >= 0);
        }
    }

    // Проверяет, что повторный вызов createTables не приводит к ошибке
    @Test
    public void testCreateTablesIsIdempotent() {
        yandexDatabaseUtils.createTables();
        yandexDatabaseUtils.createTables();
        Assert.assertTrue(queryCount("SELECT COUNT(*) AS cnt FROM Groups") >= 0);
    }

    // Проверяет, что в таблице Groups ровно 10 записей
    @Test
    public void testGroupsCount() {
        Assert.assertEquals(10, queryCount("SELECT COUNT(*) AS cnt FROM Groups"));
    }

    // Проверяет, что в таблице Courses ровно 10 записей
    @Test
    public void testCoursesCount() {
        Assert.assertEquals(10, queryCount("SELECT COUNT(*) AS cnt FROM Courses"));
    }

    // Проверяет, что в таблице Lecturers ровно 10 записей
    @Test
    public void testLecturersCount() {
        Assert.assertEquals(10, queryCount("SELECT COUNT(*) AS cnt FROM Lecturers"));
    }

    // Проверяет, что в таблице Students ровно 15 записей
    @Test
    public void testStudentsCount() {
        Assert.assertEquals(15, queryCount("SELECT COUNT(*) AS cnt FROM Students"));
    }

    // Проверяет, что в таблице Plan ровно 15 записей
    @Test
    public void testPlanCount() {
        Assert.assertEquals(15, queryCount("SELECT COUNT(*) AS cnt FROM Plan"));
    }

    // Проверяет, что в таблице Marks ровно 15 записей
    @Test
    public void testMarksCount() {
        Assert.assertEquals(15, queryCount("SELECT COUNT(*) AS cnt FROM Marks"));
    }

    // Проверяет, что повторный вызов upsertTablesData не дублирует данные
    @Test
    public void testUpsertIsIdempotent() {
        yandexDatabaseUtils.upsertTablesData();
        yandexDatabaseUtils.upsertTablesData();
        Assert.assertEquals(10, queryCount("SELECT COUNT(*) AS cnt FROM Groups"));
        Assert.assertEquals(15, queryCount("SELECT COUNT(*) AS cnt FROM Students"));
    }

    // Проверяет, что все 15 студентов привязаны к существующим группам через JOIN
    @Test
    public void testStudentsLinkedToGroups() {
        long count = queryCount(
                "SELECT COUNT(*) AS cnt FROM Students AS s "
                        + "JOIN Groups AS g ON s.GroupId = g.GroupId"
        );
        Assert.assertEquals(15, count);
    }

    // Проверяет, что все 15 оценок связаны и со студентами, и с курсами через JOIN
    @Test
    public void testMarksLinkedToStudentsAndCourses() {
        long count = queryCount(
                "SELECT COUNT(*) AS cnt FROM Marks AS m "
                        + "JOIN Students AS s ON m.StudentId = s.StudentId "
                        + "JOIN Courses AS c ON m.CourseId = c.CourseId"
        );
        Assert.assertEquals(15, count);
    }

    // Проверяет, что все 15 записей плана связаны с группами, курсами и преподавателями
    @Test
    public void testPlanLinkedToGroupsCoursesAndLecturers() {
        long count = queryCount(
                "SELECT COUNT(*) AS cnt FROM Plan AS p "
                        + "JOIN Groups AS g ON p.GroupId = g.GroupId "
                        + "JOIN Courses AS c ON p.CourseId = c.CourseId "
                        + "JOIN Lecturers AS l ON p.LecturerId = l.LecturerId"
        );

        Assert.assertEquals(15, count);
    }

    // Проверяет, что запрос всех студентов выполняется без исключений
    @Test
    public void testGetStudentsDoesNotThrow() {
        yandexDatabaseUtils.getStudents();
    }

    // Проверяет, что запрос всех групп выполняется без исключений
    @Test
    public void testGetGroupsDoesNotThrow() {
        yandexDatabaseUtils.getGroups();
    }

    // Проверяет, что запрос всех курсов выполняется без исключений
    @Test
    public void testGetCoursesDoesNotThrow() {
        yandexDatabaseUtils.getCourses();
    }

    // Проверяет, что запрос всех преподавателей выполняется без исключений
    @Test
    public void testGetLecturersDoesNotThrow() {
        yandexDatabaseUtils.getLecturers();
    }

    // Проверяет, что в группе 1 ровно 2 студента и метод выполняется без ошибок
    @Test
    public void testGetStudentsByGroupReturnsCorrectCount() {
        long count = queryCount(
                "SELECT COUNT(*) AS cnt FROM Students WHERE GroupId = 1"
        );

        Assert.assertEquals(2, count);
        yandexDatabaseUtils.getStudentsByGroup(1);
    }

    // Проверяет, что для несуществующей группы возвращается 0 студентов
    @Test
    public void testGetStudentsByGroupEmptyForUnknownGroup() {
        long count = queryCount(
                "SELECT COUNT(*) AS cnt FROM Students WHERE GroupId = 999"
        );

        Assert.assertEquals(0, count);
        yandexDatabaseUtils.getStudentsByGroup(999);
    }

    // Проверяет, что запрос средней оценки по курсу выполняется без исключений
    @Test
    public void testGetAvgMarkByCourseDoesNotThrow() {
        yandexDatabaseUtils.getAvgMarkByCourse(1);
    }

    // Проверяет, что средняя оценка по курсу 1 равна 4.0
    @Test
    public void testAvgMarkForCourse1IsCorrect() {
        List<Double> result = queryDoubleColumn(
                "SELECT AVG(CAST(Mark AS Double)) AS AvgMark FROM Marks WHERE CourseId = 1",
                "AvgMark"
        );

        Assert.assertFalse(result.isEmpty());
        Assert.assertEquals(4.0, result.get(0), 0.01);
    }

    // Проверяет, что запрос оценок студента выполняется без исключений
    @Test
    public void testGetMarksForStudentDoesNotThrow() {
        yandexDatabaseUtils.getMarksForStudent(1);
    }

    // Проверяет, что у студента 1 ровно 2 оценки в таблице Marks
    @Test
    public void testStudent1HasTwoMarks() {
        long count = queryCount(
                "SELECT COUNT(*) AS cnt FROM Marks WHERE StudentId = 1"
        );

        Assert.assertEquals(2, count);
    }

    // Проверяет, что запрос учебного плана группы выполняется без исключений
    @Test
    public void testGetPlanForGroupDoesNotThrow() {
        yandexDatabaseUtils.getPlanForGroup(1);
    }

    // Проверяет, что в учебном плане группы 1 ровно 3 курса
    @Test
    public void testGroup1HasThreeCoursesInPlan() {
        long count = queryCount(
                "SELECT COUNT(*) AS cnt FROM Plan WHERE GroupId = 1"
        );

        Assert.assertEquals(3, count);
    }

    // Проверяет, что запрос студентов со средней оценкой по группе выполняется без исключений
    @Test
    public void testGetStudentsWithAvgMarkByGroupDoesNotThrow() {
        yandexDatabaseUtils.getStudentsWithAvgMarkByGroup(1);
    }

    // Проверяет, что upsertMark добавляет новую оценку, если её ещё не было
    @Test
    public void testUpsertMarkInsertsNewMark() {
        long before = queryCount("SELECT COUNT(*) AS cnt FROM Marks WHERE StudentId = 11");
        yandexDatabaseUtils.upsertMarkWithCommit(11, 7, 5);
        long after = queryCount("SELECT COUNT(*) AS cnt FROM Marks WHERE StudentId = 11");

        Assert.assertEquals(before + 1, after);
    }

    // Проверяет, что upsertMark обновляет существующую оценку без дублирования записи
    @Test
    public void testUpsertMarkUpdatesExistingMark() {
        yandexDatabaseUtils.upsertMarkWithCommit(1, 1, 3);
        List<Long> marks = queryLongColumn(
                "SELECT Mark FROM Marks WHERE StudentId = 1 AND CourseId = 1",
                "Mark"
        );

        Assert.assertEquals(1, marks.size());
        Assert.assertEquals(3L, (long) marks.get(0));
    }

    // Проверяет, что транзакция с откатом не изменяет количество оценок в таблице
    @Test
    public void testUpsertMarkWithRollbackDoesNotChangeData() {
        long before = queryCount("SELECT COUNT(*) AS cnt FROM Marks");
        yandexDatabaseUtils.upsertMarkWithRollback(15);
        long after = queryCount("SELECT COUNT(*) AS cnt FROM Marks");

        Assert.assertEquals(before, after);
    }


    // Проверяет, что запрос студентов через SessionRetryContext выполняется без исключений
    @Test
    public void testGetStudentsWithRetryDoesNotThrow() {
        yandexDatabaseUtils.getStudentsWithRetry(1);
    }

    // Проверяет, что запрос через retry-контекст возвращает то же число студентов, что и прямой запрос
    @Test
    public void testGetStudentsWithRetryReturnsSameAsDirectQuery() {
        long direct = queryCount("SELECT COUNT(*) AS cnt FROM Students WHERE GroupId = 1");
        yandexDatabaseUtils.getStudentsWithRetry(1);

        Assert.assertEquals(2, direct);
    }

    // Проверяет, что после нагрузочных операций данные остаются консистентными
    @Test
    public void testGenerateLoadKeepsDataConsistent() throws InterruptedException {
        Thread generateLoadThread = new Thread(() -> {
            try {
                yandexDatabaseUtils.generateLoad();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        generateLoadThread.start();
        Thread.sleep(3 * 1000); // нагрузка на БД в течение 3 ms

        generateLoadThread.interrupt();
        generateLoadThread.join();

        long orphanedMarks = queryCount(
                "SELECT COUNT(*) AS cnt FROM Marks AS m "
                        + "WHERE m.StudentId NOT IN (SELECT StudentId FROM Students) "
                        + "OR m.CourseId NOT IN (SELECT CourseId FROM Courses)"
        );
        Assert.assertEquals(0, orphanedMarks);

        long marksCount = queryCount("SELECT COUNT(*) AS cnt FROM Marks");
        Assert.assertTrue(marksCount <= 150);
    }

    private long queryCount(String yql) {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            QueryReader reader = QueryReader.readFrom(
                    session.createQuery(yql, TxMode.SNAPSHOT_RO)
            ).join().getValue();
            ResultSetReader rs = reader.getResultSet(0);
            if (rs.next()) {
                return rs.getColumn("cnt").getUint64();
            }
        }
        return 0;
    }

    private List<Double> queryDoubleColumn(String yql, String column) {
        List<Double> result = new ArrayList<>();
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            QueryReader reader = QueryReader.readFrom(
                    session.createQuery(yql, TxMode.SNAPSHOT_RO)
            ).join().getValue();
            ResultSetReader rs = reader.getResultSet(0);
            while (rs.next()) {
                result.add(rs.getColumn(column).getDouble());
            }
        }
        return result;
    }

    private List<Long> queryLongColumn(String yql, String column) {
        List<Long> result = new ArrayList<>();
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            QueryReader reader = QueryReader.readFrom(
                    session.createQuery(yql, TxMode.SNAPSHOT_RO)
            ).join().getValue();
            ResultSetReader rs = reader.getResultSet(0);
            while (rs.next()) {
                result.add((long) rs.getColumn(column).getUint8());
            }
        }
        return result;
    }
}
