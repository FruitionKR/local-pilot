package fruition.chat.domain;

import jakarta.persistence.*;

import java.util.List;

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

    @Column(name = "document_id")
    private String documentId;

    @Column(name = "rank")
    private Integer rank;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "source_block_ids", columnDefinition = "TEXT")
    private List<String> sourceBlockIds;

    @Column(columnDefinition = "TEXT")
    private String quote;

    protected ChatMessageReference() {}

    public ChatMessageReference(String chatMessageId, String referenceType,
                                 String documentId, Integer rank,
                                 List<String> sourceBlockIds, String quote) {
        this.chatMessageId = chatMessageId;
        this.referenceType = referenceType;
        this.documentId = documentId;
        this.rank = rank;
        this.sourceBlockIds = sourceBlockIds;
        this.quote = quote;
    }

    public Long getId() { return id; }
    public String getChatMessageId() { return chatMessageId; }
    public String getReferenceType() { return referenceType; }
    public String getDocumentId() { return documentId; }
    public Integer getRank() { return rank; }
    public List<String> getSourceBlockIds() { return sourceBlockIds; }
    public String getQuote() { return quote; }
}
