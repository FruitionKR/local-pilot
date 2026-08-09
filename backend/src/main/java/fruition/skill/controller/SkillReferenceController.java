package fruition.skill.controller;

import fruition.agent.service.AgentServiceTokenVerifier;
import fruition.skill.dto.SkillReferenceReadRequest;
import fruition.skill.dto.SkillReferenceReadResponse;
import fruition.skill.service.SkillReferenceDocumentLoader;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/agent/skill-authoring/references")
public class SkillReferenceController {
    private final AgentServiceTokenVerifier tokenVerifier;
    private final WorkspaceMemberRepository memberRepository;
    private final SkillReferenceDocumentLoader documentLoader;

    public SkillReferenceController(
            AgentServiceTokenVerifier tokenVerifier,
            WorkspaceMemberRepository memberRepository,
            SkillReferenceDocumentLoader documentLoader) {
        this.tokenVerifier = tokenVerifier;
        this.memberRepository = memberRepository;
        this.documentLoader = documentLoader;
    }

    @PostMapping("/read")
    public ResponseEntity<SkillReferenceReadResponse> read(
            @RequestHeader(value = "X-Agent-Service-Token", required = false) String token,
            @Valid @RequestBody SkillReferenceReadRequest request) {
        tokenVerifier.verify(token);
        if (!memberRepository.existsByWorkspace_IdAndUser_Id(request.workspaceId(), request.userId())) {
            throw new WorkspaceNotFoundException(request.workspaceId());
        }
        String markdown = documentLoader.load(request.workspaceId(), List.of(request.documentId())).getFirst().content();
        return ResponseEntity.ok(new SkillReferenceReadResponse(markdown));
    }
}
