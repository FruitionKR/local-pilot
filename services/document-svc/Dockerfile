# document-svc 배포 이미지. 빌드 컨텍스트는 services/ (공유 모듈 java-shared 포함).
# 1단계: gradle로 bootJar 빌드 (테스트는 CI에서 별도 수행)
FROM gradle:8.14-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle ./
COPY java-shared java-shared
COPY document-svc document-svc
RUN gradle :document-svc:bootJar --no-daemon -x test

# 2단계: JRE 런타임만 포함한 실행 이미지
FROM eclipse-temurin:21-jre
# healthcheck용 curl
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/document-svc/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
