# YDB Java SDK — OpenTelemetry Examples

Демонстрационное приложение для [ydb-java-sdk](https://github.com/ydb-platform/ydb-java-sdk),
показывающее интеграцию с OpenTelemetry: метрики и трейсы из реальной нагрузки на YDB.

## Схема базы данных

```
Groups    (GroupId, GroupName)
Students  (StudentId, StudentName, GroupId)
Courses   (CourseId, CourseName)
Lecturers (LecturerId, LecturerName)
Plan      (GroupId, CourseId, LecturerId)
Marks     (StudentId, CourseId, Mark)
```

## Метрики

| Метрика | Тип | Описание | Теги |
|---------|-----|----------|------|
| `db.client.operation.duration` | Histogram | Длительность операций YDB (p50/p95/p99) | `ydb.operation.name`, `db.namespace`, `server.address`, `server.port` |
| `ydb.client.operation.failed` | Counter | Количество ошибочных операций | `ydb.operation.name`, `db.response.status.code` |
| `ydb.query.session.count` | Gauge | Текущее состояние пула сессий (idle/used) | `ydb.query.session.pool.name`, `ydb.query.session.state` |
| `ydb.query.session.create_time` | Histogram | Время создания новой сессии в пуле | `ydb.query.session.pool.name` |

## Панели дашборда

| Панель | Что показывает |
|--------|---------------|
| Request Rate (RPS) | Частота операций по типу: ExecuteQuery, Commit, Rollback |
| Error Rate by Status Code | Ошибки по типу операции и статус-коду YDB |
| Operation Latency p50/p95/p99 | Перцентили задержки по типу операции |
| Session Pool Idle / Used | Текущее состояние пула сессий |
| Session Create Time | Время создания новой сессии в пуле |
| Total Operations | Суммарное количество операций за выбранный период |
| Total Errors | Суммарное количество ошибок (красный если > 0) |
| Traces | Последние трейсы из Jaeger |

## Скриншоты

![Dashboard](images/dashboard-1.png)
![Dashboard](images/dashboard-2.png)

## Архитектура

```
App
  └─ OTLP gRPC (:4317)
        └─ OTel Collector              # принимает метрики и трейсы по OTLP
              ├─ Prometheus exporter   # поднимает /metrics эндпоинт (:9464)
              │    └─ ← Prometheus     # получает метрики каждые 15с
              └─ OTLP → Jaeger         # пересылает трейсы в Jaeger (:4317)

Grafana → Prometheus (метрики)         # строит графики метрик
Grafana → Jaeger     (трейсы)          # визуализирует трейсы
```

## Быстрый старт

### 1. Установить локальный снепшот SDK

```bash
git clone https://github.com/ydb-platform/ydb-java-sdk
cd ydb-java-sdk
mvn install -DskipTests
```

### 2. Поднять инфраструктуру

```bash
cd ../ydb-java-otel-examples
docker compose up -d
```

Проверить что все сервисы запустились:
```bash
docker compose ps
```

Ожидаемый вывод (5 сервисов):
```
CONTAINER ID   IMAGE                                         STATUS
xxxxxxxxxxxx   grafana/grafana:10.4.2                        Up
xxxxxxxxxxxx   prom/prometheus:latest                        Up
xxxxxxxxxxxx   otel/opentelemetry-collector-contrib:latest   Up
xxxxxxxxxxxx   jaegertracing/all-in-one:latest               Up
xxxxxxxxxxxx   ydbplatform/local-ydb:trunk                   Up (healthy)
```

### 3. Собрать и запустить

```bash
mvn package -q
java -jar target/ydb-java-otel-examples-1.0-SNAPSHOT.jar
```

Аргументы (опциональны):
```
java -jar target/ydb-java-otel-examples-1.0-SNAPSHOT.jar \
  "grpc://localhost:2136/?database=/local" \
  "http://localhost:4317"
```

### 4. Открыть Grafana

http://localhost:3000

Дашборд **YDB Java SDK — OpenTelemetry** появится автоматически.

### 5. Открыть Jaeger UI

http://localhost:16686
