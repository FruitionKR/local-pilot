package fruition.access;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import fruition.shared.util.OpenApiConfig;

/**
 * 로그인·OAuth·세션·워크스페이스를 담당하는 access 앱.
 * 공유 모듈에서는 JWT(발급·검증)와 Idempotency만 스캔한다
 * (fruition.shared.util 전체를 스캔하면 document 전용 MinioConfig까지 끌려온다).
 * JPA 배선(@EntityScan·@EnableJpaRepositories)은 slice 테스트에 끌려가지 않도록 {@link JpaConfig}에 둔다.
 */
@SpringBootApplication(scanBasePackages = {
        "fruition.access",
        "fruition.shared.ai",
        "fruition.shared.security",
        "fruition.shared.idempotency"
})
@Import(OpenApiConfig.class)
public class AccessApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessApplication.class, args);
    }

}
