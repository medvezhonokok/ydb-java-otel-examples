package tech.ydb.examples.otel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.query.QueryClient;
import tech.ydb.query.QuerySession;

import java.time.Duration;

/**
 * Утилита для инициализации схемы университетской БД и наполнения начальными данными.
 *
 * <p>Схема:
 * <ul>
 *   <li>Groups — учебные группы</li>
 *   <li>Students — студенты, привязанные к группам</li>
 *   <li>Courses — учебные курсы</li>
 *   <li>Lecturers — преподаватели</li>
 *   <li>Plan — план: какая группа изучает какой курс у какого преподавателя</li>
 *   <li>Marks — оценки студентов по курсам</li>
 * </ul>
 */
public class DatabaseUtil {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUtil.class);

    public void createTables(QueryClient client) {
        log.info("--[ CreateTables ]--");

        try (QuerySession session = client.createSession(Duration.ofSeconds(10)).join().getValue()) {
            execute(session, "CREATE TABLE IF NOT EXISTS Groups ("
                             + "GroupId Uint64, GroupName Utf8, PRIMARY KEY (GroupId))");
            execute(session, "CREATE TABLE IF NOT EXISTS Students ("
                             + "StudentId Uint64, StudentName Utf8, GroupId Uint64, PRIMARY KEY (StudentId))");
            execute(session, "CREATE TABLE IF NOT EXISTS Courses ("
                             + "CourseId Uint64, CourseName Utf8, PRIMARY KEY (CourseId))");
            execute(session, "CREATE TABLE IF NOT EXISTS Lecturers ("
                             + "LecturerId Uint64, LecturerName Utf8, PRIMARY KEY (LecturerId))");
            execute(session, "CREATE TABLE IF NOT EXISTS Plan ("
                             + "GroupId Uint64, CourseId Uint64, LecturerId Uint64, PRIMARY KEY (GroupId, CourseId))");
            execute(session, "CREATE TABLE IF NOT EXISTS Marks ("
                             + "StudentId Uint64, CourseId Uint64, Mark Uint8, PRIMARY KEY (StudentId, CourseId))");
        }
    }

    public void upsertTablesData(QueryClient client) {
        log.info("--[ UpsertTables ]--");

        try (QuerySession session = client.createSession(Duration.ofSeconds(10)).join().getValue()) {
            execute(session, "UPSERT INTO Groups (GroupId, GroupName) VALUES "
                             + "(1, 'M3432'), (2, 'M3433'), (3, 'M3434')");

            execute(session, "UPSERT INTO Courses (CourseId, CourseName) VALUES "
                             + "(1, 'Введение в программирование'), (2, 'Дискретная математика'), (3, 'АиСД')");

            execute(session, "UPSERT INTO Lecturers (LecturerId, LecturerName) VALUES "
                             + "(1, 'Кирилл Алексеевич'), (2, 'Михаил Дмитриевич'), (3, 'Мария Сергеевна')");

            execute(session, "UPSERT INTO Students (StudentId, StudentName, GroupId) VALUES "
                             + "(1, 'Сергей Иванов', 1), (2, 'Иванов Сергей', 1), (3, 'Иван Иванов', 2)");

            execute(session, "UPSERT INTO Plan (GroupId, CourseId, LecturerId) VALUES "
                             + "(1, 1, 1), (1, 2, 2), (2, 1, 1), (3, 3, 3)");

            execute(session, "UPSERT INTO Marks (StudentId, CourseId, Mark) VALUES "
                             + "(1, 1, 5), (2, 1, 4), (3, 1, 3)");
        }
    }

    private void execute(QuerySession session, String yql) {
        session.createQuery(yql, TxMode.NONE).execute().join().getStatus().expectSuccess(yql);
    }
}
