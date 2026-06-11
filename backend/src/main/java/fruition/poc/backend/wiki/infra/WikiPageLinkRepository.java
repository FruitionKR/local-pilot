package fruition.poc.backend.wiki.infra;

import fruition.poc.backend.wiki.domain.WikiPageLink;
import fruition.poc.backend.wiki.domain.WikiPageLinkId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WikiPageLinkRepository extends JpaRepository<WikiPageLink, WikiPageLinkId> {

    List<WikiPageLink> findAllByIdFromPageId(String fromPageId);

    List<WikiPageLink> findAllByIdToPageId(String toPageId);
}
