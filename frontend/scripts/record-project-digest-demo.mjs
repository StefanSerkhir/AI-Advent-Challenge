import {chromium} from "@playwright/test";
import {spawn, spawnSync} from "node:child_process";
import {access, mkdir, rename, rm, stat} from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import {pathToFileURL} from "node:url";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/project-digest-demo.webm");
const temporary = path.join(root, "video/.project-digest-demo-recording");
const port = 18080;
const baseURL = `http://127.0.0.1:${port}`;
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

try {
  await access(output);
  if (process.env.PROJECT_DIGEST_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите PROJECT_DIGEST_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
  }
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
}

const listening = spawnSync("lsof", ["-nP", `-iTCP@127.0.0.1:${port}`, "-sTCP:LISTEN"], {encoding: "utf8"});
if (listening.error) throw new Error(`Не удалось проверить порт ${port} через lsof: ${listening.error.message}`);
if (listening.status === 0 && listening.stdout.trim()) {
  throw new Error(`Порт ${port} уже занят. Неизвестный процесс не был остановлен:\n${listening.stdout.trim()}`);
}
if (listening.status !== 0 && listening.status !== 1) {
  throw new Error(`lsof завершился с кодом ${listening.status}: ${listening.stderr.trim()}`);
}

await rm(temporary, {recursive: true, force: true});
await mkdir(temporary, {recursive: true});
await mkdir(path.dirname(output), {recursive: true});

const gradle = path.join(root, process.platform === "win32" ? "gradlew.bat" : "gradlew");
const fixture = spawn(gradle, ["--console=plain", "runWebFixture", `-PwebPort=${port}`], {
  cwd: root,
  env: {...process.env},
  stdio: ["ignore", "pipe", "pipe"],
});
let fixtureOutput = "";
fixture.stdout.on("data", (chunk) => { fixtureOutput = (fixtureOutput + chunk).slice(-12_000); });
fixture.stderr.on("data", (chunk) => { fixtureOutput = (fixtureOutput + chunk).slice(-12_000); });

async function waitForFixture() {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    if (fixture.exitCode !== null) throw new Error(`runWebFixture завершился раньше времени:\n${fixtureOutput}`);
    try {
      const response = await fetch(`${baseURL}/api/state`);
      if (response.ok) return;
    } catch {
      // Fixture is still compiling or starting.
    }
    await pause(300);
  }
  throw new Error(`runWebFixture не запустился за 180 секунд:\n${fixtureOutput}`);
}

async function stopFixture() {
  if (fixture.exitCode !== null) return;
  fixture.kill("SIGTERM");
  await Promise.race([
    new Promise((resolve) => fixture.once("exit", resolve)),
    pause(10_000).then(() => fixture.kill("SIGKILL")),
  ]);
}

let browser;
let context;
let video;
let recorded;
let recordingError;
try {
  await waitForFixture();
  browser = await chromium.launch({headless: true});
  context = await browser.newContext({
    viewport: {width: 1440, height: 900},
    recordVideo: {dir: temporary, size: {width: 1440, height: 900}},
  });
  const page = await context.newPage();
  video = page.video();

  async function readState() {
    const response = await context.request.get(`${baseURL}/api/state`);
    if (!response.ok()) throw new Error(`GET /api/state: ${response.status()}`);
    return response.json();
  }

  await page.goto(baseURL, {waitUntil: "networkidle"});
  await page.getByLabel("Режим ответа").waitFor({state: "visible"});
  await page.waitForFunction(() => !document.querySelector('select[aria-label="Режим ответа"]')?.disabled);
  await page.getByLabel("Провайдер", {exact: true}).selectOption("OPENAI");
  await page.getByLabel("Режим ответа").selectOption("unrestricted");
  await page.getByTestId("project-digest-hint").scrollIntoViewIfNeeded();
  await pause(2500);

  const composer = page.getByRole("textbox", {name: "Новый запрос", exact: true});
  await composer.fill("/digest 24");
  await pause(2200);
  await page.getByRole("button", {name: "Отправить", exact: true}).click();
  const exchange = page.getByTestId("exchange").last();
  const card = exchange.getByTestId("response-card");
  const facts = card.getByTestId("project-digest-diagnostics");
  await facts.waitFor({state: "visible", timeout: 30_000});
  await facts.scrollIntoViewIfNeeded();
  await pause(4200);
  await exchange.getByText("Завершено", {exact: true}).waitFor({timeout: 30_000});

  const state = await readState();
  const outputState = state.exchanges.at(-1)?.outputs?.[0];
  if (state.exchanges.at(-1)?.status !== "completed" ||
      outputState?.title !== "AI-СВОДКА РАЗРАБОТКИ" ||
      outputState?.projectDigestDiagnostics?.schemaVersion !== 1 ||
      outputState.projectDigestDiagnostics.sinceHours !== 24 ||
      outputState.projectDigestDiagnostics.branch !== "fixture-main" ||
      outputState.projectDigestDiagnostics.commitCount !== 2 ||
      outputState.projectDigestDiagnostics.workingTreeDirty !== true ||
      outputState?.mcpCalls?.length !== 1 ||
      outputState.mcpCalls[0].serverId !== "workspace" ||
      outputState.mcpCalls[0].toolName !== "git_activity_report" ||
      outputState.mcpCalls[0].status !== "success" ||
      !outputState.content?.includes("## Источники") ||
      !outputState.content?.includes("[C1]") ||
      outputState.mcpCalls[0].result.includes("fixtureProjectApi.ts")) {
    throw new Error("Fixture не подтвердил сквозную grounded AI-сводку и безопасную диагностику.");
  }

  await card.getByTestId("mcp-diagnostics").scrollIntoViewIfNeeded();
  await pause(2500);
  await card.getByTestId("mcp-diagnostics").locator("details").click();
  await pause(3500);
  await card.getByText("AI-сводка по проверенным фактам", {exact: true}).scrollIntoViewIfNeeded();
  await pause(3500);
  await card.getByText("Источники", {exact: true}).last().scrollIntoViewIfNeeded();
  await pause(4500);
} catch (error) {
  recordingError = error;
} finally {
  try {
    if (context) await context.close();
  } catch (error) {
    recordingError ??= error;
  }
  try {
    if (browser) await browser.close();
  } catch (error) {
    recordingError ??= error;
  }
  try {
    await stopFixture();
  } catch (error) {
    recordingError ??= error;
  }
}
if (recordingError) {
  await rm(temporary, {recursive: true, force: true});
  throw recordingError;
}

try {
  recorded = await video.path();
  const file = await stat(recorded);
  if (file.size <= 0) throw new Error("Записанный WebM имеет нулевой размер.");

  const verifier = await chromium.launch({headless: true});
  let metadata;
  try {
    const verificationPage = await verifier.newPage();
    await verificationPage.goto(pathToFileURL(recorded).href, {waitUntil: "load"});
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
  await rename(recorded, output);
  await rm(temporary, {recursive: true, force: true});
  process.stdout.write(`${output}\n${JSON.stringify({...metadata, size: file.size, videoStream: true, inference: "fixture/fake"})}\n`);
} catch (error) {
  await rm(temporary, {recursive: true, force: true});
  throw error;
}
