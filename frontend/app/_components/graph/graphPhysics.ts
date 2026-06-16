import type { GraphLink, GraphNode, NodePosition, NodePositionMap } from "../../_lib/types";
import { GRAPH_CENTER, GRAPH_HEIGHT, GRAPH_PHYSICS, GRAPH_WIDTH, graphNodeKind, randomBetween } from "../../_lib/graph";

export type GraphLinkForce = GraphLink & {
  idealDistance: number;
  weight: number;
};

export type GraphPairForce = {
  nodeA: GraphNode;
  nodeB: GraphNode;
  linked: boolean;
  desiredDistance: number;
  minDistance: number;
};

export function buildNodeSizes(nodes: GraphNode[], nodeDegrees: Record<string, number>) {
  return nodes.reduce<Record<string, number>>((sizes, node) => {
    const degree = nodeDegrees[node.id] ?? 0;
    if (node.kind === "raw") sizes[node.id] = Math.min(26, 16 + degree * 4);
    else if (node.kind === "source") sizes[node.id] = Math.min(44, 22 + degree * 3.2);
    else if (node.kind === "progress") sizes[node.id] = Math.min(38, 22 + degree * 4);
    else sizes[node.id] = Math.min(32, 15 + degree * 3.5);
    return sizes;
  }, {});
}

export function buildNodeDegrees(nodes: GraphNode[], links: GraphLink[]) {
  return nodes.reduce<Record<string, number>>((degrees, node) => {
    degrees[node.id] = links.filter((link) => link.from === node.id || link.to === node.id).length;
    return degrees;
  }, {});
}

export function buildLinkForces({
  links,
  nodes,
  nodeDegrees
}: {
  links: GraphLink[];
  nodes: GraphNode[];
  nodeDegrees: Record<string, number>;
}) {
  return links.map((link) => ({
    ...link,
    idealDistance: idealLinkDistanceValue(link, nodes),
    weight: 1 + Math.min(0.85, ((nodeDegrees[link.from] ?? 0) + (nodeDegrees[link.to] ?? 0)) * 0.035)
  }));
}

export function buildPairForces({
  nodes,
  linkedNodePairs,
  nodeSize,
  linkKey
}: {
  nodes: GraphNode[];
  linkedNodePairs: Set<string>;
  nodeSize: (node: GraphNode) => number;
  linkKey: (nodeAId: string, nodeBId: string) => string;
}) {
  return nodes.flatMap((nodeA, index) =>
    nodes.slice(index + 1).map((nodeB) => {
      const linked = linkedNodePairs.has(linkKey(nodeA.id, nodeB.id));
      return {
        nodeA,
        nodeB,
        linked,
        desiredDistance: linked ? 0 : pairDistanceValue(nodeA, nodeB, nodeSize),
        minDistance: physicsNodeRadius(nodeA, nodeSize) + physicsNodeRadius(nodeB, nodeSize)
      };
    })
  );
}

export function createRandomNodePositions(nodes: GraphNode[]) {
  const outerRadiusX = GRAPH_WIDTH * 0.32;
  const outerRadiusY = GRAPH_HEIGHT * 0.29;
  const innerRadiusX = GRAPH_WIDTH * 0.16;
  const innerRadiusY = GRAPH_HEIGHT * 0.14;
  const primaryNodeId = nodes.find((node) => node.kind === "source")?.id ?? nodes[0]?.id;

  return Object.fromEntries(
    nodes.map((node) => {
      if (node.id === primaryNodeId) return [node.id, GRAPH_CENTER];

      const isPrimaryNode = node.kind === "source" || node.kind === "progress";
      const angle = randomBetween(0, Math.PI * 2);
      const ringIndex = isPrimaryNode || Math.random() > 0.58 ? 0 : 1;
      const radiusX = ringIndex === 0 ? outerRadiusX : innerRadiusX;
      const radiusY = ringIndex === 0 ? outerRadiusY : innerRadiusY;
      const jitterX = randomBetween(-38, 38);
      const jitterY = randomBetween(-30, 30);

      return [node.id, {
        x: GRAPH_CENTER.x + Math.cos(angle) * radiusX + jitterX,
        y: GRAPH_CENTER.y + Math.sin(angle) * radiusY + jitterY
      }];
    })
  );
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

export function resolveGraphCollisions({
  nodes,
  positions,
  initialNodePositions,
  pairForces,
  anchorId,
  clampPosition
}: {
  nodes: GraphNode[];
  positions: NodePositionMap;
  initialNodePositions: NodePositionMap;
  pairForces: GraphPairForce[];
  anchorId: string | null;
  clampPosition: (position: NodePosition, nodeId?: string) => NodePosition;
}) {
  const next: NodePositionMap = Object.fromEntries(
    nodes.map((node) => [node.id, positions[node.id] ?? initialNodePositions[node.id]])
  );

  for (let iteration = 0; iteration < 4; iteration += 1) {
    for (const pair of pairForces) {
      const { nodeA, nodeB, minDistance } = pair;
      const posA = next[nodeA.id];
      const posB = next[nodeB.id];
      const dx = posB.x - posA.x;
      const dy = posB.y - posA.y;
      const distance = Math.hypot(dx, dy) || 0.01;
      const overlap = minDistance - distance;

      if (overlap <= 0) continue;

      const pushX = (dx / distance) * overlap;
      const pushY = (dy / distance) * overlap;

      if (anchorId && nodeA.id === anchorId) {
        next[nodeB.id] = clampPosition({ x: posB.x + pushX, y: posB.y + pushY }, nodeB.id);
      } else if (anchorId && nodeB.id === anchorId) {
        next[nodeA.id] = clampPosition({ x: posA.x - pushX, y: posA.y - pushY }, nodeA.id);
      } else {
        next[nodeA.id] = clampPosition({ x: posA.x - pushX / 2, y: posA.y - pushY / 2 }, nodeA.id);
        next[nodeB.id] = clampPosition({ x: posB.x + pushX / 2, y: posB.y + pushY / 2 }, nodeB.id);
      }
    }
  }

  return next;
}

export function tickGraphPositions({
  nodes,
  positions,
  initialNodePositions,
  linkForces,
  pairForces,
  anchorId,
  isRevealingGraph,
  clampPosition
}: {
  nodes: GraphNode[];
  positions: NodePositionMap;
  initialNodePositions: NodePositionMap;
  linkForces: GraphLinkForce[];
  pairForces: GraphPairForce[];
  anchorId: string | null;
  isRevealingGraph: boolean;
  clampPosition: (position: NodePosition, nodeId?: string) => NodePosition;
}) {
  const next: NodePositionMap = Object.fromEntries(
    nodes.map((node) => [node.id, positions[node.id] ?? initialNodePositions[node.id]])
  );
  const deltas: Record<string, NodePosition> = Object.fromEntries(
    nodes.map((node) => [node.id, { x: 0, y: 0 }])
  );

  linkForces.forEach((link) => {
    const from = next[link.from];
    const to = next[link.to];
    if (!from || !to) return;

    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const distance = Math.hypot(dx, dy) || 0.01;
    const revealBoost = isRevealingGraph ? GRAPH_PHYSICS.revealLinkBoost : 1;
    const force = (distance - link.idealDistance) * GRAPH_PHYSICS.linkStrength * link.weight * revealBoost;
    const fx = (dx / distance) * force;
    const fy = (dy / distance) * force;

    if (link.from !== anchorId) {
      deltas[link.from].x += fx;
      deltas[link.from].y += fy;
    }

    if (link.to !== anchorId) {
      deltas[link.to].x -= fx;
      deltas[link.to].y -= fy;
    }
  });

  for (const pair of pairForces) {
    const { nodeA, nodeB, linked, desiredDistance } = pair;
    const posA = next[nodeA.id];
    const posB = next[nodeB.id];
    const dx = posB.x - posA.x;
    const dy = posB.y - posA.y;
    const distance = Math.hypot(dx, dy) || 0.01;
    if (linked || distance >= desiredDistance || distance >= GRAPH_PHYSICS.repulsionRange) continue;

    const proximity = 1 - distance / GRAPH_PHYSICS.repulsionRange;
    const force = (desiredDistance - distance) * GRAPH_PHYSICS.repulsionStrength * proximity;
    const fx = (dx / distance) * force;
    const fy = (dy / distance) * force;

    if (nodeA.id !== anchorId) {
      deltas[nodeA.id].x -= fx;
      deltas[nodeA.id].y -= fy;
    }

    if (nodeB.id !== anchorId) {
      deltas[nodeB.id].x += fx;
      deltas[nodeB.id].y += fy;
    }
  }

  nodes.forEach((node) => {
    if (node.id === anchorId) return;
    const initialPosition = initialNodePositions[node.id];
    const position = next[node.id] ?? initialPosition;
    const centerStrength = GRAPH_PHYSICS.centerStrength * (isRevealingGraph ? GRAPH_PHYSICS.revealCenterBoost : 1);
    deltas[node.id].x += (GRAPH_CENTER.x - position.x) * centerStrength;
    deltas[node.id].y += (GRAPH_CENTER.y - position.y) * centerStrength;
    deltas[node.id].x += (initialPosition.x - position.x) * GRAPH_PHYSICS.originStrength;
    deltas[node.id].y += (initialPosition.y - position.y) * GRAPH_PHYSICS.originStrength;
  });

  const nextPositions = resolveGraphCollisions({
    nodes,
    positions: Object.fromEntries(
      nodes.map((node) => {
        const position = next[node.id] ?? initialNodePositions[node.id];
        const delta = deltas[node.id];
        if (node.id === anchorId) return [node.id, clampPosition(position, node.id)];
        const damping = isRevealingGraph ? GRAPH_PHYSICS.revealDamping : GRAPH_PHYSICS.damping;

        return [node.id, clampPosition({
          x: position.x + delta.x * damping,
          y: position.y + delta.y * damping
        }, node.id)];
      })
    ),
    initialNodePositions,
    pairForces,
    anchorId,
    clampPosition
  });

  if (!isRevealingGraph && !anchorId) {
    const maxMovement = nodes.reduce((movement, node) => {
      const previous = positions[node.id] ?? initialNodePositions[node.id];
      const current = nextPositions[node.id] ?? previous;
      return Math.max(movement, Math.hypot(current.x - previous.x, current.y - previous.y));
    }, 0);

    if (maxMovement < GRAPH_PHYSICS.settleThreshold) return positions;
  }

  return nextPositions;
}

function idealLinkDistanceValue(link: GraphLink, nodes: GraphNode[]) {
  const from = nodes.find((node) => node.id === link.from);
  const to = nodes.find((node) => node.id === link.to);
  if (!from || !to) return GRAPH_PHYSICS.linkDistance.fallback * GRAPH_PHYSICS.linkDistanceMultiplier;

  const kinds = [graphNodeKind(from), graphNodeKind(to)];
  let distance = GRAPH_PHYSICS.linkDistance.concept;
  if (kinds.every((kind) => kind === "source")) distance = GRAPH_PHYSICS.linkDistance.source;
  else if (kinds.includes("raw")) distance = GRAPH_PHYSICS.linkDistance.raw;
  else if (kinds.includes("progress")) distance = GRAPH_PHYSICS.linkDistance.progress;
  else if (kinds.includes("source") && kinds.includes("concept")) distance = GRAPH_PHYSICS.linkDistance.sourceConcept;
  return distance * GRAPH_PHYSICS.linkDistanceMultiplier;
}

function physicsNodeRadius(node: GraphNode, nodeSize: (node: GraphNode) => number) {
  return (nodeSize(node) / 2) * GRAPH_PHYSICS.collisionRadiusMultiplier;
}

function pairDistanceValue(nodeA: GraphNode, nodeB: GraphNode, nodeSize: (node: GraphNode) => number) {
  const base = physicsNodeRadius(nodeA, nodeSize) + physicsNodeRadius(nodeB, nodeSize);
  const kindA = graphNodeKind(nodeA);
  const kindB = graphNodeKind(nodeB);
  let distance = base + 48;
  if (kindA === "source" && kindB === "source") distance = base + 118;
  else if (kindA === "source" || kindB === "source") distance = base + 58;
  else if (kindA === "raw" || kindB === "raw") distance = base + 42;
  const typeMultiplier = kindA === "source" && kindB === "source"
    ? GRAPH_PHYSICS.sourceNodeDistanceMultiplier
    : 1;
  return distance * GRAPH_PHYSICS.nodeDistanceMultiplier * typeMultiplier;
}
