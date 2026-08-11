package fruition.core.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.core.skill.repository.PipelineSkillRequester;
import fruition.shared.ai.AiModelCatalog;
import org.springframework.stereotype.Service;

@Service
public class SkillService {

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineSkillRequester requester;
    private final AiModelCatalog aiModelCatalog;

    public SkillService(WorkspaceAccessGuard workspaceAccessGuard, PipelineSkillRequester requester,
                        AiModelCatalog aiModelCatalog) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.requester = requester;
        this.aiModelCatalog = aiModelCatalog;
    }

    public JsonNode author(String workspaceId, String userId, SkillAuthoringRequest request) {
        requireMember(workspaceId, userId);
        aiModelCatalog.resolve(request.provider(), request.model());
        return requester.author(workspaceId, userId, request);
    }

    public JsonNode publish(String workspaceId, String userId, SkillPublishRequest request) {
        requireMember(workspaceId, userId);
        aiModelCatalog.resolve(request.provider(), request.model());
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
        aiModelCatalog.resolve(request.provider(), request.model());
        return requester.update(workspaceId, userId, skillId, request);
    }

    public JsonNode setEnabled(String workspaceId, String userId, String skillId, boolean enabled) {
        requireMember(workspaceId, userId);
        return requester.setEnabled(workspaceId, userId, skillId, enabled);
    }

    private void requireMember(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }
}
