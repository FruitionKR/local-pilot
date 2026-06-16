package fruition.chat.controller;

import fruition.chat.domain.ChatMessageReference;
import fruition.chat.dto.ChatMessageResponse;
import fruition.chat.dto.ChatMessagesResponse;
import fruition.chat.repository.ChatMessageReferenceRepository;
import fruition.chat.repository.ChatMessageRepository;
import fruition.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@Tag(name = "Chat", description = "채팅 기록 조회 API")
public class ChatController {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;

    public ChatController(ChatMessageRepository chatMessageRepository,
                          ChatMessageReferenceRepository referenceRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
    }

    @Operation(summary = "채팅 기록 조회", description = "모든 채팅 메시지 기록을 생성 순서대로 반환합니다. 오른쪽 채팅 영역의 이전 질문/답변 표시에 사용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "채팅 기록 조회 성공",
            content = @Content(schema = @Schema(implementation = ChatMessagesResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/messages")
    public ResponseEntity<ChatMessagesResponse> getMessages() {
        var messages = chatMessageRepository.findAllByOrderByCreatedAtAsc();
        List<String> messageIds = messages.stream().map(m -> m.getId()).toList();

        // N+1 방지: 메시지 ID 목록으로 references를 한 번에 조회한다.
        Map<String, List<ChatMessageReference>> refsByMessageId = referenceRepository
                .findAllByChatMessageIdIn(messageIds).stream()
                .collect(Collectors.groupingBy(ChatMessageReference::getChatMessageId));

        List<ChatMessageResponse> responses = messages.stream()
                .map(m -> {
                    var refs = refsByMessageId.getOrDefault(m.getId(), List.of()).stream()
                            .map(r -> new fruition.chat.dto.ChatMessageReference(
                                    r.getId(), r.getReferenceType(),
                                    r.getWikiPageId(), r.getDocumentId(),
                                    r.getPageRole(),
                                    r.getRelevanceScore() != null ? r.getRelevanceScore() : 0.0,
                                    r.getRank(), r.getPageNumber(),
                                    r.getParagraphIndex(), r.getSentenceIndex(),
                                    r.getQuote()
                            ))
                            .toList();
                    return new ChatMessageResponse(m.getId(), m.getRole(), m.getContent(), m.getStatus(), m.getCreatedAt(), refs);
                })
                .toList();

        return ResponseEntity.ok(new ChatMessagesResponse(responses));
    }
}
