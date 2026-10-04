// Node.js port of worker_python/test_benchmark.py
import { mkdir, writeFile, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEST_DIR = path.resolve(__dirname, "..", "datalake_benchmark_tmp_node");
const NUM_BOOKS = 1000;
const TEST_HEADER = "Title: Benchmark Test\nAuthor: Data Team\nLanguage: English";
const TEST_BODY = "Small simulated text to focus purely on filesystem I/O overhead. ".repeat(20);
const ROUNDS = 5;
// Rondas descartadas antes de medir, para igualar @Warmup(iterations = 2) de JMH
// en worker_java: las primeras escrituras pagan el calentamiento de la cache de
// disco del SO y falsean la media si se cuentan.
const WARMUP_ROUNDS = 2;

function pad2(n) {
  return String(n).padStart(2, "0");
}

async function saveTimeBased(i) {
  const now = new Date();
  const yyyymmdd = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`;
  const hh = pad2(now.getHours());
  const outputPath = path.join(TEST_DIR, yyyymmdd, hh);
  await mkdir(outputPath, { recursive: true });
  await writeFile(path.join(outputPath, `${i}.header.txt`), TEST_HEADER, "utf-8");
  await writeFile(path.join(outputPath, `${i}.body.txt`), TEST_BODY, "utf-8");
}

async function saveBookBased(i) {
  const outputPath = path.join(TEST_DIR, "books", String(i));
  await mkdir(outputPath, { recursive: true });
  await writeFile(path.join(outputPath, "header.txt"), TEST_HEADER, "utf-8");
  await writeFile(path.join(outputPath, "body.txt"), TEST_BODY, "utf-8");
}

async function saveRangeBased(i, rangeSize) {
  const lower = Math.floor((i - 1) / rangeSize) * rangeSize + 1;
  const upper = lower + rangeSize - 1;
  const outputPath = path.join(TEST_DIR, `batch_${lower}_${upper}`);
  await mkdir(outputPath, { recursive: true });
  await writeFile(path.join(outputPath, `${i}.header.txt`), TEST_HEADER, "utf-8");
  await writeFile(path.join(outputPath, `${i}.body.txt`), TEST_BODY, "utf-8");
}

// Se ejecuta antes de CADA ronda (también las de warmup) y no se cronometra. Sin esto, solo
// la primera ronda crearía carpetas y ficheros y las siguientes los sobrescribirían,
// ocultando el coste de creación de directorios que distingue a las tres estructuras.
async function freshDatalake() {
  if (existsSync(TEST_DIR)) await rm(TEST_DIR, { recursive: true, force: true });
  await mkdir(TEST_DIR, { recursive: true });
}

async function runBenchmark(batchFn) {
  for (let w = 0; w < WARMUP_ROUNDS; w++) {
    await freshDatalake();
    await batchFn();
  }

  const timesMs = [];
  for (let r = 0; r < ROUNDS; r++) {
    await freshDatalake();
    const start = process.hrtime.bigint();
    await batchFn();
    const end = process.hrtime.bigint();
    timesMs.push(Number(end - start) / 1_000_000);
  }
  await rm(TEST_DIR, { recursive: true, force: true });
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
    `Datalake structure benchmark (Node.js) - ${WARMUP_ROUNDS} warmup + ${ROUNDS} measured rounds x ${NUM_BOOKS} books`
  );

  const timeBased = await runBenchmark(async () => {
    for (let i = 0; i < NUM_BOOKS; i++) await saveTimeBased(i);
  });
  const bookBased = await runBenchmark(async () => {
    for (let i = 0; i < NUM_BOOKS; i++) await saveBookBased(i);
  });
  const rangeBased = await runBenchmark(async () => {
    for (let i = 0; i < NUM_BOOKS; i++) await saveRangeBased(i, 100);
  });

  printStats("time_based", timeBased);
  printStats("book_based", bookBased);
  printStats("range_based", rangeBased);
}

main();
