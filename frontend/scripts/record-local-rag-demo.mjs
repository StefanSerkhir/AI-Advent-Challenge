import {chromium} from "@playwright/test";
import {access, mkdir, rename, rm, stat} from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import {pathToFileURL} from "node:url";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/local-rag-demo.webm");
const report = path.join(root, ".llm-rag-evaluation/comparison.md");
const temporary = path.join(root, "video/.local-rag-demo-recording");
const baseURL = process.env.LOCAL_RAG_DEMO_BASE_URL ?? "http://127.0.0.1:18082";
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

const questions = [
  "Где находится production entry point web-приложения?",
  "Какие ограничения безопасности применяются к save_to_file?",
];

try {
  await access(output);
  if (process.env.LOCAL_RAG_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите LOCAL_RAG_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
  }
  await rm(output);
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
}
await access(report);
await rm(temporary, {recursive: true, force: true});
await mkdir(temporary, {recursive: true});
await mkdir(path.dirname(output), {recursive: true});

const browser = await chromium.launch({headless: true});
const context = await browser.newContext({
  viewport: {width: 1440, height: 900},
  recordVideo: {dir: temporary, size: {width: 1440, height: 900}},
});
const page = await context.newPage();
const video = page.video();
page.setDefaultTimeout(600_000);

async function readState() {
  const response = await context.request.get(`${baseURL}/api/state`);
  if (!response.ok()) throw new Error(`GET /api/state: ${response.status()}`);
  return response.json();
}

async function send(question) {
  const oldCount = await page.getByTestId("exchange").count();
  await page.getByRole("textbox", {name: "Новый запрос", exact: true}).fill(question);
  await pause(1500);
  await page.getByRole("button", {name: "Отправить", exact: true}).click();
  const exchange = page.getByTestId("exchange").nth(oldCount);
  await exchange.waitFor();
  await exchange.getByText("Завершено", {exact: true}).waitFor({timeout: 600_000});
  const state = await readState();
  const wireExchange = state.exchanges.at(-1);
  if (wireExchange?.status !== "completed" || wireExchange.outputs.length !== 3) {
    throw new Error(`RAG exchange не завершён тремя карточками: ${wireExchange?.status}`);
  }
  for (const branch of wireExchange.outputs.slice(1)) {
    const diagnostics = branch.ragDiagnostics;
    if (branch.error || !diagnostics || diagnostics.embeddingProvider !== "ollama" ||
        diagnostics.embeddingModel !== "qwen3-embedding:0.6b" ||
        diagnostics.generationProvider !== "ollama" || diagnostics.generationModel !== "qwen3:14b") {
      throw new Error(`Не подтверждена полностью локальная ветка ${branch.id}: ${branch.error ?? "diagnostics mismatch"}`);
    }
    if (diagnostics.evidence.status !== "verified" || diagnostics.evidence.sources.length === 0 ||
        diagnostics.evidence.sources.some((source) => source.quotes.length === 0)) {
      throw new Error(`Ветка ${branch.id} не прошла verified citations/exact quotes.`);
    }
    if (branch.metrics.estimatedCostUsd !== null) throw new Error(`У локальной ветки ${branch.id} появилась облачная стоимость.`);
  }
  const cards = exchange.getByTestId("response-card");
  await cards.nth(1).scrollIntoViewIfNeeded();
  await pause(7000);
  await cards.nth(2).scrollIntoViewIfNeeded();
  await pause(9000);
}

try {
  await page.goto(baseURL, {waitUntil: "networkidle", timeout: 120_000});
  const provider = page.getByLabel("Провайдер", {exact: true});
  const model = page.getByLabel("Модель", {exact: true});
  const mode = page.getByLabel("Режим ответа", {exact: true});
  await provider.waitFor({state: "visible"});
  if (await provider.inputValue() !== "OLLAMA" || await model.inputValue() !== "qwen3:14b" || await mode.inputValue() !== "rag") {
    throw new Error("Production runtime должен быть запущен как OLLAMA/qwen3:14b в режиме rag.");
  }
  await page.getByTestId("local-provider-note").waitFor();
  await pause(6000);
  for (const question of questions) await send(question);

  await page.goto(pathToFileURL(report).href, {waitUntil: "load"});
  await page.getByText("RAG evaluation comparison", {exact: false}).waitFor();
  await pause(10000);
} finally {
  await context.close();
  await browser.close();
}

const recorded = await video.path();
await rename(recorded, output);
await rm(temporary, {recursive: true, force: true});
const file = await stat(output);
if (file.size <= 0) throw new Error("Записанный WebM имеет нулевой размер.");

const verifier = await chromium.launch({headless: true});
let metadata;
try {
  const verificationPage = await verifier.newPage();
  await verificationPage.goto(pathToFileURL(output).href, {waitUntil: "load"});
  await verificationPage.locator("video").waitFor({state: "attached"});
  metadata = await verificationPage.locator("video").evaluate((element) => new Promise((resolve, reject) => {
    const media = /** @type {HTMLVideoElement} */ (element);
    const read = () => resolve({duration: media.duration, width: media.videoWidth, height: media.videoHeight});
    if (media.readyState >= 1) read();
    else {
      media.addEventListener("loadedmetadata", read, {once: true});
      media.addEventListener("error", () => reject(new Error("Chromium не смог прочитать WebM metadata.")), {once: true});
    }
  }));
} finally {
  await verifier.close();
}
if (!Number.isFinite(metadata.duration) || metadata.duration <= 0 || metadata.width !== 1440 || metadata.height !== 900) {
  throw new Error(`Некорректные WebM metadata: ${JSON.stringify(metadata)}`);
}
process.stdout.write(`${output}\n${JSON.stringify({...metadata, size: file.size})}\n`);
