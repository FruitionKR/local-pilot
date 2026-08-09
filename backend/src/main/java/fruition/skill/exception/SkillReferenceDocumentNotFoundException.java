package fruition.skill.exception;

public class SkillReferenceDocumentNotFoundException extends RuntimeException {
    public SkillReferenceDocumentNotFoundException() {
        super("참조 문서를 찾을 수 없습니다.");
    }
}
