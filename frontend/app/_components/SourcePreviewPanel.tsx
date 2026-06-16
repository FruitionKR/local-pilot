export function SourcePreviewPanel({ title }: { title: string }) {
  return (
    <section className="source-preview-panel" aria-label="원본문서 미리보기" onClick={(event) => event.stopPropagation()}>
      <header>
        <h2>{title} - 원본문서</h2>
      </header>
      <div className="source-preview-content">
        <strong>내용 내용 내용</strong>
      </div>
    </section>
  );
}
