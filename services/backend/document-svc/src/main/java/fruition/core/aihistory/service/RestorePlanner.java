package fruition.core.aihistory.service;

import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.wiki.domain.WikiPageContribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 복구 판정. 기여 명단만 보고 페이지마다 삭제·복원·재조립을 가른다.
 *
 * <p>본문을 읽지 않는 순수 계산이라 미리보기가 가볍고 저장소 접근이 없다.
 * 판정 근거는 설계 문서 {@code docs/backlog/design/ai-operation-log.md} §5.1이다.
 *
 * <p>입력에는 <b>비활성 기여도 포함</b>해야 한다. 복원 목적지가 유효한지 판단하려면
 * 그 revision이 담고 있던 기여를 알아야 하는데, 이전 복구로 꺼진 기여가 그 안에 들어 있을 수 있다.
 */
@Component
public class RestorePlanner {

    /**
     * @param excludedOperationIds 제외할 작업. {@code mode}로 결정된 집합이다
     * @param contributionsByPage  페이지별 <b>전체</b> 기여(활성·비활성 모두)
     */
    public RestorePlan plan(Set<String> excludedOperationIds,
                            Map<String, List<WikiPageContribution>> contributionsByPage) {
        List<PageRestorePlan> pages = new ArrayList<>();
        for (Map.Entry<String, List<WikiPageContribution>> entry : contributionsByPage.entrySet()) {
            planPage(entry.getKey(), entry.getValue(), excludedOperationIds).ifPresent(pages::add);
        }
        return new RestorePlan(pages);
    }

    private Optional<PageRestorePlan> planPage(String pageId,
                                               List<WikiPageContribution> contributions,
                                               Set<String> excluded) {
        List<WikiPageContribution> ordered = contributions.stream()
                .sorted(Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                .toList();

        List<WikiPageContribution> kept = new ArrayList<>();
        List<WikiPageContribution> removed = new ArrayList<>();
        for (WikiPageContribution c : ordered) {
            if (!c.isActive()) {
                continue;  // 이전 복구로 이미 걷어낸 기여
            }
            if (excluded.contains(c.getIngestOperationId())) {
                removed.add(c);
            } else {
                kept.add(c);
            }
        }

        // 제외 대상이 이 페이지를 건드린 적이 없으면 복구 후보가 아니다.
        if (removed.isEmpty()) {
            return Optional.empty();
        }

        // 받치는 기여가 하나도 남지 않으면 페이지가 존재할 이유가 사라진다.
        if (kept.isEmpty()) {
            return Optional.of(PageRestorePlan.delete(pageId));
        }

        WikiPageContribution lastKept = kept.get(kept.size() - 1);
        long lastKeptRevision = lastKept.getSequenceRevision();
        if (snapshotMatchesKept(ordered, kept, lastKeptRevision)) {
            // 그 revision이 담고 있던 기여가 남길 집합과 정확히 같다.
            // 새로 쓸 필요 없이 그 revision의 본문과 object key를 재사용한다.
            return Optional.of(PageRestorePlan.restore(
                    pageId, lastKeptRevision, lastKept.getIngestOperationId(), kept.size()));
        }

        // 남은 기여만의 본문이 저장된 적이 없다. 조각을 다시 붙여야 한다.
        List<PageRestorePlan.Kept> keepContributions = kept.stream()
                .map(c -> new PageRestorePlan.Kept(
                        c.getIngestOperationId(), c.getSourceDocumentId(), c.getObjectKey()))
                .toList();
        return Optional.of(PageRestorePlan.rebuild(pageId, keepContributions));
    }

    /**
     * {@code revision} 시점의 본문이 {@code kept}와 같은 기여로 이루어졌는지.
     *
     * <p>그 revision까지 반영된 기여는 {@code sequence_revision <= revision}인 것 전부다.
     * 그중 하나라도 이번에 빼거나 이전 복구로 이미 뺀 것이 있으면 스냅샷을 그대로 쓸 수 없다.
     */
    private boolean snapshotMatchesKept(List<WikiPageContribution> ordered,
                                        List<WikiPageContribution> kept,
                                        long revision) {
        long appliedAtRevision = ordered.stream()
                .filter(c -> c.getSequenceRevision() <= revision)
                .count();
        return appliedAtRevision == kept.size();
    }
}
