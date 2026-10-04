// Node.js port of worker_python/test_index_benchmark.py
import { mkdir, writeFile, readFile, readdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEST_DIR = path.resolve(__dirname, "..", "index_benchmark_tmp_node");
const DB_PATH = path.join(TEST_DIR, "inverted_index.db");
const JSON_PATH = path.join(TEST_DIR, "inverted_index.json");
const FOLDER_DIR = path.join(TEST_DIR, "folders");

const VOCAB_SIZE = 5000;
const TEST_BOOK_ID = 9999;
const ROUNDS = 8;
// Iguala @Warmup(iterations = 3) de JMH en worker_java.
const WARMUP_ROUNDS = 3;

let vocabulary, bookWords;

function shuffle(arr) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

async function resetDir() {
  if (existsSync(TEST_DIR)) await rm(TEST_DIR, { recursive: true, force: true });
  await mkdir(TEST_DIR, { recursive: true });
}

function makeWorkload() {
  vocabulary = Array.from({ length: VOCAB_SIZE }, (_, i) => `word${i}`);
  const shuffled = shuffle(vocabulary);
  bookWords = new Set(shuffled.slice(0, 450));
  for (let i = 0; i < 50; i++) bookWords.add(`new_word${i}`);
}

// SQLite: 5000 words -> book_id 1
async function seedSqlite() {
  await resetDir();
  const db = new DatabaseSync(DB_PATH);
  db.exec("CREATE TABLE postings (term TEXT, book_id INTEGER, PRIMARY KEY (term, book_id))");
  const insert = db.prepare("INSERT INTO postings (term, book_id) VALUES (?, ?)");
  db.exec("BEGIN");
  for (const w of vocabulary) insert.run(w, 1);
  db.exec("COMMIT");
  db.close();
}

// JSON: 5000 words -> [1]
async function seedJson() {
  await resetDir();
  const index = {};
  for (const w of vocabulary) index[w] = [1];
  await writeFile(JSON_PATH, JSON.stringify(index), "utf-8");
}

// Folders: only the words of the new book that already exist in the vocabulary get a file
async function seedFolders() {
  await resetDir();
  await mkdir(FOLDER_DIR, { recursive: true });
  const vocabSet = new Set(vocabulary);
  for (const w of bookWords) {
    if (vocabSet.has(w)) {
      const letterDir = path.join(FOLDER_DIR, w[0].toUpperCase());
      await mkdir(letterDir, { recursive: true });
      await writeFile(path.join(letterDir, `${w}.txt`), "1\n", "utf-8");
    }
  }
}

async function insertJson() {
  const index = JSON.parse(await readFile(JSON_PATH, "utf-8"));
  for (const w of bookWords) {
    if (!index[w]) index[w] = [];
    if (!index[w].includes(TEST_BOOK_ID)) index[w].push(TEST_BOOK_ID);
  }
  await writeFile(JSON_PATH, JSON.stringify(index), "utf-8");
}

function insertSqlite() {
  const db = new DatabaseSync(DB_PATH);
  const insert = db.prepare("INSERT OR IGNORE INTO postings (term, book_id) VALUES (?, ?)");
  db.exec("BEGIN");
  for (const w of bookWords) insert.run(w, TEST_BOOK_ID);
  db.exec("COMMIT");
  db.close();
}

async function insertFolders() {
  for (const w of bookWords) {
    const letterDir = path.join(FOLDER_DIR, w[0].toUpperCase());
    await mkdir(letterDir, { recursive: true });
    const wordFile = path.join(letterDir, `${w}.txt`);

    let existing = new Set();
    if (existsSync(wordFile)) {
      const text = await readFile(wordFile, "utf-8");
      existing = new Set(text.split("\n").map((l) => l.trim()).filter((l) => /^\d+$/.test(l)).map(Number));
    }
    if (!existing.has(TEST_BOOK_ID)) {
      existing.add(TEST_BOOK_ID);
      const sorted = [...existing].sort((a, b) => a - b);
      await writeFile(wordFile, sorted.join("\n") + "\n", "utf-8");
    }
  }
}

// `op` mide una ronda; `seed` deja el índice en el estado previo a indexar el libro nuevo y
// se ejecuta antes de CADA ronda (también las de warmup) sin cronometrarse. Si el estado se
// reutilizara, toda ronda posterior a la primera sería "reinsertar un libro que ya está en
// el índice", una operación distinta y más barata que indexar un libro nuevo.
async function time(seed, op) {
  for (let w = 0; w < WARMUP_ROUNDS; w++) {
    await seed();
    await op();
  }

  const timesMs = [];
  for (let r = 0; r < ROUNDS; r++) {
    await seed();
    const start = process.hrtime.bigint();
    await op();
    const end = process.hrtime.bigint();
    timesMs.push(Number(end - start) / 1_000_000);
  }
  return timesMs;
}

function printStats(name, times) {
  const min = Math.min(...times);
  const max = Math.max(...times);
  const mean = times.reduce((a, b) => a + b, 0) / times.length;
  const sorted = [...times].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)];
  const variance = times.reduce((a, t) => a + (t - mean) ** 2, 0) / (times.length - 1);
  const stddev = Math.sqrt(variance);
  console.log(
    `${name.padEnd(16)} min=${min.toFixed(4).padStart(10)}  max=${max.toFixed(4).padStart(10)}  ` +
      `mean=${mean.toFixed(4).padStart(10)}  median=${median.toFixed(4).padStart(10)}  stddev=${stddev.toFixed(4).padStart(10)}  (ms)`
  );
}

async function main() {
  console.log(
    `Inverted index benchmark (Node.js) - ${WARMUP_ROUNDS} warmup + ${ROUNDS} measured rounds, 5000-word vocabulary, +500-word book`
  );

  makeWorkload();
  const sqliteTimes = await time(seedSqlite, insertSqlite);
  const jsonTimes = await time(seedJson, insertJson);
  const folderTimes = await time(seedFolders, insertFolders);

  printStats("index_sqlite", sqliteTimes);
  printStats("index_json", jsonTimes);
  printStats("index_folders", folderTimes);

  await rm(TEST_DIR, { recursive: true, force: true });
}

main();
