import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { normalizePrice, getSourceId, getCategory, convertOffers, saveOffers, loadMagnitOffers, updateCatalog } from "./update-magnit-offers.mjs";

const url = "https://magnit.ru/promo-product/2189944-kofe?shopCode=992301";
const sample = JSON.parse(readFileSync(new URL("../fixtures/offers/magnit-source-sample.json", import.meta.url), "utf8"));

test("цены и идентификаторы", () => {
    assert.equal(normalizePrice("1 338,99 ₽"), "1338.99");
    assert.equal(normalizePrice("нет цены"), null);
    assert.equal(normalizePrice("12.3.4 ₽"), null);
    assert.equal(normalizePrice(undefined), null);
    assert.equal(getSourceId(url), "magnit-2189944");
    assert.equal(getSourceId("https://other.ru/promo-product/2189944-kofe"), null);
    assert.equal(getCategory("Подгузники"), "health");
    assert.equal(getCategory("Средство для стирки"), "home");
});

test("преобразование и контракт", () => {
    const items = convertOffers([
        ...sample.data,
        { ...sample.data[0], title: "Кофе обновлённый" },
        { title: "", url },
        { title: "Без ссылки" },
        { title: "Неверный адрес", url: "https://magnit.ru/promo-product/invalid" },
    ]);
    assert.equal(items.length, 2);
    assert.equal(items[0].title, "Кофе обновлённый");
    assert.equal(items[0].regularPrice, "1338.99");
    assert.equal(items[0].salePrice, "499.99");
    assert.ok(Number(items[0].regularPrice) > Number(items[0].salePrice));
    assert.equal(items[1].regularPrice, null);
    assert.equal(items[1].salePrice, "59.90");
    assert.equal(new Set(items.map((x) => x.sourceId)).size, items.length);
    for (const item of items) {
        assert.equal(item.currency, "RUB");
        assert.equal(item.isTest, false);
        assert.match(item.checkedAt, /^\d{4}-\d\d-\d\dT/);
        for (const price of [item.regularPrice, item.salePrice]) {
            assert.ok(price === null || /^\d+\.\d{2}$/.test(price));
        }
    }
});

test("ошибка и пустой ответ сохраняют прежний каталог", () => {
    const directory = mkdtempSync(join(tmpdir(), "finsmart-offers-"));
    const destination = join(directory, "offers.json");
    writeFileSync(destination, "old catalog");
    assert.throws(() => loadMagnitOffers({ run: () => ({ status: 1, stderr: "failure" }) }));
    assert.throws(() => loadMagnitOffers({ run: () => ({ error: Object.assign(new Error("timed out"), { code: "ETIMEDOUT" }) }) }));
    assert.throws(() => loadMagnitOffers({ run: () => ({ status: 0, stdout: JSON.stringify({ ok: true, data: [] }) }) }));
    assert.throws(() => saveOffers([], destination));
    assert.equal(readFileSync(destination, "utf8"), "old catalog");
    const fromFixture = loadMagnitOffers({ run: () => ({ status: 0, stdout: JSON.stringify(sample) }) });
    assert.equal(fromFixture.length, 2);
    loadMagnitOffers({ run: (command, args, options) => {
        assert.equal(command, process.execPath);
        assert.match(args[0], /api-anything[/\\]dist[/\\]cli\.js$/);
        assert.equal(options.timeout, 30_000);
        return { status: 0, stdout: JSON.stringify(sample) };
    } });
    saveOffers(convertOffers([{ title: "Кофе", url }]), destination);
    assert.equal(JSON.parse(readFileSync(destination, "utf8")).length, 1);
    const beforeFailure = readFileSync(destination, "utf8");
    assert.throws(() => updateCatalog({ force: true, destination, fetchOffers: () => { throw new Error("source failed"); } }));
    assert.equal(readFileSync(destination, "utf8"), beforeFailure);
    let ticks = 0;
    assert.throws(() => updateCatalog({ force: true, destination, fetchOffers: () => sample.data, now: () => ticks++ === 0 ? 0 : 30_001 }), /30 секунд/);
    assert.equal(readFileSync(destination, "utf8"), beforeFailure);
    updateCatalog({ force: true, destination, fetchOffers: () => sample.data });
    assert.equal(JSON.parse(readFileSync(destination, "utf8")).length, 2);
});
