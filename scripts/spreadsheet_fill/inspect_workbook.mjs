import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = "C:/Users/user/Downloads/v6_1_КулибИТ_Заявка_регистрация_2026.xlsx";

const input = await FileBlob.load(inputPath);
const workbook = await SpreadsheetFile.importXlsx(input);

const summary = await workbook.inspect({
  kind: "workbook,sheet,table,region",
  tableMaxRows: 12,
  tableMaxCols: 12,
  tableMaxCellChars: 120,
  maxChars: 12000,
});

console.log(summary.ndjson);

const matches = await workbook.inspect({
  kind: "match",
  searchTerm: "ФИО|фамил|имя|отчеств|город|населен|телефон|возраст|дата|рожд|участник|адрес|заявк",
  options: { useRegex: true, maxResults: 100 },
  maxChars: 12000,
});

console.log(matches.ndjson);
