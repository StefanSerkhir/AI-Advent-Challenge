import {chromium} from "@playwright/test";
import {access, mkdir, rename, rm, stat} from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import {pathToFileURL} from "node:url";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/local-llm-demo.webm");
const temporary = path.join(root, "video/.local-llm-demo-recording");
const baseURL = process.env.LOCAL_LLM_DEMO_BASE_URL ?? "http://127.0.0.1:18081";
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

const prompts = [
  {
    complexity: "Простой запрос",
    text: "Ответь одним коротким предложением: что такое локальная LLM?",
    dwell: 7000,
  },
  {
    complexity: "Структурированный запрос",
    text: "У магазина есть 3 коробки по 12 яблок. Продали 17 яблок. Сколько осталось? Дай краткое вычисление и итог.",
    dwell: 8000,
  },
  {
    complexity: "Сложный запрос с кодом",
    text: "Напиши на Kotlin функцию `fun uniqueSorted(values: List<Int>): List<Int>`, которая удаляет повторы и возвращает числа по возрастанию. Объясни сложность и приведи два граничных примера.",
    dwell: 12000,
  },
];

try {
  await access(output);
  if (process.env.LOCAL_LLM_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите LOCAL_LLM_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
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
page.setDefaultTimeout(600_000);

async function send({complexity, text, dwell}) {
  const oldCount = await page.getByTestId("exchange").count();
  const input = page.getByRole("textbox", {name: "Новый запрос", exact: true});
  await input.fill(text);
  await pause(1800);
  await page.getByRole("button", {name: "Отправить", exact: true}).click();
  const exchange = page.getByTestId("exchange").nth(oldCount);
  await exchange.waitFor();
  await exchange.getByText("Завершено", {exact: true}).waitFor({timeout: 600_000});
  const card = exchange.getByTestId("response-card");
  const content = (await card.textContent())?.trim() ?? "";
  if (content.length < 20) throw new Error(`${complexity}: получен пустой или слишком короткий ответ.`);
  await card.scrollIntoViewIfNeeded();
  await pause(dwell);
}

try {
  await page.goto(baseURL, {waitUntil: "networkidle", timeout: 120_000});
  const provider = page.getByLabel("Провайдер", {exact: true});
  const model = page.getByLabel("Модель", {exact: true});
  await provider.waitFor({state: "visible"});
  await page.waitForFunction(() => !document.querySelector('select[aria-label="Провайдер"]')?.disabled);
  if (await provider.inputValue() !== "OLLAMA") throw new Error("Web runtime запущен не с провайдером OLLAMA.");
  if (await model.inputValue() !== "qwen3:14b") throw new Error("Web runtime запущен не с моделью qwen3:14b.");
  await page.getByTestId("local-provider-note").waitFor();
  await pause(7000);

  for (const prompt of prompts) await send(prompt);
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
    const video = /** @type {HTMLVideoElement} */ (element);
    const read = () => resolve({duration: video.duration, width: video.videoWidth, height: video.videoHeight});
    if (video.readyState >= 1) read();
    else {
      video.addEventListener("loadedmetadata", read, {once: true});
      video.addEventListener("error", () => reject(new Error("Chromium не смог прочитать WebM metadata.")), {once: true});
    }
  }));
} finally {
  await verifier.close();
}
if (!Number.isFinite(metadata.duration) || metadata.duration <= 0 || metadata.width !== 1440 || metadata.height !== 900) {
  throw new Error(`Некорректные WebM metadata: ${JSON.stringify(metadata)}`);
}
process.stdout.write(`${output}\n${JSON.stringify({...metadata, size: file.size})}\n`);
