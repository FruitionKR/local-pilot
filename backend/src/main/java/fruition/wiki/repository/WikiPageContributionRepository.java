package fruition.wiki.repository;

import fruition.wiki.domain.WikiPageContribution;
import fruition.wiki.domain.WikiPageContributionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WikiPageContributionRepository
        extends JpaRepository<WikiPageContribution, WikiPageContributionId> {

    /** 한 페이지를 받치는 살아 있는 기여를 적용 순서대로. 판정과 조립 지시서가 이 순서를 쓴다. */
    @Query("""
            SELECT c FROM WikiPageContribution c
            WHERE c.id.pageId = :pageId AND c.active = true
            ORDER BY c.sequenceRevision
            """)
    List<WikiPageContribution> findActiveByPageId(@Param("pageId") String pageId);

    /** 복구 대상 페이지 수집. 제외할 작업들이 건드린 페이지만 후보가 된다. */
    @Query("""
            SELECT DISTINCT c.id.pageId FROM WikiPageContribution c
            WHERE c.id.ingestOperationId IN :operationIds AND c.active = true
            """)
    List<String> findActivePageIdsByOperationIds(
            @Param("operationIds") Collection<String> operationIds);

    /** 여러 페이지의 살아 있는 기여를 한 번에. 판정 단계에서 본문을 읽지 않고 이것만으로 계산한다. */
    @Query("""
            SELECT c FROM WikiPageContribution c
            WHERE c.id.pageId IN :pageIds AND c.active = true
            ORDER BY c.id.pageId, c.sequenceRevision
            """)
    List<WikiPageContribution> findActiveByPageIds(@Param("pageIds") Collection<String> pageIds);

    long countByIdPageIdAndActiveTrue(String pageId);
}
