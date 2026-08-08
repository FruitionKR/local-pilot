package fruition.skill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fruition.document.repository.DocumentRepository;
import fruition.skill.domain.Skill;
import fruition.skill.domain.SkillScope;
import fruition.skill.domain.SkillVersion;
import fruition.skill.dto.*;
import fruition.skill.exception.InvalidSkillRequestException;
import fruition.skill.exception.SkillConflictException;
import fruition.skill.exception.SkillNotFoundException;
import fruition.skill.repository.PipelineSkillRequester;
import fruition.skill.repository.SkillRepository;
import fruition.skill.repository.SkillVersionRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

@Service
public class SkillService {
    private final WorkspaceMemberRepository memberRepository;
    private final SkillReferenceDocumentLoader referenceDocumentLoader;
    private final PipelineSkillRequester requester;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final SkillReviewTokenSigner tokenSigner;
    private final ObjectMapper objectMapper;

    public SkillService(WorkspaceMemberRepository memberRepository,
                        SkillReferenceDocumentLoader referenceDocumentLoader,
                        PipelineSkillRequester requester,
                        SkillRepository skillRepository,
                        SkillVersionRepository versionRepository,
                        DocumentRepository documentRepository,
                        SkillReviewTokenSigner tokenSigner,
                        ObjectMapper objectMapper) {
        this.memberRepository = memberRepository;
        this.referenceDocumentLoader = referenceDocumentLoader;
        this.requester = requester;
        this.skillRepository = skillRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.tokenSigner = tokenSigner;
        this.objectMapper = objectMapper;
    }

    public JsonNode refine(String workspaceId, String userId, SkillDraftRequest request) {
        return requester.refine(workspaceId, userId, request, prepare(workspaceId, userId, request));
    }

    public JsonNode review(String workspaceId, String userId, SkillDraftRequest request) {
        List<SkillReferenceDocument> references = prepare(workspaceId, userId, request);
        JsonNode result = requester.review(workspaceId, userId, request, references);
        boolean allowed = result.path("publish_allowed").asBoolean(!result.path("has_blocked_issues").asBoolean(true));
        ObjectNode response = result.deepCopy();
        response.put("publish_allowed", allowed);
        if (allowed) {
            response.put("review_token", tokenSigner.issue(
                    workspaceId, userId, definitionHash(request, references), result.toString()));
        }
        return response;
    }

    @Transactional
    public SkillDetailResponse publish(String workspaceId, String userId, SkillPublishRequest request) {
        SkillDraftRequest draft = request.draft();
        List<SkillReferenceDocument> references = prepareForPublish(workspaceId, userId, draft);
        String hash = definitionHash(draft, references);
        String safetyResult = tokenSigner.verify(request.reviewToken(), workspaceId, userId, hash);
        ensureCommandAvailable(workspaceId, userId, draft, "");
        Skill skill = skillRepository.save(new Skill(workspaceId, userId, scope(draft), draft.command()));
        SkillVersion version = versionRepository.save(version(skill, 1, draft, references, safetyResult, hash, userId));
        return detail(workspaceId, skill, version);
    }

    @Transactional(readOnly = true)
    public List<SkillSummaryResponse> list(String workspaceId, String userId) {
        requireMember(workspaceId, userId);
        return skillRepository.findAccessible(workspaceId, userId).stream()
                .map(skill -> versionRepository.findFirstBySkillIdOrderByVersionDesc(skill.getId())
                        .map(version -> summary(skill, version)).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public SkillDetailResponse get(String workspaceId, String userId, String skillId) {
        requireMember(workspaceId, userId);
        Skill skill = accessible(workspaceId, userId, skillId);
        return detail(workspaceId, skill, latest(skill));
    }

    @Transactional(readOnly = true)
    public List<SkillSummaryResponse> commands(String workspaceId, String userId, String prefix) {
        requireMember(workspaceId, userId);
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        return skillRepository.findAccessible(workspaceId, userId).stream()
                .filter(skill -> skill.getCommand().startsWith(normalized))
                .sorted(Comparator.comparing((Skill skill) -> !skill.getCommand().equals(normalized))
                        .thenComparing(Skill::getCommand)
                        .thenComparing(skill -> skill.getScope() != SkillScope.team))
                .limit(10)
                .map(skill -> summary(skill, latest(skill)))
                .toList();
    }

    @Transactional
    public SkillDetailResponse update(String workspaceId, String userId, String skillId, SkillUpdateRequest request) {
        requireMember(workspaceId, userId);
        Skill skill = skillRepository.findActiveForUpdate(workspaceId, skillId).orElseThrow(SkillNotFoundException::new);
        ensureManageable(skill, userId);
        SkillVersion current = latest(skill);
        if (!current.getId().equals(request.baseVersionId())) {
            throw new SkillConflictException("SKILL_VERSION_CONFLICT", "다른 변경이 먼저 게시되었습니다.");
        }
        SkillDraftRequest draft = request.draft();
        List<SkillReferenceDocument> references = prepareForPublish(workspaceId, userId, draft);
        String hash = definitionHash(draft, references);
        String safetyResult = tokenSigner.verify(request.reviewToken(), workspaceId, userId, hash);
        ensureCommandAvailable(workspaceId, userId, draft, skillId);
        skill.changeIdentity(scope(draft), draft.command(), userId, workspaceId);
        SkillVersion next = versionRepository.save(
                version(skill, current.getVersion() + 1, draft, references, safetyResult, hash, userId));
        return detail(workspaceId, skill, next);
    }

    @Transactional
    public SkillDetailResponse setAutoRouting(String workspaceId, String userId, String skillId, boolean enabled) {
        requireMember(workspaceId, userId);
        Skill skill = skillRepository.findActiveForUpdate(workspaceId, skillId).orElseThrow(SkillNotFoundException::new);
        ensureManageable(skill, userId);
        skill.setAutoRoutingEnabled(enabled);
        return detail(workspaceId, skill, latest(skill));
    }

    @Transactional
    public void delete(String workspaceId, String userId, String skillId) {
        requireMember(workspaceId, userId);
        Skill skill = skillRepository.findActiveForUpdate(workspaceId, skillId).orElseThrow(SkillNotFoundException::new);
        ensureManageable(skill, userId);
        skill.delete(userId);
    }

    private List<SkillReferenceDocument> prepare(String workspaceId, String userId, SkillDraftRequest request) {
        requireMember(workspaceId, userId);
        if (new HashSet<>(request.referenceDocumentIds()).size() != request.referenceDocumentIds().size()) {
            throw new InvalidSkillRequestException("참조 문서는 중복해서 선택할 수 없습니다.");
        }
        return referenceDocumentLoader.load(workspaceId, request.referenceDocumentIds());
    }

    private List<SkillReferenceDocument> prepareForPublish(String workspaceId, String userId, SkillDraftRequest draft) {
        if (draft.command() == null || draft.command().isBlank()) {
            throw new InvalidSkillRequestException("게시할 command를 입력해야 합니다.");
        }
        return prepare(workspaceId, userId, draft);
    }

    private void requireMember(String workspaceId, String userId) {
        if (!memberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    private void ensureManageable(Skill skill, String userId) {
        if (skill.getScope() == SkillScope.personal && !userId.equals(skill.getOwnerUserId())) {
            throw new SkillNotFoundException();
        }
    }

    private Skill accessible(String workspaceId, String userId, String skillId) {
        return skillRepository.findAccessibleById(workspaceId, userId, skillId)
                .orElseThrow(SkillNotFoundException::new);
    }

    private SkillVersion latest(Skill skill) {
        return versionRepository.findFirstBySkillIdOrderByVersionDesc(skill.getId())
                .orElseThrow(SkillNotFoundException::new);
    }

    private void ensureCommandAvailable(String workspaceId, String userId, SkillDraftRequest draft, String excludedId) {
        String lockOwner = scope(draft) == SkillScope.team ? workspaceId : userId;
        skillRepository.lockCommand(scope(draft).name() + ":" + lockOwner + ":" + draft.command());
        if (skillRepository.commandExists(workspaceId, userId, draft.command(), scope(draft) == SkillScope.team, excludedId)) {
            throw new SkillConflictException("SKILL_COMMAND_CONFLICT", "이미 사용 중인 command입니다.");
        }
    }

    private SkillScope scope(SkillDraftRequest draft) {
        return SkillScope.valueOf(draft.scope());
    }

    private SkillVersion version(Skill skill, int number, SkillDraftRequest draft,
                                 List<SkillReferenceDocument> references, String safetyResult,
                                 String hash, String userId) {
        try {
            List<SkillReferenceDocument> snapshots = references.stream()
                    .map(reference -> new SkillReferenceDocument(reference.id(), reference.name(), reference.contentHash(), null))
                    .toList();
            return new SkillVersion(skill.getId(), number, draft.name(), draft.description(), draft.instructions(),
                    objectMapper.writeValueAsString(draft.capabilities()),
                    objectMapper.writeValueAsString(draft.allowedTools()),
                    objectMapper.writeValueAsString(snapshots), safetyResult, hash, userId);
        } catch (Exception exception) {
            throw new IllegalStateException("Skill version을 직렬화할 수 없습니다.", exception);
        }
    }

    private SkillSummaryResponse summary(Skill skill, SkillVersion version) {
        return new SkillSummaryResponse(skill.getId(), skill.getCommand(), version.getName(), version.getDescription(),
                skill.getScope().name(), skill.isAutoRoutingEnabled(), true, true);
    }

    private SkillDetailResponse detail(String currentWorkspaceId, Skill skill, SkillVersion version) {
        try {
            List<String> capabilities = objectMapper.readValue(version.getCapabilities(), new TypeReference<>() {});
            List<String> tools = objectMapper.readValue(version.getAllowedTools(), new TypeReference<>() {});
            List<SkillReferenceDocument> stored = objectMapper.readValue(
                    version.getReferenceDocuments(), new TypeReference<>() {});
            List<SkillDetailResponse.ReferenceDocument> references = stored.stream()
                    .map(reference -> new SkillDetailResponse.ReferenceDocument(reference.id(), reference.name(),
                            reference.contentHash(), documentRepository
                            .findByIdAndWorkspaceIdAndDeletedAtIsNull(reference.id(), currentWorkspaceId)
                            .filter(document -> reference.contentHash().equals(document.getCurrentContentHash()))
                            .isPresent() ? "available" : "unavailable"))
                    .toList();
            return new SkillDetailResponse(skill.getId(), skill.getCommand(), version.getName(),
                    version.getDescription(), version.getInstructions(), skill.getScope().name(),
                    skill.isAutoRoutingEnabled(), version.getId(), version.getVersion(), capabilities, tools, references);
        } catch (Exception exception) {
            throw new IllegalStateException("Skill version을 읽을 수 없습니다.", exception);
        }
    }

    private String definitionHash(SkillDraftRequest draft, List<SkillReferenceDocument> references) {
        try {
            Object definition = List.of(draft, references.stream()
                    .map(reference -> List.of(reference.id(), reference.name(), reference.contentHash())).toList());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(definition).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Skill definition hash를 계산할 수 없습니다.", exception);
        }
    }
}
