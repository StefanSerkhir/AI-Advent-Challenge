import {chromium} from "@playwright/test";
import {access, mkdir, rename, rm, stat} from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import {pathToFileURL} from "node:url";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/support-assistant-demo.webm");
const temporary = path.join(root, "video/.support-assistant-demo-recording");
const baseURL = process.env.SUPPORT_ASSISTANT_DEMO_BASE_URL ?? "http://127.0.0.1:18080";
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

try {
  await access(output);
  if (process.env.SUPPORT_ASSISTANT_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите SUPPORT_ASSISTANT_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
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

async function waitForSettings() {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const settings = (await readState()).settings;
    if (settings.mode === "unrestricted" && settings.provider === "OPENAI") return;
    await pause(100);
  }
  throw new Error("OpenAI + unrestricted не были сохранены.");
}

async function send(prompt, dwell = 3500) {
  const oldCount = await page.getByTestId("exchange").count();
  await page.getByRole("textbox", {name: "Новый запрос", exact: true}).fill(prompt);
  await pause(900);
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
  await page.getByLabel("Провайдер", {exact: true}).selectOption("OPENAI");
  await page.getByLabel("Режим ответа").selectOption("unrestricted");
  await waitForSettings();
  await page.getByTestId("support-assistant-hint").scrollIntoViewIfNeeded();
  await pause(3500);

  await send("/support", 3500);
  const card = await send("/support TCK-1001 Почему не работает авторизация?", 5000);
  const state = await readState();
  const exchange = state.exchanges.at(-1);
  const outputState = exchange?.outputs?.[0];
  const expectedCall = outputState?.mcpCalls?.[0];
  if (exchange?.status !== "completed" ||
      outputState?.ragDiagnostics?.pipeline !== "support_assistant" ||
      outputState?.ragDiagnostics?.evidence?.status !== "verified" ||
      outputState?.mcpCalls?.length !== 1 ||
      expectedCall?.serverId !== "support" ||
      expectedCall?.toolName !== "support_get_ticket_context" ||
      expectedCall?.status !== "success" ||
      !outputState?.content?.includes("TCK-1001") ||
      !outputState?.content?.includes("AUTH_CLOCK_SKEW") ||
      !outputState?.content?.includes("742") ||
      !outputState?.content?.includes("синхрониз")) {
    throw new Error("Fixture не подтвердил grounded support answer, MCP provenance и verified evidence.");
  }
  await card.getByTestId("mcp-diagnostics").scrollIntoViewIfNeeded();
  await pause(4500);
  await card.getByTestId("rag-evidence").scrollIntoViewIfNeeded();
  await pause(5000);
  await card.getByTestId("rag-sources").scrollIntoViewIfNeeded();
  await pause(5000);
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
