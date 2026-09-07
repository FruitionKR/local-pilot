package fruition.access.user.mail;

/**
 * 워크스페이스 초대 메일 발송 추상화. SMTP 설정이 없는 환경에서는
 * {@link LoggingWorkspaceInvitationSender}가 링크를 로그로 남긴다.
 */
public interface WorkspaceInvitationSender {
    void send(String email, String workspaceName, String inviterName, String acceptUrl);
}
