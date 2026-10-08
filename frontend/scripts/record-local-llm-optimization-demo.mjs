import {chromium} from "@playwright/test";
import {access, mkdir, readFile, rename, rm, stat} from "node:fs/promises";
import {spawnSync} from "node:child_process";
import path from "node:path";
import process from "node:process";
import {pathToFileURL} from "node:url";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/local-llm-optimization-demo.webm");
const reportPath = path.join(root, ".llm-local-optimization/optimization.json");
const temporary = path.join(root, "video/.local-llm-optimization-recording");
const baseURL = process.env.LOCAL_LLM_OPTIMIZATION_DEMO_BASE_URL ?? "http://127.0.0.1:18083";
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const question = "Где находится production entry point web-приложения?";

function escapeHtml(value) {
  return String(value ?? "н/д").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
}
function percent(value) {
  return `${(Number(value) * 100).toFixed(1)}%`;
}
function seconds(value) {
  return value == null ? "н/д" : `${(Number(value) / 1000).toFixed(3)} с`;
}
function bytes(value) {
  return value == null ? "н/д" : `${(Number(value) / 1_000_000_000).toFixed(2)} ГБ`;
}
function configuration(config) {
  return `temperature=${config.temperature} · max_tokens=${config.maxTokens} · context=${config.contextWindowTokens} · prompt=${config.promptVersion} · quant=Q4_K_M`;
}
function shell(title, body) {
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><style>
    *{box-sizing:border-box}body{margin:0;background:#10131a;color:#f4f6fb;font:22px system-ui;padding:48px}h1{font-size:42px;margin:0 0 28px;color:#8bd3ff}h2{font-size:28px;color:#b8f2cf;margin:12px 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:28px}.card{background:#1b2130;border:1px solid #34405a;border-radius:18px;padding:26px;min-height:640px}.config{font:18px ui-monospace,monospace;color:#ffd98e;margin:12px 0 24px;line-height:1.5}.answer{white-space:pre-wrap;font-size:18px;line-height:1.45;max-height:450px;overflow:hidden}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:18px}.metric{background:#1b2130;border-radius:16px;padding:24px;border:1px solid #34405a}.metric b{display:block;font-size:31px;color:#b8f2cf;margin-top:10px}.winner{color:#b8f2cf}.foot{margin-top:28px;color:#aab5ca;font-size:18px}</style></head><body><h1>${escapeHtml(title)}</h1>${body}</body></html>`;
}

try {
  await access(output);
  if (process.env.LOCAL_LLM_OPTIMIZATION_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите LOCAL_LLM_OPTIMIZATION_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
  }
  await rm(output);
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
}
const report = JSON.parse(await readFile(reportPath, "utf8"));
if (report.status !== "completed" || !report.finalBaseline || !report.finalOptimized || !report.decision?.optimizedAccepted) {
  throw new Error("Нужен завершённый real-local optimization report с прошедшим quality gate.");
}
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
  await pause(5000);
  const oldCount = await page.getByTestId("exchange").count();
  await page.getByRole("textbox", {name: "Новый запрос", exact: true}).fill(question);
  await pause(1200);
  await page.getByRole("button", {name: "Отправить", exact: true}).click();
  const exchange = page.getByTestId("exchange").nth(oldCount);
  await exchange.waitFor();
  await exchange.getByText("Завершено", {exact: true}).waitFor({timeout: 600_000});
  const stateResponse = await context.request.get(`${baseURL}/api/state`);
  if (!stateResponse.ok()) throw new Error(`GET /api/state: ${stateResponse.status()}`);
  const state = await stateResponse.json();
  const actual = state.exchanges.at(-1);
  const raw = actual?.outputs?.find((item) => item.id === "rag");
  if (actual?.status !== "completed" || raw?.error || raw?.ragDiagnostics?.generationProvider !== "ollama" ||
      raw?.ragDiagnostics?.generationModel !== "qwen3:14b" || raw?.ragDiagnostics?.evidence?.status !== "verified") {
    throw new Error("Production RAG baseline не подтверждён как real-local Ollama с verified evidence.");
  }
  await exchange.getByTestId("response-card").nth(1).scrollIntoViewIfNeeded();
  await pause(7000);

  const caseId = "production-entry-point";
  const baselineRun = report.runs.find((run) => run.phase === "final-ab" && run.configurationId === "baseline-final" && run.caseId === caseId && run.repetition === 1);
  const optimizedRun = report.runs.find((run) => run.phase === "final-ab" && run.configurationId === "optimized-final" && run.caseId === caseId && run.repetition === 1);
  if (!baselineRun?.answer || !optimizedRun?.answer) throw new Error("В report нет парных ответов production-entry-point.");

  await page.setContent(shell("Один вопрос — frozen retrieval — две конфигурации", `<div class="grid">
    <section class="card"><h2>Baseline</h2><div class="config">${escapeHtml(configuration(report.finalBaseline.configuration))}</div><div class="answer">${escapeHtml(baselineRun.answer)}</div></section>
    <section class="card"><h2 class="winner">Optimized</h2><div class="config">${escapeHtml(configuration(report.finalOptimized.configuration))}</div><div class="answer">${escapeHtml(optimizedRun.answer)}</div></section>
  </div>`));
  await pause(12000);

  const baseline = report.finalBaseline;
  const optimized = report.finalOptimized;
  await page.setContent(shell("Финальный A/B: все 11 кейсов × 3 повтора", `<div class="metrics">
    <div class="metric">Quality baseline → optimized<b>${percent(baseline.qualityScore)} → ${percent(optimized.qualityScore)}</b></div>
    <div class="metric">Citation + exact quote<b>${percent(baseline.citationCorrectRate)} → ${percent(optimized.citationCorrectRate)}</b></div>
    <div class="metric">Latency p50<b>${seconds(baseline.latencyP50Millis)} → ${seconds(optimized.latencyP50Millis)}</b></div>
    <div class="metric">Latency p95<b>${seconds(baseline.latencyP95Millis)} → ${seconds(optimized.latencyP95Millis)}</b></div>
    <div class="metric">Throughput<b>${baseline.averageOutputTokensPerSecond.toFixed(2)} → ${optimized.averageOutputTokensPerSecond.toFixed(2)} tok/s</b></div>
    <div class="metric">Abstention correctness<b>${percent(baseline.abstentionCorrectness)} → ${percent(optimized.abstentionCorrectness)}</b></div>
    <div class="metric">Ollama RSS snapshot<b>${bytes(baseline.resource.ollamaProcessRssBytes)} → ${bytes(optimized.resource.ollamaProcessRssBytes)}</b></div>
    <div class="metric">Model / quantization<b>9.3 GB · Q4_K_M</b></div>
  </div><div class="foot">Winner: optimized-final · retrieval IDs stable: ${percent(optimized.retrievedChunkIdStability)} · cloud/paid API не использовались</div>`));
  await pause(12000);
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

const ffprobe = spawnSync("ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-show_entries", "format=duration,size", "-of", "json", output], {encoding: "utf8"});
if (!ffprobe.error && ffprobe.status === 0) {
  const probed = JSON.parse(ffprobe.stdout);
  if (Number(probed.format?.duration) <= 0 || Number(probed.streams?.[0]?.width) !== 1440 || Number(probed.streams?.[0]?.height) !== 900) {
    throw new Error(`ffprobe обнаружил некорректные metadata: ${ffprobe.stdout}`);
  }
} else if (ffprobe.error?.code !== "ENOENT") {
  throw new Error(`ffprobe завершился с ошибкой: ${ffprobe.stderr}`);
}
process.stdout.write(`${output}\n${JSON.stringify({...metadata, size: file.size, ffprobe: ffprobe.error?.code === "ENOENT" ? "unavailable" : "verified"})}\n`);
