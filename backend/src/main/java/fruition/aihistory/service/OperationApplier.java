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
import fruition.wiki.domain.WikiPageContribution;
import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.repository.WikiPageContributionRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.wiki.repository.WikiPageVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 읽어둔 결과를 DB에 반영한다. 저장소 읽기를 마친 뒤 <b>한 트랜잭션</b>으로 처리한다.
 *
 * <p>{@link OperationIngestService}와 분리한 이유는 자기 호출로는 {@code @Transactional}이
 * 걸리지 않기 때문이다.
 */
@Component
public class OperationApplier {

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageVersionRepository versionRepository;
    private final WikiPageContributionRepository contributionRepository;
    private final WikiLineCounter lineCounter;

    public OperationApplier(OperationLogRepository operationLogRepository,
                            OperationChangeRepository operationChangeRepository,
                            WikiPageRepository wikiPageRepository,
                            WikiPageVersionRepository versionRepository,
                            WikiPageContributionRepository contributionRepository,
                            WikiLineCounter lineCounter) {
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

        int recorded = 0;
        for (LoadedPage page : loaded) {
            if (applyPage(operation, page, now)) {
                recorded++;
            }
        }

        // 부분 실패여도 이미 만든 페이지는 기록한다.
        // 안 그러면 Wiki에는 있는데 로그에 없는 페이지가 영영 복구 대상에서 빠진다.
        OperationStatus status = request.isFailure()
                ? OperationStatus.partially_succeeded
                : OperationStatus.succeeded;
        operation.complete(status, request.summary(), recorded, payloadHash, now);
        return new OperationResultResponse(operationId, status.name(), recorded);
    }

    /** @return 새 버전을 만들었으면 true. 내용이 그대로면 건너뛴다 */
    private boolean applyPage(OperationLog operation, LoadedPage page, Instant now) {
        String pageId = page.pageId();
        WikiPage wikiPage = wikiPageRepository.findById(pageId)
                .orElseThrow(() -> new InvalidCallbackPayloadException(
                        "Wiki 페이지를 찾을 수 없습니다: pageId=" + pageId));
        if (!wikiPage.getWorkspaceId().equals(operation.getWorkspaceId())) {
            throw new InvalidCallbackPayloadException(
                    "다른 워크스페이스의 페이지입니다: pageId=" + pageId);
        }

        Optional<WikiPageVersion> previous =
                versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(pageId);
        if (previous.isPresent() && previous.get().getContentHash().equals(page.contentHash())) {
            return false;
        }

        long revision = versionRepository.findMaxRevision(pageId) + 1;

        // 기여를 먼저 넣어야 그 시점 기여 수가 나온다. 그 값이 버전 행에 들어간다.
        contributionRepository.save(new WikiPageContribution(
                pageId, operation.getOperationId(), operation.getTargetDocumentId(),
                revision, page.contributionKey(), now));
        int contributionCount = (int) contributionRepository.countByIdPageIdAndActiveTrue(pageId);

        versionRepository.save(new WikiPageVersion(
                pageId, revision, contributionCount, page.markdown(), page.markdownKey(),
                page.contentHash(), operation.getOperationId(), operation.getUserId(), now));

        // 검증을 마친 뒤에만 현재 본문 포인터를 옮긴다.
        wikiPage.moveMarkdownUri(page.markdownKey(), now);

        Long beforeRevision = previous.map(WikiPageVersion::getRevision).orElse(null);
        WikiLineCounter.LineCount lines = lineCounter.count(pageId, previous.orElse(null),
                page.markdown(), beforeRevision, revision);
        operationChangeRepository.save(new OperationChange(
                operation.getOperationId(), ResourceType.wiki_page, pageId,
                beforeRevision, revision,
                beforeRevision == null ? ChangeType.created : ChangeType.updated,
                null, lines.additions(), lines.deletions()));
        return true;
    }

    /** 저장소에서 읽어 검증까지 마친 페이지 하나. */
    public record LoadedPage(String pageId, String markdownKey, String contributionKey,
                             String markdown, String contentHash) {}
}
