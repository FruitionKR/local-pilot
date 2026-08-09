package fruition.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.agent.dto.AgentToolExecuteRequest;
import fruition.agent.dto.AgentToolReadRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.repository.DocumentEditStateRepository;
import fruition.document.service.DocumentPlacementService;
import fruition.document.service.DocumentService;
import fruition.document.service.FolderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class AgentToolServiceTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock FolderService folderService;
    @Mock DocumentService documentService;
    @Mock DocumentPlacementService documentPlacementService;
    @Mock DocumentEditStateRepository editStateRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentToolService service;

    @BeforeEach
    void setUp() {
        service = new AgentToolService(
                jdbcTemplate, objectMapper, folderService, documentService, documentPlacementService,
                editStateRepository);
    }

    @Test
    void read_rejectsRunOutsideActorScope() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        assertThrows(ResponseStatusException.class, () -> service.read("list_root_items",
                new AgentToolReadRequest("run_1", "ws_1", "user_1", objectMapper.createObjectNode())));
        verifyNoInteractions(folderService);
    }

    @Test
    void execute_dispatchesOnlyApprovedCurrentOperation() {
        var arguments = objectMapper.createObjectNode();
        arguments.put("name", "새 폴더");
        arguments.putNull("parent_folder_id");
        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(java.util.List.of(arguments.toString()));
        FolderResponse expected = new FolderResponse(
                UUID.randomUUID(), null, "새 폴더", 1, 1, null, null);
        when(folderService.create(eq("ws_1"), eq("user_1"), eq("idem_1"), any())).thenReturn(expected);

        Object actual = service.execute("create_folder", new AgentToolExecuteRequest(
                "run_1", "ws_1", "user_1", "plan_1", 1, "hash", "operation_1", "idem_1",
                arguments));

        assertSame(expected, actual);
        verify(folderService).create(eq("ws_1"), eq("user_1"), eq("idem_1"), any());
    }

    @Test
    void execute_rejectsArgumentsDifferentFromApprovedOperation() {
        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(java.util.List.of(
                "{\"name\":\"승인된 이름\",\"parent_folder_id\":null}"));
        var arguments = objectMapper.createObjectNode();
        arguments.put("name", "변조된 이름");
        arguments.putNull("parent_folder_id");

        assertThrows(ResponseStatusException.class, () -> service.execute("create_folder",
                new AgentToolExecuteRequest(
                        "run_1", "ws_1", "user_1", "plan_1", 1, "hash", "operation_1", "idem_1",
                        arguments)));

        verifyNoInteractions(folderService);
    }

    @Test
    void execute_resolvesApprovedOperationResultReferenceBeforeComparison() {
        String parentId = UUID.randomUUID().toString();
        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class)))
                .thenReturn(java.util.List.of("""
                        {"name":"하위 폴더","parent_folder_id":{
                          "$operation_result":"create_parent","field":"id"}}
                        """))
                .thenReturn(java.util.List.of("{\"id\":\"" + parentId + "\"}"));
        var arguments = objectMapper.createObjectNode();
        arguments.put("name", "하위 폴더");
        arguments.put("parent_folder_id", parentId);
        FolderResponse expected = new FolderResponse(
                UUID.randomUUID(), UUID.fromString(parentId), "하위 폴더", 1, 1, null, null);
        when(folderService.create(eq("ws_1"), eq("user_1"), eq("idem_1"), any())).thenReturn(expected);

        Object actual = service.execute("create_folder", new AgentToolExecuteRequest(
                "run_1", "ws_1", "user_1", "plan_1", 1, "hash", "operation_2", "idem_1", arguments));

        assertSame(expected, actual);
    }

    @Test
    void execute_rejectsContentMutationUntilArtifactContractExists() {
        assertThrows(ResponseStatusException.class, () -> service.execute("create_document",
                new AgentToolExecuteRequest(
                        "run_1", "ws_1", "user_1", "plan_1", 1, "hash", "operation_1", "idem_1",
                        objectMapper.createObjectNode())));
        verifyNoInteractions(jdbcTemplate, folderService, documentService, documentPlacementService);
    }
}
