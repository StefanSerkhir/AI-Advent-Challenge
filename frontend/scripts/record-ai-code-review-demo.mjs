import {chromium} from "@playwright/test";
import {spawn} from "node:child_process";
import {access, mkdir, rename, rm, stat} from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import {pathToFileURL} from "node:url";

const root = path.resolve(import.meta.dirname, "../..");
const output = path.join(root, "video/ai-code-review-demo.webm");
const fixtureOutput = path.join(root, "build/ai-code-review-video-fixture");
const report = path.join(fixtureOutput, "demo.html");
const temporary = path.join(root, "video/.ai-code-review-demo-recording");
const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

try {
  await access(output);
  if (process.env.AI_CODE_REVIEW_DEMO_OVERWRITE !== "1") {
    throw new Error(`${output} уже существует. Укажите AI_CODE_REVIEW_DEMO_OVERWRITE=1 для осознанной перезаписи.`);
  }
  await rm(output);
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
}

await rm(temporary, {recursive: true, force: true});
await rm(fixtureOutput, {recursive: true, force: true});
await mkdir(temporary, {recursive: true});
await mkdir(path.dirname(output), {recursive: true});

await new Promise((resolve, reject) => {
  const executable = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
  const child = spawn(executable, ["runAiCodeReviewFixture"], {
    cwd: root,
    stdio: "inherit",
    shell: false,
    env: {...process.env, AI_CODE_REVIEW_FIXTURE_OUTPUT: fixtureOutput},
  });
  child.once("error", reject);
  child.once("exit", (code) => code === 0 ? resolve() : reject(new Error(`Fixture завершился с кодом ${code}.`)));
});

const browser = await chromium.launch({headless: true});
const context = await browser.newContext({
  viewport: {width: 1440, height: 900},
  recordVideo: {dir: temporary, size: {width: 1440, height: 900}},
});
const page = await context.newPage();
const video = page.video();

try {
  await page.goto(pathToFileURL(report).href, {waitUntil: "load"});
  await page.getByText("Deterministic fake embeddings", {exact: false}).waitFor();
  await pause(5000);
  for (const id of ["pr", "diff", "rag", "review"]) {
    await page.locator(`#${id}`).scrollIntoViewIfNeeded();
    await pause(id === "review" ? 12_000 : 7_000);
  }
  await page.getByText("Overflow guard permits overflow", {exact: false}).scrollIntoViewIfNeeded();
  await pause(6000);
  await page.getByText("Synchronization boundary was removed", {exact: false}).scrollIntoViewIfNeeded();
  await pause(6000);
  await page.getByText("Add boundary and concurrency coverage", {exact: false}).scrollIntoViewIfNeeded();
  await pause(6000);
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
