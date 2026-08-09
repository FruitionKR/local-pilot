package fruition.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.skill.dto.SkillAuthoringRequest;
import fruition.skill.dto.SkillPublishRequest;
import fruition.skill.dto.SkillUpdateRequest;
import fruition.skill.exception.InvalidSkillRequestException;
import fruition.skill.repository.PipelineSkillRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;

@Service
public class SkillService {
    private final WorkspaceMemberRepository memberRepository;
    private final PipelineSkillRequester requester;

    public SkillService(WorkspaceMemberRepository memberRepository, PipelineSkillRequester requester) {
        this.memberRepository = memberRepository;
        this.requester = requester;
    }

    public JsonNode author(String workspaceId, String userId, SkillAuthoringRequest request) {
        requireMember(workspaceId, userId);
        if (!"preserve".equals(request.authoringMode()) && request.instruction().length() > 4000) {
            throw new InvalidSkillRequestException("enhance와 regenerate 입력은 4,000자 이하여야 합니다.");
        }
        if (new HashSet<>(request.referenceDocumentIds()).size() != request.referenceDocumentIds().size()) {
            throw new InvalidSkillRequestException("참조 문서는 중복해서 선택할 수 없습니다.");
        }
        return requester.author(workspaceId, userId, request);
    }

    public JsonNode publish(String workspaceId, String userId, SkillPublishRequest request) {
        requireMember(workspaceId, userId);
        return requester.publish(workspaceId, userId, request);
    }

    public JsonNode list(String workspaceId, String userId) {
        requireMember(workspaceId, userId);
        return requester.list(workspaceId, userId);
    }

    public JsonNode get(String workspaceId, String userId, String skillId) {
        requireMember(workspaceId, userId);
        return requester.get(workspaceId, userId, skillId);
    }

    public JsonNode update(String workspaceId, String userId, String skillId, SkillUpdateRequest request) {
        requireMember(workspaceId, userId);
        return requester.update(workspaceId, userId, skillId, request);
    }

    public JsonNode setEnabled(String workspaceId, String userId, String skillId, boolean enabled) {
        requireMember(workspaceId, userId);
        return requester.setEnabled(workspaceId, userId, skillId, enabled);
    }

    private void requireMember(String workspaceId, String userId) {
        if (!memberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }
}
