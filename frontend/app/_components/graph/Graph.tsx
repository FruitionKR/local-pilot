import type { GraphLink, GraphNode } from "../../_lib/types";
import { GraphCanvas } from "./GraphCanvas";
import { GraphFilterChips } from "./GraphFilterChips";

export function Graph({
  nodes,
  links,
  rawDocumentCount,
  focusedNodeId,
  onOpenNodePreview,
  onRestoreAgentPanel,
  loading = false,
  errorMessage = null
}: {
  nodes: GraphNode[];
  links: GraphLink[];
  rawDocumentCount: number;
  focusedNodeId: string | null;
  onOpenNodePreview: (node: GraphNode) => void;
  onRestoreAgentPanel?: () => void;
  loading?: boolean;
  errorMessage?: string | null;
}) {
  const sourceNodeCount = nodes.filter((node) => node.kind === "source").length;
  const conceptNodeCount = nodes.filter((node) => !node.kind || node.kind === "concept").length;

  return (
    <section className="graph-stage" aria-label="자료 관계 그래프">
      <GraphFilterChips
        rawDocumentCount={rawDocumentCount}
        sourceNodeCount={sourceNodeCount}
        conceptNodeCount={conceptNodeCount}
        onRestoreAgentPanel={onRestoreAgentPanel}
      />
      <GraphCanvas
        nodes={nodes}
        links={links}
        focusedNodeId={focusedNodeId}
        onOpenNodePreview={onOpenNodePreview}
        loading={loading}
        errorMessage={errorMessage}
      />
    </section>
  );
}
