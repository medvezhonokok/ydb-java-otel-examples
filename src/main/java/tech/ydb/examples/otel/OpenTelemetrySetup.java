package tech.ydb.examples.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.util.concurrent.TimeUnit;


/**
 * Утилита для настройки OpenTelemetry SDK с экспортом трейсов и метрик через OTLP/gRPC.
 *
 * <p>Конфигурирует:
 * <ul>
 *   <li>{@link SdkTracerProvider} с {@link BatchSpanProcessor} → OTLP-эндпоинт</li>
 *   <li>{@link SdkMeterProvider} с {@link PeriodicMetricReader} (интервал 5 с) → OTLP-эндпоинт</li>
 * </ul>
 */
public class OpenTelemetrySetup {

    /**
     * Создаёт и возвращает настроенный {@link OpenTelemetry}.
     *
     * @param otlpEndpoint адрес OTLP-коллектора, например {@code http://localhost:4317}
     * @param serviceName  имя сервиса, попадающее в атрибут {@code service.name} ресурса
     */
    public static OpenTelemetry create(String otlpEndpoint, String serviceName) {
        var resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"), serviceName)
                .build();

        var tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(
                        OtlpGrpcSpanExporter.builder().setEndpoint(otlpEndpoint).build()
                ).build())
                .setResource(resource)
                .build();

        var meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(
                        OtlpGrpcMetricExporter.builder().setEndpoint(otlpEndpoint).build()
                ).setInterval(5, TimeUnit.SECONDS).build())
                .setResource(resource)
                .build();

        return OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .setTracerProvider(tracerProvider)
                .build();
    }
}
