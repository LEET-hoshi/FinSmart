#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import {
    existsSync,
    mkdirSync,
    renameSync,
    statSync,
    writeFileSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectDirectory = resolve(scriptDirectory, "..");

const apiAnythingDirectory = join(
    projectDirectory,
    "tools",
    "api-anything",
);

const outputFile = join(
    projectDirectory,
    "build",
    "generated",
    "fixtures",
    "offers",
    "offers.json",
);

const oneDayInMilliseconds = 24 * 60 * 60 * 1000;
const forceUpdate = process.argv.includes("--force");

/*
 * Преобразует цену вида "1 338,99 ₽" в строку "1338.99".
 * Если цены нет или она записана неправильно, возвращает null.
 */
export function normalizePrice(value) {
    if (value === undefined || value === null) {
        return null;
    }

    const cleanedValue = String(value).trim().replace(/[\s\u00a0\u202f]/g, "").replace(/₽$/, "").replace(",", ".");
    if (!/^\d+(?:\.\d{1,2})?$/.test(cleanedValue)) {
        return null;
    }

    const number = Number(cleanedValue);

    if (!Number.isFinite(number) || number < 0) {
        return null;
    }

    return number.toFixed(2);
}

/*
 * Из ссылки товара получает постоянный идентификатор.
 * Например, из /promo-product/2189944-kofe получится magnit-2189944.
 */
export function getSourceId(url) {
    const match = String(url).match(/^https:\/\/magnit\.ru\/promo-product\/(\d+)-[^/]+/);
    return match ? `magnit-${match[1]}` : null;
}

/*
 * Определяет категорию FinSmart по названию товара.
 * Большинство товаров Магнита относятся к продуктам.
 */
export function getCategory(title) {
    const normalizedTitle = title.toLowerCase();

    const healthWords = [
        "подгуз",
        "гигиен",
        "витамин",
        "лекар",
        "аптеч",
    ];

    const homeWords = [
        "стирк",
        "уборк",
        "для посуды",
        "туалетная бумага",
        "чистящее",
    ];

    if (healthWords.some((word) => normalizedTitle.includes(word))) {
        return "health";
    }

    if (homeWords.some((word) => normalizedTitle.includes(word))) {
        return "home";
    }

    return "groceries";
}

/*
 * Проверяет, обновлялся ли каталог меньше суток назад.
 */
function catalogIsFresh() {
    if (!existsSync(outputFile)) {
        return false;
    }

    const fileAge = Date.now() - statSync(outputFile).mtimeMs;
    return fileAge < oneDayInMilliseconds;
}

/*
 * Получает данные с помощью сохранённого правила API Anything.
 */
export function loadMagnitOffers({ cliPath = resolve(projectDirectory, "node_modules", "api-anything", "dist", "cli.js"), run = spawnSync, timeoutMs = 30_000 } = {}) {
    console.log("Этап: получение данных");
    const result = run(
        process.execPath,
        [
            cliPath,
            "call",
            "magnit-ru",
            "listOffers",
        ],
        {
            cwd: projectDirectory,
            encoding: "utf8",
            maxBuffer: 10 * 1024 * 1024,
            timeout: timeoutMs,
            env: {
                ...process.env,
                API_ANYTHING_HOME: apiAnythingDirectory,
            },
        },
    );

    if (result.error) {
        const cause = result.error.code === "ETIMEDOUT" ? "превышен лимит времени 30 секунд" : result.error.code ?? "ошибка запуска";
        throw new Error(`Не удалось получить данные через API Anything: ${cause}`);
    }

    if (result.status !== 0) {
        throw new Error(
            `API Anything завершился с ошибкой (код ${result.status}); проверьте доступность сайта и установку через npm ci`,
        );
    }

    console.log("Этап: разбор ответа");
    let response;

    try {
        response = JSON.parse(result.stdout.trim());
    } catch {
        throw new Error("API Anything вернул некорректный JSON");
    }

    if (!response.ok) {
        throw new Error(
            `Источник вернул ошибку: ${response.class ?? "неизвестная ошибка"}`,
        );
    }

    if (!Array.isArray(response.data) || response.data.length === 0) {
        throw new Error("Источник вернул пустой список предложений");
    }

    return response.data;
}

/*
 * Преобразует данные Магнита в формат FinSmart.
 */
export function convertOffers(sourceOffers) {
    const checkedAt = new Date().toISOString();
    const uniqueOffers = new Map();

    for (const sourceOffer of sourceOffers) {
        const title = String(sourceOffer.title ?? "").trim();
        const url = String(sourceOffer.url ?? "").trim();
        const sourceId = getSourceId(url);

        if (!title || !url || !sourceId) {
            continue;
        }

        const discount = sourceOffer.discount
            ? String(sourceOffer.discount).trim()
            : null;

        const offer = {
            sourceId,
            title,
            category: getCategory(title),
            regularPrice: normalizePrice(sourceOffer.regularPrice),
            salePrice: normalizePrice(sourceOffer.salePrice),
            currency: "RUB",
            validFrom: null,
            validTo: null,
            conditions: discount ? `Скидка: ${discount}` : null,
            url,
            region: "Екатеринбург",
            checkedAt,
            isTest: false,
        };

        uniqueOffers.set(sourceId, offer);
    }

    return [...uniqueOffers.values()];
}

/*
 * Записывает результат через временный файл.
 * Старый каталог заменяется только после успешного получения данных.
 */
export function saveOffers(offers, destination = outputFile) {
    if (offers.length === 0) {
        throw new Error(
            "После преобразования не осталось корректных предложений",
        );
    }

    mkdirSync(dirname(destination), { recursive: true });

    const temporaryFile = `${destination}.${process.pid}.tmp`;
    const json = `${JSON.stringify(offers, null, 2)}\n`;

    writeFileSync(temporaryFile, json, "utf8");
    renameSync(temporaryFile, destination);
}

export function updateCatalog({ force = forceUpdate, destination = outputFile, fetchOffers = loadMagnitOffers, now = Date.now } = {}) {
    if (!force && destination === outputFile && catalogIsFresh()) {
        console.log(
            "Каталог обновлялся меньше 24 часов назад. Обновление не требуется.",
        );
        console.log(`Файл: ${outputFile}`);
        return;
    }

    console.log("Получаем предложения Магнита...");
    const deadline = now() + 30_000;

    const sourceOffers = fetchOffers({ timeoutMs: Math.max(1, deadline - now()) });
    console.log("Этап: преобразование данных");
    const convertedOffers = convertOffers(sourceOffers);

    if (now() >= deadline) {
        throw new Error("Превышен общий лимит 30 секунд до записи каталога");
    }
    console.log("Этап: запись файла");
    saveOffers(convertedOffers, destination);

    console.log(`Получено предложений: ${sourceOffers.length}`);
    console.log(`Сохранено предложений: ${convertedOffers.length}`);
    console.log(`Файл: ${destination}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        updateCatalog();
    } catch (error) {
        console.error(`Ошибка обновления каталога: ${error.message}`);
        console.error("Предыдущий успешно сохранённый каталог не изменён.");
        process.exitCode = 1;
    }
}
