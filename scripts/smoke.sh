#!/usr/bin/env bash
# Сквозная проверка демо-сценария по ТЗ (раздел 10) на запущенном сервере.
# Использование: API=http://localhost:8080 CSV_DIR=fixtures/csv ./scripts/smoke.sh
# Нужны curl и python3 (для разбора JSON). Фикстуры CSV — из PR «CSV-фикстуры».
set -u
API=${API:-http://localhost:8080}
CSV_DIR=${CSV_DIR:-fixtures/csv}
EMAIL="smoke-$(date +%s)@finsmart.local"
PASS=0
FAIL=0

for cand in python3 python; do "$cand" -c "import json" 2>/dev/null && PY=$cand && break; done
json() { "$PY" -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
check() { # check "описание" "ожидаемое" "фактическое"
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "ok    $1"; else FAIL=$((FAIL+1)); echo "FAIL  $1: ожидалось '$2', получено '$3'"; fi
}
req() { # req METHOD PATH [curl args...] -> тело; код в $CODE
  local method=$1 path=$2; shift 2
  local out; out=$(curl -s -w '\n%{http_code}' -X "$method" "$API$path" "$@")
  CODE=${out##*$'\n'}; BODY=${out%$'\n'*}
}

req POST /auth/register -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"demo12345\"}"
check "регистрация -> 201" 201 "$CODE"
TOKEN=$(echo "$BODY" | json 'd["accessToken"]'); REFRESH=$(echo "$BODY" | json 'd["refreshToken"]')
AUTH=(-H "Authorization: Bearer $TOKEN")

req POST /auth/register -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"demo12345\"}"
check "повторная регистрация -> 409 email_taken" "409 email_taken" "$CODE $(echo "$BODY" | json 'd["code"]')"
req POST /auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"wrong-pass\"}"
check "неверный пароль -> 401" 401 "$CODE"
req GET /transactions
check "без токена -> 401 unauthorized" "401 unauthorized" "$CODE $(echo "$BODY" | json 'd["code"]')"
req POST /auth/refresh -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}"
check "refresh -> 200" 200 "$CODE"

req POST /transactions/import "${AUTH[@]}" -F "file=@$CSV_DIR/valid.csv"
check "импорт valid.csv: imported" 12 "$(echo "$BODY" | json 'd["imported"]')"
req POST /transactions/import "${AUTH[@]}" -F "file=@$CSV_DIR/valid.csv"
check "повторный импорт valid.csv: duplicates" 12 "$(echo "$BODY" | json 'd["duplicates"]')"
req POST /transactions/import "${AUTH[@]}" -F "file=@$CSV_DIR/invalid.csv"
check "импорт invalid.csv: imported/failed" "2/6" "$(echo "$BODY" | json 'str(d["imported"])+"/"+str(d["failed"])')"
check "invalid.csv: строки с ошибками" "[3, 4, 5, 6, 7, 8]" "$(echo "$BODY" | json '[e["line"] for e in d["errors"]]')"
req POST /transactions/import "${AUTH[@]}" -F "file=@$CSV_DIR/unknown_mcc.csv"
check "импорт unknown_mcc.csv: imported" 2 "$(echo "$BODY" | json 'd["imported"]')"
printf 'id,date\n1,2026-10-01\n' > /tmp/bad-header.csv
req POST /transactions/import "${AUTH[@]}" -F "file=@/tmp/bad-header.csv"
check "неверный заголовок -> 400 invalid_csv" "400 invalid_csv" "$CODE $(echo "$BODY" | json 'd["code"]')"

req GET "/analytics/summary?month=2026-10" "${AUTH[@]}"
check "аналитика: топ-категория" groceries "$(echo "$BODY" | json 'd["topCategory"]')"
check "аналитика: прошлый месяц сравнивается" "2026-09" "$(echo "$BODY" | json 'd["previousMonth"]')"
req GET "/analytics/summary" "${AUTH[@]}"
check "аналитика без month -> 400" 400 "$CODE"

req GET "/transactions?month=2026-10&category=groceries" "${AUTH[@]}"
check "фильтр операций по категории: есть данные" True "$(echo "$BODY" | json 'd["total"]>0')"
TX=$(echo "$BODY" | json 'd["items"][0]["id"]')
req PATCH "/transactions/$TX/category" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"category":"cafes"}'
check "ручная смена категории" "cafes True" "$(echo "$BODY" | json 'd["category"]+" "+str(d["categoryManual"])')"
req POST /transactions/import "${AUTH[@]}" -F "file=@$CSV_DIR/valid.csv"
req GET "/transactions?category=cafes&limit=200" "${AUTH[@]}"
check "ручная категория не перезаписана повторным импортом" True "$(echo "$BODY" | json 'any(t["id"]=="'"$TX"'" for t in d["items"])')"

req POST /goals "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"title":"Кафе не больше 5000","type":"spending_limit","targetAmount":"5000","category":"cafes"}'
check "лимит расходов создан" 201 "$CODE"
req POST /goals "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"title":"Без категории","type":"spending_limit","targetAmount":"5000"}'
check "лимит без категории -> 400" 400 "$CODE"
req POST /goals "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"title":"Отпуск","targetAmount":"100000","currentAmount":"25000"}'
GOAL=$(echo "$BODY" | json 'd["id"]')
check "накопление: прогресс 25%" 25.0 "$(echo "$BODY" | json 'd["progressPercent"]')"
req PATCH "/goals/$GOAL" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"currentAmount":"50000","deadline":null}'
check "PATCH цели: прогресс 50%" 50.0 "$(echo "$BODY" | json 'd["progressPercent"]')"

req GET "/offers?category=groceries" "${AUTH[@]}"
check "скидки: просроченные не выдаются" False "$(echo "$BODY" | json 'any(o["sourceId"]=="demo-magnit-006" for o in d["items"])')"
OFFER=$(echo "$BODY" | json 'd["items"][0]["id"]')
req GET "/offers/$OFFER" "${AUTH[@]}"
check "карточка скидки" 200 "$CODE"

req GET /goals "${AUTH[@]}"
check "лимит по кафе: потрачено считает backend из транзакций" True "$(echo "$BODY" | json '[g for g in d if g["type"]=="spending_limit"][0]["currentAmount"]!="0.00"')"

# Изоляция данных: второй пользователь не видит и не меняет чужое.
req POST /auth/register -H 'Content-Type: application/json' -d "{\"email\":\"other-$EMAIL\",\"password\":\"demo12345\"}"
AUTH2=(-H "Authorization: Bearer $(echo "$BODY" | json 'd["accessToken"]')")
req GET /transactions "${AUTH2[@]}"
check "чужие операции не видны" 0 "$(echo "$BODY" | json 'd["total"]')"
req PATCH "/transactions/$TX/category" "${AUTH2[@]}" -H 'Content-Type: application/json' -d '{"category":"home"}'
check "смена категории чужой операции -> 404" 404 "$CODE"
req PATCH "/goals/$GOAL" "${AUTH2[@]}" -H 'Content-Type: application/json' -d '{"title":"x"}'
check "изменение чужой цели -> 404" 404 "$CODE"
req GET /goals "${AUTH2[@]}"
check "чужие цели не видны" "[]" "$(echo "$BODY" | json 'd')"

printf '\nИтого: %d ok, %d fail\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
