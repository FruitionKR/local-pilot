from app.modules.query.application.ports import AnswerGeneratorPort
from app.modules.query.domain.entities import GeneratedAnswer, QueryContext


class StaticAnswerGenerator(AnswerGeneratorPort):
    def generate_answer(self, context: QueryContext) -> GeneratedAnswer:
        if not context.evidence_snippets:
            return GeneratedAnswer(content="관련 근거를 찾지 못했어요. 문서에 직접적인 답이 있는지 확인이 더 필요합니다.")

        top = context.evidence_snippets[0]
        if top.score < 1.0:
            return GeneratedAnswer(
                content=(
                    "문서에 질문을 정확히 정의한 근거는 충분하지 않아요. "
                    f"다만 관련해서 {top.page_title}에서 {top.text.strip()} 라는 내용이 언급됩니다."
                )
            )
        return GeneratedAnswer(content=f"문서 기준으로는 {top.text.strip()}")

