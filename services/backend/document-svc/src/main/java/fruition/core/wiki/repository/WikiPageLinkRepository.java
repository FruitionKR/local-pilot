package fruition.core.wiki.repository;

import fruition.core.wiki.domain.WikiPageLink;
import fruition.core.wiki.domain.WikiPageLinkId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WikiPageLinkRepository extends JpaRepository<WikiPageLink, WikiPageLinkId> {

    List<WikiPageLink> findAllByIdFromPageId(String fromPageId);

    List<WikiPageLink> findAllByIdToPageId(String toPageId);

    List<WikiPageLink> findAllByWorkspaceId(String workspaceId);

    void deleteByIdFromPageIdOrIdToPageId(String fromPageId, String toPageId);
}
