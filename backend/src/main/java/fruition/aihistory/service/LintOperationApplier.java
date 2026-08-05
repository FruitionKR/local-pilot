package fruition.aihistory.service;

import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationStatus;
import fruition.aihistory.domain.ResourceType;
import fruition.aihistory.dto.OperationResultRequest;
import fruition.aihistory.dto.OperationResultResponse;
import fruition.aihistory.exception.InvalidCallbackPayloadException;
import fruition.aihistory.exception.OperationNotFoundException;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.repository.WikiPageContributionRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.wiki.repository.WikiPageVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** lint 결과를 새 Wiki revision과 작업 변경 이력으로 저장한다. */
@Component
public class LintOperationApplier {

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageVersionRepository versionRepository;
    private final WikiPageContributionRepository contributionRepository;
    private final LineCounter lineCounter;

    public LintOperationApplier(OperationLogRepository operationLogRepository,
                                OperationChangeRepository operationChangeRepository,
                                WikiPageRepository wikiPageRepository,
                                WikiPageVersionRepository versionRepository,
                                WikiPageContributionRepository contributionRepository,
                                LineCounter lineCounter) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.wikiPageRepository = wikiPageRepository;
        this.versionRepository = versionRepository;
        this.contributionRepository = contributionRepository;
        this.lineCounter = lineCounter;
    }

    @Transactional
    public OperationResultResponse apply(String operationId, OperationResultRequest request,
                                         List<LoadedPage> loaded, String payloadHash, Instant now) {
        OperationLog operation = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
        List<LoadedPage> ordered = loaded.stream()
                .sorted(Comparator.comparing(LoadedPage::pageId))
                .toList();

        for (LoadedPage page : ordered) {
            applyPage(operation, page, now);
        }

        OperationStatus status = request.isFailure()
                ? OperationStatus.partially_succeeded
                : OperationStatus.succeeded;
        operation.complete(status, request.summary(), ordered.size(), payloadHash, now);
        return new OperationResultResponse(operationId, status.name(), ordered.size());
    }

    private void applyPage(OperationLog operation, LoadedPage page, Instant now) {
        String pageId = page.pageId();
        WikiPage wikiPage = wikiPageRepository.findByIdForUpdate(pageId)
                .orElseThrow(() -> new InvalidCallbackPayloadException(
                        "Wiki 페이지를 찾을 수 없습니다: pageId=" + pageId));
        if (!wikiPage.getWorkspaceId().equals(operation.getWorkspaceId())) {
            throw new InvalidCallbackPayloadException(
                    "다른 워크스페이스의 페이지입니다: pageId=" + pageId);
        }

        Optional<WikiPageVersion> previous =
                versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(pageId);
        long revision = versionRepository.findMaxRevision(pageId) + 1;
        int contributionCount = (int) contributionRepository.countByIdPageIdAndActiveTrue(pageId);

        versionRepository.save(new WikiPageVersion(
                pageId, revision, contributionCount, page.markdown(), page.markdownKey(),
                page.contentHash(), operation.getOperationId(), operation.getUserId(), now));

        Long beforeRevision = previous.map(WikiPageVersion::getRevision).orElse(null);
        LineCounter.LineCount lines = lineCounter.count(pageId, beforeRevision,
                previous.map(WikiPageVersion::getMarkdown).orElse(null), revision, page.markdown());
        operationChangeRepository.save(new OperationChange(
                operation.getOperationId(), ResourceType.wiki_page, pageId,
                beforeRevision, revision,
                beforeRevision == null ? ChangeType.created : ChangeType.updated,
                null, lines.additions(), lines.deletions()));
    }

    public record LoadedPage(
            String pageId,
            String markdownKey,
            String markdown,
            String contentHash
    ) {}
}
