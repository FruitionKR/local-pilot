package fruition.aihistory.service;

import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationStatus;
import fruition.aihistory.domain.ResourceType;
import fruition.aihistory.dto.PageRestorePlan;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.exception.InvalidRestoreRequestException;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.aihistory.repository.PipelineRestoreRequester;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageContribution;
import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.domain.WikiPageVersionId;
import fruition.wiki.repository.WikiPageContributionRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.wiki.repository.WikiPageVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 복구 계획을 DB에 반영한다. <b>한 트랜잭션</b>으로 처리한다.
 *
 * <p>Backend는 저장소에도 {@code wiki_pages}에도 쓰지 않는다. 되돌릴 revision의 본문이 이미
 * {@code wiki_page_versions}에 있으므로 그것을 새 revision으로 쌓으면 그 자체가 현재 본문이 된다.
 */
@Component
public class RestoreApplier {

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageVersionRepository versionRepository;
    private final WikiPageContributionRepository contributionRepository;

    public RestoreApplier(OperationLogRepository operationLogRepository,
                          OperationChangeRepository operationChangeRepository,
                          WikiPageRepository wikiPageRepository,
                          WikiPageVersionRepository versionRepository,
                          WikiPageContributionRepository contributionRepository) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.wikiPageRepository = wikiPageRepository;
        this.versionRepository = versionRepository;
        this.contributionRepository = contributionRepository;
    }

    /**
     * @return 되돌린 페이지 목록. llmPipeline 통지에 실어 임베딩을 갱신하게 한다
     */
    @Transactional
    public List<PipelineRestoreRequester.RestoreRun.RestoredPage> apply(
            OperationLog restore, RestorePlan plan, Set<String> excludedOperationIds, Instant now) {

        // 여러 복구가 동시에 실행될 때 교착을 피하려고 page_id 순서로 잠근다.
        List<String> pageIds = plan.pages().stream()
                .map(PageRestorePlan::pageId)
                .sorted()
                .toList();
        for (String pageId : pageIds) {
            wikiPageRepository.findByIdForUpdate(pageId).orElseThrow(() ->
                    new InvalidRestoreRequestException("Wiki 페이지를 찾을 수 없습니다: pageId=" + pageId));
        }

        // 제외 대상 기여를 끈다. 행은 지우지 않는다.
        // 지우면 연속 복구에서 이전에 제외한 기여가 다시 살아난다.
        deactivate(pageIds, excludedOperationIds, restore.getOperationId());

        List<PipelineRestoreRequester.RestoreRun.RestoredPage> restored = new ArrayList<>();
        for (PageRestorePlan page : plan.pages()) {
            switch (page.action()) {
                case restore -> restored.add(restorePage(restore, page, now));
                case delete -> deletePage(restore, page, now);
                case rebuild -> delegate(restore, page);
            }
        }

        restore.moveTo(OperationStatus.notify_pending);
        operationLogRepository.save(restore);
        return restored;
    }

    private void deactivate(List<String> pageIds, Set<String> excluded, String restoreOperationId) {
        for (WikiPageContribution contribution : contributionRepository.findByPageIds(pageIds)) {
            if (contribution.isActive() && excluded.contains(contribution.getIngestOperationId())) {
                contribution.deactivate(restoreOperationId);
            }
        }
    }

    /** 되돌릴 revision의 본문과 object key를 재사용해 새 revision으로 쌓는다. */
    private PipelineRestoreRequester.RestoreRun.RestoredPage restorePage(
            OperationLog restore, PageRestorePlan page, Instant now) {
        String pageId = page.pageId();
        WikiPageVersion target = versionRepository
                .findById(new WikiPageVersionId(pageId, page.targetRevision()))
                .orElseThrow(() -> new InvalidRestoreRequestException(
                        "되돌릴 버전을 찾을 수 없습니다: pageId=" + pageId + " revision=" + page.targetRevision()));

        long maxRevision = versionRepository.findMaxRevision(pageId);
        long revision = maxRevision + 1;

        versionRepository.save(new WikiPageVersion(
                pageId, revision, page.contributionCount(),
                target.getMarkdown(), target.getMarkdownKey(), target.getContentHash(),
                restore.getOperationId(), restore.getUserId(), now));

        operationChangeRepository.save(new OperationChange(
                restore.getOperationId(), ResourceType.wiki_page, pageId,
                maxRevision, revision, ChangeType.restored,
                "revision " + page.targetRevision() + " 내용으로 되돌렸습니다.", null, null));

        return new PipelineRestoreRequester.RestoreRun.RestoredPage(pageId, revision);
    }

    /**
     * 삭제 기록만 남긴다. 받치는 기여가 하나도 없는 상태가 곧 삭제이므로 따로 표시할 것이 없다.
     *
     * <p>{@code wiki_pages}와 링크 테이블은 llmPipeline 소유라 건드리지 않는다. 조립 지시서의
     * {@code deleted_pages}로 알려주면 llmPipeline이 링크와 임베딩을 정리한다.
     */
    private void deletePage(OperationLog restore, PageRestorePlan page, Instant now) {
        String pageId = page.pageId();
        long maxRevision = versionRepository.findMaxRevision(pageId);

        operationChangeRepository.save(new OperationChange(
                restore.getOperationId(), ResourceType.wiki_page, pageId,
                maxRevision == 0 ? null : maxRevision, null, ChangeType.deleted,
                "받치는 기여가 남지 않아 삭제했습니다.", null, null));
    }

    /** 본문을 건드리지 않고 llmPipeline에 맡겼다는 기록만 남긴다. */
    private void delegate(OperationLog restore, PageRestorePlan page) {
        long maxRevision = versionRepository.findMaxRevision(page.pageId());
        operationChangeRepository.save(new OperationChange(
                restore.getOperationId(), ResourceType.wiki_page, page.pageId(),
                maxRevision == 0 ? null : maxRevision, null, ChangeType.delegated,
                "남은 기여 " + page.contributionCount() + "개로 재작성을 맡겼습니다.", null, null));
    }
}
