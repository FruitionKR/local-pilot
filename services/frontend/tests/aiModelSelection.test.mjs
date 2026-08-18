import assert from "node:assert/strict";
import test from "node:test";
import { resolveInitialModel, resolveProviderModel } from "../src/entities/ai/model/aiModel.ts";

const CATALOG = [
  { provider: "openai", model: "gpt-5-nano", display_name: "GPT-5 nano" },
  { provider: "gemini", model: "gemini-3.1-flash-lite", display_name: "Gemini 3.1 Flash-Lite" },
  { provider: "claude", model: "claude-sonnet-5", display_name: "Claude Sonnet 5" }
];

test("저장된 선택이 카탈로그에 있으면 그대로 사용한다", () => {
  const selected = resolveInitialModel(CATALOG, { provider: "claude", model: "claude-sonnet-5" });

  assert.deepEqual(selected, CATALOG[2]);
});

test("저장된 선택이 카탈로그에 없으면 카탈로그 첫 항목으로 대체한다", () => {
  const selected = resolveInitialModel(CATALOG, { provider: "gemini", model: "gemini-flash-latest" });

  assert.deepEqual(selected, CATALOG[0]);
});

test("저장된 선택이 없으면 카탈로그 첫 항목을 쓴다", () => {
  assert.deepEqual(resolveInitialModel(CATALOG, null), CATALOG[0]);
});

test("카탈로그가 비면 선택을 만들지 않는다", () => {
  assert.equal(resolveInitialModel([], { provider: "openai", model: "gpt-5-nano" }), null);
});

test("Provider 변경 시 해당 회사의 첫 활성 모델을 선택한다", () => {
  assert.deepEqual(
    resolveProviderModel(CATALOG, "gemini", { provider: "openai", model: "gpt-5-nano" }),
    CATALOG[1]
  );
});

test("활성 모델이 없는 Provider는 선택하지 않는다", () => {
  assert.equal(resolveProviderModel(CATALOG, "unknown", null), null);
});
