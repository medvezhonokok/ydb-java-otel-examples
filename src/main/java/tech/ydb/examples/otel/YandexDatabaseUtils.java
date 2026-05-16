package tech.ydb.examples.otel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.query.QueryClient;
import tech.ydb.query.QuerySession;
import tech.ydb.query.tools.SessionRetryContext;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Набор операций с YDB, используемых для демонстрации и нагрузочного тестирования.
 *
 * <p>Работает с учебной схемой из 6 таблиц:
 * {@code Groups}, {@code Students}, {@code Courses}, {@code Lecturers}, {@code Plan}, {@code Marks}.
 *
 * <p>Методы делятся на три группы:
 * <ul>
 *   <li><b>DDL/DML-инициализация</b> — {@link #createTables()}, {@link #upsertTablesData()}</li>
 *   <li><b>Запросы на чтение</b> — {@code getStudents()}, {@code getGroups()} и т.д.</li>
 *   <li><b>Запись и нагрузка</b> — {@link #upsertMarkWithCommit}, {@link #upsertMarkWithRollback},
 *       {@link #generateLoad()}</li>
 * </ul>
 */
public class YandexDatabaseUtils {
    private static final Logger log = LoggerFactory.getLogger(YandexDatabaseUtils.class);

    private final QueryClient queryClient;
    private final SessionRetryContext retryCtx;
    private long iteration = 0;

    public YandexDatabaseUtils(QueryClient queryClient, SessionRetryContext retryCtx) {
        this.queryClient = queryClient;
        this.retryCtx = retryCtx;
    }

    /** Создаёт все 6 таблиц схемы (идемпотентно — использует {@code CREATE TABLE IF NOT EXISTS}). */
    public void createTables() {
        log.info("--[ CreateTables ]--");

        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            executeYdbQuery(session, "CREATE TABLE IF NOT EXISTS Groups ("
                    + "GroupId Uint64, GroupName Utf8, PRIMARY KEY (GroupId))");
            executeYdbQuery(session, "CREATE TABLE IF NOT EXISTS Students ("
                    + "StudentId Uint64, StudentName Utf8, GroupId Uint64, PRIMARY KEY (StudentId))");
            executeYdbQuery(session, "CREATE TABLE IF NOT EXISTS Courses ("
                    + "CourseId Uint64, CourseName Utf8, PRIMARY KEY (CourseId))");
            executeYdbQuery(session, "CREATE TABLE IF NOT EXISTS Lecturers ("
                    + "LecturerId Uint64, LecturerName Utf8, PRIMARY KEY (LecturerId))");
            executeYdbQuery(session, "CREATE TABLE IF NOT EXISTS Plan ("
                    + "GroupId Uint64, CourseId Uint64, LecturerId Uint64, PRIMARY KEY (GroupId, CourseId))");
            executeYdbQuery(session, "CREATE TABLE IF NOT EXISTS Marks ("
                    + "StudentId Uint64, CourseId Uint64, Mark Uint8, PRIMARY KEY (StudentId, CourseId))");
        }
    }

    /** Наполняет таблицы тестовыми данными (10 групп, 15 студентов, 10 курсов, 15 оценок и т.д.). */
    public void upsertTablesData() {
        log.info("--[ UpsertTables ]--");

        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            executeYdbQuery(session, "UPSERT INTO Groups (GroupId, GroupName) VALUES "
                    + "(1, 'M3431'), (2, 'M3432'), (3, 'M3433'), (4, 'M3434'), (5, 'M3435'), "
                    + "(6, 'M3436'), (7, 'M3437'), (8, 'M3438'), (9, 'M3439'), (10, 'M3440')");

            executeYdbQuery(session, "UPSERT INTO Courses (CourseId, CourseName) VALUES "
                    + "(1, 'Введение в программирование'), (2, 'Дискретная математика'), "
                    + "(3, 'АиСД'), (4, 'Операционные системы'), (5, 'Базы данных'), "
                    + "(6, 'Сети и телекоммуникации'), (7, 'Теория вероятностей'), "
                    + "(8, 'Линейная алгебра'), (9, 'Математический анализ'), (10, 'Архитектура ЭВМ')");

            executeYdbQuery(session, "UPSERT INTO Lecturers (LecturerId, LecturerName) VALUES "
                    + "(1, 'Кирилл Алексеевич'), (2, 'Михаил Дмитриевич'), (3, 'Мария Сергеевна'), "
                    + "(4, 'Алексей Петрович'), (5, 'Наталья Игоревна'), (6, 'Дмитрий Олегович'), "
                    + "(7, 'Елена Владимировна'), (8, 'Андрей Николаевич'), (9, 'Ольга Андреевна'), "
                    + "(10, 'Сергей Викторович')");

            executeYdbQuery(session, "UPSERT INTO Students (StudentId, StudentName, GroupId) VALUES "
                    + "(1, 'Иван Иванов', 1), (2, 'Пётр Петров', 1), (3, 'Сергей Сидоров', 2), "
                    + "(4, 'Анна Кузнецова', 2), (5, 'Мария Смирнова', 3), (6, 'Алексей Попов', 3), "
                    + "(7, 'Дмитрий Лебедев', 4), (8, 'Екатерина Козлова', 4), (9, 'Николай Новиков', 5), "
                    + "(10, 'Ольга Морозова', 5), (11, 'Андрей Волков', 6), (12, 'Татьяна Алексеева', 7), "
                    + "(13, 'Роман Соколов', 8), (14, 'Юлия Михайлова', 9), (15, 'Артём Фёдоров', 10)");

            executeYdbQuery(session, "UPSERT INTO Plan (GroupId, CourseId, LecturerId) VALUES "
                    + "(1, 1, 1), (1, 2, 2), (1, 3, 3), (2, 1, 1), (2, 4, 4), "
                    + "(3, 2, 2), (3, 5, 5), (4, 3, 3), (4, 6, 6), (5, 1, 1), "
                    + "(6, 7, 7), (7, 8, 8), (8, 9, 9), (9, 10, 10), (10, 5, 5)");

            executeYdbQuery(session, "UPSERT INTO Marks (StudentId, CourseId, Mark) VALUES "
                    + "(1, 1, 5), (1, 2, 4), (2, 1, 3), (2, 2, 5), (3, 1, 4), "
                    + "(3, 4, 5), (4, 1, 3), (4, 4, 4), (5, 2, 5), (5, 5, 4), "
                    + "(6, 2, 3), (7, 3, 5), (8, 3, 4), (9, 10, 3), (10, 1, 5)");
        }
    }

    private List<Runnable> allOperations() {
        return List.of(
                this::getStudents,
                this::getGroups,
                this::getCourses,
                this::getLecturers,
                () -> getStudentsByGroup((iteration % 10) + 1),
                () -> getAvgMarkByCourse((iteration % 10) + 1),
                () -> getMarksForStudent((iteration % 15) + 1),
                () -> getPlanForGroup((iteration % 10) + 1),
                () -> upsertMarkWithCommit((iteration % 15) + 1, (iteration % 10) + 1, (int) (iteration % 3) + 3),
                () -> upsertMarkWithRollback((iteration % 15) + 1),
                () -> getStudentsWithAvgMarkByGroup((iteration % 10) + 1),
                () -> getStudentsWithRetry((iteration % 10) + 1),
                this::selectBadQuery
        );
    }

    /**
     * Бесконечный цикл нагрузки: поочерёдно выполняет все операции из {@link #allOperations()}
     * с паузой 200 мс между итерациями. Прерывается по {@link Thread#interrupt()}.
     */
    public void generateLoad() throws InterruptedException {
        List<Runnable> ops = allOperations();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ops.get((int) (iteration % ops.size())).run();
            } catch (Exception ignored) {}
            iteration++;
            TimeUnit.MILLISECONDS.sleep(200);
        }
    }

    public void getStudents() {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery("SELECT StudentId, StudentName, GroupId FROM Students", TxMode.SNAPSHOT_RO)
                    .execute().join();
        }
    }

    public void getGroups() {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery("SELECT GroupId, GroupName FROM Groups", TxMode.SNAPSHOT_RO)
                    .execute().join();
        }
    }

    public void getCourses() {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery("SELECT CourseId, CourseName FROM Courses", TxMode.SNAPSHOT_RO)
                    .execute().join();
        }
    }

    public void getLecturers() {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery("SELECT LecturerId, LecturerName FROM Lecturers", TxMode.SNAPSHOT_RO)
                    .execute().join();
        }
    }

    public void getStudentsByGroup(long groupId) {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery(
                    "SELECT StudentId, StudentName FROM Students WHERE GroupId = " + groupId,
                    TxMode.SNAPSHOT_RO
            ).execute().join();
        }
    }

    public void getAvgMarkByCourse(long courseId) {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery(
                    "SELECT AVG(CAST(Mark AS Double)) AS AvgMark FROM Marks WHERE CourseId = " + courseId,
                    TxMode.SNAPSHOT_RO
            ).execute().join();
        }
    }

    public void getMarksForStudent(long studentId) {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery(
                    "SELECT CourseId, Mark FROM Marks WHERE StudentId = " + studentId,
                    TxMode.SNAPSHOT_RO
            ).execute().join();
        }
    }

    public void getPlanForGroup(long groupId) {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery(
                    "SELECT p.CourseId, c.CourseName, l.LecturerName "
                    + "FROM Plan AS p "
                    + "JOIN Courses AS c ON p.CourseId = c.CourseId "
                    + "JOIN Lecturers AS l ON p.LecturerId = l.LecturerId "
                    + "WHERE p.GroupId = " + groupId,
                    TxMode.SNAPSHOT_RO
            ).execute().join();
        }
    }

    public void getStudentsWithAvgMarkByGroup(long groupId) {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery(
                    "SELECT s.StudentName, AVG(CAST(m.Mark AS Double)) AS AvgMark "
                    + "FROM Students AS s "
                    + "JOIN Marks AS m ON s.StudentId = m.StudentId "
                    + "WHERE s.GroupId = " + groupId
                    + " GROUP BY s.StudentName",
                    TxMode.SNAPSHOT_RO
            ).execute().join();
        }
    }

    /**
     * Вставляет или обновляет оценку студента по курсу в транзакции с явным коммитом.
     *
     * @param studentId идентификатор студента
     * @param courseId  идентификатор курса
     * @param mark      оценка (1–5)
     */
    public void upsertMarkWithCommit(long studentId, long courseId, int mark) {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            var tx = session.beginTransaction(TxMode.SERIALIZABLE_RW).join().getValue();
            tx.createQuery(
                    "UPSERT INTO Marks (StudentId, CourseId, Mark) VALUES ("
                    + studentId + ", " + courseId + ", " + mark + ")"
            ).execute().join().getStatus().expectSuccess("upsertMark");
            tx.commit().join().getStatus().expectSuccess("commit");
        }
    }

    /**
     * Выполняет UPSERT оценки в транзакции и откатывает её — данные не изменяются.
     * Используется для демонстрации span {@code ydb.Rollback}.
     *
     * @param studentId идентификатор студента
     */
    public void upsertMarkWithRollback(long studentId) {
        long courseId = (studentId % 10) + 1;
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            var tx = session.beginTransaction(TxMode.SERIALIZABLE_RW).join().getValue();
            tx.createQuery(
                    "UPSERT INTO Marks (StudentId, CourseId, Mark) VALUES ("
                    + studentId + ", " + courseId + ", 1)"
            ).execute().join();
            tx.rollback().join().expectSuccess("rollback");
        }
    }

    /**
     * Выполняет запрос студентов группы через {@link tech.ydb.query.tools.SessionRetryContext}
     * — создаёт span {@code ydb.RunWithRetry} и дочерние {@code ydb.Try}.
     *
     * @param groupId идентификатор группы
     */
    public void getStudentsWithRetry(long groupId) {
        retryCtx.supplyResult(session ->
                session.createQuery(
                        "SELECT StudentId, StudentName FROM Students WHERE GroupId = " + groupId,
                        TxMode.SNAPSHOT_RO
                ).execute()
        ).join();
    }

    void selectBadQuery() {
        try (QuerySession session = queryClient.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery("SELECT * FROM NonExistentTable", TxMode.NONE).execute().join();
        }
    }

    private void executeYdbQuery(QuerySession session, String yql) {
        session.createQuery(yql, TxMode.NONE).execute().join().getStatus().expectSuccess(yql);
    }
}
