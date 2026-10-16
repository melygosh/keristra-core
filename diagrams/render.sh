#!/bin/bash
# Скрипт для рендерингу PlantUML діаграм у PNG та SVG
# Вимога: Java (вже є) + plantuml.jar
#
# Встановлення PlantUML:
#   brew install plantuml
# або завантажити plantuml.jar:
#   curl -L -o plantuml.jar https://github.com/plantuml/plantuml/releases/latest/download/plantuml.jar

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
mkdir -p "$OUTPUT_DIR"

# Визначаємо як запускати PlantUML
if command -v plantuml &> /dev/null; then
    PLANTUML_CMD="plantuml"
elif [ -f "$SCRIPT_DIR/plantuml.jar" ]; then
    PLANTUML_CMD="java -jar $SCRIPT_DIR/plantuml.jar"
else
    echo "PlantUML не знайдено. Встановіть одним зі способів:"
    echo "  brew install plantuml"
    echo "  curl -L -o $SCRIPT_DIR/plantuml.jar https://github.com/plantuml/plantuml/releases/latest/download/plantuml.jar"
    exit 1
fi

echo "Рендеринг діаграм..."

for puml_file in "$SCRIPT_DIR"/*.puml; do
    filename=$(basename "$puml_file" .puml)
    echo "  → $filename"

    # PNG (для вставки у документ)
    $PLANTUML_CMD -tpng -o "$OUTPUT_DIR" "$puml_file"

    # SVG (для масштабування без втрати якості)
    $PLANTUML_CMD -tsvg -o "$OUTPUT_DIR" "$puml_file"
done

echo ""
echo "Готово! Файли збережено у $OUTPUT_DIR/"
ls -la "$OUTPUT_DIR/"
