package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.MarkdownDiff;

import java.util.List;

/** Wiki 페이지 두 revision 사이의 변경분. hunk 구조는 문서 diff와 같다. */
public record WikiPageDiffResponse(
        @JsonProperty("page_id") String pageId,
        @JsonProperty("from_revision") long fromRevision,
        @JsonProperty("to_revision") long toRevision,
        int additions,
        int deletions,
        List<DocumentContentDiffResponse.Hunk> hunks
) {
    public static WikiPageDiffResponse from(String pageId, MarkdownDiff diff) {
        return new WikiPageDiffResponse(pageId, diff.fromVersion(), diff.toVersion(),
                diff.additions(), diff.deletions(), diff.hunks());
    }
}
