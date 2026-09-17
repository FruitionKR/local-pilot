package fruition.access;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import fruition.shared.util.OpenApiConfig;

/**
 * 로그인·OAuth·세션·워크스페이스를 담당하는 access 앱.
 * 공유 모듈에서는 JWT(발급·검증)와 Idempotency, 요청 로깅만 스캔한다
 * (fruition.shared.util 전체를 스캔하면 document 전용 MinioConfig까지 끌려온다).
 * JPA 배선(@EntityScan·@EnableJpaRepositories)은 slice 테스트에 끌려가지 않도록 {@link JpaConfig}에 둔다.
 */
@SpringBootApplication(scanBasePackages = {
        "fruition.access",
        "fruition.shared.ai",
        "fruition.shared.logging",
        "fruition.shared.security",
        "fruition.shared.idempotency"
})
@Import(OpenApiConfig.class)
public class AccessApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--migrate-only")) {
            Flyway.configure()
                    .dataSource("jdbc:postgresql://" + requiredEnv("POSTGRES_HOST") + ":"
                                    + requiredEnv("POSTGRES_PORT") + "/" + requiredEnv("ACCESS_DB_NAME")
                                    + "?sslmode=" + System.getenv().getOrDefault("PGSSLMODE", "prefer"),
                            requiredEnv("ACCESS_DB_MIGRATION_USER"), requiredEnv("ACCESS_DB_MIGRATION_PASSWORD"))
                    .locations("classpath:db/migration")
                    .load().migrate();
            return;
        }
        SpringApplication.run(AccessApplication.class, args);
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("필수 migration 설정 누락: " + key);
        }
        return value;
    }

}
