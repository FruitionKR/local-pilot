package fruition.core.wiki.service;

import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.dto.MarkdownDiff;
import fruition.core.document.service.MarkdownDiffService;
import fruition.shared.util.StorageProperties;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.domain.WikiPageVersionId;
import fruition.core.wiki.dto.WikiPageDiffResponse;
import fruition.core.wiki.exception.WikiPageVersionNotFoundException;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import fruition.core.wiki.domain.DocumentWikiLink;
import fruition.core.wiki.domain.WikiPage;
import fruition.core.wiki.domain.WikiPageLink;
import fruition.core.wiki.domain.WikiPageType;
import fruition.core.wiki.exception.WikiPageNotFoundException;
import fruition.core.wiki.dto.*;
import fruition.core.wiki.repository.DocumentWikiLinkRepository;
import fruition.core.wiki.repository.PipelineWikiPageRequester;
import fruition.core.wiki.repository.WikiPageLinkRepository;
import fruition.core.wiki.repository.WikiPageRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineWikiPageRequester pipelineWikiPageRequester;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;
    private final WikiPageVersionRepository versionRepository;
    private final MarkdownDiffService markdownDiffService;

    public WikiService(WikiPageRepository wikiPageRepository,
                       WikiPageLinkRepository wikiPageLinkRepository,
                       DocumentWikiLinkRepository documentWikiLinkRepository,
                       DocumentRepository documentRepository,
                       WorkspaceAccessGuard workspaceAccessGuard,
                       PipelineWikiPageRequester pipelineWikiPageRequester,
                       MinioClient minioClient,
                       StorageProperties storageProperties,
                       WikiPageVersionRepository versionRepository,
                       MarkdownDiffService markdownDiffService) {
        this.wikiPageRepository = wikiPageRepository;
        this.wikiPageLinkRepository = wikiPageLinkRepository;
        this.documentWikiLinkRepository = documentWikiLinkRepository;
        this.documentRepository = documentRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.pipelineWikiPageRequester = pipelineWikiPageRequester;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
        this.versionRepository = versionRepository;
        this.markdownDiffService = markdownDiffService;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    public WikiGraphResponse findGraph(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);

        List<WikiPage> pages = wikiPageRepository.findAliveByWorkspaceId(workspaceId);
        Set<String> pageIds = pages.stream().map(WikiPage::getId).collect(Collectors.toSet());
        // workspace 격리는 wiki_page_links.workspace_id 컬럼으로 DB에서 거른다.
        // 양 끝점이 alive page(soft-delete 제외)인 링크만 그래프에 포함한다.
        List<WikiPageLink> links = wikiPageLinkRepository.findAllByWorkspaceId(workspaceId).stream()
                .filter(l -> pageIds.contains(l.getFromPageId()) && pageIds.contains(l.getToPageId()))
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
        WikiPage page = wikiPageRepository.findAliveByIdAndWorkspaceId(id, workspaceId)
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
                currentMarkdown(page),
                page.getStatus().name(),
                page.getCreatedAt(),
                page.getUpdatedAt(),
                sourceDocuments,
                relatedPages);
    }

    /**
     * 현재 본문. Backend가 쌓은 최신 revision이 곧 현재 내용이다.
     *
     * <p>{@code wiki_pages.markdown_uri}는 llmPipeline 소유라 Backend가 갱신하지 않는다.
     * 이 기능 이전에 만들어져 revision 기록이 없는 페이지만 그 값으로 폴백한다.
     */
    private String currentMarkdown(WikiPage page) {
        return versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(page.getId())
                .map(WikiPageVersion::getMarkdown)
                .orElseGet(() -> readMarkdown(page.getMarkdownUri()));
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WikiPageRenameResponse rename(String workspaceId, String userId, String wikiPageId,
                                         WikiPageRenameRequest request) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return pipelineWikiPageRequester.rename(workspaceId, userId, wikiPageId, request);
    }

    private List<WikiRelatedPage> buildRelatedPages(String fromPageId) {
        List<WikiPageLink> outLinks = wikiPageLinkRepository.findAllByIdFromPageId(fromPageId);
        if (outLinks.isEmpty()) return List.of();

        List<String> targetIds = outLinks.stream()
                .map(WikiPageLink::getToPageId)
                .toList();
        Map<String, WikiPage> pageMap = wikiPageRepository.findAllById(targetIds).stream()
                .collect(Collectors.toMap(WikiPage::getId, p -> p));
        Set<String> alive = Set.copyOf(wikiPageRepository.findAliveIds(targetIds));

        // 받치는 기여가 사라진 페이지로 가는 링크는 뺀다. 링크 정리는 llmPipeline 몫이라
        // 그 전에 조회가 들어올 수 있다.
        // 대상 자체가 없는 링크는 기존대로 남겨 필드만 null로 내려간다.
        return outLinks.stream()
                .filter(link -> !pageMap.containsKey(link.getToPageId())
                        || alive.contains(link.getToPageId()))
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

    /**
     * 두 revision 사이의 변경분. 저장된 본문을 읽어 그 자리에서 계산한다.
     *
     * <p>diff 본문을 저장하지 않는 이유는 전체 본문이 바로 옆에 있어 언제든 다시 만들 수 있고,
     * 중복 저장하면 두 값이 어긋날 여지가 생기기 때문이다. 사용자가 펼칠 때만 호출된다.
     */
    @Transactional(readOnly = true)
    public WikiPageDiffResponse diff(String workspaceId, String userId, String pageId,
                                     long fromRevision, long toRevision) {
        verifyWorkspaceOwnership(workspaceId, userId);
        wikiPageRepository.findAliveByIdAndWorkspaceId(pageId, workspaceId)
                .orElseThrow(() -> new WikiPageNotFoundException(pageId));
        WikiPageVersion before = loadVersion(pageId, fromRevision);
        WikiPageVersion after = loadVersion(pageId, toRevision);
        MarkdownDiff diff = markdownDiffService.diff(
                fromRevision, before.getMarkdown(), toRevision, after.getMarkdown());
        return WikiPageDiffResponse.from(pageId, diff);
    }

    private WikiPageVersion loadVersion(String pageId, long revision) {
        return versionRepository.findById(new WikiPageVersionId(pageId, revision))
                .orElseThrow(() -> new WikiPageVersionNotFoundException(pageId, revision));
    }
}
