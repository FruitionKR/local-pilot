package fruition.chat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.List;

@Entity
@Table(name = "chat_message_references")
public class ChatMessageReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_message_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChatMessage chatMessage;

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

    public ChatMessageReference(ChatMessage chatMessage, String referenceType,
                                 String documentId, Integer rank,
                                 List<String> sourceBlockIds, String quote) {
        this.chatMessage = chatMessage;
        this.referenceType = referenceType;
        this.documentId = documentId;
        this.rank = rank;
        this.sourceBlockIds = sourceBlockIds;
        this.quote = quote;
    }

    public Long getId() { return id; }
    public String getChatMessageId() { return chatMessage.getId(); }
    public String getReferenceType() { return referenceType; }
    public String getDocumentId() { return documentId; }
    public Integer getRank() { return rank; }
    public List<String> getSourceBlockIds() { return sourceBlockIds; }
    public String getQuote() { return quote; }
}
