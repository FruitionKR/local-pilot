import type { GraphLink, GraphNode } from "../../_lib/types";
import { GraphEmptyState } from "./GraphEmptyState";
import { useGraphCanvas } from "./useGraphCanvas";

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
    <div className="graph-canvas" {...graphCanvasProps} style={{ touchAction: "none" }}>
      <canvas ref={canvasRef} className="graph-surface" aria-label="자료 관계 그래프 캔버스" />
      {nodes.length === 0 && <GraphEmptyState loading={loading} errorMessage={errorMessage} />}
    </div>
  );
}
