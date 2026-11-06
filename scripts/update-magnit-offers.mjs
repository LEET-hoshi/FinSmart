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
function normalizePrice(value) {
    if (value === undefined || value === null) {
        return null;
    }

    const cleanedValue = String(value)
        .replace(/\s/g, "")
        .replace(",", ".")
        .replace(/[^\d.]/g, "");

    if (cleanedValue === "") {
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
function getSourceId(url) {
    const match = String(url).match(/\/promo-product\/(\d+)-/);
    return match ? `magnit-${match[1]}` : null;
}

/*
 * Определяет категорию FinSmart по названию товара.
 * Большинство товаров Магнита относятся к продуктам.
 */
function getCategory(title) {
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
function loadMagnitOffers() {
    // npm ships npx as a .cmd wrapper on Windows. Invoking the underlying
    // JavaScript entry point through Node avoids cmd.exe and keeps timeout
    // handling reliable. POSIX distributions expose npx as an executable.
    const useWindowsNpxCli = process.platform === "win32";
    const npxCommand = useWindowsNpxCli ? process.execPath : "npx";
    const npxArguments = useWindowsNpxCli
        ? [join(dirname(process.execPath), "node_modules", "npm", "bin", "npx-cli.js")]
        : [];
    const result = spawnSync(
        npxCommand,
        [
            ...npxArguments,
            "-y",
            "api-anything",
            "call",
            "magnit-ru",
            "listOffers",
        ],
        {
            cwd: projectDirectory,
            encoding: "utf8",
            maxBuffer: 10 * 1024 * 1024,
            timeout: 30_000,
            env: {
                ...process.env,
                API_ANYTHING_HOME: apiAnythingDirectory,
            },
        },
    );

    if (result.error) {
        throw new Error(`Не удалось запустить API Anything: ${result.error.message}`);
    }

    if (result.status !== 0) {
        throw new Error(
            `API Anything завершился с ошибкой: ${result.stderr.trim()}`,
        );
    }

    let response;

    try {
        response = JSON.parse(result.stdout.trim());
    } catch {
        throw new Error("API Anything вернул некорректный JSON");
    }

    if (!response.ok) {
        throw new Error(
            `Источник вернул ошибку: ${response.reason ?? response.class ?? "неизвестная ошибка"}`,
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
function convertOffers(sourceOffers) {
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
function saveOffers(offers) {
    if (offers.length === 0) {
        throw new Error(
            "После преобразования не осталось корректных предложений",
        );
    }

    mkdirSync(dirname(outputFile), { recursive: true });

    const temporaryFile = `${outputFile}.tmp`;
    const json = `${JSON.stringify(offers, null, 2)}\n`;

    writeFileSync(temporaryFile, json, "utf8");
    renameSync(temporaryFile, outputFile);
}

function main() {
    if (!forceUpdate && catalogIsFresh()) {
        console.log(
            "Каталог обновлялся меньше 24 часов назад. Обновление не требуется.",
        );
        console.log(`Файл: ${outputFile}`);
        return;
    }

    console.log("Получаем предложения Магнита...");

    const sourceOffers = loadMagnitOffers();
    const convertedOffers = convertOffers(sourceOffers);

    saveOffers(convertedOffers);

    console.log(`Получено предложений: ${sourceOffers.length}`);
    console.log(`Сохранено предложений: ${convertedOffers.length}`);
    console.log(`Файл: ${outputFile}`);
}

try {
    main();
} catch (error) {
    console.error(`Ошибка обновления каталога: ${error.message}`);
    console.error("Предыдущий успешно сохранённый каталог не изменён.");
    process.exitCode = 1;
}
