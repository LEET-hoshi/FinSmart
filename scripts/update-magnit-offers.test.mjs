import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync, utimesSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { MAGNIT_STORE, normalizePrice, normalizeDate, getSourceId, getRegion, getCategory,
    convertOffers, saveOffers, loadMagnitOffers, updateCatalog, catalogIsFresh } from "./update-magnit-offers.mjs";

const sample = JSON.parse(readFileSync(new URL("../fixtures/offers/magnit-source-sample.json", import.meta.url), "utf8"));
const url = "https://magnit.ru/promo-product/2190451-lamber-syr-50-100-g?shopCode=667052";
const temporaryCatalog = () => join(mkdtempSync(join(tmpdir(), "finsmart-offers-")), "offers.json");

test("цены в копейках становятся рублями без округления и подмены отсутствующих цен", () => {
    assert.equal(normalizePrice(9999), "99.99");
    assert.equal(normalizePrice(133899), "1338.99");
    assert.equal(normalizePrice(1), "0.01");
    assert.equal(normalizePrice(100), "1.00");
    for (const bad of [null, undefined, "", "9999", "99,99 ₽", -1, 0, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
        assert.equal(normalizePrice(bad), null);
    }
});

test("правило запрашивает именно товарные скидки проверенного магазина Екатеринбурга", () => {
    const spec = JSON.parse(readFileSync(new URL("../tools/api-anything/sites/magnit-ru.json", import.meta.url), "utf8"));
    const op = spec.operations.find(x => x.name === "listOffers");
    const source = new URL(op.request.url);
    assert.equal(source.origin, "https://magnit.ru");
    assert.equal(source.pathname, "/webgate/v1/promotions/type");
    assert.equal(source.searchParams.get("storeCode"), MAGNIT_STORE.code);
    assert.equal(source.searchParams.get("type"), "1");
    assert.equal(source.searchParams.get("limit"), "30");
    assert.equal(op.response.extract, "data");
    assert.equal(op.response.format, "json");
    assert.equal(op.request.headers.cookie, undefined);
    assert.equal(op.request.headers.authorization, undefined);
    assert.equal(getRegion(url), "Екатеринбург");
    assert.equal(getRegion(url.replace("667052", "992301")), null);
    assert.equal(getRegion(url.replace("magnit.ru", "other.ru")), null);
    assert.equal(getSourceId(url), "magnit-2190451");
    assert.equal(getCategory("Подгузники"), "health");
    assert.equal(getCategory("Средство для стирки"), "home");
});

test("преобразование сохраняет цены, условия карты и количества, даты и уникальные ID", () => {
    const items = convertOffers([
        ...sample.data,
        { ...sample.data[0], name: "Сыр обновлённый" },
        null,
        { ...sample.data[0], id: "bad" },
        { ...sample.data[0], id: "5", slug: "../wrong" },
        { ...sample.data[0], id: "6", name: "" },
        { ...sample.data[0], id: "7", type: "19" },
        { ...sample.data[0], id: "8", endDate: "2026-02-30" },
        { ...sample.data[0], id: "9", endDate: "2026-11-01" },
    ]);
    assert.equal(items.length, 2);
    assert.equal(items[0].title, "Сыр обновлённый");
    assert.equal(items[0].regularPrice, "159.99");
    assert.equal(items[0].salePrice, "99.99");
    assert.equal(items[1].regularPrice, null);
    assert.equal(items[1].salePrice, "139.99");
    assert.match(items[0].conditions, /карты Магнит Плюс/);
    assert.match(items[1].conditions, /двух акционных товаров/);
    assert.equal(items[0].validFrom, "2026-11-11");
    assert.equal(items[0].validTo, "2026-11-17");
    assert.equal(new Set(items.map(x => x.sourceId)).size, items.length);
    for (const item of items) {
        assert.equal(item.currency, "RUB");
        assert.equal(item.isTest, false);
        assert.equal(item.region, "Екатеринбург");
        assert.equal(new URL(item.url).searchParams.get("shopCode"), "667052");
        assert.match(item.conditions, /Первомайская, 76/);
        assert.match(item.checkedAt, /^\d{4}-\d\d-\d\dT/);
        assert.match(item.salePrice, /^\d+\.\d{2}$/);
    }
});

test("цена из описания или от покупки нескольких штук не выдаётся за обычную цену", () => {
    const withoutPrice = { ...sample.data[0], id: "1", price: null, description: "От 2 шт — 134,99 ₽",
        progressiveDiscount: [{ count: 2, value: 13499 }] };
    assert.deepEqual(convertOffers([withoutPrice]), []);
    assert.equal(convertOffers([withoutPrice, ...sample.data]).length, 2);
    assert.equal(normalizeDate("2026-02-30"), null);
    assert.equal(normalizeDate("2026-11-17"), "2026-11-17");
    assert.equal(normalizeDate(null), null);
});

test("сбой, timeout, повреждённый и пустой ответ не перезаписывают каталог", () => {
    const destination = temporaryCatalog();
    writeFileSync(destination, "old catalog");
    const responses = [
        { status: 1, stderr: "failure" },
        { error: Object.assign(new Error("timed out"), { code: "ETIMEDOUT" }) },
        { status: 0, stdout: "not JSON" },
        { status: 0, stdout: JSON.stringify({ ok: false, class: "network" }) },
        { status: 0, stdout: JSON.stringify({ ok: true, data: [] }) },
        { status: 0, stdout: JSON.stringify({ ok: true, data: {} }) },
    ];
    for (const response of responses) {
        assert.throws(() => updateCatalog({ force: true, destination,
            fetchOffers: () => loadMagnitOffers({ run: () => response }) }));
        assert.equal(readFileSync(destination, "utf8"), "old catalog");
    }
    assert.throws(() => saveOffers([], destination));
    let ticks = 0;
    assert.throws(() => updateCatalog({ force: true, destination, fetchOffers: () => sample.data,
        now: () => ticks++ === 0 ? 0 : 30_001 }), /30 секунд/);
    assert.equal(readFileSync(destination, "utf8"), "old catalog");
});

test("ответ без цен сохраняет предыдущий файл; кэш другого города не считается свежим", () => {
    const destination = temporaryCatalog();
    writeFileSync(destination, "previous catalog");
    assert.throws(() => updateCatalog({ force: true, destination,
        fetchOffers: () => [{ ...sample.data[0], price: null }] }), /с ценой/);
    assert.equal(readFileSync(destination, "utf8"), "previous catalog");
    assert.equal(catalogIsFresh(destination), false);
    const good = convertOffers(sample.data);
    for (const bad of [[], good.map(x => ({ ...x, salePrice: null })),
        good.map(x => ({ ...x, region: "Краснодар" })),
        good.map(x => ({ ...x, url: x.url.replace("667052", "992301") }))]) {
        writeFileSync(destination, JSON.stringify(bad));
        assert.equal(catalogIsFresh(destination), false);
    }
    writeFileSync(destination, JSON.stringify(good));
    assert.equal(catalogIsFresh(destination), true);
    const old = new Date(Date.now() - 25 * 60 * 60 * 1000);
    utimesSync(destination, old, old);
    assert.equal(catalogIsFresh(destination), false);
});

test("локальный CLI запускается через Node; повторная запись не создаёт дубли", () => {
    const fromFixture = loadMagnitOffers({ run: (command, args, options) => {
        assert.equal(command, process.execPath);
        assert.match(args[0], /api-anything[/\\]dist[/\\]cli\.js$/);
        assert.deepEqual(args.slice(1), ["call", "magnit-ru", "listOffers"]);
        assert.equal(options.timeout, 30_000);
        return { status: 0, stdout: JSON.stringify(sample) };
    } });
    assert.equal(fromFixture.length, 2);
    const destination = temporaryCatalog();
    for (let i = 0; i < 2; i++) {
        updateCatalog({ force: true, destination, fetchOffers: () => fromFixture });
        const actual = JSON.parse(readFileSync(destination, "utf8"));
        assert.equal(actual.length, 2);
        assert.equal(actual[0].salePrice, "99.99");
    }
});
