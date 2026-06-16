package fruition.chat.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_message_references")
public class ChatMessageReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_message_id", nullable = false)
    private String chatMessageId;

    @Column(name = "reference_type", nullable = false)
    private String referenceType;

    @Column(name = "wiki_page_id")
    private String wikiPageId;

    @Column(name = "document_id")
    private String documentId;

    @Column(name = "page_role")
    private String pageRole;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "paragraph_index")
    private Integer paragraphIndex;

    @Column(name = "sentence_index")
    private Integer sentenceIndex;

    @Column(columnDefinition = "TEXT")
    private String quote;

    protected ChatMessageReference() {}

    public ChatMessageReference(String chatMessageId, String referenceType,
                                 String wikiPageId, String documentId,
                                 String pageRole,
                                 Double relevanceScore, Integer pageNumber,
                                 Integer rank, Integer paragraphIndex,
                                 Integer sentenceIndex, String quote) {
        this.chatMessageId = chatMessageId;
        this.referenceType = referenceType;
        this.wikiPageId = wikiPageId;
        this.documentId = documentId;
        this.pageRole = pageRole;
        this.relevanceScore = relevanceScore;
        this.pageNumber = pageNumber;
        this.rank = rank;
        this.paragraphIndex = paragraphIndex;
        this.sentenceIndex = sentenceIndex;
        this.quote = quote;
    }

    public Long getId() { return id; }
    public String getChatMessageId() { return chatMessageId; }
    public String getReferenceType() { return referenceType; }
    public String getWikiPageId() { return wikiPageId; }
    public String getDocumentId() { return documentId; }
    public String getPageRole() { return pageRole; }
    public Double getRelevanceScore() { return relevanceScore; }
    public Integer getPageNumber() { return pageNumber; }
    public Integer getRank() { return rank; }
    public Integer getParagraphIndex() { return paragraphIndex; }
    public Integer getSentenceIndex() { return sentenceIndex; }
    public String getQuote() { return quote; }
}
