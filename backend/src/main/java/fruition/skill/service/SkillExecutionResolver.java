package fruition.skill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.document.repository.DocumentRepository;
import fruition.skill.domain.Skill;
import fruition.skill.domain.SkillVersion;
import fruition.skill.dto.SkillExecutionDefinition;
import fruition.skill.dto.SkillExecutionPlan;
import fruition.skill.exception.SkillNotFoundException;
import fruition.skill.exception.SkillReferenceStaleException;
import fruition.skill.repository.SkillRepository;
import fruition.skill.repository.SkillVersionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SkillExecutionResolver {
    private static final Pattern EXPLICIT_COMMAND = Pattern.compile(
            "^/([a-z0-9][a-z0-9-]{0,62})(?:\\s+([\\s\\S]*))?$");

    private final SkillRepository skillRepository;
    private final SkillVersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public SkillExecutionResolver(SkillRepository skillRepository,
                                  SkillVersionRepository versionRepository,
                                  DocumentRepository documentRepository,
                                  ObjectMapper objectMapper) {
        this.skillRepository = skillRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SkillExecutionPlan resolve(String workspaceId, String userId, String message) {
        Matcher matcher = EXPLICIT_COMMAND.matcher(message.strip());
        if (matcher.matches()) {
            Skill skill = skillRepository.findAccessibleByCommand(
                            workspaceId, userId, matcher.group(1), PageRequest.of(0, 1)).stream()
                    .findFirst().orElseThrow(SkillNotFoundException::new);
            String remainingMessage = matcher.group(2) == null ? "" : matcher.group(2).strip();
            if (remainingMessage.isBlank()) remainingMessage = "Skill을 실행해 주세요.";
            return SkillExecutionPlan.explicit(remainingMessage, definition(workspaceId, skill));
        }
        List<SkillExecutionDefinition> candidates = skillRepository
                .findAutoRoutingCandidates(workspaceId, userId, PageRequest.of(0, 20)).stream()
                .flatMap(skill -> availableDefinition(workspaceId, skill))
                .toList();
        return SkillExecutionPlan.auto(message, candidates);
    }

    private Stream<SkillExecutionDefinition> availableDefinition(String workspaceId, Skill skill) {
        try {
            return Stream.of(definition(workspaceId, skill));
        } catch (SkillReferenceStaleException exception) {
            return Stream.empty();
        }
    }

    private SkillExecutionDefinition definition(String workspaceId, Skill skill) {
        SkillVersion version = versionRepository.findFirstBySkillIdOrderByVersionDesc(skill.getId())
                .orElseThrow(SkillNotFoundException::new);
        try {
            List<String> capabilities = objectMapper.readValue(version.getCapabilities(), new TypeReference<>() {});
            List<String> allowedTools = objectMapper.readValue(version.getAllowedTools(), new TypeReference<>() {});
            List<SkillReferenceDocument> references = objectMapper.readValue(
                    version.getReferenceDocuments(), new TypeReference<>() {});
            List<SkillExecutionDefinition.ReferenceDocument> snapshots = references.stream()
                    .map(reference -> {
                        boolean current = documentRepository
                                .findByIdAndWorkspaceIdAndDeletedAtIsNull(reference.id(), workspaceId)
                                .filter(document -> reference.contentHash().equals(document.getCurrentContentHash()))
                                .isPresent();
                        if (!current) throw new SkillReferenceStaleException();
                        return new SkillExecutionDefinition.ReferenceDocument(
                                reference.id(), reference.name(), reference.contentHash());
                    })
                    .toList();
            return new SkillExecutionDefinition(skill.getId(), version.getId(), skill.getCommand(),
                    version.getName(), version.getDescription(), version.getInstructions(), capabilities,
                    allowedTools, snapshots);
        } catch (SkillReferenceStaleException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Skill 실행 definition을 읽을 수 없습니다.", exception);
        }
    }
}
