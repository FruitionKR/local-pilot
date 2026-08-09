package fruition.core.wiki.repository;

import fruition.core.wiki.domain.WikiPage;
import fruition.core.wiki.domain.WikiPageStatus;
import fruition.core.wiki.domain.WikiPageType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WikiPageRepository extends JpaRepository<WikiPage, String> {

    /**
     * 행을 잠그고 읽는다. revision 채번과 markdown_uri 이동이 겹치지 않게 직렬화한다.
     * 교착을 피하려고 호출 측에서 page_id 순서로 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM WikiPage p WHERE p.id = :pageId")
    Optional<WikiPage> findByIdForUpdate(@Param("pageId") String pageId);


    Optional<WikiPage> findByUserIdAndWorkspaceIdAndPageTypeAndSlug(
            String userId, String workspaceId, WikiPageType pageType, String slug);

    List<WikiPage> findAllByStatus(WikiPageStatus status);

    List<WikiPage> findAllByPageType(WikiPageType pageType);

    /**
     * 그래프 조회용. 복구로 받치는 기여가 모두 사라진 페이지를 뺀다.
     *
     * <p>{@code wiki_pages}에 삭제 표시를 하지 않는다. 그 테이블은 llmPipeline 소유이고,
     * 삭제 여부는 기여 원장이 이미 답하고 있어 따로 적을 이유가 없다.
     *
     * <p>기여가 <b>하나도 없는</b> 페이지는 살아 있는 것으로 본다. 이 기능 이전에 만들어진
     * 페이지들이라 복구로 지워진 것과 구분해야 한다.
     */
    @Query("""
            SELECT p FROM WikiPage p
            WHERE p.workspaceId = :workspaceId
              AND (NOT EXISTS (SELECT 1 FROM WikiPageContribution c WHERE c.id.pageId = p.id)
                   OR EXISTS (SELECT 1 FROM WikiPageContribution c
                              WHERE c.id.pageId = p.id AND c.active = true))
            """)
    List<WikiPage> findAliveByWorkspaceId(@Param("workspaceId") String workspaceId);

    /** 상세 조회용. 받치는 기여가 모두 사라진 페이지는 없는 것으로 본다. */
    @Query("""
            SELECT p FROM WikiPage p
            WHERE p.id = :id AND p.workspaceId = :workspaceId
              AND (NOT EXISTS (SELECT 1 FROM WikiPageContribution c WHERE c.id.pageId = p.id)
                   OR EXISTS (SELECT 1 FROM WikiPageContribution c
                              WHERE c.id.pageId = p.id AND c.active = true))
            """)
    Optional<WikiPage> findAliveByIdAndWorkspaceId(
            @Param("id") String id, @Param("workspaceId") String workspaceId);

    /** needs_lint 판단용: workspace 위키 페이지의 마지막 변경 시각. */
    @Query("SELECT MAX(p.updatedAt) FROM WikiPage p WHERE p.workspaceId = :workspaceId")
    Optional<Instant> findMaxUpdatedAtByWorkspaceId(@Param("workspaceId") String workspaceId);

    /** 여러 페이지 중 주어진 유형인 것. 복구 지시서에 실을 source page를 고를 때 쓴다. */
    @Query("SELECT p.id FROM WikiPage p WHERE p.id IN :pageIds AND p.pageType = :pageType")
    List<String> findIdsByPageType(@Param("pageIds") java.util.Collection<String> pageIds,
                                   @Param("pageType") WikiPageType pageType);

    /** 삭제 판정용. 여러 페이지 중 살아 있는 것만. */
    @Query("""
            SELECT p.id FROM WikiPage p
            WHERE p.id IN :pageIds
              AND (NOT EXISTS (SELECT 1 FROM WikiPageContribution c WHERE c.id.pageId = p.id)
                   OR EXISTS (SELECT 1 FROM WikiPageContribution c
                              WHERE c.id.pageId = p.id AND c.active = true))
            """)
    List<String> findAliveIds(@Param("pageIds") java.util.Collection<String> pageIds);
}
