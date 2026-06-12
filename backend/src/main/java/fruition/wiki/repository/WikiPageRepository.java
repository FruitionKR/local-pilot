package fruition.wiki.repository;

import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageStatus;
import fruition.wiki.domain.WikiPageType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WikiPageRepository extends JpaRepository<WikiPage, String> {

    Optional<WikiPage> findByPageTypeAndSlug(WikiPageType pageType, String slug);

    List<WikiPage> findAllByStatus(WikiPageStatus status);

    List<WikiPage> findAllByPageType(WikiPageType pageType);
}
