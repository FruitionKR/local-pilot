from app.modules.query.domain.entities import GraphContext, QueryContext, RetrievedPage, TraversalPath


class BuildQueryContextUseCase:
    def execute(
        self,
        question: str,
        related_pages: list[RetrievedPage],
        graph_context: GraphContext,
        traversal_paths: list[TraversalPath],
    ) -> QueryContext:
        return QueryContext(
            question=question,
            graph_context=graph_context,
            traversal_paths=traversal_paths,
            related_pages=related_pages,
        )

