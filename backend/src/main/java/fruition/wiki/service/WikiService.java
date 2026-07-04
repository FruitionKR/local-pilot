package fruition.wiki.service;

import fruition.document.domain.Document;
import fruition.document.repository.DocumentRepository;
import fruition.util.StorageProperties;
import fruition.wiki.domain.DocumentWikiLink;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageLink;
import fruition.wiki.domain.WikiPageType;
import fruition.wiki.exception.InvalidWikiPageTitleException;
import fruition.wiki.exception.WikiPageNotFoundException;
import fruition.wiki.exception.WikiPageSlugConflictException;
import fruition.wiki.dto.*;
import fruition.wiki.repository.DocumentWikiLinkRepository;
import fruition.wiki.repository.WikiPageLinkRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class WikiService {

    private final WikiPageRepository wikiPageRepository;
    private final WikiPageLinkRepository wikiPageLinkRepository;
    private final DocumentWikiLinkRepository documentWikiLinkRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    public WikiService(WikiPageRepository wikiPageRepository,
                       WikiPageLinkRepository wikiPageLinkRepository,
                       DocumentWikiLinkRepository documentWikiLinkRepository,
                       DocumentRepository documentRepository,
                       WorkspaceMemberRepository workspaceMemberRepository,
                       MinioClient minioClient,
                       StorageProperties storageProperties) {
        this.wikiPageRepository = wikiPageRepository;
        this.wikiPageLinkRepository = wikiPageLinkRepository;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.documentRepository = documentRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    public WikiGraphResponse findGraph(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);

        List<WikiPage> pages = wikiPageRepository.findAllByWorkspaceId(workspaceId);
        Set<String> pageIds = pages.stream().map(WikiPage::getId).collect(Collectors.toSet());
        // wiki_page_links에는 workspace 컬럼이 없으므로, 이 workspace의 page id 집합 안에서
        // 양 끝점이 모두 존재하는 링크만 포함한다.
        List<WikiPageLink> links = wikiPageLinkRepository.findAllByIdFromPageIdIn(pageIds).stream()
                .filter(l -> pageIds.contains(l.getToPageId()))
                .toList();

        Map<String, WikiGraphNode.SourceDocRef> sourceDocByWikiPageId = buildSourceDocRefs(pages);

        List<WikiGraphNode> nodes = pages.stream()
                .map(p -> new WikiGraphNode(
                        p.getId(),
                        p.getPageType().name(),
                        p.getTitle(),
                        p.getSlug(),
                        p.getSummary(),
                        p.getStatus().name(),
                        sourceDocByWikiPageId.get(p.getId())))
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

    private Map<String, WikiGraphNode.SourceDocRef> buildSourceDocRefs(List<WikiPage> pages) {
        List<String> sourcePageIds = pages.stream()
                .filter(p -> p.getPageType() == WikiPageType.source)
                .map(WikiPage::getId)
                .toList();

        if (sourcePageIds.isEmpty()) return Map.of();

        List<DocumentWikiLink> docLinks = documentWikiLinkRepository.findAllByIdWikiPageIdIn(sourcePageIds);
        if (docLinks.isEmpty()) return Map.of();

        List<String> documentIds = docLinks.stream()
                .map(DocumentWikiLink::getDocumentId)
                .distinct()
                .toList();
        Map<String, Document> docMap = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, d -> d));

        return docLinks.stream()
                .collect(Collectors.toMap(
                        DocumentWikiLink::getWikiPageId,
                        link -> {
                            Document doc = docMap.get(link.getDocumentId());
                            return new WikiGraphNode.SourceDocRef(
                                    link.getDocumentId(),
                                    doc != null ? doc.getFilename() : null);
                        },
                        (a, b) -> a
                ));
    }

    public WikiPageDetailResponse findById(String workspaceId, String userId, String id) {
        verifyWorkspaceOwnership(workspaceId, userId);
        WikiPage page = wikiPageRepository.findByIdAndWorkspaceId(id, workspaceId)
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
                readMarkdown(page.getMarkdownUri()),
                page.getStatus().name(),
                page.getCreatedAt(),
                page.getUpdatedAt(),
                sourceDocuments,
                relatedPages);
    }

    private String readMarkdown(String markdownUri) {
        if (markdownUri == null || markdownUri.isBlank()) return null;

        String objectName = normalizeObjectName(markdownUri);
        try (var stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(storageProperties.getBucket())
                        .object(objectName)
                        .build())) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeObjectName(String markdownUri) {
        String bucketPrefix = "s3://" + storageProperties.getBucket() + "/";
        if (markdownUri.startsWith(bucketPrefix)) {
            return markdownUri.substring(bucketPrefix.length());
        }
        if (markdownUri.startsWith("s3://")) {
            int objectStart = markdownUri.indexOf('/', "s3://".length());
            return objectStart >= 0 ? markdownUri.substring(objectStart + 1) : markdownUri;
        }
        return markdownUri;
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

    @Transactional
    public WikiPageRenameResponse rename(String workspaceId, String userId, String wikiPageId,
                                         WikiPageRenameRequest request) {
        verifyWorkspaceOwnership(workspaceId, userId);
        validateTitle(request.title());

        WikiPage page = wikiPageRepository.findByIdAndWorkspaceId(wikiPageId, workspaceId)
                .orElseThrow(() -> new WikiPageNotFoundException(wikiPageId));

        String previousTitle = page.getTitle();
        String previousSlug = page.getSlug();
        String newTitle = request.title().trim();
        boolean updateSlug = Boolean.TRUE.equals(request.updateSlug());

        page.renameTitle(newTitle);

        String currentSlug = previousSlug;
        boolean slugUpdated = false;

        if (updateSlug) {
            String newSlug = generateSlug(newTitle);
            if (!newSlug.equals(previousSlug)) {
                boolean conflict = wikiPageRepository.findByUserIdAndWorkspaceIdAndPageTypeAndSlug(
                                page.getUserId(), page.getWorkspaceId(), page.getPageType(), newSlug)
                        .filter(existing -> !existing.getId().equals(page.getId()))
                        .isPresent();
                if (conflict) {
                    throw new WikiPageSlugConflictException(newSlug);
                }
                page.updateSlug(newSlug);
                currentSlug = newSlug;
                slugUpdated = true;
            }
        }

        return new WikiPageRenameResponse(
                page.getId(),
                page.getPageType().name(),
                page.getTitle(),
                previousTitle,
                currentSlug,
                previousSlug,
                slugUpdated,
                page.getUpdatedAt()
        );
    }

    private void validateTitle(String title) {
        if (title == null) {
            throw new InvalidWikiPageTitleException("Wiki page 제목은 1자 이상 255자 이하여야 합니다.");
        }
        String trimmed = title.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new InvalidWikiPageTitleException("Wiki page 제목은 1자 이상 255자 이하여야 합니다.");
        }
    }

    private String generateSlug(String title) {
        return title.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s]+", "-")
                .replaceAll("[^a-z0-9가-힣-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
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
