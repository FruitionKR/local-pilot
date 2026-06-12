package fruition.wiki.service;

import fruition.document.domain.Document;
import fruition.document.repository.DocumentRepository;
import fruition.wiki.domain.DocumentWikiLink;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageLink;
import fruition.wiki.exception.WikiPageNotFoundException;
import fruition.wiki.dto.*;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import fruition.wiki.repository.WikiPageLinkRepository;
import fruition.wiki.repository.WikiPageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class WikiService {

    private final WikiPageRepository wikiPageRepository;
    private final WikiPageLinkRepository wikiPageLinkRepository;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;
    private final DocumentRepository documentRepository;

    public WikiService(WikiPageRepository wikiPageRepository,
                       WikiPageLinkRepository wikiPageLinkRepository,
                       DocumentWikiLinkRepository documentWikiLinkRepository,
                       DocumentRepository documentRepository) {
        this.wikiPageRepository = wikiPageRepository;
        this.wikiPageLinkRepository = wikiPageLinkRepository;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.documentRepository = documentRepository;
    }

    public WikiGraphResponse findGraph() {
        List<WikiPage> pages = wikiPageRepository.findAll();
        List<WikiPageLink> links = wikiPageLinkRepository.findAll();

        List<WikiGraphNode> nodes = pages.stream()
                .map(p -> new WikiGraphNode(
                        p.getId(),
                        p.getPageType().name(),
                        p.getTitle(),
                        p.getSlug(),
                        p.getSummary(),
                        p.getStatus().name(),
                        null))
                .toList();

        List<WikiGraphEdge> edges = links.stream()
                .map(l -> new WikiGraphEdge(
                        l.getFromPageId(),
                        l.getToPageId(),
                        l.getLinkType(),
                        l.getLabel(),
                        l.getConfidence() != null ? l.getConfidence() : 0.0))
                .toList();

        return new WikiGraphResponse(nodes, edges);
    }

    public WikiPageDetailResponse findById(String id) {
        WikiPage page = wikiPageRepository.findById(id)
                .orElseThrow(() -> new WikiPageNotFoundException(id));

        List<WikiPageSourceDoc> sourceDocuments = buildSourceDocs(id);
        List<WikiRelatedPage> relatedPages = buildRelatedPages(id);

        return new WikiPageDetailResponse(
                page.getId(),
                page.getPageType().name(),
                page.getTitle(),
                page.getSlug(),
                page.getSummary(),
                page.getMarkdownUri(),
                null,
                page.getStatus().name(),
                page.getCreatedAt(),
                page.getUpdatedAt(),
                sourceDocuments,
                relatedPages);
    }

    private List<WikiPageSourceDoc> buildSourceDocs(String wikiPageId) {
        List<DocumentWikiLink> docLinks = documentWikiLinkRepository.findAllByIdWikiPageId(wikiPageId);
        if (docLinks.isEmpty()) return List.of();

        List<String> documentIds = docLinks.stream()
                .map(DocumentWikiLink::getDocumentId)
                .toList();
        Map<String, Document> docMap = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, d -> d));

        return docLinks.stream()
                .map(link -> {
                    Document doc = docMap.get(link.getDocumentId());
                    return new WikiPageSourceDoc(
                            link.getDocumentId(),
                            doc != null ? doc.getFilename() : null,
                            doc != null ? doc.getSourceUri() : null,
                            link.getRelationType().name(),
                            link.getConfidence() != null ? link.getConfidence() : 0.0);
                })
                .toList();
    }

    private List<WikiRelatedPage> buildRelatedPages(String fromPageId) {
        List<WikiPageLink> outLinks = wikiPageLinkRepository.findAllByIdFromPageId(fromPageId);
        if (outLinks.isEmpty()) return List.of();

        List<String> targetIds = outLinks.stream()
                .map(WikiPageLink::getToPageId)
                .toList();
        Map<String, WikiPage> pageMap = wikiPageRepository.findAllById(targetIds).stream()
                .collect(Collectors.toMap(WikiPage::getId, p -> p));

        return outLinks.stream()
                .map(link -> {
                    WikiPage target = pageMap.get(link.getToPageId());
                    return new WikiRelatedPage(
                            link.getToPageId(),
                            target != null ? target.getPageType().name() : null,
                            target != null ? target.getTitle() : null,
                            target != null ? target.getSlug() : null,
                            link.getLinkType(),
                            link.getLabel(),
                            link.getConfidence() != null ? link.getConfidence() : 0.0);
                })
                .toList();
    }
}
