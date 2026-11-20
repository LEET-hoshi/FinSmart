#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import {
    existsSync,
    mkdirSync,
    readFileSync,
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
const allowedClockSkewInMilliseconds = 5 * 60 * 1000;
const forceUpdate = process.argv.includes("--force");
export const MAGNIT_STORE = Object.freeze({
    code: "667052",
    region: "Екатеринбург",
    address: "Екатеринбург, ул. Первомайская, 76",
});

/*
 * JSON сайта передаёт цену в копейках: 9999 → "99.99".
 * Не извлекаем цены из рекламных описаний и не подменяем отсутствие нулём.
 */
export function normalizePrice(value) {
    if (!Number.isSafeInteger(value) || value <= 0) {
        return null;
    }
    return `${Math.floor(value / 100)}.${String(value % 100).padStart(2, "0")}`;
}

/*
 * Из ссылки товара получает постоянный идентификатор.
 * Например, из /promo-product/2189944-kofe получится magnit-2189944.
 */
export function getSourceId(url) {
    const match = String(url).match(/^https:\/\/magnit\.ru\/promo-product\/(\d+)-[^/]+/);
    return match ? `magnit-${match[1]}` : null;
}

// Код проверен через официальный выбор магазина. Другие магазины не смешиваем.
export function getRegion(url) {
    try {
        const source = new URL(url);
        return source.origin === "https://magnit.ru" && source.searchParams.get("shopCode") === MAGNIT_STORE.code
            ? MAGNIT_STORE.region : null;
    } catch {
        return null;
    }
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
function hasCurrentPrices(offers, now) {
    return Array.isArray(offers) && offers.length > 0 && offers.every((offer) =>
        offer && typeof offer.salePrice === "string" && /^\d+\.\d{2}$/.test(offer.salePrice)
        && Number(offer.salePrice) > 0 && offer.region === MAGNIT_STORE.region
        && getRegion(offer.url) === MAGNIT_STORE.region
        && typeof offer.checkedAt === "string" && Number.isFinite(Date.parse(offer.checkedAt))
        && now - Date.parse(offer.checkedAt) >= -allowedClockSkewInMilliseconds
        && now - Date.parse(offer.checkedAt) < oneDayInMilliseconds);
}

export function catalogIsFresh(file = outputFile, now = Date.now()) {
    if (!existsSync(file)) {
        return false;
    }

    const fileAge = now - statSync(file).mtimeMs;
    if (fileAge < -allowedClockSkewInMilliseconds || fileAge >= oneDayInMilliseconds) {
        return false;
    }

    try {
        return hasCurrentPrices(JSON.parse(readFileSync(file, "utf8")), now);
    } catch {
        return false;
    }
}

/*
 * Получает данные с помощью сохранённого правила API Anything.
 */
export function loadMagnitOffers({ cliPath = resolve(projectDirectory, "node_modules", "api-anything", "dist", "cli.js"), run = spawnSync, timeoutMs = 30_000 } = {}) {
    // Не позволяем случайно поменять правило и подписать цены другого города Екатеринбургом.
    const spec = JSON.parse(readFileSync(join(apiAnythingDirectory, "sites", "magnit-ru.json"), "utf8"));
    const request = spec.operations.find((operation) => operation.name === "listOffers")?.request;
    const source = new URL(request?.url);
    if (source.origin !== "https://magnit.ru" || source.pathname !== "/webgate/v1/promotions/type"
        || source.searchParams.get("storeCode") !== MAGNIT_STORE.code || source.searchParams.get("type") !== "1") {
        throw new Error("Правило источника не соответствует выбранному магазину Екатеринбурга");
    }
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
export function convertOffersWithStats(sourceOffers, checkedAt = new Date().toISOString()) {
    const uniqueOffers = new Map();
    const skippedByReason = {
        missingPrice: 0,
        invalidData: 0,
        invalidDates: 0,
        duplicateSourceId: 0,
    };

    for (const sourceOffer of sourceOffers) {
        if (!sourceOffer || typeof sourceOffer !== "object") {
            skippedByReason.invalidData += 1;
            continue;
        }
        const title = String(sourceOffer.name ?? "").trim();
        const id = String(sourceOffer.id ?? "");
        const slug = String(sourceOffer.slug ?? "");
        if (!title || !/^\d+$/.test(id) || !/^[a-z0-9-]+$/.test(slug)
            || String(sourceOffer.type) !== "1") {
            skippedByReason.invalidData += 1;
            continue;
        }
        const url = `https://magnit.ru/promo-product/${id}-${slug}?shopCode=${MAGNIT_STORE.code}`;
        const sourceId = getSourceId(url);
        const region = getRegion(url);
        const salePrice = normalizePrice(sourceOffer.price);

        if (!sourceId || !region) {
            skippedByReason.invalidData += 1;
            continue;
        }

        if (salePrice === null) {
            skippedByReason.missingPrice += 1;
            continue;
        }

        const discount = sourceOffer.discountLabel
            ? String(sourceOffer.discountLabel).trim()
            : null;
        const description = String(sourceOffer.description ?? "").trim();
        const validFrom = normalizeDate(sourceOffer.startDate);
        const validTo = normalizeDate(sourceOffer.endDate);
        if ((sourceOffer.startDate != null && validFrom === null)
            || (sourceOffer.endDate != null && validTo === null)
            || (validFrom && validTo && validFrom > validTo)) {
            skippedByReason.invalidDates += 1;
            continue;
        }

        const offer = {
            sourceId,
            title,
            category: getCategory(title),
            regularPrice: normalizePrice(sourceOffer.oldPrice),
            salePrice,
            currency: "RUB",
            validFrom,
            validTo,
            conditions: [discount ? `Скидка: ${discount}` : null, description,
                `Магазин: ${MAGNIT_STORE.address}.`].filter(Boolean).join("\n"),
            url,
            region,
            checkedAt,
            isTest: false,
        };

        if (uniqueOffers.has(sourceId)) {
            skippedByReason.duplicateSourceId += 1;
        }
        uniqueOffers.set(sourceId, offer);
    }

    const offers = [...uniqueOffers.values()];
    return {
        offers,
        stats: {
            received: sourceOffers.length,
            accepted: offers.length,
            skipped: sourceOffers.length - offers.length,
            skippedByReason,
        },
    };
}

export function convertOffers(sourceOffers) {
    return convertOffersWithStats(sourceOffers).offers;
}

export function formatImportSummary(stats, updatedAt) {
    return [
        `Дата обновления: ${updatedAt}`,
        `Получено предложений: ${stats.received}`,
        `Принято предложений: ${stats.accepted}`,
        `Пропущено предложений: ${stats.skipped}`,
        `Причины пропуска: без корректной цены — ${stats.skippedByReason.missingPrice}; `
            + `некорректные данные — ${stats.skippedByReason.invalidData}; `
            + `некорректные даты — ${stats.skippedByReason.invalidDates}; `
            + `повторный sourceId — ${stats.skippedByReason.duplicateSourceId}.`,
    ];
}

export function normalizeDate(value) {
    if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
    const parsed = new Date(`${value}T00:00:00Z`);
    return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value ? value : null;
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
        const cachedOffers = JSON.parse(readFileSync(outputFile, "utf8"));
        console.log(
            "Каталог обновлялся меньше 24 часов назад. Обновление не требуется.",
        );
        console.log(`Дата последнего успешного обновления: ${cachedOffers[0].checkedAt}`);
        console.log(`Файл: ${outputFile}`);
        return;
    }

    console.log("Получаем предложения Магнита...");
    const deadline = now() + 30_000;

    const sourceOffers = fetchOffers({ timeoutMs: Math.max(1, deadline - now()) });
    console.log("Этап: преобразование данных");
    const { offers: convertedOffers, stats } = convertOffersWithStats(sourceOffers);

    if (convertedOffers.length === 0) {
        const reasons = formatImportSummary(stats, new Date().toISOString()).at(-1);
        throw new Error(`Источник не содержит корректных товарных предложений с ценой; ${reasons}`);
    }

    if (now() >= deadline) {
        throw new Error("Превышен общий лимит 30 секунд до записи каталога");
    }
    console.log("Этап: запись файла");
    saveOffers(convertedOffers, destination);

    for (const line of formatImportSummary(stats, convertedOffers[0].checkedAt)) {
        console.log(line);
    }
    console.log(`Магазин: ${MAGNIT_STORE.address} (${MAGNIT_STORE.code})`);
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
