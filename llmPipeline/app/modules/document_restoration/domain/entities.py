from enum import Enum


class RestorationStage(str, Enum):
    DOCLING_BASELINE = "docling_baseline"
    DETECT_LAYOUT_BLOCKS = "detect_layout_blocks"
    DETECT_EQUATION_CANDIDATES = "detect_docling_equation_candidates"
    BUILD_PRIMARY_MANIFEST = "build_docling_primary_manifest"
    AUGMENT_TEXT_CANDIDATES = "augment_text_candidates_with_crop_ocr"
    RECOVER_BLOCKS = "recover_blocks"
    REVIEW_BLOCKS_WITH_VISION = "review_blocks_with_vision"
    RECOVER_FIGURES_WITH_VISION = "recover_figure_blocks_with_vision"
    ASSEMBLE_MARKDOWN = "process_auto_layout_blocks"
