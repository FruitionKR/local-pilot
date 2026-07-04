package fruition.wiki.repository;

import fruition.wiki.domain.WikiPageLink;
import fruition.wiki.domain.WikiPageLinkId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface WikiPageLinkRepository extends JpaRepository<WikiPageLink, WikiPageLinkId> {

    List<WikiPageLink> findAllByIdFromPageId(String fromPageId);

    List<WikiPageLink> findAllByIdToPageId(String toPageId);

    List<WikiPageLink> findAllByIdFromPageIdIn(Collection<String> fromPageIds);

    void deleteByIdFromPageIdOrIdToPageId(String fromPageId, String toPageId);
}
