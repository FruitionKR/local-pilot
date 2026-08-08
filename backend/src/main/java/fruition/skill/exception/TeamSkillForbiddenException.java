package fruition.skill.exception;

public class TeamSkillForbiddenException extends RuntimeException {
    public TeamSkillForbiddenException() {
        super("팀 Skill은 Workspace 소유자만 게시할 수 있습니다.");
    }
}
