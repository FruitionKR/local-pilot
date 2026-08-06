package fruition.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 문서·채팅·Wiki·query를 담당하는 document 앱. 공유 모듈(fruition.shared)을 함께 스캔한다.
 * JPA 배선(@EntityScan·@EnableJpaRepositories)은 slice 테스트에 끌려가지 않도록 {@link fruition.core.config.JpaConfig}에 둔다.
 */
@SpringBootApplication(scanBasePackages = {"fruition.core", "fruition.shared"})
@EnableScheduling
public class DocumentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentApplication.class, args);
    }

}
