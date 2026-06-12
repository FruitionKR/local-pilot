from app.modules.query.application.ports import AnswerGeneratorPort
from app.modules.query.domain.entities import GeneratedAnswer, QueryContext


class StaticAnswerGenerator(AnswerGeneratorPort):
    def generate_answer(self, context: QueryContext) -> GeneratedAnswer:
        return GeneratedAnswer(content=f"답변 context pages={len(context.related_pages)}")

