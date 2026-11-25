#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { updateCatalog } from "./update-magnit-offers.mjs";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
export const projectDirectory = resolve(scriptDirectory, "..");
export const generatedOffersFile = join(
    projectDirectory,
    "build",
    "generated",
    "fixtures",
    "offers",
    "offers.json",
);
export const demoOffersFile = join(projectDirectory, "fixtures", "offers", "offers.json");

export function validateCatalog(file, expectedTest) {
    if (!existsSync(file)) throw new Error(`Каталог не найден: ${file}`);
    const offers = JSON.parse(readFileSync(file, "utf8"));
    if (!Array.isArray(offers) || offers.length === 0) {
        throw new Error("Каталог пуст или имеет неверный формат");
    }
    if (offers.some((offer) => offer?.isTest !== expectedTest)) {
        throw new Error(expectedTest
            ? "Демо-каталог содержит рабочие записи"
            : "Рабочий каталог содержит демонстрационные записи");
    }
    return offers.length;
}

function copyAtomically(source, destination) {
    mkdirSync(dirname(destination), { recursive: true });
    const temporary = `${destination}.${process.pid}.tmp`;
    writeFileSync(temporary, readFileSync(source));
    renameSync(temporary, destination);
}

export function prepareOffers({
    mode = process.env.OFFERS_MODE ?? "live",
    destination = generatedOffersFile,
    update = updateCatalog,
} = {}) {
    if (mode === "demo") {
        validateCatalog(demoOffersFile, true);
        copyAtomically(demoOffersFile, destination);
        const count = validateCatalog(destination, true);
        console.log(`Подготовлен демонстрационный каталог: ${count}`);
        return { mode, count, fallback: false };
    }
    if (mode !== "live") throw new Error(`Неизвестный режим каталога: ${mode}`);

    try {
        update({ destination });
    } catch (error) {
        try {
            const count = validateCatalog(destination, false);
            console.warn(`Источник недоступен: ${error.message}`);
            console.warn(`Используется предыдущий рабочий каталог: ${count}`);
            return { mode, count, fallback: true };
        } catch {
            throw error;
        }
    }

    const count = validateCatalog(destination, false);
    console.log(`Подготовлен рабочий каталог: ${count}`);
    return { mode, count, fallback: false };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        prepareOffers();
    } catch (error) {
        console.error(`Каталог не подготовлен: ${error.message}`);
        process.exitCode = 1;
    }
}
