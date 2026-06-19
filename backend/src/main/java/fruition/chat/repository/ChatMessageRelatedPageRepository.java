package fruition.chat.repository;

import fruition.chat.domain.ChatMessageRelatedPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRelatedPageRepository extends JpaRepository<ChatMessageRelatedPage, Long> {
    List<ChatMessageRelatedPage> findAllByChatMessageIdIn(List<String> chatMessageIds);
}
