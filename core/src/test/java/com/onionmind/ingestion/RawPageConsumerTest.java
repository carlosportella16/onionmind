package com.onionmind.ingestion;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RawPageConsumerTest {

    @Mock
    private IngestionPipeline pipeline;

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("raw-pages", 0, 0L, "key", payload);
    }

    @Test
    void validEventIsDeserializedAndDelegatedToPipeline() {
        var consumer = new RawPageConsumer(pipeline);
        String payload = """
            {"url":"http://example.onion","source_type":"tor","html":"<html></html>","fetched_at":"2026-08-22T00:00:00Z"}
            """;

        consumer.consume(record(payload));

        verify(pipeline).process(any(RawPageEvent.class));
    }

    @Test
    void malformedEventIsLoggedAndDoesNotThrow() {
        var consumer = new RawPageConsumer(pipeline);

        consumer.consume(record("{not valid json"));

        // must not throw — the consumer group must not be poisoned
    }
}
