package fruition.wikischema.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wikischema.dto.CreateWikiSchemaDraftRequest;
import fruition.wikischema.repository.PipelineWikiSchemaRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;

@Service
public class WikiSchemaService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final PipelineWikiSchemaRequester pipelineWikiSchemaRequester;

    public WikiSchemaService(
            WorkspaceMemberRepository workspaceMemberRepository,
            PipelineWikiSchemaRequester pipelineWikiSchemaRequester) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.pipelineWikiSchemaRequester = pipelineWikiSchemaRequester;
    }

    public JsonNode preview(String workspaceId, String userId, String rawMarkdown) {
        verifyWorkspaceMembership(workspaceId, userId);
        return pipelineWikiSchemaRequester.preview(rawMarkdown);
    }

    public JsonNode createDraft(String workspaceId, String userId, CreateWikiSchemaDraftRequest request) {
        verifyWorkspaceMembership(workspaceId, userId);
        // 빈 이름은 pipeline 기본값("default")을 쓰도록 생략한다.
        String name = request.name() == null || request.name().isBlank() ? null : request.name();
        return pipelineWikiSchemaRequester.createDraft(request.rawMarkdown(), workspaceId, userId, name);
    }

    public JsonNode activate(String workspaceId, String userId, String schemaId) {
        verifyWorkspaceMembership(workspaceId, userId);
        return pipelineWikiSchemaRequester.activate(schemaId);
    }

    public JsonNode getActive(String workspaceId, String userId) {
        verifyWorkspaceMembership(workspaceId, userId);
        return pipelineWikiSchemaRequester.getActive(workspaceId, userId);
    }

    private void verifyWorkspaceMembership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }
}
