import {chromium} from "@playwright/test";
import {access, mkdir, rename, rm} from "node:fs/promises";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/rag-memory-chat-demo.webm");
const temporary = path.join(root, "video/.rag-memory-demo-recording");
const baseURL = process.env.RAG_DEMO_BASE_URL ?? "http://127.0.0.1:18080";
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

try {
  await access(output);
  if (process.env.RAG_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите RAG_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
  }
  await rm(output);
} catch (error) {
  if (error?.code !== "ENOENT" && !String(error?.message).includes("осознанной перезаписи")) throw error;
  if (String(error?.message).includes("осознанной перезаписи")) throw error;
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

async function waitReady() {
  await page.getByLabel("Режим ответа").waitFor({state: "visible"});
  await page.getByLabel("Режим ответа").waitFor({state: "attached"});
  await page.waitForFunction(() => !document.querySelector('select[aria-label="Режим ответа"]')?.disabled);
}

async function waitForSetting(name, expected) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const response = await page.request.get(`${baseURL}/api/state`);
    if (response.ok() && (await response.json()).settings[name] === expected) return;
    await pause(100);
  }
  throw new Error(`Настройка ${name} не приняла значение ${expected}`);
}

async function send(prompt, dwell = 2200) {
  const oldCount = await page.getByTestId("exchange").count();
  await page.getByRole("textbox", {name: "Новый запрос", exact: true}).fill(prompt);
  await pause(900);
  await page.getByRole("button", {name: "Отправить", exact: true}).click();
  await page.getByTestId("exchange").nth(oldCount).waitFor();
  await page.getByTestId("exchange").nth(oldCount).getByText("Завершено", {exact: true}).waitFor({timeout: 30_000});
  const card = page.getByTestId("exchange").nth(oldCount).getByTestId("response-card");
  await card.scrollIntoViewIfNeeded();
  await pause(dwell);
  return card;
}

try {
  await page.goto(baseURL, {waitUntil: "networkidle"});
  await waitReady();
  await pause(5000);

  await page.getByLabel("Режим ответа").selectOption("unrestricted");
  await waitForSetting("mode", "unrestricted");
  await waitReady();
  await page.getByLabel("Стратегия контекста").selectOption("MEMORY_LAYERS");
  await waitForSetting("contextStrategy", "MEMORY_LAYERS");
  await page.getByTestId("memory-layers").waitFor();
  await pause(2500);

  await page.getByLabel("RAG-чат с источниками").press("Space");
  await waitForSetting("assistantRagEnabled", true);
  await page.getByTestId("rag-settings").waitFor();
  await pause(6500);
  const recent = page.getByLabel("Сообщений в краткосрочной памяти");
  await recent.fill("4");
  await recent.press("Enter");
  await waitForSetting("recentMessagesLimit", 4);

  await page.getByLabel("Тип записи текущей задачи").selectOption({label: "Ограничение"});
  await page.getByLabel("Текст записи").fill("Сохранить JDK 21 и не использовать внешнюю сеть");
  await page.getByRole("button", {name: "Добавить запись"}).click();
  await page.locator('[data-layer="WORKING"]').getByText(/Сохранить JDK 21/).waitFor();
  await page.getByLabel("Тип записи текущей задачи").selectOption({label: "Термин"});
  await page.getByLabel("Текст записи").fill("Итог означает проверенный release checklist");
  await page.getByRole("button", {name: "Добавить запись"}).click();
  await pause(2500);

  const task = page.getByTestId("task-state-panel");
  await task.getByLabel("Цель задачи").fill("Подготовить release checklist");
  await task.getByLabel("Текущий шаг задачи").fill("Собрать подтверждённые шаги");
  await task.getByLabel("Ожидаемое следующее действие").fill("Продолжить список по источникам");
  await task.getByRole("button", {name: "Начать задачу", exact: true}).click();
  await task.getByText("Планирование").waitFor();
  await pause(3500);

  await send("Каков первый шаг release checklist?", 3500);
  await send("Продолжай", 3500);
  await send("А второй вариант?", 3500);
  await send("Какие проверки входят в release gate?", 4000);
  await send("Какое ограничение нужно сохранить?", 4000);
  await send("Вернись к цели и продолжай по источникам", 4000);
  await send("Кстати, что означает restore drill?", 3000);
  const summary = await send("Учти прежнее ограничение, продолжай и собери итог", 4500);
  await summary.getByTestId("rag-evidence").scrollIntoViewIfNeeded();
  await pause(4500);
  await summary.getByTestId("rag-sources").scrollIntoViewIfNeeded();
  await pause(7000);

  await page.locator('[data-layer="SHORT_TERM"]').scrollIntoViewIfNeeded();
  await pause(1800);
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", {name: "Новый диалог", exact: true}).click();
  await page.locator('[data-layer="SHORT_TERM"] .memory-entry').waitFor({state: "detached"});
  await page.locator('[data-layer="WORKING"]').scrollIntoViewIfNeeded();
  await pause(3500);
  await task.scrollIntoViewIfNeeded();
  await pause(3500);

  await send("Продолжай", 3500);
  const abstention = await send("Продолжай с неизвестным контекстом [[rag-abstain]]", 3500);
  await abstention.getByTestId("rag-evidence").scrollIntoViewIfNeeded();
  await pause(24000);
} finally {
  await context.close();
  await browser.close();
}

const recorded = await video.path();
await rename(recorded, output);
await rm(temporary, {recursive: true, force: true});
process.stdout.write(`${output}\n`);
