import {chromium} from "@playwright/test";
import process from "node:process";

if (process.env.ALLOW_PAID_OPENAI_RAG_SCENARIOS !== "1") {
  throw new Error("Платный прогон заблокирован. Укажите ALLOW_PAID_OPENAI_RAG_SCENARIOS=1 после осознанного подтверждения 20 UI submissions и 20 query-embedding calls.");
}
if (process.env.ALLOW_PERSISTENT_RAG_SCENARIO_MUTATIONS !== "1") {
  throw new Error("Прогон временно меняет .env, SHORT_TERM, WORKING и task state. Укажите ALLOW_PERSISTENT_RAG_SCENARIO_MUTATIONS=1; скрипт откажется запускаться при непустом пользовательском состоянии и очистит только созданные им данные.");
}

const baseURL = process.env.RAG_REAL_BASE_URL ?? "http://127.0.0.1:8080";
const headers = {"X-Workbench-Request": "1", "Content-Type": "application/json"};
const browser = await chromium.launch({headless: process.env.RAG_REAL_HEADLESS === "1"});
const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
const page = await context.newPage();
const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function state() {
  const response = await context.request.get(`${baseURL}/api/state`);
  if (!response.ok()) throw new Error(`GET /api/state: ${response.status()}`);
  return response.json();
}

async function command(method, pathname, data) {
  const response = await context.request.fetch(`${baseURL}${pathname}`, {method, headers, data});
  if (!response.ok()) {
    const problem = await response.text();
    throw new Error(`${method} ${pathname}: ${response.status()} ${problem.slice(0, 400)}`);
  }
  return response.json();
}

async function clearLayer(layer) {
  const current = await state();
  return command("POST", "/api/assistant/memory/clear", {
    expectedSettingsVersion: current.settingsVersion,
    layer,
  });
}

async function addWorking(text) {
  const current = await state();
  return command("POST", "/api/assistant/memory", {
    expectedSettingsVersion: current.settingsVersion,
    layer: "WORKING",
    text,
  });
}

async function startTask(goal, currentStep, expectedAction) {
  const current = await state();
  return command("POST", "/api/assistant/task-state/start", {
    expectedSettingsVersion: current.settingsVersion,
    goal,
    currentStep,
    expectedAction,
  });
}

async function resetTask() {
  const current = await state();
  if (!current.taskState) return current;
  return command("POST", "/api/assistant/task-state/reset", {
    expectedSettingsVersion: current.settingsVersion,
  });
}

async function newDialogue() {
  const current = await state();
  return command("POST", "/api/assistant/dialogue/new", {
    expectedSettingsVersion: current.settingsVersion,
  });
}

async function waitForTerminalExchange(exchangeIndex, turnIndex) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const current = await state();
    const exchange = current.exchanges[exchangeIndex];
    if (exchange?.status === "completed") return {current, exchange};
    if (exchange?.status === "failed" || exchange?.status === "cancelled") {
      throw new Error(`Ход ${turnIndex}: ${exchange.status}: ${exchange.error ?? "без описания"}`);
    }
    await wait(500);
  }
  throw new Error(`Ход ${turnIndex}: операция не завершилась за 180 секунд`);
}

async function sendAndVerify(prompt, expectedGoal, expectedWorkingFragments, index) {
  const count = await page.getByTestId("exchange").count();
  await page.getByRole("textbox", {name: "Новый запрос", exact: true}).fill(prompt);
  await page.getByRole("button", {name: "Отправить", exact: true}).click();
  const exchange = page.getByTestId("exchange").nth(count);
  const {current, exchange: wireExchange} = await waitForTerminalExchange(count, index);
  await exchange.getByText("Завершено", {exact: true}).waitFor({timeout: 10_000});
  const output = wireExchange?.outputs?.[0];
  if (wireExchange?.status !== "completed" || !output?.ragDiagnostics) {
    throw new Error(`Ход ${index}: операция не завершилась RAG-результатом`);
  }
  const diagnostics = output.ragDiagnostics;
  if (!diagnostics.retrievalQuery || diagnostics.candidateCount < 1) {
    throw new Error(`Ход ${index}: retrieval не подтверждён diagnostics`);
  }
  const evidenceAccepted = diagnostics.abstained
    ? diagnostics.evidence.status === "not_applicable" && diagnostics.evidence.sources.length === 0
    : diagnostics.evidence.status === "verified" && diagnostics.evidence.sources.length > 0 &&
      diagnostics.evidence.sources.every((source) => source.quotes.length > 0);
  if (!evidenceAccepted) throw new Error(`Ход ${index}: evidence postflight не подтверждён`);
  if (current.taskState?.goal !== expectedGoal) throw new Error(`Ход ${index}: активная цель потеряна`);
  const working = current.assistantMemory.layers.find((layer) => layer.layer === "WORKING");
  for (const fragment of expectedWorkingFragments) {
    if (!working?.entries.some((entry) => entry.text.includes(fragment))) {
      throw new Error(`Ход ${index}: WORKING-контекст потерян: ${fragment}`);
    }
    if (!diagnostics.retrievalQuery.includes(fragment)) {
      throw new Error(`Ход ${index}: contextual query не содержит WORKING-контекст: ${fragment}`);
    }
  }
  if (!diagnostics.retrievalQuery.includes(expectedGoal)) {
    throw new Error(`Ход ${index}: contextual query не содержит active goal`);
  }
  process.stdout.write(`turn ${index}: ${diagnostics.abstained ? "abstained" : "verified"}, sources=${diagnostics.evidence.sources.length}\n`);
  return diagnostics.evidence.sources.map((source) => source.chunkId);
}

const scenarioOne = [
  "Какая команда запускает production web-приложение?",
  "Продолжай и назови entry point.",
  "А второй вариант запуска для разработки?",
  "Учти прежнее ограничение и перечисли обязательные версии JDK и Node.js.",
  "Какие компоненты отвечают за REST и SSE?",
  "Отвлекающий вопрос: что в документации сказано о PDF?",
  "Вернись к цели и продолжай.",
  "Что происходит с SHORT_TERM при ошибке ответа?",
  "Какие проверки нужны перед показом?",
  "Продолжай и собери итог.",
];

const scenarioTwo = [
  "Как устроен structure-aware документный индекс?",
  "Продолжай и назови descriptor embedding.",
  "Какие metadata сохраняет chunk?",
  "Учти прежнее ограничение: не включай vectors в wire.",
  "А threshold применяется до или после candidate retrieval?",
  "Каким должен быть abstention?",
  "Продолжай.",
  "Как backend проверяет exact quote?",
  "Что происходит при неизвестной citation?",
  "Продолжай и собери итог по индексации.",
];

let originalSettings;
let ownsScenarioState = false;
try {
  await page.goto(baseURL, {waitUntil: "networkidle"});
  const initial = await state();
  const openAI = initial.providers.find((provider) => provider.id === "OPENAI");
  if (!openAI?.hasKey) throw new Error("OpenAI key не настроен в masked production state.");
  const shortTerm = initial.assistantMemory.layers.find((layer) => layer.layer === "SHORT_TERM")?.count ?? 0;
  const working = initial.assistantMemory.layers.find((layer) => layer.layer === "WORKING")?.count ?? 0;
  if (shortTerm !== 0 || working !== 0 || initial.taskState) {
    throw new Error("Прогон требует пустые SHORT_TERM, WORKING и task state, чтобы не удалять пользовательские данные.");
  }
  originalSettings = initial.settings;
  await command("PUT", "/api/settings", {
    expectedSettingsVersion: initial.settingsVersion,
    settings: {
      ...initial.settings,
      provider: "OPENAI",
      mode: "unrestricted",
      contextStrategy: "MEMORY_LAYERS",
      assistantRagEnabled: true,
      recentMessagesLimit: 4,
      ragCandidateLimit: 10,
      ragResultLimit: 5,
      ragMinSimilarity: 0.2,
      maxTokens: Math.max(initial.settings.maxTokens, 700),
    },
  });
  ownsScenarioState = true;

  const goalOne = "Подготовить production-like runbook web-приложения";
  const workingOne = ["JDK 21", "release gate"];
  const scenarioOneSources = new Set();
  await addWorking("Ограничение: использовать только JDK 21 и локальные команды репозитория");
  await addWorking("Термин: release gate означает полный набор unit, API и browser-проверок");
  await startTask(goalOne, "Собрать подтверждённые команды", "Сформировать runbook по источникам");
  for (let index = 0; index < scenarioOne.length; index++) {
    for (const source of await sendAndVerify(scenarioOne[index], goalOne, workingOne, index + 1)) {
      scenarioOneSources.add(source);
    }
  }

  await newDialogue();
  await clearLayer("WORKING");
  await resetTask();

  const goalTwo = "Подготовить политику документного RAG и evidence postflight";
  const workingTwo = ["vectors", "grounded result"];
  const scenarioTwoSources = new Set();
  await addWorking("Ограничение: vectors и полный текст chunks не должны попадать в REST/SSE");
  await addWorking("Термин: grounded result означает verified citations и exact quotes");
  await startTask(goalTwo, "Проверить retrieval contract", "Собрать итог по индексации");
  for (let index = 0; index < scenarioTwo.length; index++) {
    if (index === 3) {
      await addWorking("Уточнение: metadata источников разрешена только после backend verification");
      workingTwo.push("backend verification");
    }
    if (index === 5) {
      await newDialogue();
      const afterClear = await state();
      const short = afterClear.assistantMemory.layers.find((layer) => layer.layer === "SHORT_TERM");
      const work = afterClear.assistantMemory.layers.find((layer) => layer.layer === "WORKING");
      if (short?.count !== 0 || afterClear.taskState?.goal !== goalTwo || work?.count !== 3) {
        throw new Error("Новый диалог нарушил task goal или WORKING state");
      }
    }
    for (const source of await sendAndVerify(scenarioTwo[index], goalTwo, workingTwo, scenarioOne.length + index + 1)) {
      scenarioTwoSources.add(source);
    }
  }
  if (![...scenarioTwoSources].some((source) => !scenarioOneSources.has(source))) {
    throw new Error("Сценарий 2 не подтвердил отличный от сценария 1 набор источников");
  }
  process.stdout.write(`RAG scenario runner completed against ${baseURL}: 20 UI submissions, 20 query-embedding calls\n`);
} finally {
  if (ownsScenarioState) {
    await newDialogue().catch(() => {});
    await clearLayer("WORKING").catch(() => {});
    await resetTask().catch(() => {});
    if (originalSettings) {
      const current = await state().catch(() => null);
      if (current) {
        await command("PUT", "/api/settings", {
          expectedSettingsVersion: current.settingsVersion,
          settings: originalSettings,
        }).catch(() => {});
      }
    }
  }
  await context.close();
  await browser.close();
}
