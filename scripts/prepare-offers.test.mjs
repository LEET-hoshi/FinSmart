import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { prepareOffers, validateCatalog } from "./prepare-offers.mjs";

function temporaryCatalog(value = null) {
    const file = join(mkdtempSync(join(tmpdir(), "finsmart-prepare-")), "offers.json");
    if (value !== null) writeFileSync(file, JSON.stringify(value));
    return file;
}

test("live-режим принимает только настоящий непустой каталог", () => {
    const destination = temporaryCatalog();
    const result = prepareOffers({
        destination,
        update: ({ destination: file }) => writeFileSync(file, JSON.stringify([
            { sourceId: "magnit-1", isTest: false },
        ])),
    });
    assert.deepEqual(result, { mode: "live", count: 1, fallback: false });
    assert.equal(validateCatalog(destination, false), 1);
});

test("ошибка источника сохраняет предыдущий настоящий каталог", () => {
    const destination = temporaryCatalog([{ sourceId: "magnit-old", isTest: false }]);
    const result = prepareOffers({
        destination,
        update: () => { throw new Error("network"); },
    });
    assert.deepEqual(result, { mode: "live", count: 1, fallback: true });
    assert.equal(JSON.parse(readFileSync(destination, "utf8"))[0].sourceId, "magnit-old");
});

test("без рабочего кэша ошибка источника останавливает запуск", () => {
    assert.throws(() => prepareOffers({
        destination: temporaryCatalog(),
        update: () => { throw new Error("network"); },
    }), /network/);
    assert.throws(() => validateCatalog(temporaryCatalog([]), false), /пуст/);
    assert.throws(() => validateCatalog(
        temporaryCatalog([{ sourceId: "demo-1", isTest: true }]),
        false,
    ), /демонстрацион/);
});

test("demo-режим копирует только явно помеченные демонстрационные записи", () => {
    const destination = temporaryCatalog();
    const result = prepareOffers({ mode: "demo", destination });
    assert.equal(result.mode, "demo");
    assert.equal(result.count, 20);
    assert.equal(validateCatalog(destination, true), 20);
});
