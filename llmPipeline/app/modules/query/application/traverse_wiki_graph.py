from collections import defaultdict, deque

from app.modules.query.domain.entities import GraphContext, RetrievedPage, TraversalEdge, TraversalPath, WikiPage, WikiPageLink
from app.modules.query.domain.scoring import edge_role, traversal_score


class TraverseWikiGraphUseCase:
    def __init__(
        self,
        max_depth: int = 3,
        relative_score_floor: float = 0.95,
        frontier_limit: int = 8,
        min_node_score: float | None = None,
    ) -> None:
        self._max_depth = max_depth
        self._relative_score_floor = relative_score_floor
        self._frontier_limit = frontier_limit

    def execute(
        self,
        pages_by_id: dict[str, WikiPage],
        links: list[WikiPageLink],
        seed_source_ids: list[str],
        node_scores: dict[str, float],
    ) -> tuple[GraphContext, list[TraversalPath], str]:
        adjacency = self._build_adjacency(links)
        visited: dict[str, RetrievedPage] = {}
        edges_by_key: dict[tuple[str, str, str], TraversalEdge] = {}
        traversal_paths: list[TraversalPath] = []
        frontier = deque((seed_id, [seed_id], [], node_scores.get(seed_id, 0.0), 0) for seed_id in seed_source_ids)
        best_observed_score = max((node_scores.get(seed_id, 0.0) for seed_id in seed_source_ids), default=0.0)

        for seed_id in seed_source_ids:
            page = pages_by_id.get(seed_id)
            if page:
                visited[seed_id] = RetrievedPage(page=page, score=node_scores.get(seed_id, 0.0), role="seed_source", depth=0)

        if best_observed_score <= 0:
            related_pages = sorted(visited.values(), key=lambda item: item.score, reverse=True)
            return GraphContext(nodes=related_pages), [], "no_relevant_seed"

        stop_reason = "max_depth"
        while frontier:
            current_id, path_nodes, path_edges, base_score, depth = frontier.popleft()

            next_candidates = []
            next_floor = best_observed_score * self._relative_score_floor
            for link in adjacency.get(current_id, []):
                target_id = link.to_page_id if link.from_page_id == current_id else link.from_page_id
                if target_id in path_nodes or target_id not in pages_by_id:
                    continue
                target_score = node_scores.get(target_id, 0.0)
                if target_score < next_floor:
                    continue
                next_score = traversal_score(
                    base_score=base_score,
                    node_score=target_score,
                    edge_score=link.confidence,
                    depth=depth + 1,
                )
                next_candidates.append((target_score, next_score, target_id, link))

            next_candidates.sort(key=lambda item: item[0], reverse=True)
            if not next_candidates and depth == 0:
                stop_reason = "no_frontier"

            expanded = 0
            if next_candidates:
                best_observed_score = max(best_observed_score, next_candidates[0][0])

            for target_score, next_score, target_id, link in next_candidates[: self._frontier_limit]:
                if target_score < best_observed_score * self._relative_score_floor:
                    continue
                target = pages_by_id[target_id]
                role = self._node_role(target, depth + 1)
                previous = visited.get(target_id)
                if previous is not None and previous.score >= target_score:
                    continue
                visited[target_id] = RetrievedPage(page=target, score=target_score, role=role, depth=depth + 1)

                traversal_edge = TraversalEdge(
                    from_page_id=link.from_page_id,
                    to_page_id=link.to_page_id,
                    link_type=link.link_type,
                    role=edge_role(link.link_type),
                    score=link.confidence,
                )
                edge_key = (traversal_edge.from_page_id, traversal_edge.to_page_id, traversal_edge.link_type)
                edges_by_key[edge_key] = traversal_edge

                next_path_nodes = [*path_nodes, target_id]
                next_path_edges = [*path_edges, traversal_edge]
                traversal_paths.append(
                    TraversalPath(
                        path_id=f"path_{len(traversal_paths) + 1}",
                        role="primary_answer_path" if len(traversal_paths) == 0 else "candidate_path",
                        nodes=next_path_nodes,
                        edges=next_path_edges,
                        score=target_score,
                    )
                )
                frontier.append((target_id, next_path_nodes, next_path_edges, next_score, depth + 1))
                expanded += 1

            if expanded:
                stop_reason = "no_frontier"
            elif next_candidates:
                stop_reason = "relative_score_floor"

        related_pages = sorted(visited.values(), key=lambda item: item.score, reverse=True)
        graph_context = GraphContext(nodes=related_pages, edges=list(edges_by_key.values()))
        return graph_context, sorted(traversal_paths, key=lambda item: item.score, reverse=True), stop_reason

    def _build_adjacency(self, links: list[WikiPageLink]) -> dict[str, list[WikiPageLink]]:
        adjacency: dict[str, list[WikiPageLink]] = defaultdict(list)
        allowed = {"source_mentions_concept", "concept_related_to", "source_related_to"}
        for link in links:
            if link.link_type not in allowed:
                continue
            adjacency[link.from_page_id].append(link)
            adjacency[link.to_page_id].append(link)
        return adjacency

    def _node_role(self, page: WikiPage, depth: int) -> str:
        if page.is_source and depth == 0:
            return "seed_source"
        if page.is_source:
            return "supporting_source"
        if page.is_concept and depth <= 1:
            return "focus_concept"
        return "related_context"

