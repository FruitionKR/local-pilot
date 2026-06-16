import type { PointerEvent as ReactPointerEvent } from "react";

export function SourcePreviewPanel({
  title,
  width,
  onResizeStart
}: {
  title: string;
  width: number;
  onResizeStart: (event: ReactPointerEvent<HTMLButtonElement>) => void;
}) {
  return (
    <section
      className="source-preview-panel"
      style={{ width }}
      aria-label="원본문서 미리보기"
      onClick={(event) => event.stopPropagation()}
    >
      <header>
        <h2>{title} - 원본문서</h2>
      </header>
      <div className="source-preview-content">
        <strong>내용 내용 내용</strong>
      </div>
      <button
        type="button"
        className="source-preview-resize-handle"
        aria-label="원본문서 패널 폭 조절"
        onPointerDown={onResizeStart}
      />
    </section>
  );
}
