from app.modules.query.application.ports import QueryEventPublisherPort


def publish_query_event(
    event_publisher: QueryEventPublisherPort | None,
    stage: str,
    message: str,
    data: dict[str, object] | None = None,
) -> None:
    if event_publisher is None:
        return
    try:
        event_publisher.publish(stage, message, data)
    except Exception:
        return
