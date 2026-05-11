import fs from "node:fs/promises";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "C:/Users/user/Downloads/v6_1_КулибИТ_Заявка_регистрация_2026.xlsx";
const outputDir = "C:/Users/user/Desktop/chatapp/outputs/registration_20260509";
const outputPath = `${outputDir}/v6_1_КулибИТ_Заявка_регистрация_2026_заполнено.xlsx`;

const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);
const sheet = workbook.worksheets.getItem("Заявка");

sheet.getRange("E12").values = [["Иваново"]];
sheet.getRange("E18").values = [["Ощепков Евгений Игоревич"]];
sheet.getRange("E21").values = [["Тел.: +79016891059"]];
sheet.getRange("F18").values = [["Возраст: 17 лет"]];

sheet.getRange("E12:F21").format = {
  font: { color: "#000000" },
  wrapText: true,
  horizontalAlignment: "left",
  verticalAlignment: "center",
};
sheet.getRange("E21").format.numberFormat = "@";

const check = await workbook.inspect({
  kind: "table",
  range: "Заявка!A10:F22",
  include: "values,formulas",
  tableMaxRows: 20,
  tableMaxCols: 6,
  tableMaxCellChars: 120,
  maxChars: 8000,
});
console.log(check.ndjson);

const errors = await workbook.inspect({
  kind: "match",
  searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
  options: { useRegex: true, maxResults: 300 },
  summary: "final formula error scan",
  maxChars: 4000,
});
console.log(errors.ndjson);

const preview = await workbook.render({
  sheetName: "Заявка",
  range: "A1:F65",
  scale: 1,
  format: "png",
});
await fs.mkdir(outputDir, { recursive: true });
await fs.writeFile(`${outputDir}/preview.png`, new Uint8Array(await preview.arrayBuffer()));

const exported = await SpreadsheetFile.exportXlsx(workbook);
await exported.save(outputPath);
console.log(outputPath);
