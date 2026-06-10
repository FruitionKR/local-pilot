package fruition.poc.backend.chat.api;

import fruition.poc.backend.chat.dto.ChatMessagesResponse;
import fruition.poc.backend.common.ErrorResponse;
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

@RestController
@RequestMapping("/api/chat")
@Tag(name = "Chat", description = "채팅 기록 조회 API")
public class ChatController {

    @Operation(summary = "채팅 기록 조회", description = "모든 채팅 메시지 기록을 생성 순서대로 반환합니다. 오른쪽 채팅 영역의 이전 질문/답변 표시에 사용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "채팅 기록 조회 성공",
            content = @Content(schema = @Schema(implementation = ChatMessagesResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/messages")
    public ResponseEntity<ChatMessagesResponse> getMessages() {
        return ResponseEntity.ok(new ChatMessagesResponse(List.of()));
    }
}
