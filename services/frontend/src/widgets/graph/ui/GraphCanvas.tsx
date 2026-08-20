import type { GraphLink, GraphNode } from "@/entities/wiki";
import { GraphEmptyState } from "./GraphEmptyState";
import { useGraphCanvas } from "../model/useGraphCanvas";
import styles from "./Graph.module.css";

export function GraphCanvas({
  nodes = [],
  links = [],
  focusedNodeId,
  onOpenNodePreview,
  loading = false,
  errorMessage = null
}: {
  nodes: GraphNode[];
  links: GraphLink[];
  focusedNodeId: string | null;
  onOpenNodePreview: (node: GraphNode) => void;
  loading?: boolean;
  errorMessage?: string | null;
}) {
  const { canvasRef, graphCanvasProps } = useGraphCanvas({ nodes, links, focusedNodeId, onOpenNodePreview });

  return (
    <div className={styles["graph-canvas"]} {...graphCanvasProps} style={{ touchAction: "none" }}>
      <canvas ref={canvasRef} className={styles["graph-surface"]} aria-label="자료 관계 그래프 캔버스" />
      {nodes.length === 0 && <GraphEmptyState loading={loading} errorMessage={errorMessage} />}
    </div>
  );
}
