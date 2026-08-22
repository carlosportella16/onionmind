package com.onionmind.ingestion;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RawPageConsumer {
    private static final Logger log = LoggerFactory.getLogger(RawPageConsumer.class);
    private final IngestionPipeline pipeline;
    private final ObjectMapper mapper = new ObjectMapper(); // Jackson 3 registers java.time support natively

    public RawPageConsumer(IngestionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(topics = "raw-pages", groupId = "onionmind-ingestion")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            var event = mapper.readValue(record.value(), RawPageEvent.class);
            pipeline.process(event);
        } catch (Exception e) {
            // log and continue — must not poison the consumer group
            log.error("Failed to process event: {}", e.getMessage(), e);
        }
    }
}
