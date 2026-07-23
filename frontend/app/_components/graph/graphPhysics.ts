import { forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY } from "d3-force";
import type { Simulation, SimulationLinkDatum } from "d3-force";
import type { GraphLink, GraphNode, NodePosition, NodePositionMap } from "../../_lib/types";
import { GRAPH_CENTER, GRAPH_HEIGHT, GRAPH_PHYSICS, GRAPH_WIDTH } from "@/entities/graph/lib/graph";
import { clampGraphPosition } from "./graphGeometry";

export const FIXED_NODE_SIZE = 14;

/** d3-force simulation 노드. d3가 x/y/vx/vy/fx/fy를 직접 갱신한다. */
export type GraphSimNode = {
  id: string;
  kind?: GraphNode["kind"];
  x: number;
  y: number;
  vx?: number;
  vy?: number;
  fx?: number | null;
  fy?: number | null;
};

type GraphSimLink = SimulationLinkDatum<GraphSimNode>;

export type GraphSimulation = {
  simulation: Simulation<GraphSimNode, GraphSimLink>;
  nodeById: Map<string, GraphSimNode>;
};

/** d3-force 힘 세기 튜닝 값 */
const SIM_FORCES = {
  linkStrength: 0.28,
  /** 노드 종류별 반발력(charge) 세기 */
  charge: {
    source: -170,
    concept: -60,
    raw: -40
  },
  chargeDistanceMax: 260,
  /** 노드 종류별 최소 간격 반경 (collide) */
  collideRadius: {
    source: 44,
    concept: 20,
    raw: 16
  },
  collideStrength: 0.85,
  centerStrength: 0.006,
  /** 초기 배치 위치로 되돌리는 힘 (클러스터 구조 유지) */
  originStrength: 0.045,
  alphaMin: 0.02,
  /** 드래그 중 유지하는 최소 alpha */
  dragAlpha: 0.3
};

export function createGraphSimulation({
  nodes,
  links,
  startPositions,
  originPositions
}: {
  nodes: GraphNode[];
  links: GraphLink[];
  startPositions: NodePositionMap;
  originPositions: NodePositionMap;
}): GraphSimulation {
  const simNodes: GraphSimNode[] = nodes.map((node) => ({
    id: node.id,
    kind: node.kind,
    x: startPositions[node.id]?.x ?? GRAPH_CENTER.x,
    y: startPositions[node.id]?.y ?? GRAPH_CENTER.y
  }));
  const nodeById = new Map(simNodes.map((node) => [node.id, node]));
  const simLinks: GraphSimLink[] = links
    .filter((link) => nodeById.has(link.from) && nodeById.has(link.to))
    .map((link) => ({ source: link.from, target: link.to }));

  const simulation = forceSimulation(simNodes)
    .force(
      "link",
      forceLink<GraphSimNode, GraphSimLink>(simLinks)
        .id((node) => node.id)
        .distance((link) => idealLinkDistanceValue(link))
        .strength(SIM_FORCES.linkStrength)
    )
    .force(
      "charge",
      forceManyBody<GraphSimNode>()
        .strength((node) => SIM_FORCES.charge[simNodeKind(node)])
        .distanceMax(SIM_FORCES.chargeDistanceMax)
    )
    .force(
      "collide",
      forceCollide<GraphSimNode>((node) => SIM_FORCES.collideRadius[simNodeKind(node)])
        .strength(SIM_FORCES.collideStrength)
    )
    .force("centerX", forceX<GraphSimNode>(GRAPH_CENTER.x).strength(SIM_FORCES.centerStrength))
    .force("centerY", forceY<GraphSimNode>(GRAPH_CENTER.y).strength(SIM_FORCES.centerStrength))
    .force(
      "originX",
      forceX<GraphSimNode>((node) => originPositions[node.id]?.x ?? GRAPH_CENTER.x).strength(SIM_FORCES.originStrength)
    )
    .force(
      "originY",
      forceY<GraphSimNode>((node) => originPositions[node.id]?.y ?? GRAPH_CENTER.y).strength(SIM_FORCES.originStrength)
    )
    .alphaMin(SIM_FORCES.alphaMin)
    .stop(); // 내부 타이머 대신 RAF 루프에서 수동 tick

  return { simulation, nodeById };
}

/**
 * simulation을 한 tick 진행하고 노드를 그래프 영역 안으로 clamp한다.
 * 이미 settle됐으면(alpha < alphaMin) false를 반환한다.
 */
export function tickGraphSimulation(graphSimulation: GraphSimulation, isDragging: boolean): boolean {
  const { simulation } = graphSimulation;
  if (isDragging) simulation.alpha(Math.max(simulation.alpha(), SIM_FORCES.dragAlpha));
  if (simulation.alpha() < simulation.alphaMin()) return false;

  simulation.tick();

  for (const node of simulation.nodes()) {
    const clamped = clampSimPosition(node);
    node.x = clamped.x;
    node.y = clamped.y;
  }

  return true;
}

/** 드래그 종료 등으로 simulation을 다시 데운다. */
export function reheatGraphSimulation(graphSimulation: GraphSimulation) {
  const { simulation } = graphSimulation;
  simulation.alpha(Math.max(simulation.alpha(), SIM_FORCES.dragAlpha));
}

/** simulation 노드 좌표를 캐시 저장용 위치 맵으로 변환한다. */
export function simulationPositions(graphSimulation: GraphSimulation): NodePositionMap {
  return Object.fromEntries(
    graphSimulation.simulation.nodes().map((node) => [node.id, { x: node.x, y: node.y }])
  );
}

export function clampSimPosition(node: GraphSimNode): NodePosition {
  return clampGraphPosition({
    position: { x: node.x, y: node.y },
    node: { id: node.id, label: "", kind: node.kind },
    nodeSize: FIXED_NODE_SIZE
  });
}

export function createSourceCenteredNodePositions(nodes: GraphNode[], links: GraphLink[]) {
  const sourceNodes = nodes.filter((node) => node.kind === "source");
  if (sourceNodes.length === 0) return createFallbackCirclePositions(nodes);

  const sourcePositions = buildSourcePositions(sourceNodes);
  const sourceIds = new Set(sourceNodes.map((node) => node.id));
  const groupedNodes = new Map(sourceNodes.map((source) => [source.id, [] as GraphNode[]]));

  nodes
    .filter((node) => node.kind !== "source")
    .forEach((node) => {
      const sourceId = nearestSourceId(node.id, sourceIds, groupedNodes, links) ?? sourceNodes[0].id;
      groupedNodes.get(sourceId)?.push(node);
    });

  const positions: NodePositionMap = { ...sourcePositions };

  sourceNodes.forEach((source, sourceIndex) => {
    const center = sourcePositions[source.id] ?? GRAPH_CENTER;
    const grouped = groupedNodes.get(source.id) ?? [];
    const rawNodes = grouped.filter((node) => node.kind === "raw");
    const relatedNodes = grouped.filter((node) => node.kind !== "raw");
    const baseAngle = -Math.PI / 2 + (sourceIndex * Math.PI) / Math.max(2, sourceNodes.length);

    rawNodes.forEach((node, index) => {
      positions[node.id] = circlePosition(center, 52, index, rawNodes.length, baseAngle + Math.PI / 6);
    });

    relatedNodes.forEach((node, index) => {
      positions[node.id] = circlePosition(center, 126, index, relatedNodes.length, baseAngle);
    });
  });

  return Object.fromEntries(nodes.map((node) => [node.id, positions[node.id] ?? GRAPH_CENTER]));
}

function buildSourcePositions(sourceNodes: GraphNode[]) {
  if (sourceNodes.length === 1) return { [sourceNodes[0].id]: GRAPH_CENTER };

  const radius = Math.min(150, Math.max(76, sourceNodes.length * 18));
  return Object.fromEntries(
    sourceNodes.map((source, index) => [
      source.id,
      circlePosition(GRAPH_CENTER, radius, index, sourceNodes.length, -Math.PI / 2)
    ])
  );
}

function nearestSourceId(
  nodeId: string,
  sourceIds: Set<string>,
  groupedNodes: Map<string, GraphNode[]>,
  links: GraphLink[]
) {
  const candidates = Array.from(new Set(
    links
      .map((link) => {
        if (link.from === nodeId && sourceIds.has(link.to)) return link.to;
        if (link.to === nodeId && sourceIds.has(link.from)) return link.from;
        return null;
      })
      .filter((sourceId): sourceId is string => Boolean(sourceId))
  ));

  if (candidates.length === 0) return null;
  return candidates.sort((sourceA, sourceB) =>
    (groupedNodes.get(sourceA)?.length ?? 0) - (groupedNodes.get(sourceB)?.length ?? 0)
  )[0];
}

function createFallbackCirclePositions(nodes: GraphNode[]) {
  if (nodes.length === 1) return { [nodes[0].id]: GRAPH_CENTER };
  return Object.fromEntries(
    nodes.map((node, index) => [
      node.id,
      circlePosition(GRAPH_CENTER, 126, index, nodes.length, -Math.PI / 2)
    ])
  );
}

function circlePosition(center: NodePosition, radius: number, index: number, total: number, offset = 0) {
  const angle = offset + (Math.PI * 2 * index) / Math.max(1, total);
  return {
    x: Math.min(GRAPH_WIDTH, Math.max(0, center.x + Math.cos(angle) * radius)),
    y: Math.min(GRAPH_HEIGHT, Math.max(0, center.y + Math.sin(angle) * radius))
  };
}

export function getGraphDistances(links: GraphLink[], sourceId: string) {
  const distances: Record<string, number> = { [sourceId]: 0 };
  const queue = [sourceId];

  for (let index = 0; index < queue.length; index += 1) {
    const current = queue[index];
    const nextDistance = distances[current] + 1;

    links.forEach((link) => {
      const neighbor = link.from === current ? link.to : link.to === current ? link.from : null;
      if (!neighbor || distances[neighbor] !== undefined) return;
      distances[neighbor] = nextDistance;
      queue.push(neighbor);
    });
  }

  return distances;
}

function simNodeKind(node: GraphSimNode): "source" | "concept" | "raw" {
  return node.kind ?? "concept";
}

function idealLinkDistanceValue(link: GraphSimLink) {
  const from = link.source as GraphSimNode;
  const to = link.target as GraphSimNode;
  if (typeof from !== "object" || typeof to !== "object") {
    return GRAPH_PHYSICS.linkDistance.fallback * GRAPH_PHYSICS.linkDistanceMultiplier;
  }

  const kinds = [simNodeKind(from), simNodeKind(to)];
  let distance = GRAPH_PHYSICS.linkDistance.concept;
  if (kinds.every((kind) => kind === "source")) distance = GRAPH_PHYSICS.linkDistance.source;
  else if (kinds.includes("raw")) distance = GRAPH_PHYSICS.linkDistance.raw;
  else if (kinds.includes("source") && kinds.includes("concept")) distance = GRAPH_PHYSICS.linkDistance.sourceConcept;
  return distance * GRAPH_PHYSICS.linkDistanceMultiplier;
}
