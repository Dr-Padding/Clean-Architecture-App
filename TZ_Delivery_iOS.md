# ТЗ: Доставка (код + квартира + фотоотчёт) — iOS

Цель: повторить **текущее финальное** поведение Android. Без legacy-переделок и опционального polish.

---

## 1. Область

| # | Функция |
|---|---|
| 1 | Опциональный код доставки (`require_delivery_code`) |
| 2 | Поле «Номер квартиры/офис» (в форме + в `details`) |
| 3 | Автоотправка кода получателю + статус у пассажира |
| 4 | Диалог 4-значного кода у водителя + подсказка IVR |
| 5 | Кнопка «Я у отправителя» (вместо длинного текста про таксометр) |
| 6 | Фотоотчёт об исполнении (пассажир ↔ водитель) |

Тип заказа: **Доставка**, `ordertypeid == 3` (и multi-desc тариф с `multi_desc_req=true`).

---

## 2. Создание заказа (пассажир)

### 2.1. UI формы (BottomSheet / аналог `delivery_description_dialog`)

Поля из серверного `desc_list` тарифа (`PlaceDetails` / calc rate):

| Индекс | Назначение | Обязательность |
|---|---|---|
| 0…3 | Товар / Кол-во / Стоимость / Примечание (как пришлёт бэк) | **да** |
| 4 | Квартира/офис | **нет** |
| 5+ | Телефон получателя в `desc_list` **не мапить** на отдельный phone UI | — |

Для слотов 0…4:

- `title` → заголовок + placeholder
- `icon_link` → иконка (absolute URL или `domain + path`)
- fallback-иконка локальная, если `icon_link` пустой/ошибка

Отдельно (не из `desc_list`):

- **Телефон получателя** — маска, контакты, валидация 11 цифр (`8…`)
- **Чекбокс** «Выдача только по коду доставки»
  - default: **unchecked** (`false`)
  - рядом `(?)` → краткий info (тост/диалог 3–4 сек)
- Кнопка заказа («Заказать» / текст с тарифа)

### 2.2. Сборка `details` (строка для бэка)

Формат строк:

```text
{title}: {value}
```

Пример:

```text
Товар: тумба
Кол-во: 1
Стоимость: 333
Квартира/офис: 55
примечание...
```

Правила:

1. Обязательные поля 0…3 — не пустые; для `type=number` — число.
2. Квартира (слот 4) — если пусто, **не** включать в `details`.
3. Нормализовать ключи к канону (как на Android):
   - `Товар`, `Кол-во`, `Стоимость`
   - квартира: канон `Квартира/офис` (алиасы: «номер квартиры/офис», «номер квартиры или офиса», содержит «квартир»/«офис», `apartment` / `apartment_office`)
4. Примечание / неизвестные ключи — хвостом без обязательного ключа.

### 2.3. Параметры `createTaxiOrder` (доставка)

| Параметр | Тип | Когда |
|---|---|---|
| `details` / description | string | всегда (нормализованная строка) |
| `require_delivery_code` | bool | доставка: значение чекбокса |
| `apartment_office` | string? | доставка: если поле не пустое (дубль для бэка; display — через `details`) |
| телефон получателя | как сейчас | обязателен, 11 цифр |

После SUCCESS сохранить в локальную модель заказа:

- `requireDeliveryCode`
- `apartmentOffice` (если было)
- `details`

---

## 3. Экран «На исполнении» (пассажир)

### 3.1. Блок «Код доставки»

Показывать **только если**:

- `ordertypeid == 3`
- `requireDeliveryCode != false`
  - `null` (старые заказы) → **код нужен** (legacy)
  - `true` → нужен
  - `false` → блок **скрыть**
- и есть непустой `delivery_code`

UI:

- Заголовок «Код доставки»
- Значение кода
- Под кодом статус автоотправки (если пришёл):

| `delivery_code_send_status` | Текст (пример) | Цвет |
|---|---|---|
| `sent` | Код отправлен | зелёный |
| `failed` | Ошибка отправки | оранжевый |
| `pending` | Отправляется… | серый |
| иное / null | статус скрыть | — |

Источник: poll позиции водителя / статуса заказа (аналог `driverPositionOrder`), поля:

- `delivery_code`
- `require_delivery_code`
- `delivery_code_send_status`
- `apartment_office` (если есть)

FCM для статуса — **не обязательно** (на Android хватает poll).

### 3.2. Товар / фотоотчёт (пассажир)

- Показывать название товара (`item_name` / из `details`), если есть
- Чекбокс **«Фотоотчёт о выполнении»** — sync на сервер (`passengerSendItem` / ваш iOS-эквивалент с `photo_report_required`)
- Превью фото после загрузки водителем — по `delivery_photo_uploaded` + `delivery_photo_url` (если уже есть в Android-парности)

Фотоотчёт **независим** от кода доставки.

---

## 4. Водитель: карточка заказа

### 4.1. Блок описания

Показывать `details` (или `desc`, если `details` пуст) **как есть**, многострочно.  
Квартира уже внутри `details` — отдельный row не нужен.

### 4.2. Кнопки доставки (`ordertypeid == 3`)

| Кнопка | Текст |
|---|---|
| Вкл. таксометр / «у отправителя» | **«Я у отправителя»** (коротко) |
| Завершение | текст для доставки (как на Android: «Отдать посылку» / `delivery_done_button_text`) |
| Передать заказ | из `order_status_control.transfer_btn_text` или force-majeure для delivery |

### 4.3. Поля модели заказа (`FeedOrder`)

| Поле JSON | Тип | Смысл |
|---|---|---|
| `require_delivery_code` | Bool? | `null`=код нужен (legacy), `false`=не нужен, `true`=нужен |
| `apartment_office` | String? | опционально |
| `photo_report_required` | Bool | нужен фотоотчёт |
| `delivery_code_ivr_phone` | String? | номер IVR (когда бэк начнёт слать) |
| `details` | String | описание с ключами |

---

## 5. Водитель: завершение заказа (критичный flow)

Точка входа: подтверждение «заказ выполнен».

```
если не доставка → обычное закрытие

если photo_report_required == true И фото ещё не загружено водителем
    → показать ТОЛЬКО диалог обязательного фото
    → после успешного upload:
         если require_delivery_code == false → закрыть заказ
         иначе → диалог кода

иначе если require_delivery_code == false
    → закрыть заказ (без диалога кода)

иначе
    → запрос done → NEED_CONFIRMATION_CODE → диалог 4 цифр
```

**Важно:** при `require_delivery_code=false` нельзя сразу закрывать заказ, не проверив фотоотчёт.

### 5.1. Диалог обязательного фото

Layout: title + preview + «Сделать фото» + «Закрыть» + progress.

- Камера → upload на сервер
- Успех → дальше по flow (код или закрытие)
- Если во время процесса пассажир снял `photo_report_required` → не рвать upload; после завершения продолжить без фото-требования
- Если фото уже uploaded в сессии — не показывать снова

### 5.2. Диалог кода (4 цифры)

Показывать **только если** `requireDeliveryCode != false`.

- Input 4 символа
- Confirm → `feedDoneOrder` с `delivery_code` + `is_confirmed`
- Ошибки: `ERROR_INVALID_CODE`, `ERROR_CI_CONFIRM`, etc.
- Подсказка IVR:

```
resolveIvrPhone():
  if feedOrder.delivery_code_ivr_phone не пустой → его
  else → fallback "+7 747 094 02 25"  // как call_tech_support / временный хардкод
```

Текст вида: «Если у получателя нет интернета, он может позвонить на {phone}…»  
Тап по номеру → `tel:`.

Пока бэк не шлёт поле — всегда fallback; когда начнёт — подставится автоматически.

---

## 6. API-контракт (сводка)

### Create

```
require_delivery_code: Bool
apartment_office: String?   // если заполнено
details: String             // с ключами, квартира внутри если есть
```

### Poll / статус заказа (пассажир)

```
delivery_code: String?
require_delivery_code: Bool?
delivery_code_send_status: "sent" | "failed" | "pending" | null
apartment_office: String?
photo_report_required: Bool?
delivery_photo_uploaded: Bool?
delivery_photo_url: String?
```

### Feed заказа (водитель)

```
require_delivery_code: Bool?
apartment_office: String?
photo_report_required: Bool
details: String
delivery_code_ivr_phone: String?   // будущее; пока null
```

### Done (водитель)

Как на Android: сначала без кода → `NEED_CONFIRMATION_CODE` → повтор с кодом;  
если код не требуется — done без кода;  
фото — отдельный upload endpoint до done при `photo_report_required`.

---

## 7. Матрица сценариев (приёмка)

| require_code | photo_report | Ожидание водитель при «выполнен» | Пассажир |
|---|---|---|---|
| false | false | сразу закрытие | нет блока кода |
| false | true | **только** диалог фото → закрытие | нет блока кода |
| true | false | диалог кода (+ IVR hint) | код + статус |
| true | true | фото → код | код + статус |
| null (old) | * | как `true` для кода | как `true` |

Create:

- чекбокс false + квартира «55» → payload `require_delivery_code=false`, в `details` есть `Квартира/офис: 55`, у водителя в описании видно
- чекбокс true → код генерится, статус `sent` (на бою), у водителя диалог кода

Иконка/title квартиры на форме — с `desc_list[4].icon_link` / `title`.

---

## 8. Строки (RU, ориентир)

- Чекбокс: «Выдача только по коду доставки»
- Info кода: текст из `require_delivery_code_info`
- Статусы: «Код отправлен» / ошибка / ожидание
- Кнопка таксометра доставка: «Я у отправителя»
- Диалог фото: «Требуется фотоотчёт» + «Сделать фото»
- Диалог кода: заголовок ввода кода + IVR hint с `%@` номером
- Fallback IVR: `+7 747 094 02 25`

---

## 9. Что не делать

- Не брать IVR из `phone_quest` / обычного СЦ
- Не делать квартиру обязательной
- Не прятать фото-flow при `require_delivery_code=false`
- Не парсить телефон получателя как 5-й обязательный multi-desc слот
- Не ждать FCM для статуса кода — достаточно poll
- Не добавлять отдельный UI квартиры у водителя, если она уже в `details`

---

## 10. Логи (рекомендуется)

Тег вроде `DELIVERY_SERVER`:

- create: `require_delivery_code`, `apartment_office`, ordertype
- passenger poll: code / require / send_status
- driver feed: require / apartment / photo
- done flow: `delivery`, `required`, `uploadDone`, `codeRequired`
- code dialog: `ivrPhone`, `fromServer`

---

Этого достаточно, чтобы 1-в-1 закрыть Android-поведение на iOS.
