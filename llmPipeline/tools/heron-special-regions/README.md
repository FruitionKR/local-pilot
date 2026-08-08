# Heron special-region detector

제품의 `crop-first` 문서 복원 mode가 표·수식·그림 bbox만 빠르게 얻기 위해
사용하는 Rust helper다. `docling.rs`의 MIT 라이선스 `docling-pdf` crate를
검증한 commit에 고정해 사용한다.

Rust 1.88 이상에서 설치한다. AnyDoc은 Node 20 이상에서
`npm install --global @firecrawl/anydoc@0.1.7`로 별도 설치한다.

```bash
cargo install --path tools/heron-special-regions
```

실행 시 `docling.rs`가 제공하는 `layout_heron_int8.onnx`와 PDFium library가
필요하다. 각각 CLI의 `--heron-model`, `--pdfium-library`로 전달한다.
`--pdfium-library`에는 `libpdfium.so`, `libpdfium.dylib` 또는
`pdfium.dll`이 들어 있는 디렉터리를 지정한다.
