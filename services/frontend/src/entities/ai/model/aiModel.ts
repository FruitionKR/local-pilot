// 백엔드 GET /api/ai-models 응답의 모델 한 개.
export type AiModel = {
  provider: string;
  model: string;
  display_name: string;
};

export type AiModelsResponse = { models: AiModel[] };

/** 질의 요청에 실을 provider/model 쌍. 반드시 함께 전달해야 한다. */
export type AiModelSelection = { provider: string; model: string };

/**
 * 저장된 마지막 선택을 카탈로그와 대조해 초기 선택을 정한다.
 * 저장값이 카탈로그에 없으면(모델 교체·provider 비활성화·localStorage 조작) 첫 항목으로 되돌린다.
 */
export function resolveInitialModel(
  catalog: AiModel[],
  stored: AiModelSelection | null
): AiModel | null {
  if (catalog.length === 0) return null;
  const matched = stored
    ? catalog.find((item) => item.provider === stored.provider && item.model === stored.model)
    : undefined;
  return matched ?? catalog[0];
}
