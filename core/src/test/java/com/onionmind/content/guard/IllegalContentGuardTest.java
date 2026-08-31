package com.onionmind.content.guard;

import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ProcessingResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IllegalContentGuardTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private IllegalContentGuard guard(String denylist, String patterns) {
        return new IllegalContentGuard(
            new ByteArrayResource(denylist.getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource(patterns.getBytes(StandardCharsets.UTF_8)),
            registry);
    }

    private Document doc(String url, String text) {
        return new Document(url, "tor", "<html>" + text + "</html>", text, DocumentType.HTML);
    }

    @Test
    void haltsWhenHostIsOnTheDenylist() {
        var guard = guard("# banned services\nevilservice.onion\n", "");

        ProcessingResult result = guard.process(doc("http://EvilService.onion/page", "innocuous text"));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.HALT);
        assertThat(result.error()).isEqualTo("url-denylist");
    }

    @Test
    void haltsWhenTextMatchesAPattern() {
        var guard = guard("", "# patterns\nnothing here\nforbidden\\s+phrase\n");

        ProcessingResult result = guard.process(doc("http://ok.onion/", "this contains a forbidden   phrase inside"));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.HALT);
        assertThat(result.error()).isEqualTo("text-pattern:2");
        assertThat(registry.get("content.quarantined.total").tag("reason", "text-pattern").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void passesCleanContentThrough() {
        var guard = guard("evil.onion\n", "forbidden\n");

        ProcessingResult result = guard.process(doc("http://good.onion/", "perfectly ordinary content"));

        assertThat(result.status()).isEqualTo(ProcessingResult.Status.SUCCESS);
    }

    @Test
    void passesWhenThereIsNoExtractedTextToScan() {
        var guard = guard("evil.onion\n", "forbidden\n");
        Document noText = new Document("http://good.onion/", "tor", "<html></html>", null, DocumentType.HTML);

        assertThat(guard.process(noText).status()).isEqualTo(ProcessingResult.Status.SUCCESS);
    }

    @Test
    void runsAtOrderFiveForHtml() {
        var guard = guard("", "");

        assertThat(guard.order()).isEqualTo(5);
        assertThat(guard.supports(DocumentType.HTML)).isTrue();
        assertThat(guard.supports(DocumentType.PDF)).isFalse();
    }
}
