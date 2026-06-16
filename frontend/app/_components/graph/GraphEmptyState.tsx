export function GraphEmptyState({
  loading,
  errorMessage
}: {
  loading: boolean;
  errorMessage: string | null;
}) {
  return (
    <div className={`graph-empty ${errorMessage ? "is-error" : ""}`}>
      {errorMessage ?? (loading ? "그래프를 불러오는 중입니다." : "표시할 Wiki node가 없습니다.")}
    </div>
  );
}
