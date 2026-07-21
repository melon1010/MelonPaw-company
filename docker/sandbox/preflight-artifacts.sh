#!/usr/bin/env bash
set -euo pipefail

for executable in node python soffice pandoc pdftoppm; do
  command -v "$executable" >/dev/null
done

node -e "for (const name of ['docx','pptxgenjs','react','react-dom','react-icons','sharp']) require.resolve(name)"
python -c "import defusedxml, docx, lxml, markitdown, openpyxl, pdf2image, pdfplumber, PIL, pptx, pypdf"
