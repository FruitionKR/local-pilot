package fruition.wiki.repository;

import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.domain.WikiPageVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WikiPageVersionRepository extends JpaRepository<WikiPageVersion, WikiPageVersionId> {

    /**
     * 다음 revision 채번 기준. {@code wiki_pages}에 현재 버전 컬럼을 두지 않고 여기서 얻는다.
     * {@code (page_id, revision DESC)} 인덱스의 첫 항목 한 행만 읽는다.
     */
    @Query("SELECT COALESCE(MAX(v.id.revision), 0) FROM WikiPageVersion v WHERE v.id.pageId = :pageId")
    long findMaxRevision(@Param("pageId") String pageId);

    Optional<WikiPageVersion> findTopByIdPageIdOrderByIdRevisionDesc(String pageId);

    /** 이력 목록. 본문을 제외한 메타데이터만 최신 순으로 반환한다. */
    @Query("""
            SELECT v.id.revision AS revision, v.contributionCount AS contributionCount,
                   v.contentHash AS contentHash, v.operationId AS operationId,
                   v.createdBy AS createdBy, v.createdAt AS createdAt
            FROM WikiPageVersion v
            WHERE v.id.pageId = :pageId
            ORDER BY v.id.revision DESC
            """)
    List<Summary> findSummaries(@Param("pageId") String pageId);

    interface Summary {
        long getRevision();
        int getContributionCount();
        String getContentHash();
        String getOperationId();
        String getCreatedBy();
        Instant getCreatedAt();
    }
}
