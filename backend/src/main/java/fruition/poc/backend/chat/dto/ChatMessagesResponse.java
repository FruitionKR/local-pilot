package fruition.poc.backend.chat.dto;

import java.util.List;

public record ChatMessagesResponse(List<ChatMessageResponse> messages) {}
