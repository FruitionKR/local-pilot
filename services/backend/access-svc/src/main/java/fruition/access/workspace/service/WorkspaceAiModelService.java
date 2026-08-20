package fruition.access.workspace.service;

import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.dto.WorkspaceAiModelRequest;
import fruition.access.workspace.dto.WorkspaceAiModelResponse;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceRepository;
import fruition.shared.ai.AiModelCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceAiModelService {
    private final WorkspaceRepository workspaceRepository;
    private final AiModelCatalog catalog;

    public WorkspaceAiModelService(WorkspaceRepository workspaceRepository,
                                   AiModelCatalog catalog) {
        this.workspaceRepository = workspaceRepository;
        this.catalog = catalog;
    }

    @Transactional
    public WorkspaceAiModelResponse updateInternal(String workspaceId, WorkspaceAiModelRequest request) {
        Workspace workspace = findActive(workspaceId);
        AiModelCatalog.AiModel selected = catalog.resolve(
                request.ingestLint().provider(), request.ingestLint().model());
        workspace.changeIngestLintModel(selected.provider(), selected.model());
        return WorkspaceAiModelResponse.from(workspace);
    }

    @Transactional(readOnly = true)
    public WorkspaceAiModelResponse getInternal(String workspaceId) {
        return WorkspaceAiModelResponse.from(findActive(workspaceId));
    }

    private Workspace findActive(String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .filter(workspace -> workspace.getDeletedAt() == null)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }
}
