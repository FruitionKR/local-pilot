package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.dto.EmailChangeRequest;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EmailChangeConflictIntegrationTest {
    @Autowired AuthService authService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean UserRepository users;
    @MockitoBean EmailVerificationService emailVerificationService;

    @Test
    void competingCommitAfterDuplicateCheck_returnsDuplicateAndRollsBackEmail() {
        users.saveAndFlush(new User("email_loser", "loser@example.com", "local", "사용자", null));
        users.saveAndFlush(new User("email_winner", "winner@example.com", "local", "사용자", null));
        TransactionTemplate competitor = new TransactionTemplate(transactionManager);
        competitor.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 중복 조회가 빈 결과를 얻은 직후 다른 변경이 커밋되는 순서를 재현한다.
        doAnswer(invocation -> {
            competitor.executeWithoutResult(status -> users.findById("email_winner").orElseThrow()
                    .changeEmail("shared@example.com"));
            return false;
        }).when(users).existsByEmailAndProvider("shared@example.com", "local");

        assertThatThrownBy(() -> authService.changeEmail("email_loser",
                new EmailChangeRequest("shared@example.com", "verification-token"), null))
                .isInstanceOf(DuplicateEmailException.class);
        assertThat(users.findById("email_loser").orElseThrow().getEmail()).isEqualTo("loser@example.com");
        assertThat(users.findById("email_winner").orElseThrow().getEmail()).isEqualTo("shared@example.com");
    }
}
