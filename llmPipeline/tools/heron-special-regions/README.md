# Heron special-region detector

제품의 `crop-first` 문서 복원 mode가 표·수식·그림 bbox만 빠르게 얻기 위해
사용하는 Rust helper다. `docling.rs`의 MIT 라이선스 `docling-pdf` crate를
검증한 commit에 고정해 사용한다.

Rust 1.88 이상에서 설치한다. Rust가 없다면 공식 rustup으로 먼저 설치한다.

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
  | sh -s -- -y --profile minimal --default-toolchain 1.88.0
. "$HOME/.cargo/env"
```

AnyDoc은 Node 20 이상에서
`npm install --global @firecrawl/anydoc@0.1.7`로 별도 설치한다.

```bash
cargo install --locked --path tools/heron-special-regions
```

실행 시 Heron INT8 model과 PDFium library가 필요하다. 아래 명령은
`docling.rs`의 `models-v1` release에서 model을 받고, Python 환경에 설치된
`pypdfium2`에서 현재 플랫폼의 PDFium library 위치를 찾는다.

```bash
export DOCLING_RS_ASSET_DIR="$PWD/.local/docling-rs"
mkdir -p "$DOCLING_RS_ASSET_DIR/models"
curl -fL \
  https://github.com/docling-project/docling.rs/releases/download/models-v1/layout_heron_int8.onnx \
  -o "$DOCLING_RS_ASSET_DIR/models/layout_heron_int8.onnx"

export DOCLING_PDFIUM_DIR="$(python -c \
  'from pathlib import Path; import pypdfium2_raw; print(Path(pypdfium2_raw.__file__).resolve().parent)')"
test -s "$DOCLING_RS_ASSET_DIR/models/layout_heron_int8.onnx"
find "$DOCLING_PDFIUM_DIR" -maxdepth 1 -type f \
  \( -name 'libpdfium.so' -o -name 'libpdfium.dylib' -o -name 'pdfium.dll' \) \
  -print -quit | grep .
```

CLI에는 다음 경로를 전달한다.

```bash
--heron-model "$DOCLING_RS_ASSET_DIR/models/layout_heron_int8.onnx" \
--pdfium-library "$DOCLING_PDFIUM_DIR"
```

Heron model은 Apache-2.0, `docling.rs` helper는 MIT, PDFium은
BSD-3-Clause 조건을 따른다.
