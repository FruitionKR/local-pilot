package fruition.skill.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skill_versions")
public class SkillVersion {
    @Id private String id;
    @Column(name = "skill_id", nullable = false) private String skillId;
    @Column(nullable = false) private int version;
    @Column(nullable = false, length = 63) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "instructions_markdown", nullable = false, columnDefinition = "TEXT") private String instructions;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String capabilities;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "allowed_tools", nullable = false, columnDefinition = "jsonb") private String allowedTools;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "reference_documents", nullable = false, columnDefinition = "jsonb") private String referenceDocuments;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "safety_result", nullable = false, columnDefinition = "jsonb") private String safetyResult;
    @Column(name = "definition_hash", nullable = false, length = 64) private String definitionHash;
    @Column(name = "created_by", nullable = false, updatable = false) private String createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected SkillVersion() {}

    public SkillVersion(String skillId, int version, String name, String description, String instructions,
                        String capabilities, String allowedTools, String referenceDocuments,
                        String safetyResult, String definitionHash, String createdBy) {
        this.id = UUID.randomUUID().toString();
        this.skillId = skillId;
        this.version = version;
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.capabilities = capabilities;
        this.allowedTools = allowedTools;
        this.referenceDocuments = referenceDocuments;
        this.safetyResult = safetyResult;
        this.definitionHash = definitionHash;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getSkillId() { return skillId; }
    public int getVersion() { return version; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInstructions() { return instructions; }
    public String getCapabilities() { return capabilities; }
    public String getAllowedTools() { return allowedTools; }
    public String getReferenceDocuments() { return referenceDocuments; }
    public String getSafetyResult() { return safetyResult; }
    public String getDefinitionHash() { return definitionHash; }
}
