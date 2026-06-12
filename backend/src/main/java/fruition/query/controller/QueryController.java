package fruition.query.controller;

import fruition.util.ErrorResponse;
import fruition.query.dto.QueryRequest;
import fruition.query.dto.QueryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/query")
@Tag(name = "Query", description = "Wiki 기반 자연어 질의 API")
public class QueryController {

    @Operation(
        summary = "Wiki 기반 자연어 질의",
        description = "질문을 받아 Wiki 페이지를 검색하고 LLM으로 답변을 생성합니다. " +
                      "응답에는 답변, 관련 Wiki 페이지, 원본 출처, 그래프 하이라이트 경로가 포함됩니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "질의 성공",
            content = @Content(schema = @Schema(implementation = QueryResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 (질문이 비어 있는 경우)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<?> query(@Valid @RequestBody QueryRequest request) {
        return ResponseEntity
                .status(HttpStatus.NOT_IMPLEMENTED)
                .body(ErrorResponse.of("NOT_IMPLEMENTED", "준비 중입니다."));
    }
}
