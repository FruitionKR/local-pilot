import { MarkdownViewer } from "@/shared/ui/MarkdownViewer";
import type { MarkdownEditPreview as MarkdownEditPreviewData } from "../lib/markdownAgent";
import styles from "./AgentChat.module.css";

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
  onOpenAsNewDocument,
  onRegenerate
}: {
  preview: MarkdownEditPreviewData;
  validationError: string | null;
  isLoading: boolean;
  onApply: () => void;
  onCancel: () => void;
  onOpenAsNewDocument: () => void;
  onRegenerate: () => void;
}) {
  return (
    <section className={styles["markdown-edit-preview"]} aria-label="AI Markdown 편집 제안">
      <header>
        <div>
          <strong>{preview.summary}</strong>
          <details className={styles["markdown-edit-details"]}>
            <summary>
              <p>{preview.replacementMarkdown}</p>
              <span className={styles["markdown-edit-details-label"]}>변경 내용 자세히 보기</span>
            </summary>
            <div className={styles["markdown-edit-diff"]} aria-label="원문과 편집안 line diff">
              {preview.diffLines.map((line, index) => (
                <code className={styles[`is-${line.type}`]} key={`${line.type}-${index}`}>
                  <span aria-hidden="true">{DIFF_MARKERS[line.type]}</span>
                  {line.text || " "}
                </code>
              ))}
            </div>
            <div className={styles["markdown-edit-rendered-preview"]}>
              <MarkdownViewer markdown={preview.replacementMarkdown} />
            </div>
          </details>
        </div>
      </header>

      {validationError && <p className={styles["markdown-edit-error"]} role="alert">{validationError}</p>}

      <footer>
        <button type="button" disabled={isLoading} onClick={onCancel}>취소</button>
        <button type="button" disabled={isLoading} onClick={onRegenerate}>
          {isLoading ? "재시도 중" : "재시도"}
        </button>
        <button type="button" disabled={isLoading} onClick={onOpenAsNewDocument}>새 문서로 열기</button>
        <button type="button" className={styles["is-primary"]} disabled={isLoading || Boolean(validationError)} onClick={onApply}>
          수락
        </button>
      </footer>
    </section>
  );
}
