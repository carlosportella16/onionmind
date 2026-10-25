package com.onionmind.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/chat}: one independent question per call, no conversation history (Fase 5). */
@RestController
@RequestMapping("/api")
class ChatController {

    record ChatRequest(String question) {
    }

    private final ChatService chat;
    private final int maxQuestionChars;

    ChatController(ChatService chat, @Value("${chat.max-question-chars:1000}") int maxQuestionChars) {
        this.chat = chat;
        this.maxQuestionChars = maxQuestionChars;
    }

    @PostMapping("/chat")
    ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String question = request == null || request.question() == null ? "" : request.question().strip();
        if (question.isEmpty() || question.length() > maxQuestionChars) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(chat.ask(question));
    }
}
