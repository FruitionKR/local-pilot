import { useMemo } from "react";
import { useUserPreferences } from "@/entities/user";
import type { GraphFilterKind, GraphLink, GraphNode } from "@/entities/wiki";
import { GraphCanvas } from "./GraphCanvas";
import { GraphFilterChips } from "./GraphFilterChips";
import styles from "./Graph.module.css";

function nodeFilterKind(node: GraphNode): GraphFilterKind {
  return node.kind === "source" ? "source" : node.kind === "raw" ? "raw" : "concept";
}

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
  const { preferences, updatePreferences } = useUserPreferences();
  const sourceNodeCount = nodes.filter((node) => node.kind === "source").length;
  const conceptNodeCount = nodes.filter((node) => !node.kind || node.kind === "concept").length;

  const visibleKinds = preferences.graph.visibleKinds;
  const toggleKind = (kind: GraphFilterKind) => {
    updatePreferences((current) => {
      const nextVisibleKinds = {
        ...current.graph.visibleKinds,
        [kind]: !current.graph.visibleKinds[kind]
      };
      if (!Object.values(nextVisibleKinds).some(Boolean)) return current;
      return {
        ...current,
        graph: { ...current.graph, visibleKinds: nextVisibleKinds }
      };
    });
  };

  // 필터에서 끈 종류의 노드와, 그 노드에 걸린 링크를 렌더 대상에서 제외한다.
  const { visibleNodes, visibleLinks } = useMemo(() => {
    const filteredNodes = nodes.filter((node) => visibleKinds[nodeFilterKind(node)]);
    const visibleIds = new Set(filteredNodes.map((node) => node.id));
    const filteredLinks = links.filter((link) => visibleIds.has(link.from) && visibleIds.has(link.to));
    return { visibleNodes: filteredNodes, visibleLinks: filteredLinks };
  }, [nodes, links, visibleKinds]);

  return (
    <section className={styles["graph-stage"]} aria-label="자료 관계 그래프">
      <GraphFilterChips
        rawDocumentCount={rawDocumentCount}
        sourceNodeCount={sourceNodeCount}
        conceptNodeCount={conceptNodeCount}
        visibleKinds={visibleKinds}
        onToggleKind={toggleKind}
        onRestoreAgentPanel={onRestoreAgentPanel}
      />
      <GraphCanvas
        nodes={visibleNodes}
        links={visibleLinks}
        focusedNodeId={focusedNodeId}
        onOpenNodePreview={onOpenNodePreview}
        loading={loading}
        errorMessage={errorMessage}
      />
    </section>
  );
}
