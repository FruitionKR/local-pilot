package fruition.wiki.repository;

import fruition.wiki.domain.DocumentWikiLink;
import fruition.wiki.domain.DocumentWikiLinkId;
import fruition.wiki.domain.DocumentWikiRelationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DocumentWikiLinkRepository extends JpaRepository<DocumentWikiLink, DocumentWikiLinkId> {

    List<DocumentWikiLink> findAllByIdDocumentId(String documentId);

    List<DocumentWikiLink> findAllByIdWikiPageId(String wikiPageId);

    List<DocumentWikiLink> findAllByIdWikiPageIdIn(Collection<String> wikiPageIds);

    List<DocumentWikiLink> findAllByIdDocumentIdAndIdRelationType(
            String documentId, DocumentWikiRelationType relationType);

    void deleteByIdDocumentId(String documentId);

    /** 페이지가 소프트 삭제될 때 그 페이지를 가리키는 문서 연결을 정리한다. */
    void deleteByIdWikiPageId(String wikiPageId);
}
