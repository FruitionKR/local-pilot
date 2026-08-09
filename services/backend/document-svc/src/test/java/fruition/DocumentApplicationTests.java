package fruition;

import fruition.core.DocumentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
// 앱 클래스가 하위 패키지(fruition.core)에 있어 upward 탐색으로는 찾지 못한다 — 명시 지정.
@SpringBootTest(classes = DocumentApplication.class)
@AutoConfigureMockMvc
class DocumentApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void health_unauthenticated_returnsUp() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void flywayPreparesWikiBoundaryWithoutDroppingDataTables() {
		for (String table : new String[]{
				"pipeline_runs",
				"wiki_page_embeddings",
				"wiki_embedding_vectors",
				"wiki_embedding_units",
				"wiki_pages",
				"source_blocks"
		}) {
			Boolean exists = jdbcTemplate.queryForObject(
					"SELECT to_regclass(?) IS NOT NULL",
					Boolean.class,
					"public." + table
			);
			assertThat(exists).as(table).isTrue();
		}
		Integer crossDatabaseConstraints = jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM information_schema.table_constraints
				WHERE constraint_name IN (
				    'fk_wiki_page_versions_page',
				    'fk_wiki_page_contributions_page',
				    'pipeline_runs_document_id_fkey'
				)
				""", Integer.class);
		assertThat(crossDatabaseConstraints).isZero();
		Integer actorColumns = jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM information_schema.columns
				WHERE table_name = 'pipeline_runs'
				  AND column_name IN ('user_id', 'workspace_id')
				""", Integer.class);
		assertThat(actorColumns).isEqualTo(2);
	}

}
