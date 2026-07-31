package fruition.wiki.repository;

import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageStatus;
import fruition.wiki.domain.WikiPageType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<WikiPage> findAllByWorkspaceId(String workspaceId);

    Optional<WikiPage> findByIdAndWorkspaceId(String id, String workspaceId);

    /** 그래프 조회용. 복구로 소프트 삭제된 페이지는 현재 상태가 아니므로 뺀다. */
    List<WikiPage> findAllByWorkspaceIdAndStatusNot(String workspaceId, WikiPageStatus status);

    /** 상세 조회용. 삭제된 페이지는 없는 것으로 본다. */
    Optional<WikiPage> findByIdAndWorkspaceIdAndStatusNot(
            String id, String workspaceId, WikiPageStatus status);
}
