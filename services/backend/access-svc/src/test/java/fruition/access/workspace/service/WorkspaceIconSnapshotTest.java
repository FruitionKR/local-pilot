package fruition.access.workspace.service;

import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceIcon;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceIconUpdateRequest;
import fruition.access.workspace.exception.WorkspaceIconNotFoundException;
import fruition.access.workspace.repository.WorkspaceIconRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WorkspaceIconService.class, WorkspaceIconValidator.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class WorkspaceIconSnapshotTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};

    @Autowired EntityManager entityManager;
    @Autowired WorkspaceIconService service;
    @Autowired WorkspaceIconValidator validator;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceIconRepository icons;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean WorkspaceMemberRepository members;

    String workspaceId;
    String userId;
    TransactionTemplate writer;

    @BeforeEach
    void setUp() {
        workspaceId = "ws_" + UUID.randomUUID();
        userId = "user_" + UUID.randomUUID();
        writer = new TransactionTemplate(transactionManager);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writer.executeWithoutResult(status -> {
            User user = users.save(new User(userId, userId + "@example.com", User.PROVIDER_LOCAL, "소유자", null));
            Workspace workspace = new Workspace(workspaceId, "아이콘 테스트");
            workspace.changeIconImage("image/png", "hash-old");
            workspace = workspaces.save(workspace);
            members.save(new WorkspaceMember(workspace, user, WorkspaceRole.OWNER));
            icons.save(new WorkspaceIcon(workspaceId, PNG));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readKeepsSnapshotWhenAnotherTransactionReplacesOrDeletesImage(boolean delete) {
        MockMultipartFile upload = new MockMultipartFile("file", "icon.jpg", "image/jpeg", JPEG);
        AtomicBoolean replace = new AtomicBoolean(true);
        // 첫 SELECT 직후 독립 트랜잭션을 커밋하여 두 SELECT 사이의 경합을 확정적으로 만든다.
        doAnswer(invocation -> {
            var workspace = java.util.Optional.ofNullable(entityManager.find(Workspace.class, workspaceId));
            if (replace.getAndSet(false)) {
                writer.executeWithoutResult(status -> {
                    if (delete) {
                        service.updateIcon(userId, workspaceId, new WorkspaceIconUpdateRequest(null));
                    } else {
                        service.updateIconImage(userId, workspaceId, upload);
                    }
                });
            }
            return workspace;
        }).when(members).findActiveWorkspaceForMember(workspaceId, userId);

        var image = service.readIconImage(userId, workspaceId);
        assertThat(image.bytes()).isEqualTo(PNG);
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.hash()).isEqualTo("hash-old");

        if (delete) {
            assertThatThrownBy(() -> service.readIconImage(userId, workspaceId))
                    .isInstanceOf(WorkspaceIconNotFoundException.class);
        } else {
            var latest = service.readIconImage(userId, workspaceId);
            assertThat(latest.bytes()).isEqualTo(JPEG);
            assertThat(latest.contentType()).isEqualTo("image/jpeg");
            assertThat(latest.hash()).isEqualTo(validator.validate(upload).hash());
        }
    }
}
