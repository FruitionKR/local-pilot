# Infra 변경 이력

## 2026-08-10

- backend에서 노출할 AI provider를 제한하는 `AI_ENABLED_PROVIDERS` 설정을 로컬 배포 예시와 Kubernetes ConfigMap에 추가했다.
- 기본 활성 provider는 `openai`다.
