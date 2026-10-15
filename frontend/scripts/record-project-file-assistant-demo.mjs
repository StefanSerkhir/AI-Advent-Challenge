import {chromium} from "@playwright/test";
import {spawn, spawnSync} from "node:child_process";
import {access, mkdir, rename, rm, stat} from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import {pathToFileURL} from "node:url";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/project-file-assistant-demo.webm");
const temporary = path.join(root, "video/.project-file-assistant-demo-recording");
const port = 18080;
const baseURL = `http://127.0.0.1:${port}`;
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

try {
  await access(output);
  if (process.env.PROJECT_FILE_ASSISTANT_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите PROJECT_FILE_ASSISTANT_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
  }
  await rm(output);
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

  async function waitForSettings() {
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
      const settings = (await readState()).settings;
      if (settings.mode === "unrestricted" && settings.provider === "OPENAI") return;
      await pause(100);
    }
    throw new Error("OpenAI + Простой агент не были сохранены.");
  }

  async function send(prompt, dwell = 4000) {
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

  await page.goto(baseURL, {waitUntil: "networkidle"});
  await page.getByLabel("Режим ответа").waitFor({state: "visible"});
  await page.waitForFunction(() => !document.querySelector('select[aria-label="Режим ответа"]')?.disabled);
  await page.getByLabel("Провайдер", {exact: true}).selectOption("OPENAI");
  await page.getByLabel("Режим ответа").selectOption("unrestricted");
  await waitForSettings();
  await pause(3500);

  const analysis = await send(
    "Найди все места использования API FixtureProjectApi в проекте, прочитай связанные файлы и объясни путь вызова от Kotlin до TypeScript. Ничего не изменяй.",
    5500,
  );
  let state = await readState();
  let outputState = state.exchanges.at(-1)?.outputs?.[0];
  if (state.exchanges.at(-1)?.status !== "completed" ||
      outputState?.mcpCalls?.map((call) => call.toolName).join(",") !== "project_search,project_read_files" ||
      outputState.mcpCalls.some((call) => call.serverId !== "workspace" || call.status !== "success") ||
      outputState.mcpCalls.some((call) => call.toolName === "project_write_file") ||
      outputState.mcpCalls[0].result.includes("snippet") ||
      outputState.mcpCalls[1].result.includes("content") ||
      !outputState.content.includes("src/main/kotlin/fixture/FixtureProjectApi.kt") ||
      !outputState.content.includes("frontend/src/api/fixtureProjectApi.ts") ||
      !outputState.content.includes("Ничего не изменено")) {
    throw new Error("Fixture не подтвердил безопасный двухшаговый анализ project files.");
  }
  await analysis.getByTestId("mcp-diagnostics").scrollIntoViewIfNeeded();
  await pause(5500);

  const update = await send(
    "Проверь, соответствует ли документация FixtureProjectApi фактической реализации. Если она устарела, обнови её и покажи diff.",
    6500,
  );
  state = await readState();
  outputState = state.exchanges.at(-1)?.outputs?.[0];
  if (state.exchanges.at(-1)?.status !== "completed" ||
      outputState?.mcpCalls?.map((call) => call.toolName).join(",") !==
        "project_search,project_read_files,project_write_file" ||
      outputState.mcpCalls.length !== 3 ||
      outputState.mcpCalls.some((call) => call.serverId !== "workspace" || call.status !== "success") ||
      outputState.mcpCalls[1].result.includes("Outdated") ||
      outputState.mcpCalls[2].arguments.includes("GET /api/fixture-project") ||
      outputState.mcpCalls[2].result.includes('"diff"') ||
      !outputState.mcpCalls[2].result.includes('"applied":true') ||
      !outputState.content.includes("docs/fixture-project-api.md") ||
      !outputState.content.includes("```diff") ||
      !outputState.content.includes("POST") ||
      !outputState.content.includes("GET")) {
    throw new Error("Fixture не подтвердил search → batch read → atomic write и проверяемый diff.");
  }
  await update.getByTestId("mcp-diagnostics").scrollIntoViewIfNeeded();
  await pause(6000);
  await update.getByText("Изменённый файл: docs/fixture-project-api.md", {exact: false}).scrollIntoViewIfNeeded();
  await pause(6500);
} finally {
  if (context) await context.close();
  if (browser) await browser.close();
  await stopFixture();
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
process.stdout.write(`${output}\n${JSON.stringify({...metadata, size: file.size, videoStream: true})}\n`);
