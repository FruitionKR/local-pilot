import assert from "node:assert/strict";
import test from "node:test";
import {
  DEFAULT_USER_PREFERENCES,
  normalizeUserPreferences,
  resolveEditorMode
} from "../src/entities/user/model/preferences.ts";

test("저장 값이 없으면 안전한 개인 설정 기본값을 사용한다", () => {
  const preferences = normalizeUserPreferences(null);

  assert.deepEqual(preferences, DEFAULT_USER_PREFERENCES);
  assert.equal(preferences.motion, "system");
  assert.equal(preferences.editor.markdown.lineWrapping, true);
  assert.equal(preferences.graph.visibleKinds.raw, false);
  assert.equal(preferences.notifications.failed, true);
});

test("구버전 일부 설정은 누락 필드를 기본값으로 보정한다", () => {
  const preferences = normalizeUserPreferences({
    motion: "reduced",
    editor: {
      defaultMode: "markdown",
      markdown: { lineNumbers: true }
    }
  });

  assert.equal(preferences.motion, "reduced");
  assert.equal(preferences.editor.defaultMode, "markdown");
  assert.equal(preferences.editor.markdown.lineNumbers, true);
  assert.equal(preferences.editor.markdown.lineWrapping, true);
  assert.equal(preferences.documentFont, "system-sans");
});

test("Graph 노드 표시를 모두 끈 저장 값은 Concept를 복원한다", () => {
  const preferences = normalizeUserPreferences({
    graph: {
      visibleKinds: { raw: false, source: false, concept: false }
    }
  });

  assert.deepEqual(preferences.graph.visibleKinds, {
    raw: false,
    source: false,
    concept: true
  });
});

test("마지막으로 고른 AI 모델은 provider/model 쌍으로 복원한다", () => {
  const preferences = normalizeUserPreferences({
    aiModel: { provider: "gemini", model: "gemini-3.1-flash-lite" }
  });

  assert.deepEqual(preferences.aiModel, { provider: "gemini", model: "gemini-3.1-flash-lite" });
});

test("오염된 AI 모델 저장 값은 null로 정규화한다", () => {
  assert.equal(normalizeUserPreferences({ aiModel: { provider: 123, model: "gpt-5-nano" } }).aiModel, null);
  assert.equal(normalizeUserPreferences({ aiModel: { provider: "openai" } }).aiModel, null);
  assert.equal(normalizeUserPreferences({ aiModel: { provider: "", model: "" } }).aiModel, null);
  assert.equal(normalizeUserPreferences({ aiModel: "openai" }).aiModel, null);
  assert.equal(normalizeUserPreferences({ aiModel: {} }).aiModel, null);
});

test("마지막 사용 모드는 저장된 실제 편집 모드로 해석한다", () => {
  const preferences = normalizeUserPreferences({
    editor: {
      defaultMode: "last",
      lastMode: "markdown"
    }
  });

  assert.equal(resolveEditorMode(preferences), "markdown");
});
