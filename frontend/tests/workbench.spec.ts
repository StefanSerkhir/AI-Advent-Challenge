import {expect, type Page, test} from "@playwright/test";
import type {Mode, State} from "../src/api/types";

const headers = {
  "X-Workbench-Request": "1",
  "Content-Type": "application/json",
};
async function ready(page: Page) {
  await expect(page.getByLabel("Режим ответа")).toBeEnabled();
}
async function setMode(page: Page, mode: Mode) {
  await ready(page);
  await page.getByLabel("Режим ответа").selectOption(mode);
  await expect
    .poll(
      async () =>
        ((await (await page.request.get("/api/state")).json()) as State)
          .settings.mode,
    )
    .toBe(mode);
  await ready(page);
}
async function send(page: Page, prompt: string, shortcut?: string) {
  const oldCount = await page.getByTestId("exchange").count();
  await page
    .getByRole("textbox", { name: "Новый запрос", exact: true })
    .fill(prompt);
  if (shortcut)
    await page
      .getByRole("textbox", { name: "Новый запрос", exact: true })
      .press(shortcut);
  else
    await page.getByRole("button", { name: "Отправить", exact: true }).click();
  await expect(page.getByTestId("exchange")).toHaveCount(oldCount + 1);
}
async function done(page: Page) {
  await expect(
    page.getByTestId("exchange").last().getByText("Завершено", { exact: true }),
  ).toBeVisible();
  await ready(page);
}

test.beforeEach(async ({ page }) => {
  const state: State = await (await page.request.get("/api/state")).json();
  if (state.operation) {
    await page.request.post(`/api/operations/${state.operation.id}/cancel`, {
      headers,
      data: {},
    });
    await expect
      .poll(
        async () =>
          (await (await page.request.get("/api/state")).json()).operation,
      )
      .toBeNull();
  }
  await page.request.put("/api/settings", {
    headers,
    data: {
      expectedSettingsVersion: state.settingsVersion,
      settings: {
        ...state.settings,
        mode: "compare",
        provider: "OPENAI",
        maxTokens: 300,
        maxWords: 60,
        bulletCount: 3,
        historyEnabled: true,
        contextManagementEnabled: false,
        recentMessagesLimit: 10,
        summarizationBatchSize: 10,
      },
    },
  });
  await page.request.delete("/api/results", { headers, data: {} });
  await page.request.delete("/api/history", { headers, data: {} });
  await page.request.delete("/api/notice", { headers, data: {} });
  await page.goto("/");
  await ready(page);
});

test("settings are shown only when the selected mode uses them", async ({
  page,
}) => {
  await setMode(page, "unrestricted");
  await expect(page.getByText("Контекст", { exact: true })).toBeVisible();
  await expect(page.getByLabel("История диалога")).toBeVisible();
  await expect(page.locator(".history-counts span")).toHaveCount(1);
  await expect(page.locator(".history-counts span")).toContainText(
    "Простой агент",
  );
  await expect(page.getByLabel("Максимум токенов")).toBeVisible();
  await expect(page.getByLabel("Переполнение контекста")).toBeVisible();
  await expect(page.getByLabel("Управление контекстом")).not.toBeChecked();
  await expect(page.getByLabel("Последних сообщений без изменений")).toHaveCount(0);
  await page.getByLabel("Управление контекстом").click();
  await expect(page.getByLabel("Последних сообщений без изменений")).toHaveValue("10");
  await expect(page.getByLabel("Сжимать каждые N сообщений")).toHaveValue("10");
  await expect(page.getByLabel("Максимум слов")).toHaveCount(0);
  await expect(
    page.locator(".history-counts").getByText("С ограничениями", {
      exact: true,
    }),
  ).toHaveCount(0);

  await setMode(page, "reasoning");
  await expect(page.getByText("Контекст", { exact: true })).toHaveCount(0);
  await expect(page.getByLabel("История диалога")).toHaveCount(0);
  await expect(page.getByLabel("Управление контекстом")).toHaveCount(0);
  await expect(page.getByLabel("Максимум токенов")).toHaveCount(0);

  await setMode(page, "models");
  await expect(page.getByLabel("Максимум токенов")).toBeVisible();
  await expect(page.getByLabel("Максимум слов")).toHaveCount(0);
  await expect(page.getByText("Контекст", { exact: true })).toHaveCount(0);
});

test("simple agent shows live context compression savings", async ({ page }) => {
  await setMode(page, "unrestricted");
  const current: State = await (await page.request.get("/api/state")).json();
  await page.request.put("/api/settings", {
    headers,
    data: {
      expectedSettingsVersion: current.settingsVersion,
      settings: {
        ...current.settings,
        contextManagementEnabled: true,
        recentMessagesLimit: 2,
        summarizationBatchSize: 2,
      },
    },
  });
  await page.reload();
  await ready(page);

  await send(page, "Факт для будущего summary");
  await done(page);
  await send(page, "Второй факт для сжатия");
  await done(page);

  const table = page.getByTestId("exchange").last().getByTestId("context-savings-table");
  await expect(table).toBeVisible();
  await expect(table.getByRole("region", { name: "Таблица экономии от сжатия контекста" })).toBeVisible();
  await expect(table.locator("tbody tr")).toHaveCount(2);
  await expect(table.locator("tbody tr").first()).toContainText("Полная история");
  await expect(table.locator("tbody tr").last()).toContainText("Summary + recent");

  const state: State = await (await page.request.get("/api/state")).json();
  expect(state.contextSavings?.mainRequests).toBe(2);
  expect(state.contextSavings?.summarizationRequests).toBe(1);
  expect(state.contextSavings?.compressedTotalTokens).toBe(600);
  expect(state.contextSavings?.savingPercent).not.toBeNull();
});

test("prompt focus uses one clean highlight around the composer", async ({
  page,
}) => {
  const prompt = page.getByRole("textbox", {
    name: "Новый запрос",
    exact: true,
  });
  const composer = page.locator(".composer");

  await prompt.focus();

  await expect(prompt).toBeFocused();
  expect(await prompt.evaluate((node) => getComputedStyle(node).outlineStyle)).toBe(
    "none",
  );
  await expect
    .poll(() =>
      composer.evaluate((node) => getComputedStyle(node).borderColor),
    )
    .toBe("rgb(133, 139, 217)");
});

test("all seven modes, demos, history, settings, keys and keyboard shortcuts", async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  page.on("pageerror", (error) => consoleErrors.push(error.message));
  await expect(page.getByText("Хорошие ответы начинаются")).toBeVisible();
  await page.getByLabel("Провайдер", { exact: true }).selectOption("DEEPSEEK");
  await ready(page);
  await expect(page.getByLabel("Модель", { exact: true })).toHaveValue(
    "deepseek-v4-flash",
  );
  await page
    .getByLabel("API-ключ", { exact: true })
    .fill("browser-test-secret-key");
  await page
    .getByRole("button", { name: "Заменить ключ", exact: true })
    .click();
  await expect(page.getByLabel("API-ключ", { exact: true })).toHaveValue("");
  expect(await (await page.request.get("/api/state")).text()).not.toContain(
    "browser-test-secret-key",
  );
  expect(
    await page.evaluate(() =>
      JSON.stringify({ ...localStorage, ...sessionStorage }),
    ),
  ).not.toContain("browser-test-secret-key");
  await setMode(page, "compare");
  await page.getByLabel("Максимум слов").fill("42");
  await page.getByLabel("Максимум слов").press("Tab");
  await expect
    .poll(
      async () =>
        (await (await page.request.get("/api/state")).json()).settings.maxWords,
    )
    .toBe(42);
  await ready(page);
  await send(page, "Расскажи о квантовом компьютере", "Control+Enter");
  await done(page);
  await expect(
    page.getByTestId("exchange").last().getByTestId("response-card"),
  ).toHaveCount(2);
  expect((await (await page.request.get("/api/state")).json()).history).toEqual(
    { unrestricted: 1, controlled: 1 },
  );
  await page
    .getByRole("button", { name: "Очистить экран", exact: true })
    .click();
  await expect(page.getByTestId("exchange")).toHaveCount(0);
  expect((await (await page.request.get("/api/state")).json()).history).toEqual(
    { unrestricted: 1, controlled: 1 },
  );
  await ready(page);
  for (const [mode, cards] of [
    ["controlled", 1],
    ["unrestricted", 1],
    ["reasoning", 6],
    ["temperature", 4],
    ["models", 4],
  ] as const) {
    await setMode(page, mode);
    if (mode === "models") {
      await expect(page.getByLabel("Провайдер", { exact: true })).toHaveValue(
        "OPENAI",
      );
      await expect(
        page.getByLabel("Провайдер", { exact: true }),
      ).toBeDisabled();
      await expect(page.getByLabel("Максимум токенов")).toHaveValue("1000");
    }
    await send(
      page,
      `Проверка ${mode}`,
      mode === "unrestricted" ? "Meta+Enter" : undefined,
    );
    await done(page);
    if (mode === "reasoning")
      await page
        .getByTestId("exchange")
        .last()
        .locator("summary")
        .filter({ hasText: "Сгенерированный промпт" })
        .click();
    await expect(
      page.getByTestId("exchange").last().getByTestId("response-card"),
    ).toHaveCount(cards);
    if (mode === "unrestricted") {
      const exchange = page.getByTestId("exchange").last();
      await expect(
        exchange
          .getByTestId("response-card")
          .getByText("ОТВЕТ АГЕНТА", { exact: true }),
      ).toBeVisible();
      await expect(
        exchange.getByRole("region", { name: "Метрики ответа агента" }),
      ).toBeVisible();
      await expect(exchange.locator(".metrics summary")).toHaveText(
        "Метрики ответа",
      );
      await expect(exchange.locator(".metrics thead th").first()).toHaveText(
        "Ответ",
      );
      await expect(exchange.locator(".metrics thead th")).toHaveText([
        "Ответ",
        "Символы",
        "Слова",
        "Input",
        "Completion",
        "Total",
        "Finish reason",
      ]);
      await expect(exchange.locator(".metrics tbody td")).toHaveText([
        /\d+/,
        /\d+/,
        "120",
        "80",
        "200",
        "stop",
      ]);
      await expect(page.getByTestId("token-metrics-panel")).toHaveCount(1);
      const tokenPanel = exchange.getByTestId("token-metrics-panel");
      await expect(tokenPanel).not.toHaveAttribute("open", "");
      await expect(exchange.getByLabel("Токенные метрики хода 2")).toBeHidden();
      await tokenPanel.locator("summary").click();
      await expect(exchange.getByLabel("Токенные метрики хода 2")).toBeVisible();
      await expect(exchange.getByText("usage API получен").last()).toBeVisible();
      await expect(exchange.getByLabel("Таблица роста токенов по ходам")).toBeVisible();
    }
    await expect(
      page
        .getByTestId("exchange")
        .last()
        .getByRole("region", {
          name:
            mode === "unrestricted"
              ? "Метрики ответа агента"
              : "Таблица метрик",
        }),
    ).toBeVisible();
  }
  await setMode(page, "compare");
  await page
    .getByRole("button", { name: "Очистить историю", exact: true })
    .click();
  await ready(page);
  expect((await (await page.request.get("/api/state")).json()).history).toEqual(
    { unrestricted: 0, controlled: 0 },
  );
  await expect(page.getByTestId("exchange")).toHaveCount(5);
  for (const name of ["Демо: 4 способа рассуждения", "Демо: температура"]) {
    await page.getByRole("button", { name, exact: true }).click();
    await done(page);
  }
  await expect(page.getByTestId("exchange")).toHaveCount(7);
  expect(consoleErrors).toEqual([]);
});

test("token and context demos show growth and safe overflow without a paid API", async ({ page }) => {
  await page.getByRole("button", { name: "Токены: короткий диалог", exact: true }).click();
  await done(page);
  let exchange = page.getByTestId("exchange").last();
  let tokenPanel = exchange.getByTestId("token-metrics-panel");
  await expect(tokenPanel).not.toHaveAttribute("open", "");
  await tokenPanel.locator("summary").click();
  await expect(exchange.getByLabel("Таблица роста токенов по ходам")).toBeVisible();
  await expect(exchange.getByLabel("Токенные метрики хода 4")).toBeVisible();
  await expect(exchange.getByText("Приблизительный tokenizer").first()).toBeVisible();

  await page.getByRole("button", { name: "Токены: длинный диалог", exact: true }).click();
  await done(page);
  exchange = page.getByTestId("exchange").last();
  tokenPanel = exchange.getByTestId("token-metrics-panel");
  await tokenPanel.locator("summary").click();
  await expect(exchange.getByLabel("Токенные метрики хода 14")).toBeVisible();
  await expect(exchange.locator(".conversation-summary")).toContainText("Cumulative API input");

  await page.getByRole("button", { name: "Токены: переполнение 6K", exact: true }).click();
  await done(page);
  exchange = page.getByTestId("exchange").last();
  tokenPanel = exchange.getByTestId("token-metrics-panel");
  await tokenPanel.locator("summary").click();
  await expect(exchange.getByText("REJECT · локальное отклонение").first()).toBeVisible();
  await expect(exchange.getByText("Активный контекст сокращён")).toBeVisible();
  await expect(exchange.getByText(/в постоянной истории — да; в активном API-контексте.*— нет/)).toBeVisible();
  await expect(exchange.getByText("Симуляция уменьшенного окна").first()).toBeVisible();
});

test("refresh during request, second tab, reconnect and cancellation do not duplicate generation", async ({
  page,
  context,
}) => {
  await setMode(page, "reasoning");
  await send(page, "[[slow]] Проверка восстановления");
  await expect(
    page.getByRole("button", { name: "Отменить", exact: true }),
  ).toBeEnabled();
  const current: State = await (await page.request.get("/api/state")).json();
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Отменить", exact: true }),
  ).toBeEnabled();
  expect(
    (await (await page.request.get("/api/state")).json()).operation.id,
  ).toBe(current.operation!.id);
  const tab = await context.newPage();
  await tab.goto("/");
  await expect(tab.getByTestId("exchange")).toHaveCount(1);
  await expect(tab.getByLabel("Режим ответа")).toBeDisabled();
  await context.setOffline(true);
  await expect(page.getByText("Связь с сервером потеряна")).toBeVisible();
  await context.setOffline(false);
  await expect(
    page.getByRole("button", { name: "Отменить", exact: true }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "Отменить", exact: true }).click();
  await expect(
    page.getByTestId("exchange").getByText("Отменено", { exact: true }),
  ).toBeVisible();
  await expect(
    tab.getByTestId("exchange").getByText("Отменено", { exact: true }),
  ).toBeVisible();
  await ready(page);
  await setMode(page, "unrestricted");
  await send(page, "Новый запрос после отмены");
  await done(page);
  await expect(tab.getByTestId("exchange")).toHaveCount(2);
  await tab.close();
});

test("streaming response grows through SSE, survives refresh and reveals metrics only when complete", async ({
  page,
}) => {
  await setMode(page, "unrestricted");
  await send(page, "[[stream]] Проверка настоящего потока");

  const exchange = page.getByTestId("exchange").last();
  const card = exchange.getByTestId("response-card");
  await expect(card).toContainText("Думаю");
  await expect(exchange).not.toContainText("Формируется следующий ответ");
  await expect(card.getByRole("status", { name: "Ответ генерируется" })).toBeVisible();
  await expect(card).toContainText("Поток");
  await expect(card).not.toContainText("Думаю");
  const partial = await card.locator(".response-body").innerText();
  expect(partial.length).toBeGreaterThan(0);
  await expect
    .poll(() => card.locator(".response-body").innerText())
    .not.toHaveLength(partial.length);
  await expect(exchange.locator(".metrics")).toHaveCount(0);

  await page.reload();
  const restored = page.getByTestId("exchange").last();
  await expect(restored.getByTestId("response-card")).toContainText("Поток");
  await expect(
    restored.getByRole("status", { name: "Ответ генерируется" }),
  ).toBeVisible();
  await expect(restored.locator(".metrics")).toHaveCount(0);

  await done(page);
  const completed = page.getByTestId("exchange").last();
  await expect(
    completed.getByRole("status", { name: "Ответ генерируется" }),
  ).toHaveCount(0);
  await expect(completed.locator(".response-card .response-body strong")).toContainText("Жирный текст");
  await expect(completed.locator("pre")).toContainText("val answer = 42");
  await expect(completed.locator(".metrics")).toBeVisible();
});

test("lost POST reply safely retries the same operation; network and individual model errors recover", async ({
  page,
}) => {
  await setMode(page, "unrestricted");
  let intercepted = false;
  await page.route("**/api/operations", async (route) => {
    if (intercepted) return route.continue();
    intercepted = true;
    await route.fetch(); // The real server accepted it, but the browser does not get the reply.
    await route.abort("connectionfailed");
  });
  await send(page, "Потерянный ответ команды");
  await expect(
    page.getByRole("button", { name: "Проверить отправку", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Проверить отправку", exact: true })
    .click();
  await done(page);
  await expect(page.getByTestId("exchange")).toHaveCount(1);
  await page.unroute("**/api/operations");
  await send(page, "[[network]]");
  await expect(
    page.getByTestId("exchange").last().getByText("Ошибка", { exact: true }),
  ).toBeVisible();
  await ready(page);
  await setMode(page, "models");
  await send(page, "[[partial]]");
  await done(page);
  await expect(
    page
      .getByTestId("exchange")
      .last()
      .getByText("Ошибка модели", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByTestId("exchange").last().getByTestId("response-card"),
  ).toHaveCount(4);
});

test("long markdown, math, safe HTML and narrow layout", async ({ page }) => {
  await setMode(page, "unrestricted");
  await send(page, "[[long]]");
  await done(page);
  await page
    .getByRole("button", { name: "Развернуть ответ", exact: true })
    .click();
  await expect(
    page.locator(".response-card .katex-display").first(),
  ).toBeVisible();
  await expect(page.locator("pre").first()).toContainText("fun square");
  await expect(page.locator(".response-card table td br").first()).toBeAttached();
  expect(
    await page.evaluate(
      () => (window as unknown as Record<string, unknown>).injected,
    ),
  ).toBeUndefined();
  expect(await page.locator(".markdown script, .markdown img").count()).toBe(0);
  await page.screenshot({
    path: "test-results/desktop-long.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 430, height: 900 });
  await expect(
    page.getByRole("button", { name: "Открыть настройки" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/narrow-long.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Открыть настройки" }).click();
  await expect(
    page.getByRole("dialog").getByLabel("Режим ответа"),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
