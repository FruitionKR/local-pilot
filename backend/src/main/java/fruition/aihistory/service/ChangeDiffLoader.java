package fruition.aihistory.service;

import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.ResourceType;
import fruition.document.domain.DocumentContentVersionId;
import fruition.document.dto.DocumentContentDiffResponse;
import fruition.document.dto.MarkdownDiff;
import fruition.document.repository.DocumentContentVersionRepository;
import fruition.document.service.MarkdownDiffService;
import fruition.wiki.domain.WikiPageVersionId;
import fruition.wiki.repository.WikiPageVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 변경내역 한 건의 실제 변경분을 계산한다. 저장된 본문 두 벌을 읽어 그 자리에서 비교한다.
 *
 * <p>한 항목의 계산이 실패해도 상세 조회 전체를 실패시키지 않는다. 큰 페이지 하나 때문에
 * 나머지 멀쩡한 항목까지 못 보는 것은 잘못된 트레이드오프다.
 */
@Component
public class ChangeDiffLoader {

    private static final Logger log = LoggerFactory.getLogger(ChangeDiffLoader.class);

    private final WikiPageVersionRepository wikiVersionRepository;
    private final DocumentContentVersionRepository documentVersionRepository;
    private final MarkdownDiffService markdownDiffService;

    public ChangeDiffLoader(WikiPageVersionRepository wikiVersionRepository,
                            DocumentContentVersionRepository documentVersionRepository,
                            MarkdownDiffService markdownDiffService) {
        this.wikiVersionRepository = wikiVersionRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.markdownDiffService = markdownDiffService;
    }

    public Diff load(OperationChange change) {
        Long before = change.getBeforeRevision();
        Long after = change.getAfterRevision();
        // 생성·삭제·위임·재작성실패는 비교할 짝이 없다.
        if (before == null || after == null) {
            return Diff.none();
        }

        Optional<String> beforeMarkdown = markdown(change, before);
        Optional<String> afterMarkdown = markdown(change, after);
        if (beforeMarkdown.isEmpty() || afterMarkdown.isEmpty()) {
            return Diff.none();
        }

        try {
            MarkdownDiff diff = markdownDiffService.diff(
                    before, beforeMarkdown.get(), after, afterMarkdown.get());
            return new Diff(diff.hunks(), false);
        } catch (RuntimeException e) {
            log.warn("[변경분 계산 생략] resourceId={} {}→{} reason={}",
                    change.getResourceId(), before, after, e.getMessage());
            return new Diff(null, true);
        }
    }

    private Optional<String> markdown(OperationChange change, long revision) {
        if (change.getResourceType() == ResourceType.wiki_page) {
            return wikiVersionRepository
                    .findById(new WikiPageVersionId(change.getResourceId(), revision))
                    .map(v -> v.getMarkdown());
        }
        return documentVersionRepository
                .findById(new DocumentContentVersionId(change.getResourceId(), revision))
                .map(v -> v.getMarkdown());
    }

    /**
     * @param tooLarge 두 본문 차이가 너무 커서 계산을 거부한 경우. 개별 diff 엔드포인트로도 볼 수 없다
     */
    public record Diff(List<DocumentContentDiffResponse.Hunk> hunks, boolean tooLarge) {
        private static final Diff NONE = new Diff(null, false);

        public static Diff none() {
            return NONE;
        }
    }
}
