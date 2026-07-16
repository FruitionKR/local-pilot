import time

from app.modules.document_restoration.application.models import (
    RestoreDocumentCommand,
    StageTiming,
)
from app.modules.document_restoration.application.ports import (
    DocumentRestorationStagesPort,
)
from app.modules.document_restoration.domain.entities import RestorationStage


class RestoreDocumentUseCase:
    def __init__(self, stages: DocumentRestorationStagesPort) -> None:
        self._stages = stages

    def execute(self, command: RestoreDocumentCommand) -> list[StageTiming]:
        started_at = time.perf_counter()
        prepared = self._stages.prepare(command)
        timings: list[StageTiming] = []

        if self._stages.needs_docling_baseline(prepared):
            timings.append(
                self._stages.run_stage(
                    RestorationStage.DOCLING_BASELINE,
                    command,
                    prepared,
                )
            )

        stages = [
            RestorationStage.DETECT_LAYOUT_BLOCKS,
            RestorationStage.DETECT_EQUATION_CANDIDATES,
            RestorationStage.BUILD_PRIMARY_MANIFEST,
            RestorationStage.AUGMENT_TEXT_CANDIDATES,
            RestorationStage.RECOVER_BLOCKS,
        ]
        if command.use_local_vision:
            stages.extend(
                [
                    RestorationStage.REVIEW_BLOCKS_WITH_VISION,
                    RestorationStage.RECOVER_FIGURES_WITH_VISION,
                ]
            )
        stages.append(RestorationStage.ASSEMBLE_MARKDOWN)

        for stage in stages:
            timings.append(self._stages.run_stage(stage, command, prepared))

        self._stages.write_timings(
            command,
            timings,
            time.perf_counter() - started_at,
        )
        return timings
