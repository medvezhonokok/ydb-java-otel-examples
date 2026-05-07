package tech.ydb.examples.otel;

import tech.ydb.common.transaction.TxMode;
import tech.ydb.query.QueryClient;
import tech.ydb.query.QuerySession;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Генератор нагрузки на университетскую БД. Выполняет циклически 5 типов операций:
 * <ol>
 *   <li>SELECT студентов по случайной группе (SNAPSHOT_RO)</li>
 *   <li>SELECT среднего балла по курсу (агрегация, SNAPSHOT_RO)</li>
 *   <li>UPSERT оценки в транзакции + commit (SERIALIZABLE_RW)</li>
 *   <li>UPSERT оценки в транзакции + rollback (имитация отмены)</li>
 *   <li>SELECT несуществующей таблицы — намеренная ошибка для метрики ydb.client.operation.failed</li>
 * </ol>
 * Между итерациями пауза 200–500 мс.
 */
public class LoadGenerator {
    private final QueryClient client;

    public LoadGenerator(QueryClient client) {
        this.client = client;
    }

    public void run() throws InterruptedException {
        long iteration = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                int op = (int) (iteration % 5);
                switch (op) {
                    case 0:
                        selectStudentsByGroup();
                        break;
                    case 1:
                        selectAvgMark();
                        break;
                    case 2:
                        upsertMark(iteration);
                        break;
                    case 3:
                        upsertMarkRollback(iteration);
                        break;
                    case 4:
                        selectBadQuery();
                        break;
                }
            } catch (Exception ignored) {
            }

            iteration++;
            TimeUnit.MILLISECONDS.sleep(200 + ThreadLocalRandom.current().nextInt(300));
        }
    }

    private void selectAvgMark() {
        final int courseId = ThreadLocalRandom.current().nextInt(1, 4);

        try (QuerySession session = client.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery(
                    "SELECT AVG(CAST(Mark AS Double)) AS AvgMark FROM Marks WHERE CourseId = " + courseId,
                    TxMode.SNAPSHOT_RO
            ).execute().join();
        }
    }

    private void selectStudentsByGroup() {
        final int groupId = ThreadLocalRandom.current().nextInt(1, 4);

        try (QuerySession session = client.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery(
                    "SELECT StudentId, StudentName FROM Students WHERE GroupId = " + groupId,
                    TxMode.SNAPSHOT_RO
            ).execute().join();
        }
    }

    private void upsertMark(long iteration) {
        final long studentId = (iteration % 3) + 1;

        final int courseId = ThreadLocalRandom.current().nextInt(1, 4);
        final int mark = ThreadLocalRandom.current().nextInt(3, 6);

        try (QuerySession session = client.createSession(Duration.ofSeconds(5)).join().getValue()) {
            var tx = session.beginTransaction(TxMode.SERIALIZABLE_RW).join().getValue();
            tx.createQuery(
                    "UPSERT INTO Marks (StudentId, CourseId, Mark) VALUES ("
                    + studentId + ", " + courseId + ", " + mark + ")"
            ).execute().join().getStatus().expectSuccess("upsertMark");
            tx.commit().join().getStatus().expectSuccess("commit");
        }
    }

    private void upsertMarkRollback(long iteration) {
        final long studentId = (iteration % 3) + 1;
        final int courseId = ThreadLocalRandom.current().nextInt(1, 4);

        try (QuerySession session = client.createSession(Duration.ofSeconds(5)).join().getValue()) {
            var tx = session.beginTransaction(TxMode.SERIALIZABLE_RW).join().getValue();
            tx.createQuery(
                    "UPSERT INTO Marks (StudentId, CourseId, Mark) VALUES ("
                    + studentId + ", " + courseId + ", 1)"
            ).execute().join();

            tx.rollback().join().expectSuccess("upsertMarkRollback rollback");
        }
    }

    private void selectBadQuery() {
        try (QuerySession session = client.createSession(Duration.ofSeconds(5)).join().getValue()) {
            session.createQuery(
                    "SELECT * FROM NonExistentTable",
                    TxMode.NONE
            ).execute().join();
        }
    }
}
