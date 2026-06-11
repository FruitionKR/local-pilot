package fruition.poc.backend.wiki.infra;

import fruition.poc.backend.wiki.domain.DocumentWikiLink;
import fruition.poc.backend.wiki.domain.DocumentWikiLinkId;
import fruition.poc.backend.wiki.domain.DocumentWikiRelationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentWikiLinkRepository extends JpaRepository<DocumentWikiLink, DocumentWikiLinkId> {

    List<DocumentWikiLink> findAllByIdDocumentId(String documentId);

    List<DocumentWikiLink> findAllByIdWikiPageId(String wikiPageId);

    List<DocumentWikiLink> findAllByIdDocumentIdAndIdRelationType(
            String documentId, DocumentWikiRelationType relationType);
}
