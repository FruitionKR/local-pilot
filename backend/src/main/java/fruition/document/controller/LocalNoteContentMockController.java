package fruition.document.controller;

import fruition.document.dto.NoteContentResponse;
import fruition.document.dto.NoteContentUpdateRequest;
import fruition.util.ErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** local frontend 프로토타입 전용 메모리 저장소. DB, MinIO, pipeline을 변경하지 않는다. */
@Profile("local")
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/documents")
public class LocalNoteContentMockController {

    private final Map<DraftKey, NoteContentResponse> drafts = new ConcurrentHashMap<>();

    @GetMapping("/{document_id}/content")
    public ResponseEntity<?> getContent(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("document_id") String documentId) {
        NoteContentResponse draft = drafts.get(new DraftKey(workspaceId, documentId));
        if (draft == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of("NOTE_DRAFT_NOT_FOUND", "저장된 local 노트 draft가 없습니다."));
        }
        return ResponseEntity.ok(draft);
    }

    @PutMapping("/{document_id}/content")
    public synchronized ResponseEntity<?> updateContent(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("document_id") String documentId,
            @RequestBody NoteContentUpdateRequest request) {
        if (request.markdown() == null || request.expectedContentVersion() == null
                || request.expectedContentVersion() < 0) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("INVALID_REQUEST", "markdown와 expected_content_version이 필요합니다."));
        }

        DraftKey key = new DraftKey(workspaceId, documentId);
        NoteContentResponse current = drafts.get(key);
        long currentVersion = current == null ? 0 : current.contentVersion();
        if (request.expectedContentVersion() != currentVersion) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("NOTE_CONTENT_VERSION_CONFLICT", "다른 편집 내용이 먼저 저장되었습니다."));
        }

        NoteContentResponse saved = new NoteContentResponse(
                documentId,
                request.markdown(),
                currentVersion + 1,
                Instant.now()
        );
        drafts.put(key, saved);
        return ResponseEntity.ok(saved);
    }

    private record DraftKey(String workspaceId, String documentId) {}
}
