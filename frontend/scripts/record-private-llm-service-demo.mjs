import {chromium} from "@playwright/test";
import {spawn} from "node:child_process";
import {access, mkdir, readFile, rename, rm, stat} from "node:fs/promises";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/private-llm-service-demo.webm");
const temporary = path.join(root, "video/.private-llm-service-recording");
const reportPath = path.resolve(root, process.env.PRIVATE_LLM_REPORT_PATH ?? "build/reports/private-llm-service.json");
const baseURL = required("PRIVATE_LLM_BASE_URL").replace(/\/$/, "");
const username = required("PRIVATE_LLM_USERNAME");
const password = required("PRIVATE_LLM_PASSWORD");
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function required(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

function escapeHtml(value) {
  return String(value ?? "н/д")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

async function runVerifier() {
  await new Promise((resolve, reject) => {
    const child = spawn("python3", [path.join(root, "deploy/private-llm/verify_private_llm.py")], {
      cwd: root,
      env: {...process.env, PRIVATE_LLM_REPORT_PATH: reportPath},
      stdio: "inherit",
    });
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolve() : reject(new Error(`Verifier exited with code ${code}.`)));
  });
}

try {
  await access(output);
  if (process.env.PRIVATE_LLM_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите PRIVATE_LLM_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
  }
  await rm(output);
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
}

await runVerifier();
const report = JSON.parse(await readFile(reportPath, "utf-8"));
if (report.status !== "passed") throw new Error("Real-network verifier report is not passed.");

await rm(temporary, {recursive: true, force: true});
await mkdir(temporary, {recursive: true});
await mkdir(path.dirname(output), {recursive: true});

const chromiumSpkiPin = process.env.PRIVATE_LLM_CHROMIUM_SPKI_PIN?.trim();
const browser = await chromium.launch({
  headless: true,
  args: chromiumSpkiPin ? [`--ignore-certificate-errors-spki-list=${chromiumSpkiPin}`] : [],
});
const context = await browser.newContext({
  viewport: {width: 1440, height: 900},
  recordVideo: {dir: temporary, size: {width: 1440, height: 900}},
  httpCredentials: {username, password},
});
const page = await context.newPage();
const video = page.video();
page.setDefaultTimeout(600_000);

try {
  await page.goto(baseURL, {waitUntil: "networkidle", timeout: 120_000});
  const provider = page.getByLabel("Провайдер", {exact: true});
  const model = page.getByLabel("Модель", {exact: true});
  await provider.waitFor({state: "visible"});
  await page.waitForFunction(() => !document.querySelector('select[aria-label="Провайдер"]')?.disabled);
  if (await provider.inputValue() !== "OLLAMA") throw new Error("Private Workbench is not configured for OLLAMA.");
  if (await model.inputValue() !== "qwen3:14b") throw new Error("Private Workbench is not configured for qwen3:14b.");
  await page.getByTestId("local-provider-note").waitFor();
  await pause(5000);

  const oldCount = await page.getByTestId("exchange").count();
  const input = page.getByRole("textbox", {name: "Новый запрос", exact: true});
  await input.fill("Ответь кратко: почему этот локальный AI-сервис остаётся приватным?");
  await pause(1500);
  await page.getByRole("button", {name: "Отправить", exact: true}).click();
  const exchange = page.getByTestId("exchange").nth(oldCount);
  await exchange.waitFor();
  await exchange.getByText("Завершено", {exact: true}).waitFor({timeout: 600_000});
  const responseCard = exchange.getByTestId("response-card");
  const content = (await responseCard.textContent())?.trim() ?? "";
  if (content.length < 20) throw new Error("Web chat returned an empty or unexpectedly short response.");
  await responseCard.scrollIntoViewIfNeeded();
  await pause(8000);

  const {checks} = report;
  await page.setContent(`<!doctype html>
    <html lang="ru"><head><meta charset="utf-8"><style>
      body{margin:0;background:#0b1020;color:#edf2ff;font:18px/1.32 Inter,system-ui,sans-serif;overflow:hidden}
      main{padding:30px 48px}.eyebrow{color:#74c0fc;font-weight:700;letter-spacing:.08em;text-transform:uppercase}
      h1{font-size:36px;margin:8px 0 6px}.url{font:21px ui-monospace,monospace;color:#a5d8ff;margin-bottom:20px}
      .grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.card{background:#151d33;border:1px solid #34405f;border-radius:14px;padding:18px}
      .card h2{font-size:20px;margin:0 0 9px;color:#bac8ff}.big{font-size:29px;font-weight:750;color:#69db7c}
      dl{display:grid;grid-template-columns:1fr auto;gap:6px 16px;margin:0}dt{color:#adb5bd}dd{margin:0;font-weight:650}
      footer{margin-top:12px;color:#868e96;font-size:15px}.ok{color:#69db7c}
    </style></head><body><main>
      <div class="eyebrow">Real local inference · authenticated TLS gateway</div>
      <h1>Приватный LLM-сервис проверен</h1>
      <div class="url">${escapeHtml(report.baseUrl)}</div>
      <div class="grid">
        <section class="card"><h2>HTTP API и streaming</h2><div class="big">PASS</div><dl>
          <dt>Без авторизации</dt><dd>HTTP ${escapeHtml(checks.authentication.unauthenticatedStatus)}</dd>
          <dt>Non-streaming</dt><dd>HTTP ${escapeHtml(checks.nonStreaming.status)}</dd>
          <dt>Streaming завершён</dt><dd>${checks.streaming.doneEvent ? "да" : "нет"}</dd>
          <dt>Модель</dt><dd>${escapeHtml(checks.versions.model)}</dd>
        </dl></section>
        <section class="card"><h2>Параллельная проверка</h2><div class="big">${escapeHtml(checks.parallel.successful)}/${escapeHtml(checks.parallel.requested)}</div><dl>
          <dt>Ошибки</dt><dd>${escapeHtml(checks.parallel.errors)}</dd>
          <dt>min / max</dt><dd>${escapeHtml(checks.parallel.minLatencyMs)} / ${escapeHtml(checks.parallel.maxLatencyMs)} ms</dd>
          <dt>p50 / p95</dt><dd>${escapeHtml(checks.parallel.p50LatencyMs)} / ${escapeHtml(checks.parallel.p95LatencyMs)} ms</dd>
        </dl></section>
        <section class="card"><h2>Rate limit</h2><div class="big">HTTP 429 × ${escapeHtml(checks.rateLimit.rejected429)}</div><dl>
          <dt>Политика</dt><dd>${escapeHtml(checks.rateLimit.advertised)}</dd>
          <dt>После окна</dt><dd>HTTP ${escapeHtml(checks.rateLimit.afterWindowStatus)}</dd>
          <dt>Gateway body limit</dt><dd>${escapeHtml(checks.gatewayRequestLimit.advertised)} · HTTP ${escapeHtml(checks.gatewayRequestLimit.status)}</dd>
        </dl></section>
        <section class="card"><h2>Контекст и версии</h2><dl>
          <dt>Ollama</dt><dd>${escapeHtml(checks.versions.ollama)}</dd>
          <dt>Metadata context</dt><dd>${escapeHtml(checks.context.metadataContextLength)}</dd>
          <dt>Runtime context</dt><dd>${escapeHtml(checks.context.runtimeContextLength)}</dd>
          <dt>Safe probe</dt><dd>${escapeHtml(checks.context.chosenSafeProbeLimit)}</dd>
          <dt>Above-limit</dt><dd>${escapeHtml(checks.context.above.observedBehavior)}</dd>
        </dl></section>
      </div>
      <footer><span class="ok">●</span> ${escapeHtml(report.networkEvidence)} · секреты не показаны и не записаны в отчёт</footer>
    </main></body></html>`, {waitUntil: "load"});
  await pause(14000);
} finally {
  await context.close();
  await browser.close();
}

const recorded = await video.path();
await rename(recorded, output);
await rm(temporary, {recursive: true, force: true});

const file = await stat(output);
if (file.size <= 0) throw new Error("Recorded WebM is empty.");
const containerBytes = await readFile(output);
const codec = ["V_VP8", "V_VP9", "V_AV1"].find((candidate) => containerBytes.includes(Buffer.from(candidate, "ascii")));
if (!codec) throw new Error("Recorded WebM does not contain a recognized video CodecID.");
const verifierBrowser = await chromium.launch({headless: true});
let metadata;
try {
  const verificationPage = await verifierBrowser.newPage();
  await verificationPage.goto(`file://${output}`, {waitUntil: "load"});
  await verificationPage.locator("video").waitFor({state: "attached"});
  metadata = await verificationPage.locator("video").evaluate((element) => new Promise((resolve, reject) => {
    const media = /** @type {HTMLVideoElement} */ (element);
    const read = () => resolve({duration: media.duration, width: media.videoWidth, height: media.videoHeight});
    if (media.readyState >= 1) read();
    else {
      media.addEventListener("loadedmetadata", read, {once: true});
      media.addEventListener("error", () => reject(new Error("Chromium could not read WebM metadata.")), {once: true});
    }
  }));
} finally {
  await verifierBrowser.close();
}
if (!Number.isFinite(metadata.duration) || metadata.duration <= 0 || metadata.width !== 1440 || metadata.height !== 900) {
  throw new Error(`Invalid WebM metadata: ${JSON.stringify(metadata)}`);
}
process.stdout.write(`${output}\n${JSON.stringify({...metadata, codec, size: file.size})}\n`);
