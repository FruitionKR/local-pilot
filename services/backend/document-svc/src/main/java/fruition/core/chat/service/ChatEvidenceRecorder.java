package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatMessageReference;
import fruition.core.chat.domain.ChatMessageRelatedPage;
import fruition.core.chat.domain.SourceRef;
import fruition.core.chat.repository.ChatMessageReferenceRepository;
import fruition.core.chat.repository.ChatMessageRelatedPageRepository;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.query.repository.PipelineQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 답변의 근거를 메시지에 붙인다.
 *
 * <p>질의 경로와 통합 입구(Agent turn)가 같은 pipeline 응답을 받으므로 저장 규칙을 여기 한 곳에 둔다.
 * 두 곳에 복제하면 같은 답변이 어느 입구로 들어왔는지에 따라 근거가 보이거나 사라진다.
 */
@Service
public class ChatEvidenceRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChatEvidenceRecorder.class);
    private static final String REFERENCE_TYPE_SOURCE_BLOCK = "source_block";
    /** chat_message_related_pages.title 컬럼 길이. */
    private static final int MAX_TITLE_LENGTH = 255;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReferenceRepository referenceRepository;
    private final ChatMessageRelatedPageRepository relatedPageRepository;
    private final DocumentRepository documentRepository;

    public ChatEvidenceRecorder(ChatMessageRepository chatMessageRepository,
                                ChatMessageReferenceRepository referenceRepository,
                                ChatMessageRelatedPageRepository relatedPageRepository,
                                DocumentRepository documentRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.referenceRepository = referenceRepository;
        this.relatedPageRepository = relatedPageRepository;
        this.documentRepository = documentRepository;
    }

    /** 메시지 ID만 아는 호출부(결과 이벤트 반영)를 위한 진입점. */
    @Transactional
    public void record(String assistantMessageId, PipelineQueryResponse response) {
        chatMessageRepository.findById(assistantMessageId)
                .ifPresent(message -> record(message, response));
    }

    @Transactional
    public void record(ChatMessage assistantMessage, PipelineQueryResponse response) {
        List<ChatMessageReference> references = buildReferences(assistantMessage, response);
        List<ChatMessageRelatedPage> relatedPages = buildRelatedPages(assistantMessage, response);
        referenceRepository.saveAll(references);
        relatedPageRepository.saveAll(relatedPages);
        log.info("[답변 근거 저장] messageId={} referenceCount={} relatedPageCount={}",
                assistantMessage.getId(), references.size(), relatedPages.size());
    }

    /**
     * 제목은 pipeline 쪽 컬럼이 text라 길이 상한이 없는데 여기 컬럼은 varchar(255)다. 그대로 넣으면
     * flush에서 터져 답변까지 되돌아가므로 잘라서 담는다. slug·page_type은 양쪽 다 255자라 그대로 둔다.
     */
    private List<ChatMessageRelatedPage> buildRelatedPages(ChatMessage assistantMessage,
                                                           PipelineQueryResponse pipelineResponse) {
        if (pipelineResponse.relatedPages() == null) return List.of();

        List<ChatMessageRelatedPage> pages = new ArrayList<>();
        List<PipelineQueryResponse.RelatedPage> relatedPages = pipelineResponse.relatedPages();
        for (int i = 0; i < relatedPages.size(); i++) {
            PipelineQueryResponse.RelatedPage rp = relatedPages.get(i);
            pages.add(new ChatMessageRelatedPage(
                    assistantMessage, rp.id(), rp.pageType(), truncateTitle(rp.title()), rp.slug(),
                    rp.relevanceScore(), rp.role(), rp.depth(), i + 1
            ));
        }
        return pages;
    }

    private static String truncateTitle(String title) {
        if (title == null || title.length() <= MAX_TITLE_LENGTH) return title;
        // 경계가 서로게이트 쌍 한가운데면 한 글자 더 줄인다. 반쪽만 남기면 UTF-8로 인코딩할 수 없어
        // 결국 같은 자리에서 터진다.
        int end = Character.isHighSurrogate(title.charAt(MAX_TITLE_LENGTH - 1))
                ? MAX_TITLE_LENGTH - 1
                : MAX_TITLE_LENGTH;
        return title.substring(0, end);
    }

    /**
     * 웹 검색 근거는 우리 문서가 아니라 저장하지 않는다. 화면에는 응답으로만 보인다.
     *
     * <p>document_id에는 documents FK가 걸려 있다. 없는 문서를 가리키는 근거를 그대로 넣으면
     * flush에서 터지는데, 이 저장은 결과 반영 트랜잭션 안에서 일어나므로 답변까지 함께 되돌아가고
     * 컨슈머가 같은 메시지를 계속 재시도한다. 근거는 답변에 딸린 정보라 여기서 걸러 낸다.
     * 워크스페이스가 다른 문서도 같은 자리에서 뺀다.
     */
    private List<ChatMessageReference> buildReferences(ChatMessage assistantMessage,
                                                       PipelineQueryResponse pipelineResponse) {
        if (pipelineResponse.evidenceSnippets() == null) return List.of();

        List<PipelineQueryResponse.EvidenceSnippet> usable = pipelineResponse.evidenceSnippets().stream()
                .filter(snippet -> snippet.sourceDocumentId() != null
                        && !snippet.sourceDocumentId().startsWith("web:")
                        && snippet.text() != null && !snippet.text().isBlank())
                .toList();
        if (usable.isEmpty()) return List.of();

        Set<String> citableDocumentIds = citableDocumentIds(usable, assistantMessage.getSession().getWorkspaceId());
        List<ChatMessageReference> refs = new ArrayList<>();
        for (PipelineQueryResponse.EvidenceSnippet snippet : usable) {
            if (!citableDocumentIds.contains(snippet.sourceDocumentId())) {
                log.warn("[답변 근거 제외] messageId={} rank={} reason=이 워크스페이스의 문서가 아님 documentId={}",
                        assistantMessage.getId(), snippet.rank(), snippet.sourceDocumentId());
                continue;
            }
            refs.add(new ChatMessageReference(
                    assistantMessage, REFERENCE_TYPE_SOURCE_BLOCK,
                    snippet.sourceDocumentId(), snippet.rank(),
                    snippet.sourceBlockIds(), snippet.text(),
                    toDomainSourceRefs(snippet.sourceRefs())
            ));
        }
        return refs;
    }

    /**
     * 근거로 남길 수 있는 문서 ID. 근거 개수만큼 조회하면 N+1이 되므로 ID를 모아 한 번에 확인한다.
     *
     * <p>이 대화가 속한 워크스페이스의 문서만 통과시킨다. 남의 워크스페이스 문서 ID가 섞여 들어오면
     * 화면에서 열리지 않는 근거가 되고, 문서 제목이 근거 목록으로 새어 나간다.
     */
    private Set<String> citableDocumentIds(List<PipelineQueryResponse.EvidenceSnippet> snippets,
                                           String workspaceId) {
        List<String> documentIds = snippets.stream()
                .map(PipelineQueryResponse.EvidenceSnippet::sourceDocumentId)
                .distinct()
                .toList();
        return documentRepository.findAllById(documentIds).stream()
                .filter(document -> document.getWorkspaceId().equals(workspaceId))
                .map(Document::getId)
                .collect(Collectors.toSet());
    }

    private List<SourceRef> toDomainSourceRefs(List<PipelineQueryResponse.SourceRef> sourceRefs) {
        if (sourceRefs == null) return null;
        return sourceRefs.stream()
                .map(r -> new SourceRef(r.sourceDocumentId(), r.sourceBlockId()))
                .toList();
    }
}
