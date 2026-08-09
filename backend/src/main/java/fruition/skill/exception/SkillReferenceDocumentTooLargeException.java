package fruition.skill.exception;

public class SkillReferenceDocumentTooLargeException extends RuntimeException {
    public SkillReferenceDocumentTooLargeException() {
        super("참조 문서는 문서당 30,000자, 합계 60,000자 이하여야 합니다.");
    }
}
