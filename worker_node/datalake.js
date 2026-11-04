// Node.js port of worker_python/datalake.py
import { mkdir, writeFile, readFile, appendFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const START_MARKER = "*** START OF THE PROJECT GUTENBERG EBOOK";
const END_MARKER = "*** END OF THE PROJECT GUTENBERG EBOOK";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE_DIR = path.resolve(__dirname, "..");
const DATALAKE_DIR = path.join(BASE_DIR, "datalake");
const CONTROL_DIR = path.join(BASE_DIR, "control");
const DOWNLOADED_FILE = path.join(CONTROL_DIR, "downloaded_books_node.txt");

async function getDownloadedBooks() {
  if (!existsSync(DOWNLOADED_FILE)) return new Set();
  const text = await readFile(DOWNLOADED_FILE, "utf-8");
  return new Set(
    text.split("\n").map((l) => l.trim()).filter((l) => /^\d+$/.test(l)).map(Number)
  );
}

async function markAsDownloaded(bookId) {
  await mkdir(CONTROL_DIR, { recursive: true });
  await appendFile(DOWNLOADED_FILE, `${bookId}\n`, "utf-8");
}

async function fetchAndSplitBook(bookId) {
  const url = `https://www.gutenberg.org/cache/epub/${bookId}/pg${bookId}.txt`;
  console.log(`Descargando libro ${bookId}...`);
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
    if (!response.ok) {
      console.log(`Error al descargar libro ${bookId}: HTTP ${response.status}`);
      return null;
    }
    const text = await response.text();
    if (!text.includes(START_MARKER) || !text.includes(END_MARKER)) {
      console.log(`Error: Marcadores no encontrados en el libro ${bookId}`);
      return null;
    }
    const startIdx = text.indexOf(START_MARKER);
    const endIdx = text.indexOf(END_MARKER);
    const header = text.slice(0, startIdx).trim();
    const body = text.slice(startIdx + START_MARKER.length, endIdx).trim();
    return { header, body };
  } catch (e) {
    console.log(`Error al descargar libro ${bookId}: ${e.message}`);
    return null;
  }
}

function pad2(n) {
  return String(n).padStart(2, "0");
}

async function saveTimeBased(bookId, header, body) {
  const now = new Date();
  const yyyymmdd = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`;
  const hh = pad2(now.getHours());
  const outputPath = path.join(DATALAKE_DIR, "time_based_node", yyyymmdd, hh);
  await mkdir(outputPath, { recursive: true });
  await writeFile(path.join(outputPath, `${bookId}.header.txt`), header, "utf-8");
  await writeFile(path.join(outputPath, `${bookId}.body.txt`), body, "utf-8");
}

async function saveBookBased(bookId, header, body) {
  const outputPath = path.join(DATALAKE_DIR, "book_based_node", "books", String(bookId));
  await mkdir(outputPath, { recursive: true });
  await writeFile(path.join(outputPath, "header.txt"), header, "utf-8");
  await writeFile(path.join(outputPath, "body.txt"), body, "utf-8");
}

async function saveRangeBased(bookId, header, body, rangeSize = 1000) {
  const lower = Math.floor((bookId - 1) / rangeSize) * rangeSize + 1;
  const upper = lower + rangeSize - 1;
  const outputPath = path.join(DATALAKE_DIR, "range_based_node", `batch_${lower}_${upper}`);
  await mkdir(outputPath, { recursive: true });
  await writeFile(path.join(outputPath, `${bookId}.header.txt`), header, "utf-8");
  await writeFile(path.join(outputPath, `${bookId}.body.txt`), body, "utf-8");
}

// IDs from the command line or, when there are none, from the shared config file.
async function loadBookIds() {
  const args = process.argv.slice(2);
  const values = args.length > 0
    ? args
    : (await readFile(path.join(BASE_DIR, "config", "book_ids.txt"), "utf-8")).split("\n");
  return values.map((v) => v.split("#")[0].trim()).filter(Boolean).map(Number);
}

async function main() {
  const descargados = await getDownloadedBooks();

  for (const libro of await loadBookIds()) {
    if (descargados.has(libro)) {
      console.log(`Saltando libro ${libro}: Ya se encuentra en el Datalake.`);
      continue;
    }
    const hb = await fetchAndSplitBook(libro);
    if (hb) {
      await saveTimeBased(libro, hb.header, hb.body);
      await saveBookBased(libro, hb.header, hb.body);
      await saveRangeBased(libro, hb.header, hb.body, 1000);
      await markAsDownloaded(libro);
      console.log(`Exito: Libro ${libro} guardado y registrado en control.`);
    }
  }
}

main();
