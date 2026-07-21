import { MarkdownViewer } from "../MarkdownViewer";
import type { MarkdownEditPreview as MarkdownEditPreviewData } from "../../_lib/markdownAgent";

const DIFF_MARKERS = {
  context: " ",
  delete: "−",
  insert: "+"
} as const;

export function MarkdownEditPreview({
  preview,
  validationError,
  isLoading,
  onApply,
  onCancel,
  onRegenerate
}: {
  preview: MarkdownEditPreviewData;
  validationError: string | null;
  isLoading: boolean;
  onApply: () => void;
  onCancel: () => void;
  onRegenerate: () => void;
}) {
  return (
    <section className="markdown-edit-preview" aria-label="AI Markdown 편집 제안">
      <header>
        <div>
          <span>{preview.operation === "replace" ? "범위 교체" : "섹션 이어 쓰기"}</span>
          <strong>{preview.summary}</strong>
        </div>
      </header>

      <div className="markdown-edit-diff" aria-label="원문과 편집안 line diff">
        {preview.diffLines.map((line, index) => (
          <code className={`is-${line.type}`} key={`${line.type}-${index}`}>
            <span aria-hidden="true">{DIFF_MARKERS[line.type]}</span>
            {line.text || " "}
          </code>
        ))}
      </div>

      <details className="markdown-edit-rendered-preview">
        <summary>렌더링 미리보기</summary>
        <MarkdownViewer markdown={preview.replacementMarkdown} />
      </details>

      {validationError && <p className="markdown-edit-error" role="alert">{validationError}</p>}

      <footer>
        <button type="button" disabled={isLoading} onClick={onCancel}>취소</button>
        <button type="button" disabled={isLoading} onClick={onRegenerate}>
          {isLoading ? "재생성 중" : "재생성"}
        </button>
        <button type="button" className="is-primary" disabled={isLoading || Boolean(validationError)} onClick={onApply}>
          적용
        </button>
      </footer>
    </section>
  );
}
