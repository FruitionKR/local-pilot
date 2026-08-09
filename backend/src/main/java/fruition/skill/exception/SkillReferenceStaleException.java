package fruition.skill.exception;

public class SkillReferenceStaleException extends RuntimeException {
    public SkillReferenceStaleException() {
        super("Skill 참조 문서가 변경되었거나 삭제되었습니다. 다시 검토해 주세요.");
    }
}
