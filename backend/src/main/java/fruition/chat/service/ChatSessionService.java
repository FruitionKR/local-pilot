package fruition.chat.service;

import fruition.chat.domain.ChatSession;
import fruition.chat.dto.ChatSessionCreateRequest;
import fruition.chat.dto.ChatSessionListResponse;
import fruition.chat.dto.ChatSessionResponse;
import fruition.chat.exception.ChatSessionLimitExceededException;
import fruition.chat.exception.ChatSessionNotFoundException;
import fruition.chat.repository.ChatSessionRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ChatSessionService {

    private static final int MAX_SESSIONS_PER_WORKSPACE = 10;

    private final ChatSessionRepository chatSessionRepository;
    private final WorkspaceRepository workspaceRepository;

    public ChatSessionService(ChatSessionRepository chatSessionRepository, WorkspaceRepository workspaceRepository) {
        this.chatSessionRepository = chatSessionRepository;
        this.workspaceRepository = workspaceRepository;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        workspaceRepository.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }

    /** workspace 소유권과 세션 소속을 함께 검증한다. 호출부는 컨트롤러/QueryController에서 재사용한다. */
    public ChatSession verifyOwnedSession(String workspaceId, String userId, String sessionId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return chatSessionRepository.findByIdAndWorkspaceId(sessionId, workspaceId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
    }

    @Transactional
    public ChatSessionResponse create(String workspaceId, String userId, ChatSessionCreateRequest request) {
        verifyWorkspaceOwnership(workspaceId, userId);

        if (chatSessionRepository.countByWorkspaceId(workspaceId) >= MAX_SESSIONS_PER_WORKSPACE) {
            throw new ChatSessionLimitExceededException(workspaceId);
        }

        String sessionId = "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ChatSession session = new ChatSession(sessionId, workspaceId, userId, request.title());
        chatSessionRepository.save(session);

        return toResponse(session);
    }

    public ChatSessionListResponse list(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);

        return new ChatSessionListResponse(
                chatSessionRepository.findAllByWorkspaceIdOrderByLastMessageAtDesc(workspaceId).stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    @Transactional
    public void delete(String workspaceId, String userId, String sessionId) {
        ChatSession session = verifyOwnedSession(workspaceId, userId, sessionId);
        chatSessionRepository.delete(session);
    }

    private ChatSessionResponse toResponse(ChatSession session) {
        return new ChatSessionResponse(session.getId(), session.getTitle(), session.getCreatedAt(), session.getLastMessageAt());
    }
}
