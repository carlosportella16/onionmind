package com.onionmind.chat;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Answer;
import com.onionmind.ai.TaskContext;
import com.onionmind.search.PassageRetriever;
import com.onionmind.search.PassageRetriever.Passage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The RAG pipeline (phase5-rag-chat D6, master-sdd sec. 9): rewrite with its own deadline
 * (on failure, retrieval goes on with the original question), retrieval always, generation
 * within {@code chat.answer-timeout}. A failed or late answer still returns the search
 * results, flagged {@code degraded}. Both LLM calls are interactive, so they may use the
 * budget batch tasks leave free (ai-token-quota D4).
 */
@Service
class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final PassageRetriever retriever;
    private final AIOrchestrator ai;
    private final int topK;
    private final int maxContextChars;
    private final Duration rewriteTimeout;
    private final Duration answerTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    ChatService(PassageRetriever retriever,
                AIOrchestrator ai,
                @Value("${chat.top-k:8}") int topK,
                @Value("${chat.max-context-chars:12000}") int maxContextChars,
                @Value("${chat.rewrite-timeout:3s}") Duration rewriteTimeout,
                @Value("${chat.answer-timeout:12s}") Duration answerTimeout) {
        this.retriever = retriever;
        this.ai = ai;
        this.topK = topK;
        this.maxContextChars = maxContextChars;
        this.rewriteTimeout = rewriteTimeout;
        this.answerTimeout = answerTimeout;
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }

    ChatResponse ask(String question) {
        var retrieval = retriever.retrieve(question, rewrite(question), topK, maxContextChars);
        List<Passage> passages = retrieval.passages();
        if (passages.isEmpty()) {
            // Nothing to ground an answer on: no LLM call.
            return new ChatResponse(Answer.NO_ANSWER, List.of(), retrieval.results(), false);
        }

        List<String> sources = passages.stream().map(ChatService::asSource).toList();
        var ctx = interactive(TaskContext.TaskType.ANSWER, question.length() + sources.stream().mapToInt(String::length).sum());
        Answer answer;
        try {
            answer = CompletableFuture.supplyAsync(() -> ai.answer(question, sources, ctx), executor)
                .orTimeout(answerTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .join();
        } catch (CompletionException e) {
            log.warn("Chat answer degraded: {}", e.getCause().toString());
            return new ChatResponse("", List.of(), retrieval.results(), true);
        }

        List<ChatResponse.Source> cited = new ArrayList<>();
        for (String id : answer.citations()) {
            Passage p = passages.get(Integer.parseInt(id.substring(1)) - 1);
            cited.add(new ChatResponse.Source(id, p.page().url(), p.text()));
        }
        return new ChatResponse(answer.text(), cited, retrieval.results(), false);
    }

    /** The English search query, or null when the rewrite fails or misses its deadline. */
    private String rewrite(String question) {
        var ctx = interactive(TaskContext.TaskType.REWRITE_QUERY, question.length());
        return CompletableFuture.supplyAsync(() -> ai.rewriteQuery(question, ctx).query(), executor)
            .completeOnTimeout(null, rewriteTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(e -> {
                log.warn("Chat query rewrite failed, retrieving with the original question: {}", e.toString());
                return null;
            })
            .join();
    }

    private static TaskContext interactive(TaskContext.TaskType type, int chars) {
        return new TaskContext(type, chars / 4, null, false, true, null, 0);
    }

    /** What the model sees of one source; the orchestrator wraps it in its {@code <source>} tag. */
    private static String asSource(Passage p) {
        String summary = p.page().summary() == null ? "" : "Summary: " + p.page().summary() + "\n";
        return "URL: " + p.page().url() + "\n" + summary + "\n" + p.text();
    }
}
