package fruition.chat.controller;

import fruition.chat.domain.ChatMessageReference;
import fruition.chat.dto.ChatMessageRelatedPageResponse;
import fruition.chat.dto.ChatMessageResponse;
import fruition.chat.dto.ChatMessagesResponse;
import fruition.chat.repository.ChatMessageReferenceRepository;
import fruition.chat.repository.ChatMessageRelatedPageRepository;
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
    private final ChatMessageRelatedPageRepository relatedPageRepository;

    public ChatController(ChatMessageRepository chatMessageRepository,
                          ChatMessageReferenceRepository referenceRepository,
                          ChatMessageRelatedPageRepository relatedPageRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
        this.relatedPageRepository = relatedPageRepository;
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

        // N+1 방지: 메시지 ID 목록으로 references와 related_pages를 한 번에 조회한다.
        Map<String, List<ChatMessageReference>> refsByMessageId = referenceRepository
                .findAllByChatMessageIdIn(messageIds).stream()
                .collect(Collectors.groupingBy(ChatMessageReference::getChatMessageId));

        Map<String, List<ChatMessageRelatedPageResponse>> relatedPagesByMessageId = relatedPageRepository
                .findAllByChatMessageIdIn(messageIds).stream()
                .collect(Collectors.groupingBy(
                        p -> p.getChatMessageId(),
                        Collectors.mapping(p -> new ChatMessageRelatedPageResponse(
                                p.getWikiPageId(), p.getPageType(), p.getTitle(), p.getSlug(),
                                p.getRelevanceScore() != null ? p.getRelevanceScore() : 0.0,
                                p.getRole(),
                                p.getDepth() != null ? p.getDepth() : 0,
                                p.getRank() != null ? p.getRank() : 0
                        ), Collectors.toList())
                ));

        List<ChatMessageResponse> responses = messages.stream()
                .map(m -> {
                    var refs = refsByMessageId.getOrDefault(m.getId(), List.of()).stream()
                            .map(r -> new fruition.chat.dto.ChatMessageReference(
                                    r.getId(), r.getReferenceType(),
                                    r.getRank(), r.getDocumentId(),
                                    r.getSourceBlockIds(),
                                    r.getQuote()
                            ))
                            .toList();
                    var relatedPages = relatedPagesByMessageId.getOrDefault(m.getId(), List.of());
                    return new ChatMessageResponse(m.getId(), m.getRole(), m.getContent(), m.getStatus(), m.getCreatedAt(), relatedPages, refs, m.getErrorMessage());
                })
                .toList();

        return ResponseEntity.ok(new ChatMessagesResponse(responses));
    }
}
