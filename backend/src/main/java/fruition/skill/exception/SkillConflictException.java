package fruition.skill.exception;

public class SkillConflictException extends RuntimeException {
    private final String code;

    public SkillConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
