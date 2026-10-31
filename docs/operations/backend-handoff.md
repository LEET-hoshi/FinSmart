# Передача backend-разработчику (T1)

Состояние на 31.10.2026. Ветка `feature/schema-and-csv-import` поверх `feature/server-skeleton` (PR #1).

## Что уже есть

- Ktor + PostgreSQL 16 + Flyway, запуск `docker compose up --build`, `/health`.
- Миграции V1 (схема), V2 (`refresh_tokens`), V3 (тип цели, `deleted_at`, `sync_changes`), повторяемый seed категорий и MCC.
- Единый формат ошибки `{code, message, details}`: `details` всегда присутствует (`null`), 400 для кривого запроса, 404 в том же формате, 500 для остального.
- `transactions/CsvImportParser` — разбор и валидация CSV по контракту, без БД; 11 unit-тестов (валидные строки, неизвестный MCC, дубликаты внутри файла, ошибки с номерами строк, кавычки, BOM, лимиты 1 МБ и 5000 строк).
- Тесты: `cd server && ./gradlew test` (нужен JDK 21). Проверено локально и на реальной PostgreSQL в Docker.

## Что нужно сделать (по приоритету демо)

1. **Auth.** `POST /auth/register|login|refresh|logout`, BCrypt/Argon2, JWT (access 1 ч, refresh 30 дней, хранится хеш в `refresh_tokens`), `JWT_SECRET` только из окружения, плагин Authentication. `userId` брать из токена, не из тела.
2. **Импорт.** `POST /transactions/import`: прочитать multipart, загрузить `mcc_mappings` в `Map`, вызвать `CsvImportParser.parse`, затем вставить строки `INSERT ... ON CONFLICT (user_id, external_id) DO NOTHING` и посчитать `duplicates` = повторы в файле + пропущенные вставкой. `InvalidCsvException` → 400 `invalid_csv`, `CsvTooLargeException` → 413 `file_too_large`. Ответ `ImportResult {imported, duplicates, failed, errors}`.
3. **Транзакции.** `GET /transactions` (фильтры `month`, `category`, пагинация), `PATCH /transactions/{id}/category` (ставит `category_manual = true`; при повторном импорте категория не перезаписывается — сейчас конфликт игнорируется, так что это уже выполняется).
4. **Аналитика.** `GET /analytics/summary?month=` — только `amount < 0`, по модулю; `changePercent = null`, если в прошлом месяце расходов не было; границы месяца по Asia/Yekaterinburg. Деньги — `BigDecimal`, в JSON строкой с 2 знаками.
5. **Цели.** `GET/POST/PATCH /goals`. Для `spending_limit` считать потраченное из транзакций (см. ADR 0002).
6. **Скидки.** Загрузка `fixtures/offers/offers.json` (после слияния PR #3) upsert по `source_id`, пустой/битый файл не очищает каталог; `GET /offers`, `GET /offers/{id}` — `validTo` включительно, без `validTo` бессрочно, `region` включает `null`; `potentialSaving` по правилу контракта. Цены парсить сразу в `BigDecimal`.
7. **Sync push** (ADR 0002), затем `/ai/recommendation` вместе с T3 (`RuleBasedProvider` обязателен).

## Что нужно согласовать до реализации

- **Контракт целей.** В `docs/api/openapi.yaml` (PR #2) у `Goal` пока нет поля `type` — добавить `type: saving | spending_limit` и для лимита `spentAmount`/прогресс от backend.
- **Категории предложений.** В БД `offer_categories` — многие-ко-многим, в API у предложения одна `category`. Решить: брать первую категорию или отдавать массив.
- **`isTest`.** Есть в фикстуре и БД (`offers.is_test`), в API-схему `Offer` не включён: отдавать клиенту или нет.
- **ТЗ vs контракт.** Главным выбран контракт PR #2 (строки для денег, `month`, snake_case-коды, `errors`/`category`, `open_offers`). ТЗ в этих местах устарело и требует правки.
- **Порт 8080** на машине разработчика может быть занят (у нас там Apache): задайте `API_PORT` в `.env`.

## Известные ограничения

- Возвраты (положительные суммы) расходы не уменьшают — только `amount < 0`.
- CSV с переносами строк внутри кавычек не поддерживается (строка читается построчно).
- Пароль БД и порт по умолчанию — только для разработки.
- CI (PR #4) заработает после слияния PR #1.
