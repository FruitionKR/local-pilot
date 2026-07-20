package fruition;

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
@SpringBootTest
@AutoConfigureMockMvc
class BackendApplicationTests {

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
	void flywayCreatesPipelineTables() {
		for (String table : new String[]{
				"pipeline_runs",
				"wiki_page_embeddings",
				"wiki_embedding_vectors",
				"wiki_embedding_units",
				"wiki_schemas"
		}) {
			Boolean exists = jdbcTemplate.queryForObject(
					"SELECT to_regclass(?) IS NOT NULL",
					Boolean.class,
					"public." + table
			);
			assertThat(exists).as(table).isTrue();
		}
	}

}
