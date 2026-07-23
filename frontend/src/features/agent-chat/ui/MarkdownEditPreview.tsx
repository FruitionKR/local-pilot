import { MarkdownViewer } from "@/shared/ui/MarkdownViewer";
import type { MarkdownEditPreview as MarkdownEditPreviewData } from "../lib/markdownAgent";

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
          <strong>{preview.summary}</strong>
          <details className="markdown-edit-details">
            <summary>
              <p>{preview.replacementMarkdown}</p>
              <span className="markdown-edit-details-label">변경 내용 자세히 보기</span>
            </summary>
            <div className="markdown-edit-diff" aria-label="원문과 편집안 line diff">
              {preview.diffLines.map((line, index) => (
                <code className={`is-${line.type}`} key={`${line.type}-${index}`}>
                  <span aria-hidden="true">{DIFF_MARKERS[line.type]}</span>
                  {line.text || " "}
                </code>
              ))}
            </div>
            <div className="markdown-edit-rendered-preview">
              <MarkdownViewer markdown={preview.replacementMarkdown} />
            </div>
            <button type="button" className="markdown-edit-regenerate" disabled={isLoading} onClick={onRegenerate}>
              {isLoading ? "재생성 중" : "재생성"}
            </button>
          </details>
        </div>
      </header>

      {validationError && <p className="markdown-edit-error" role="alert">{validationError}</p>}

      <footer>
        <button type="button" disabled={isLoading} onClick={onCancel}>취소</button>
        <button type="button" className="is-primary" disabled={isLoading || Boolean(validationError)} onClick={onApply}>
          수락
        </button>
      </footer>
    </section>
  );
}
