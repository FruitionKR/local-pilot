package fruition.access.user.mail;

import fruition.access.workspace.exception.InvitationSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** 운영용 SMTP 발송. 수락 링크와 수신 이메일 원문은 로그에 남기지 않는다. */
public class SmtpWorkspaceInvitationSender implements WorkspaceInvitationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpWorkspaceInvitationSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpWorkspaceInvitationSender(JavaMailSender mailSender, String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "SMTP 발송에는 발신 주소(app.auth.email-verification.from / MAIL_FROM)가 필요합니다.");
        }
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String email, String workspaceName, String inviterName, String acceptUrl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("[Fruition] " + workspaceName + " 워크스페이스에 초대되었습니다");
        message.setText(
                inviterName + "님이 회원님을 '" + workspaceName + "' 워크스페이스에 초대했습니다.\n\n"
                        + "아래 링크에서 초대를 수락하세요.\n"
                        + acceptUrl + "\n\n"
                        + "이 링크는 이 메일을 받은 주소의 계정으로 로그인했을 때만 수락됩니다.\n"
                        + "초대를 요청하지 않으셨다면 이 메일을 무시하셔도 됩니다.");
        try {
            mailSender.send(message);
            log.info("[초대 메일 발송 완료] workspaceName={}", workspaceName);
        } catch (MailException e) {
            log.warn("[초대 메일 발송 실패] workspaceName={} error={}", workspaceName, e.getMessage());
            throw new InvitationSendException("초대 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }
}
