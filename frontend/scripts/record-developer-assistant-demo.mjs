import {chromium} from "@playwright/test";
import {access, mkdir, rename, rm, stat} from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import {pathToFileURL} from "node:url";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/developer-assistant-demo.webm");
const temporary = path.join(root, "video/.developer-assistant-demo-recording");
const baseURL = process.env.DEVELOPER_ASSISTANT_DEMO_BASE_URL ?? "http://127.0.0.1:18080";
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

try {
  await access(output);
  if (process.env.DEVELOPER_ASSISTANT_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите DEVELOPER_ASSISTANT_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
  }
  await rm(output);
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
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

async function readState() {
  const response = await context.request.get(`${baseURL}/api/state`);
  if (!response.ok()) throw new Error(`GET /api/state: ${response.status()}`);
  return response.json();
}

async function waitReady() {
  await page.getByLabel("Режим ответа").waitFor({state: "visible"});
  await page.waitForFunction(() => !document.querySelector('select[aria-label="Режим ответа"]')?.disabled);
}

async function waitForMode(expected) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if ((await readState()).settings.mode === expected) return;
    await pause(100);
  }
  throw new Error(`Режим ${expected} не был сохранён.`);
}

async function send(prompt, dwell = 4000) {
  const oldCount = await page.getByTestId("exchange").count();
  await page.getByRole("textbox", {name: "Новый запрос", exact: true}).fill(prompt);
  await pause(1200);
  await page.getByRole("button", {name: "Отправить", exact: true}).click();
  const exchange = page.getByTestId("exchange").nth(oldCount);
  await exchange.waitFor();
  await exchange.getByText("Завершено", {exact: true}).waitFor({timeout: 30_000});
  const card = exchange.getByTestId("response-card");
  await card.scrollIntoViewIfNeeded();
  await pause(dwell);
  return card;
}

try {
  await page.goto(baseURL, {waitUntil: "networkidle"});
  await waitReady();
  await page.getByLabel("Режим ответа").selectOption("unrestricted");
  await waitForMode("unrestricted");
  await page.getByTestId("developer-help-hint").scrollIntoViewIfNeeded();
  await pause(5500);

  await send("/help", 5000);

  const main = await send(
    "/help Объясни архитектуру проекта, как запустить web-интерфейс и на какой Git-ветке я сейчас нахожусь?",
    5500,
  );
  const state = await readState();
  const outputState = state.exchanges.at(-1)?.outputs?.[0];
  if (state.exchanges.at(-1)?.status !== "completed" ||
      outputState?.ragDiagnostics?.pipeline !== "developer_help" ||
      outputState?.ragDiagnostics?.evidence?.status !== "verified" ||
      !outputState?.content?.includes("fixture-main") ||
      outputState?.mcpCalls?.length !== 1 ||
      outputState.mcpCalls[0].serverId !== "workspace" ||
      outputState.mcpCalls[0].toolName !== "git_current_branch" ||
      outputState.mcpCalls[0].status !== "success" ||
      !outputState.mcpCalls[0].result.includes('"branch":"fixture-main"')) {
    throw new Error("Fixture не подтвердил grounded developer-help и git_current_branch diagnostics.");
  }
  await main.getByTestId("mcp-diagnostics").scrollIntoViewIfNeeded();
  await pause(6000);
  await main.getByTestId("rag-evidence").scrollIntoViewIfNeeded();
  await pause(6500);
  await main.getByTestId("rag-sources").scrollIntoViewIfNeeded();
  await pause(6500);

  const second = await send("/help Где находится production entry point?", 4500);
  await second.getByTestId("rag-evidence").scrollIntoViewIfNeeded();
  await pause(6500);
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
