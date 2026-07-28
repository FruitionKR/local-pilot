package fruition.user.mail;

import fruition.user.exception.EmailVerificationSendException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailVerificationSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    @Test
    void send_signup_setsFromToSubjectAndCode() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, "no-reply@fruition.app");

        sender.send("user@example.com", "signup", "123456");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@fruition.app");
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).contains("회원가입");
        assertThat(message.getText()).contains("123456");
    }

    @Test
    void send_passwordReset_usesResetSubject() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, "no-reply@fruition.app");

        sender.send("user@example.com", "password_reset", "654321");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).contains("비밀번호 재설정");
    }

    @Test
    void constructor_rejectsBlankFrom() {
        assertThatThrownBy(() -> new SmtpEmailVerificationSender(mailSender, " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void send_wrapsMailFailure() {
        SmtpEmailVerificationSender sender = new SmtpEmailVerificationSender(mailSender, "no-reply@fruition.app");
        doThrow(new MailSendException("smtp down")).when(mailSender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send("user@example.com", "signup", "123456"))
                .isInstanceOf(EmailVerificationSendException.class);
    }
}
