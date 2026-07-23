import { MarkdownViewer } from "@/shared/ui/MarkdownViewer";
import type { GeneratedMarkdownDraft } from "../lib/markdownAgent";
import styles from "./AgentChat.module.css";

export function MarkdownCreatePreview({
  draft,
  isSubmitting,
  errorMessage,
  onCancel,
  onRegenerate,
  onCreate
}: {
  draft: GeneratedMarkdownDraft;
  isSubmitting: boolean;
  errorMessage: string | null;
  onCancel: () => void;
  onRegenerate: () => void;
  onCreate: () => void;
}) {
  return (
    <section className={styles["markdown-create-preview"]} aria-label="AI Markdown 문서 초안">
      <header>
        <span>새 문서 초안</span>
        <strong>{draft.title}</strong>
        <p>{draft.summary}</p>
      </header>
      <div className={styles["markdown-create-rendered-preview"]}>
        <MarkdownViewer markdown={draft.markdown} />
      </div>
      {errorMessage && <p className={styles["markdown-edit-error"]} role="alert">{errorMessage}</p>}
      <footer>
        <button type="button" disabled={isSubmitting} onClick={onCancel}>취소</button>
        <button type="button" disabled={isSubmitting} onClick={onRegenerate}>재생성</button>
        <button type="button" className={styles["is-primary"]} disabled={isSubmitting} onClick={onCreate}>
          {isSubmitting ? "문서 생성 중" : "새 문서로 열기"}
        </button>
      </footer>
    </section>
  );
}
