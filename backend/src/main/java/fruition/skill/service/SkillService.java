package fruition.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.skill.dto.SkillDraftRequest;
import fruition.skill.dto.SkillPublishRequest;
import fruition.skill.exception.InvalidSkillRequestException;
import fruition.skill.exception.TeamSkillForbiddenException;
import fruition.skill.repository.PipelineSkillRequester;
import fruition.workspace.domain.WorkspaceMember;
import fruition.workspace.domain.WorkspaceRole;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;

@Service
public class SkillService {

    private final WorkspaceMemberRepository memberRepository;
    private final SkillReferenceDocumentLoader referenceDocumentLoader;
    private final PipelineSkillRequester requester;

    public SkillService(
            WorkspaceMemberRepository memberRepository,
            SkillReferenceDocumentLoader referenceDocumentLoader,
            PipelineSkillRequester requester
    ) {
        this.memberRepository = memberRepository;
        this.referenceDocumentLoader = referenceDocumentLoader;
        this.requester = requester;
    }

    public JsonNode refine(String workspaceId, String userId, SkillDraftRequest request) {
        return requester.refine(workspaceId, userId, request, prepare(workspaceId, userId, request));
    }

    public JsonNode review(String workspaceId, String userId, SkillDraftRequest request) {
        return requester.review(workspaceId, userId, request, prepare(workspaceId, userId, request));
    }

    public JsonNode publish(String workspaceId, String userId, SkillPublishRequest request) {
        SkillDraftRequest draft = request.draft();
        List<SkillReferenceDocument> references = prepare(workspaceId, userId, draft);
        return requester.publish(workspaceId, userId, draft, references, request.reviewToken());
    }

    private List<SkillReferenceDocument> prepare(
            String workspaceId, String userId, SkillDraftRequest request) {
        WorkspaceMember member = memberRepository.findByWorkspace_IdAndUser_Id(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        if ("team".equals(request.scope()) && member.getRole() != WorkspaceRole.OWNER) {
            throw new TeamSkillForbiddenException();
        }
        if (new HashSet<>(request.referenceDocumentIds()).size() != request.referenceDocumentIds().size()) {
            throw new InvalidSkillRequestException("참조 문서는 중복해서 선택할 수 없습니다.");
        }
        return referenceDocumentLoader.load(workspaceId, request.referenceDocumentIds());
    }
}
