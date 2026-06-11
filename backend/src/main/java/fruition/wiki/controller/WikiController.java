package fruition.wiki.controller;

import fruition.util.ErrorResponse;
import fruition.wiki.service.WikiService;
import fruition.wiki.dto.WikiGraphResponse;
import fruition.wiki.dto.WikiPageDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wiki")
@Tag(name = "Wiki", description = "Wiki 그래프 및 페이지 조회 API")
public class WikiController {

    private final WikiService wikiService;

    public WikiController(WikiService wikiService) {
        this.wikiService = wikiService;
    }

    @Operation(summary = "Wiki 그래프 조회", description = "모든 Wiki 노드(pages)와 엣지(links)를 반환합니다. 중앙 그래프 렌더링과 답변 후 하이라이트에 사용됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "그래프 조회 성공",
            content = @Content(schema = @Schema(implementation = WikiGraphResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/graph")
    public ResponseEntity<WikiGraphResponse> getGraph() {
        return ResponseEntity.ok(wikiService.findGraph());
    }

    @Operation(summary = "Wiki 페이지 상세 조회", description = "특정 Wiki 페이지의 상세 정보를 반환합니다. source_documents와 related_pages를 포함합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "페이지 조회 성공",
            content = @Content(schema = @Schema(implementation = WikiPageDetailResponse.class))),
        @ApiResponse(responseCode = "404", description = "페이지를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/pages/{wiki_page_id}")
    public ResponseEntity<WikiPageDetailResponse> getPage(
            @Parameter(description = "Wiki 페이지 ID", example = "wp_abc123")
            @PathVariable("wiki_page_id") String wikiPageId) {
        return ResponseEntity.ok(wikiService.findById(wikiPageId));
    }
}
