from typing import Literal


class MarkdownSpecialistHandoffError(Exception):
    def __init__(
        self,
        action: Literal[
            "chat_answer",
            "conversation_reply",
            "markdown_edit",
            "markdown_create",
            "clarify",
        ],
        reason: str,
        message: str | None = None,
    ) -> None:
        super().__init__(reason)
        self.action = action
        self.reason = reason
        self.message = message
