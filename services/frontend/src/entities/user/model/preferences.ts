export type MotionPreference = "system" | "reduced" | "full";
export type DocumentFontPreference = "system-sans" | "readable-sans" | "serif";
export type EditorDefaultMode = "last" | "wysiwyg" | "markdown";
export type EditorMode = Exclude<EditorDefaultMode, "last">;

export type UserPreferences = {
  motion: MotionPreference;
  documentFont: DocumentFontPreference;
  // 채팅 composer에서 마지막으로 고른 AI 모델. 카탈로그와 대조 후에만 사용한다.
  aiModel: { provider: string; model: string } | null;
  editor: {
    defaultMode: EditorDefaultMode;
    lastMode: EditorMode;
    markdown: {
      lineWrapping: boolean;
      lineNumbers: boolean;
      highlightActiveLine: boolean;
    };
  };
  graph: {
    visibleKinds: {
      raw: boolean;
      source: boolean;
      concept: boolean;
    };
  };
  notifications: {
    completed: boolean;
    failed: boolean;
    lint: boolean;
    restore: boolean;
    query: boolean;
    browser: boolean;
  };
};

export const DEFAULT_USER_PREFERENCES: UserPreferences = {
  motion: "system",
  documentFont: "system-sans",
  aiModel: null,
  editor: {
    defaultMode: "last",
    lastMode: "wysiwyg",
    markdown: {
      lineWrapping: true,
      lineNumbers: false,
      highlightActiveLine: true
    }
  },
  graph: {
    visibleKinds: {
      raw: false,
      source: true,
      concept: true
    }
  },
  notifications: {
    completed: true,
    failed: true,
    lint: true,
    restore: true,
    query: false,
    browser: false
  }
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function booleanValue(value: unknown, fallback: boolean) {
  return typeof value === "boolean" ? value : fallback;
}

function enumValue<T extends string>(value: unknown, values: readonly T[], fallback: T): T {
  return typeof value === "string" && values.includes(value as T) ? value as T : fallback;
}

/** provider/model이 모두 비어있지 않은 문자열일 때만 통과시킨다. 그 외에는 null. */
function aiModelValue(value: unknown): UserPreferences["aiModel"] {
  if (!isRecord(value)) return null;
  const { provider, model } = value;
  if (typeof provider !== "string" || provider.length === 0) return null;
  if (typeof model !== "string" || model.length === 0) return null;
  return { provider, model };
}

export function normalizeUserPreferences(value: unknown): UserPreferences {
  if (!isRecord(value)) return DEFAULT_USER_PREFERENCES;

  const editor = isRecord(value.editor) ? value.editor : {};
  const markdown = isRecord(editor.markdown) ? editor.markdown : {};
  const graph = isRecord(value.graph) ? value.graph : {};
  const visibleKinds = isRecord(graph.visibleKinds) ? graph.visibleKinds : {};
  const notifications = isRecord(value.notifications) ? value.notifications : {};

  const normalizedVisibleKinds = {
    raw: booleanValue(visibleKinds.raw, DEFAULT_USER_PREFERENCES.graph.visibleKinds.raw),
    source: booleanValue(visibleKinds.source, DEFAULT_USER_PREFERENCES.graph.visibleKinds.source),
    concept: booleanValue(visibleKinds.concept, DEFAULT_USER_PREFERENCES.graph.visibleKinds.concept)
  };

  if (!Object.values(normalizedVisibleKinds).some(Boolean)) {
    normalizedVisibleKinds.concept = true;
  }

  return {
    motion: enumValue(value.motion, ["system", "reduced", "full"], DEFAULT_USER_PREFERENCES.motion),
    documentFont: enumValue(
      value.documentFont,
      ["system-sans", "readable-sans", "serif"],
      DEFAULT_USER_PREFERENCES.documentFont
    ),
    aiModel: aiModelValue(value.aiModel),
    editor: {
      defaultMode: enumValue(
        editor.defaultMode,
        ["last", "wysiwyg", "markdown"],
        DEFAULT_USER_PREFERENCES.editor.defaultMode
      ),
      lastMode: enumValue(
        editor.lastMode,
        ["wysiwyg", "markdown"],
        DEFAULT_USER_PREFERENCES.editor.lastMode
      ),
      markdown: {
        lineWrapping: booleanValue(
          markdown.lineWrapping,
          DEFAULT_USER_PREFERENCES.editor.markdown.lineWrapping
        ),
        lineNumbers: booleanValue(
          markdown.lineNumbers,
          DEFAULT_USER_PREFERENCES.editor.markdown.lineNumbers
        ),
        highlightActiveLine: booleanValue(
          markdown.highlightActiveLine,
          DEFAULT_USER_PREFERENCES.editor.markdown.highlightActiveLine
        )
      }
    },
    graph: { visibleKinds: normalizedVisibleKinds },
    notifications: {
      completed: booleanValue(
        notifications.completed,
        DEFAULT_USER_PREFERENCES.notifications.completed
      ),
      failed: booleanValue(
        notifications.failed,
        DEFAULT_USER_PREFERENCES.notifications.failed
      ),
      lint: booleanValue(
        notifications.lint,
        DEFAULT_USER_PREFERENCES.notifications.lint
      ),
      restore: booleanValue(
        notifications.restore,
        DEFAULT_USER_PREFERENCES.notifications.restore
      ),
      query: booleanValue(
        notifications.query,
        DEFAULT_USER_PREFERENCES.notifications.query
      ),
      browser: booleanValue(
        notifications.browser,
        DEFAULT_USER_PREFERENCES.notifications.browser
      )
    }
  };
}

export function resolveEditorMode(preferences: UserPreferences): EditorMode {
  return preferences.editor.defaultMode === "last"
    ? preferences.editor.lastMode
    : preferences.editor.defaultMode;
}
