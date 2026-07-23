import { cx } from "@/shared/lib/classNames";
import styles from "./Graph.module.css";

export function GraphEmptyState({
  loading,
  errorMessage
}: {
  loading: boolean;
  errorMessage: string | null;
}) {
  return (
    <div className={cx(styles["graph-empty"], errorMessage && styles["is-error"])}>
      {errorMessage ?? (loading ? "그래프를 불러오는 중입니다." : "표시할 Wiki node가 없습니다.")}
    </div>
  );
}
