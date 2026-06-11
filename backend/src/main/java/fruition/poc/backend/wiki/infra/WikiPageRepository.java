package fruition.poc.backend.wiki.infra;

import fruition.poc.backend.wiki.domain.WikiPage;
import fruition.poc.backend.wiki.domain.WikiPageStatus;
import fruition.poc.backend.wiki.domain.WikiPageType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WikiPageRepository extends JpaRepository<WikiPage, String> {

    Optional<WikiPage> findByPageTypeAndSlug(WikiPageType pageType, String slug);

    List<WikiPage> findAllByStatus(WikiPageStatus status);

    List<WikiPage> findAllByPageType(WikiPageType pageType);
}
