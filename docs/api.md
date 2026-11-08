# API FinSmart для Android: подключение и примеры

Полный контракт — [`docs/api/openapi.yaml`](api/openapi.yaml). Здесь — как подключиться к локальному backend и реальные ответы стенда (собраны 07.11.2026 на `docker compose up` с фикстурами из `fixtures/`). Длинные списки в примерах сокращены — это отмечено под примером.

## Подключение

Backend поднимается командой из README: `docker compose up --build`. Проверка: `GET /health` → `{"status":"ok","database":"ok"}`.

| Где запущено приложение | `BASE_URL` |
|---|---|
| Эмулятор Android Studio на том же компьютере | `http://10.0.2.2:8080/` |
| Телефон в той же Wi-Fi-сети | `http://<IP компьютера в локальной сети>:8080/` |

- `10.0.2.2` — адрес компьютера изнутри эмулятора; `localhost` в эмуляторе указывает на сам эмулятор.
- Если порт 8080 занят, в `.env` задаётся `API_PORT`, и в `BASE_URL` меняется только порт.
- Для телефона по Wi-Fi входящие соединения на порт API должен пропускать брандмауэр Windows.
- Локальный backend работает по HTTP. В PR №9 это уже разрешено (`android:usesCleartextTraffic="true"`). CORS для Android не нужен: это механизм браузера.
- `BASE_URL` удобно задавать через `buildConfigField`, а не константой в коде, чтобы эмулятор и телефон отличались только сборкой.

## Общие правила

- Тело запросов и ответов — JSON (`Content-Type: application/json`), кроме импорта CSV (`multipart/form-data`).
- Защищённые запросы — с заголовком `Authorization: Bearer <accessToken>`. Без токена работают только `/health`, `/categories` и `/auth/*`.
- Деньги — строка с двумя знаками после точки (`"-312.30"`). Во входящих запросах можно `"5000"` или `"5000.5"`. Хранить и считать — `BigDecimal`, не `Double`.
- Даты — `YYYY-MM-DD`, время — ISO 8601 в UTC (`2026-11-07T07:22:25.617237Z`). Месяц в параметрах — `YYYY-MM`.
- Категории передаются кодом (`groceries`, `cafes`, …). Русские названия для экрана — из `GET /categories`.
- Любая ошибка имеет одинаковый формат, а `details` — словарь «поле → причина» или `null`:

```json
{"code": "validation_error", "message": "Некорректные данные", "details": {"category": "Неизвестная категория"}}
```

`message` можно показывать пользователю как есть. Логику строить по `code`.

## Авторизация, refresh и logout

### Регистрация и вход

```
POST /auth/register          → 201
POST /auth/login             → 200
{"email": "user@example.com", "password": "password123"}
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "jIfkyDa7cb4zgxYusD2JtX55r062vVUT60dyAZE51dM",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {"id": "97776946-3e7f-4337-82d6-b75ad6da8137", "email": "user@example.com"}
}
```

| Ситуация | Ответ |
|---|---|
| email уже занят | `409` `email_taken` |
| некорректный email или пароль короче 8 символов | `400` `validation_error`, `details`: `{"email": "...", "password": "..."}` |
| неверный email или пароль при входе | `401` `invalid_credentials` |
| больше 10 запросов входа и регистрации в минуту с одного IP | `429` `too_many_requests`, заголовок `Retry-After` — через сколько секунд повторить |

`/auth/refresh` и `/auth/logout` под этот лимит не попадают.

Сохранять нужно **оба** токена: `accessToken` живёт 1 час (`expiresIn` в секундах), `refreshToken` — 30 дней. Хранить их — в защищённом хранилище (EncryptedSharedPreferences / Keystore), не в Room.

### Обновление access-токена

```
POST /auth/refresh  → 200
{"refreshToken": "jIfkyDa7cb4zgxYusD2JtX55r062vVUT60dyAZE51dM"}
```

Ответ — того же вида, что у входа, с новым `accessToken`. `refreshToken` при этом **не меняется** и действует до своего срока. Недействительный, истёкший или отозванный refresh-токен → `401` `invalid_refresh_token`.

### Что делать при 401

1. Защищённый запрос вернул `401` с кодом `unauthorized` (токен истёк, испорчен или не передан).
2. Клиент один раз вызывает `POST /auth/refresh` и повторяет исходный запрос с новым `accessToken`.
3. Если refresh вернул `401` `invalid_refresh_token` — удалить оба токена и открыть экран входа.

- Не вызывать refresh на `401` от `/auth/login`: там код `invalid_credentials`, это неверный пароль.
- При нескольких параллельных запросах refresh должен выполняться один раз (например, `Authenticator` в OkHttp с синхронизацией), остальные ждут новый токен.

### Выход

```
POST /auth/logout  → 204 (без тела)
{"refreshToken": "jIfkyDa7cb4zgxYusD2JtX55r062vVUT60dyAZE51dM"}
```

Отзывает refresh-токен; `accessToken` для этого не нужен. Ответ `204` приходит и для уже недействительного токена. После ответа (или без сети — сразу) клиент удаляет оба токена. Refresh после выхода → `401` `invalid_refresh_token`.

## Категории

```
GET /categories  → 200
```

```json
[
  {"code": "cafes", "name": "Кафе и рестораны"},
  {"code": "entertainment", "name": "Развлечения"},
  {"code": "groceries", "name": "Продукты"},
  {"code": "health", "name": "Здоровье"},
  {"code": "home", "name": "Дом"},
  {"code": "other", "name": "Прочее"},
  {"code": "transport", "name": "Транспорт"}
]
```

## Импорт CSV

```
POST /transactions/import  → 200
Content-Type: multipart/form-data, поле file — CSV-файл (до 1 МБ и 5000 строк)
```

`fixtures/csv/valid.csv`, первый раз и повторно:

```json
{"imported": 37, "duplicates": 0, "failed": 0, "errors": []}
{"imported": 0, "duplicates": 37, "failed": 0, "errors": []}
```

`fixtures/csv/invalid.csv` — корректные строки сохраняются, по ошибочным приходит номер строки и поле (`field: null` — ошибка всей строки):

```json
{
  "imported": 2,
  "duplicates": 0,
  "failed": 12,
  "errors": [
    {"line": 4, "field": "date", "reason": "Дата должна быть в формате YYYY-MM-DD"},
    {"line": 9, "field": "amount", "reason": "Сумма не может быть нулевой"},
    {"line": 13, "field": null, "reason": "Ожидалось 6 столбцов"}
  ]
}
```

В примере 3 из 12 ошибок; в настоящем ответе `errors` содержит все 12.

Поле с числом ошибочных строк называется `failed`. Файл пустой, без поля `file` или с неверным заголовком → `400` `invalid_csv`; больше 1 МБ или 5000 строк → `413` `file_too_large`; больше 10 импортов в минуту от одного пользователя → `429` `too_many_requests`.

## Операции

```
GET /transactions?month=2026-10&limit=2&offset=0  → 200
```

Параметры необязательные: `month`, `category`, `limit` (1–200, по умолчанию 50), `offset`. Сортировка — новые сверху.

```json
{
  "items": [
    {
      "id": "293f5a65-b339-4559-bc72-a7796a048d37",
      "operationId": "2db838d9-7265-5cfe-a914-797aa267ff1e",
      "date": "2026-10-30",
      "amount": "-312.30",
      "currency": "RUB",
      "description": "MAGNIT MM EKATERINBURG",
      "mcc": "5411",
      "category": "groceries",
      "categoryManual": false
    }
  ],
  "total": 24,
  "limit": 2,
  "offset": 0
}
```

В примере показана 1 из 2 операций ответа.

### Ручная смена категории

```
PATCH /transactions/{id}/category  → 200
{"category": "cafes"}
```

Поле тела — `category`. Ответ — обновлённая операция с `"category": "cafes"` и `"categoryManual": true`. Повторный импорт эту категорию не перезаписывает. Неизвестный код → `400`, `details: {"category": "Неизвестная категория"}`; чужая или несуществующая операция → `404` `not_found`.

## Аналитика

```
GET /analytics/summary?month=2026-10  → 200
```

```json
{
  "month": "2026-10",
  "previousMonth": "2026-09",
  "totalExpense": "30528.15",
  "previousTotalExpense": "13338.90",
  "changePercent": 128.9,
  "topCategory": "home",
  "transactionsCount": 23,
  "categories": [
    {"category": "home", "amount": "8690.00", "previousAmount": "3500.00", "changePercent": 148.3, "sharePercent": 28.5},
    {"category": "other", "amount": "2000.00", "previousAmount": "0.00", "changePercent": null, "sharePercent": 6.6},
    {"category": "entertainment", "amount": "760.00", "previousAmount": "2619.00", "changePercent": -71.0, "sharePercent": 2.5}
  ]
}
```

В примере 3 из 7 категорий ответа.

- `month` обязателен; без него или в другом формате → `400`.

- Учитываются только расходы; суммы положительные (по модулю).
- `changePercent: null` — в прошлом месяце расходов в категории не было.
- **Данные фикстур — сентябрь и октябрь 2026.** Для другого месяца ответ будет с нулями: `GET /analytics/summary?month=2026-11` → `"totalExpense": "0.00"`, `"topCategory": null`, `"transactionsCount": 0`, у категорий `"changePercent": -100.0`. Для демо на фикстурах нужен месяц `2026-10`.

## Цели

```
POST /goals  → 201
{"title": "Ноутбук", "targetAmount": "150000", "currentAmount": "20000", "deadline": "2027-06-01"}
```

```json
{
  "id": "e9a88aa0-139d-4391-9a63-e06bdce4b874",
  "title": "Ноутбук",
  "type": "saving",
  "targetAmount": "150000.00",
  "currentAmount": "20000.00",
  "progressPercent": 13.3,
  "deadline": "2027-06-01",
  "category": null,
  "createdAt": "2026-11-07T07:22:25.617237Z",
  "updatedAt": "2026-11-07T07:22:25.617237Z"
}
```

Лимит расходов на категорию:

```
POST /goals  → 201
{"title": "Кафе не больше 5000", "type": "spending_limit", "targetAmount": "5000", "category": "cafes"}
```

```json
{
  "id": "f281f972-1d7f-4ec8-a164-67848cd61771",
  "title": "Кафе не больше 5000",
  "type": "spending_limit",
  "targetAmount": "5000.00",
  "currentAmount": "0.00",
  "progressPercent": 0.0,
  "deadline": null,
  "category": "cafes",
  "createdAt": "2026-11-07T07:22:25.861111Z",
  "updatedAt": "2026-11-07T07:22:25.861111Z"
}
```

- `type`: `saving` (по умолчанию) или `spending_limit`.
- У лимита `category` обязательна, а `currentAmount` — потрачено в категории за **текущий** месяц, его считает сервер (вводить не нужно).

```
PATCH /goals/{id}  → 200
{"currentAmount": "35000"}
```

Передаются только меняемые поля; ответ — цель целиком (`"currentAmount": "35000.00"`, `"progressPercent": 23.3`). `type` после создания не меняется.

```
GET /goals  → 200
```

Массив целей, новые сверху.

## Скидки

```
GET /offers?category=groceries  → 200
```

Параметры необязательные: `category`, `region`. Истёкшие акции не возвращаются. Порядок — по выгоде (`potentialSaving`) по убыванию.

```json
{
  "items": [
    {
      "id": "08ce8032-b0d2-42b1-99b0-c23ea296b2b5",
      "sourceId": "demo-magnit-003",
      "title": "[ДЕМО] Кофе молотый, 250 г",
      "category": "groceries",
      "regularPrice": "429.99",
      "salePrice": "319.99",
      "currency": "RUB",
      "validFrom": "2026-10-28",
      "validTo": "2026-11-08",
      "conditions": null,
      "url": "https://magnit.ru/",
      "region": "Екатеринбург",
      "checkedAt": "2026-10-29T16:00:00Z",
      "potentialSaving": "110.00"
    }
  ],
  "catalogCheckedAt": "2026-10-29T16:00:00Z"
}
```

В примере показана 1 акция из списка.

- `regularPrice`, `salePrice`, `potentialSaving`, `validFrom`, `validTo`, `conditions`, `region` могут быть `null`.
- Пустой `items` — подходящих акций нет, это не ошибка.
- `GET /offers/{id}` возвращает одну акцию того же вида; истёкшая → `404`.
- Неизвестная категория → `400`, `details: {"category": "Неизвестная категория"}`.

## Рекомендация

```
GET /ai/recommendation?month=2026-10  → 200
```

```json
{
  "title": "Расходы выросли",
  "message": "Проверьте расходы в категории home и сравните их с предыдущим месяцем.",
  "category": "home",
  "actionType": "open_transactions",
  "actionLabel": "Посмотреть расходы",
  "source": "rules"
}
```

`month` обязателен. `actionType`: `open_offers`, `open_goals`, `open_transactions` или `none`; при `open_offers` экран скидок открывается с фильтром `category`. Для месяца без операций приходит нейтральная рекомендация с `"category": null`.

## Типовые ошибки

| HTTP | `code` | Когда |
|---|---|---|
| 400 | `validation_error` | Неверные параметры или тело; подробности в `details` |
| 400 | `invalid_csv` | Файл не похож на CSV нужного формата |
| 401 | `unauthorized` | Нет access-токена, он истёк или испорчен → refresh |
| 401 | `invalid_credentials` | Неверный email или пароль при входе |
| 401 | `invalid_refresh_token` | Refresh-токен недействителен → экран входа |
| 404 | `not_found` | Объекта нет или он принадлежит другому пользователю |
| 409 | `email_taken` | Email уже зарегистрирован |
| 413 | `file_too_large` | CSV больше 1 МБ или 5000 строк |
| 415 | `unsupported_media_type` | Неверный `Content-Type`: для JSON-запросов нужен `application/json`, для импорта — `multipart/form-data` |
| 429 | `too_many_requests` | Превышен лимит: вход и регистрация — 10 в минуту с IP, импорт — 10 в минуту на пользователя. Повторить через `Retry-After` секунд |

## Проверка с компьютера

На Windows кириллицу в JSON надёжнее передавать файлом, а не строкой в командной строке: `curl --data-binary @body.json -H "Content-Type: application/json" ...`. Сквозной сценарий — `scripts/smoke.sh`.
