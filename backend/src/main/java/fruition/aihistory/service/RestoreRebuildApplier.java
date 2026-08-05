package fruition.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationStatus;
import fruition.aihistory.domain.ResourceType;
import fruition.aihistory.domain.RestoreAction;
import fruition.aihistory.dto.OperationResultRequest;
import fruition.aihistory.dto.OperationResultResponse;
import fruition.aihistory.dto.PageRestorePlan;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.exception.InvalidCallbackPayloadException;
import fruition.aihistory.exception.OperationNotFoundException;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.repository.WikiPageRepository;
import fruition.wiki.repository.WikiPageVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 재조립 결과를 DB에 반영해 복구 작업을 끝낸다. 저장소 읽기를 마친 뒤 <b>한 트랜잭션</b>으로 처리한다.
 *
 * <p>ingest 적재와 다른 점은 <b>기여를 만들지 않는다</b>는 것이다. 조립에 쓴 조각은 이미
 * {@code wiki_page_contributions}에 있고 복구가 살려둔 것들이다. 따라서 {@code contribution_count}도
 * 다시 세지 않고 복구가 보관해 둔 {@code restore_manifest}에서 꺼내 쓴다. 그사이 새 ingest가
 * 들어와도 목표값이 흔들리지 않게 하기 위해서다.
 */
@Component
public class RestoreRebuildApplier {

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageVersionRepository versionRepository;
    private final LineCounter lineCounter;
    private final ObjectMapper objectMapper;

    public RestoreRebuildApplier(OperationLogRepository operationLogRepository,
                                 OperationChangeRepository operationChangeRepository,
                                 WikiPageRepository wikiPageRepository,
                                 WikiPageVersionRepository versionRepository,
                                 LineCounter lineCounter,
                                 ObjectMapper objectMapper) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.wikiPageRepository = wikiPageRepository;
        this.versionRepository = versionRepository;
        this.lineCounter = lineCounter;
        this.objectMapper = objectMapper;
    }

    /** {@code recorded_changes}는 이 복구 작업이 남긴 전체 변경내역 수다. 복구가 먼저 만든 삭제·복원·위임 기록을 포함한다. */
    @Transactional
    public OperationResultResponse apply(String operationId, OperationResultRequest request,
                                         List<RebuiltPage> loaded, String payloadHash, Instant now) {
        OperationLog operation = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));

        Map<String, Integer> targetCounts = targetContributionCounts(operation);
        for (RebuiltPage page : loaded) {
            applyPage(operation, page, targetCounts, now);
        }
        for (OperationResultRequest.FailedPage failed : request.failedPagesOrEmpty()) {
            recordFailure(operation, failed, targetCounts);
        }

        int changed = (int) operationChangeRepository.countByOperationId(operationId);
        OperationStatus status = request.failedPagesOrEmpty().isEmpty() && !request.isFailure()
                ? OperationStatus.succeeded
                : OperationStatus.partially_succeeded;
        // llmPipeline 복구 결과에는 summary가 없다. 복구 실행 때 남긴 요약을 지우지 않는다.
        String summary = request.summary() != null ? request.summary() : operation.getSummary();
        operation.complete(status, summary, changed, payloadHash, now);
        return new OperationResultResponse(operationId, status.name(), changed);
    }

    /**
     * 지시서에 담은 페이지별 목표 기여 수. 여기 없는 페이지가 결과에 오면 요청하지 않은 것이므로 거절한다.
     *
     * <p>{@code restore}로 판정한 페이지도 포함한다. Backend가 이미 되돌렸지만 llmPipeline도 자기
     * 사본을 만들어 결과에 실어 보내기 때문이다(source page가 그렇다). 본문이 같아 아래에서
     * {@code content_hash} 비교로 걸러지므로 새 revision이 생기지는 않는다.
     */
    private Map<String, Integer> targetContributionCounts(OperationLog operation) {
        if (operation.getRestoreManifest() == null) {
            throw new InvalidCallbackPayloadException(
                    "복구 지시서가 없는 작업입니다: operationId=" + operation.getOperationId());
        }
        try {
            RestorePlan plan = objectMapper.readValue(operation.getRestoreManifest(), RestorePlan.class);
            Map<String, Integer> counts = new HashMap<>();
            for (PageRestorePlan page : plan.pages()) {
                if (page.action() == RestoreAction.rebuild || page.action() == RestoreAction.restore) {
                    counts.put(page.pageId(), page.contributionCount());
                }
            }
            return counts;
        } catch (Exception e) {
            throw new IllegalStateException("복구 지시서를 읽지 못했습니다: operationId="
                    + operation.getOperationId(), e);
        }
    }

    private void applyPage(OperationLog operation, RebuiltPage page,
                           Map<String, Integer> targetCounts, Instant now) {
        String pageId = page.pageId();
        int contributionCount = targetCount(targetCounts, pageId);

        WikiPage wikiPage = wikiPageRepository.findById(pageId)
                .orElseThrow(() -> new InvalidCallbackPayloadException(
                        "Wiki 페이지를 찾을 수 없습니다: pageId=" + pageId));
        if (!wikiPage.getWorkspaceId().equals(operation.getWorkspaceId())) {
            throw new InvalidCallbackPayloadException(
                    "다른 워크스페이스의 페이지입니다: pageId=" + pageId);
        }

        Optional<WikiPageVersion> previous =
                versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(pageId);
        // 같은 결과가 다시 오면 여기서 멈춘다. 재조립 본문은 복구 시점의 본문과 다르므로
        // 해시가 같다는 것은 이미 반영했다는 뜻이다.
        if (previous.isPresent() && previous.get().getContentHash().equals(page.contentHash())) {
            return;
        }

        long revision = versionRepository.findMaxRevision(pageId) + 1;
        versionRepository.save(new WikiPageVersion(
                pageId, revision, contributionCount, page.markdown(), page.markdownKey(),
                page.contentHash(), operation.getOperationId(), operation.getUserId(), now));

        Long beforeRevision = previous.map(WikiPageVersion::getRevision).orElse(null);
        LineCounter.LineCount lines = lineCounter.count(pageId, beforeRevision,
                previous.map(WikiPageVersion::getMarkdown).orElse(null), revision, page.markdown());
        operationChangeRepository.save(new OperationChange(
                operation.getOperationId(), ResourceType.wiki_page, pageId,
                beforeRevision, revision, ChangeType.rebuilt,
                "남은 기여 " + contributionCount + "개로 다시 만들었습니다.",
                lines.additions(), lines.deletions()));
    }

    /**
     * 실패는 본문을 건드리지 않고 기록만 남긴다. 페이지는 복구 직전 내용 그대로 남아 다음 lint에 맡긴다.
     */
    private void recordFailure(OperationLog operation, OperationResultRequest.FailedPage failed,
                               Map<String, Integer> targetCounts) {
        String pageId = failed.pageId();
        targetCount(targetCounts, pageId);
        if (operationChangeRepository.existsByOperationIdAndResourceIdAndChangeType(
                operation.getOperationId(), pageId, ChangeType.rebuild_failed)) {
            return;
        }
        long maxRevision = versionRepository.findMaxRevision(pageId);
        operationChangeRepository.save(new OperationChange(
                operation.getOperationId(), ResourceType.wiki_page, pageId,
                maxRevision == 0 ? null : maxRevision, null, ChangeType.rebuild_failed,
                failed.reason() == null ? "재조립에 실패했습니다." : failed.reason(), null, null));
    }

    private int targetCount(Map<String, Integer> targetCounts, String pageId) {
        Integer count = targetCounts.get(pageId);
        if (count == null) {
            throw new InvalidCallbackPayloadException(
                    "재조립을 요청하지 않은 페이지입니다: pageId=" + pageId);
        }
        return count;
    }

    /** 저장소에서 읽어 해시 검증까지 마친 재조립 페이지 하나. */
    public record RebuiltPage(String pageId, String markdownKey, String markdown, String contentHash) {}
}
