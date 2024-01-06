package io.quarkiverse.reactive.messsaging.nats.jetstream.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;

public class ReactiveMesssagingNatsJetstreamTracingTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest().setArchiveProducer(
            () -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(TestSpanExporter.class, Data.class, DataResource.class, DataConsumingBean.class))
            .withConfigurationResource("application-tracing.properties");

    @Inject
    TestSpanExporter spanExporter;

    @BeforeEach
    public void setup() {
        RestAssured.defaultParser = Parser.JSON;
        spanExporter.reset();
    }

    @Test
    public void tracing() {
        final var messageId = "c923ca9b-27ac-4dc3-ad61-8c6733f93b11";
        final var data = "N6cXzadfafM";

        RestAssured.given().pathParam("id", messageId).pathParam("data", data).post("/data/{id}/{data}").then().statusCode(204);

        final var spans = spanExporter.getFinishedSpanItems(3);
        assertThat(spans).isNotEmpty();

        List<SpanData> parentSpans = spans.stream().filter(spanData -> spanData.getParentSpanId().equals(SpanId.getInvalid()))
                .collect(Collectors.toList());
        assertEquals(1, parentSpans.size());

        for (var parentSpan : parentSpans) {
            assertThat(spans.stream().filter(spanData -> spanData.getParentSpanId().equals(parentSpan.getSpanId())).count())
                    .isEqualTo(1);
        }
    }
}
