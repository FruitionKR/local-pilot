package fruition.core.chat.controller;

import fruition.core.chat.dto.ChatWikiExportRequest;
import fruition.core.chat.dto.ChatWikiExportResponse;
import fruition.core.chat.service.ChatWikiExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅 Wiki page화 API. 세션을 Markdown 문서로 직렬화해 기존 문서 ingestion 파이프라인에 넣는다.
 * (docs/backlog/spec/chat-to-wiki-contract.md)
 */
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/chat/sessions")
@Tag(name = "Chat Sessions", description = "채팅 세션 및 메시지 기록 API")
public class ChatWikiExportController {

    private final ChatWikiExportService chatWikiExportService;

    public ChatWikiExportController(ChatWikiExportService chatWikiExportService) {
        this.chatWikiExportService = chatWikiExportService;
    }

    @Operation(summary = "채팅 Wiki page화",
            description = "세션(full) 또는 선택 문답(partial)을 Markdown 문서로 저장하고 처리 큐에 등록합니다. "
                    + "위키 생성은 파이프라인이 비동기로 수행합니다.")
    @ApiResponse(responseCode = "202", description = "Wiki 생성 작업이 대기열에 등록됨",
            content = @Content(schema = @Schema(implementation = ChatWikiExportResponse.class)))
    @PostMapping("/{session_id}/wiki")
    public ResponseEntity<ChatWikiExportResponse> exportToWiki(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "채팅 세션 ID", example = "session_abc12345")
            @PathVariable("session_id") String sessionId,
            @RequestBody ChatWikiExportRequest request) {
        return ResponseEntity.accepted().body(chatWikiExportService.export(workspaceId, userId, sessionId, request));
    }

    @Operation(summary = "[임시] 채팅 Wiki page화 Markdown 미리보기",
            description = "세션을 llmPipeline 입력용 Markdown으로 직렬화해 결과만 반환합니다. 저장/파이프라인 호출은 하지 않습니다.")
    @PostMapping(value = "/{session_id}/wiki/preview", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> previewWikiMarkdown(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Parameter(description = "채팅 세션 ID", example = "session_abc12345")
            @PathVariable("session_id") String sessionId) {
        String markdown = chatWikiExportService.previewMarkdown(workspaceId, userId, sessionId);
        return ResponseEntity.ok(markdown);
    }
}
