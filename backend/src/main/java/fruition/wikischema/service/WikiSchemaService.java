package fruition.wikischema.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wikischema.dto.WikiSchemaDraftRequest;
import fruition.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.wikischema.repository.PipelineWikiSchemaRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;

@Service
public class WikiSchemaService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final PipelineWikiSchemaRequester requester;

    public WikiSchemaService(WorkspaceMemberRepository workspaceMemberRepository,
                             PipelineWikiSchemaRequester requester) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.requester = requester;
    }

    public JsonNode preview(String workspaceId, String userId, WikiSchemaPreviewRequest request) {
        verifyWorkspaceMembership(workspaceId, userId);
        return requester.preview(request.rawMarkdown());
    }

    public JsonNode createDraft(String workspaceId, String userId, WikiSchemaDraftRequest request) {
        verifyWorkspaceMembership(workspaceId, userId);
        return requester.createDraft(request.rawMarkdown(), request.name(), workspaceId, userId);
    }

    public JsonNode activate(String workspaceId, String userId, String schemaId) {
        verifyWorkspaceMembership(workspaceId, userId);
        return requester.activate(schemaId);
    }

    public JsonNode getActive(String workspaceId, String userId) {
        verifyWorkspaceMembership(workspaceId, userId);
        return requester.getActive(workspaceId, userId);
    }

    private void verifyWorkspaceMembership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }
}
