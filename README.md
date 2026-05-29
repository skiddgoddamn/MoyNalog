# moynalog-client

Java-библиотека для работы с API сервиса [«Мой налог»](https://lknpd.nalog.ru) (ФНС России).  
Позволяет программно регистрировать доходы, получать чеки и выставлять счета для самозанятых.

---

## Возможности

- Аутентификация по логину и паролю с автоматическим retry
- Автоматическое обновление токена (без повторного ввода пароля)
- Отправка чека: синхронно и асинхронно
- **Выставление счетов** с реквизитами банковского счёта
- **Поддержка прокси** (HTTP/HTTPS, с аутентификацией и без)
- Настраиваемый таймаут запросов
- Потокобезопасная работа (ReadWriteLock на обновление токена)
- Spring Boot Starter — достаточно двух строк в `application.properties`
- Работает и без Spring — как обычная Java-библиотека

---

## Подключение

Установите библиотеку в локальный Maven-репозиторий:

```bash
mvn install
```

Добавьте зависимость в проект-потребитель:

```xml
<dependency>
    <groupId>io.github.skiddgoddamn</groupId>
    <artifactId>moynalog-client</artifactId>
    <version>1.1.5</version>
</dependency>
```

---

## Использование со Spring Boot

Добавьте учётные данные в `application.properties`:

```properties
moy-nalog.username=ваш_логин
moy-nalog.password=ваш_пароль
```

Библиотека автоматически создаст и зарегистрирует бин `MoyNalogClient`.  
Аутентификация выполняется **при старте приложения** — если учётные данные неверны, приложение не запустится.

Используйте клиент через инъекцию зависимостей:

```java
@Service
public class TaxService {

    private final MoyNalogClient moyNalogClient;

    public TaxService(MoyNalogClient moyNalogClient) {
        this.moyNalogClient = moyNalogClient;
    }

    public Receipt registerIncome() {
        return moyNalogClient.addIncome(List.of(
            new IncomeItem("Консультация", 1, 5000.0),
            new IncomeItem("Разработка", 3, 10000.0)
        ));
    }
}
```

### Дополнительные настройки

| Свойство | По умолчанию | Описание |
|---|---|---|
| `moy-nalog.username` | — | Логин (обязательно) |
| `moy-nalog.password` | — | Пароль (обязательно) |
| `moy-nalog.zone-offset` | `Z` (UTC) | Часовой пояс чека, например `+03:00` |
| `moy-nalog.api-path` | `https://lknpd.nalog.ru/api/v1` | Базовый URL API |
| `moy-nalog.prefix` | _(пусто)_ | Префикс генерируемого ID устройства |
| `moy-nalog.request-timeout` | `30` | Таймаут ожидания ответа от сервера (секунды) |
| `moy-nalog.proxy.host` | _(не задан)_ | Хост прокси-сервера |
| `moy-nalog.proxy.port` | `8080` | Порт прокси-сервера |
| `moy-nalog.proxy.username` | _(не задан)_ | Логин для аутентификации на прокси |
| `moy-nalog.proxy.password` | _(не задан)_ | Пароль для аутентификации на прокси |

---

## Использование без Spring

```java
// С настройками по умолчанию
MoyNalogClient client = new MoyNalogClient();
client.init("ваш_логин", "ваш_пароль");

// Или с кастомной конфигурацией
MoyNalogClientConfig config = new MoyNalogClientConfig();
config.setZoneOffset("+03:00");
config.setRequestTimeout(60); // секунды

MoyNalogClient client = new MoyNalogClient(config);
AuthenticationDTO.Profile profile = client.init("ваш_логин", "ваш_пароль");

System.out.println("Авторизован: " + profile.getInn());
```

---

## Прокси

### Через application.properties (Spring Boot)

```properties
moy-nalog.proxy.host=proxy.example.com
moy-nalog.proxy.port=8080
# Если прокси требует аутентификации:
moy-nalog.proxy.username=proxyuser
moy-nalog.proxy.password=proxypass
```

### Без Spring

```java
MoyNalogClientConfig config = new MoyNalogClientConfig();
config.setProxyHost("proxy.example.com");
config.setProxyPort(8080);
// Если прокси требует аутентификации:
config.setProxyUsername("proxyuser");
config.setProxyPassword("proxypass");

MoyNalogClient client = new MoyNalogClient(config);
client.init("ваш_логин", "ваш_пароль");
```

---

## Отправка чека

### Синхронно

```java
List<IncomeItem> services = List.of(
    new IncomeItem("Консультация", 1, 3000.0),   // 1 × 3000 = 3000 руб.
    new IncomeItem("Обучение",     2, 1500.0)    // 2 × 1500 = 3000 руб.
);

Receipt receipt = client.addIncome(services);

System.out.println("UUID:      " + receipt.uuid());
System.out.println("JSON-чек:  " + receipt.jsonUrl());
System.out.println("Для печати:" + receipt.printUrl());
```

### Асинхронно

```java
CompletableFuture<Receipt> future = client.addIncomeAsync(services);

future.thenAccept(receipt -> System.out.println("Чек зарегистрирован: " + receipt.uuid()))
      .exceptionally(ex -> { System.err.println("Ошибка: " + ex.getMessage()); return null; });
```

### Чек с указанием контрагента

```java
// доход от юрлица/ИП
Receipt receipt = client.addIncome(
    List.of(new IncomeItem("Разработка", 1, new BigDecimal("50000.00"))),
    Counterparty.legalEntity("7700000000", "ООО «Ромашка»")
);
```

---

## Аннулирование чека

```java
client.cancelReceipt(receipt.uuid(), CancelReason.REFUND);          // возврат средств
client.cancelReceipt(receipt.uuid(), CancelReason.ERROR);           // сформирован ошибочно
```

---

## История доходов, профиль и налоги

```java
IncomesPage page = client.getIncomes(new IncomesQuery()
    .from(OffsetDateTime.now().minusMonths(1))
    .limit(50));
page.getContent().forEach(r -> System.out.println(r.getApprovedReceiptUuid() + " — " + r.getTotalAmount()));

AuthenticationDTO.Profile profile = client.getUser();   // актуальный профиль
TaxInfo tax = client.getTaxes();                        // налог к уплате / задолженность
String receiptJson = client.getReceiptJson(receipt.uuid());
```

---

## Выставление счёта

### 1. Получить сохранённые реквизиты

```java
// true — только избранные реквизиты, false — все
List<PaymentType> paymentTypes = client.getPaymentTypes(true);
PaymentType account = paymentTypes.get(0);
```

### 2. Выставить счёт

```java
List<IncomeItem> services = List.of(
    new IncomeItem("Разработка сайта", 1, 50000.0)
);

Invoice invoice = client.createInvoice(
    account,                          // реквизиты оплаты
    "ИП Иванов Иван Иванович",        // наименование плательщика
    "123456789012",                   // ИНН плательщика
    ClientType.FROM_LEGAL_ENTITY,     // тип плательщика
    services
);

System.out.println("Статус:        " + invoice.getStatus());
System.out.println("Сумма:         " + invoice.getTotalAmount());
System.out.println("Ссылка оплаты: " + invoice.getTransitionPageURL());
```

### Счёт физлицу и отмена

```java
// физлицу — с телефоном и email вместо ИНН
Invoice invoice = client.createInvoiceForIndividual(account, "Иван", "79001234567", "ivan@mail.ru", services);

// отмена ранее выставленного счёта
client.cancelInvoice(invoice.getInvoiceId());
```

---

## Модель данных

### `IncomeItem` — позиция в чеке / счёте

| Поле | Тип | Описание |
|---|---|---|
| `name` | `String` | Наименование услуги |
| `quantity` | `int` | Количество единиц |
| `amount` | `double` | Цена за **одну** единицу (не итог) |

> Итоговая сумма по услуге рассчитывается автоматически: `amount × quantity`.

### `Receipt` — подтверждение регистрации чека

| Поле | Тип | Описание |
|---|---|---|
| `uuid` | `String` | Идентификатор чека в ФНС |
| `jsonUrl` | `String` | Ссылка на данные чека (JSON) |
| `printUrl` | `String` | Ссылка на чек для печати |

### `PaymentType` — реквизиты оплаты

| Поле | Тип | Описание |
|---|---|---|
| `id` | `Long` | Идентификатор в системе ФНС |
| `type` | `String` | Тип: `ACCOUNT`, `CARD` и др. |
| `bankName` | `String` | Наименование банка |
| `bankBik` | `String` | БИК банка |
| `currentAccount` | `String` | Расчётный счёт |
| `corrAccount` | `String` | Корреспондентский счёт |
| `favorite` | `Boolean` | Является ли избранным |

### `ClientType` — тип плательщика

| Значение | Описание |
|---|---|
| `ClientType.FROM_LEGAL_ENTITY` | Юридическое лицо или ИП |
| `ClientType.FROM_INDIVIDUAL` | Физическое лицо |

### `Invoice` — выставленный счёт

| Поле | Тип | Описание |
|---|---|---|
| `invoiceId` | `Long` | Числовой идентификатор счёта |
| `uuid` | `String` | UUID счёта |
| `status` | `String` | Статус: `CREATED`, `PAID`, `CANCELLED` |
| `transitionPageURL` | `String` | Ссылка на страницу оплаты |
| `clientType` | `ClientType` | Тип плательщика |
| `clientName` | `String` | Наименование плательщика |
| `clientInn` | `String` | ИНН плательщика |
| `totalAmount` | `BigDecimal` | Итоговая сумма (руб.) |
| `totalTax` | `BigDecimal` | Сумма налога (руб.) |
| `services` | `List<ServiceItem>` | Список позиций |
| `createdAt` | `String` | Время создания (ISO-8601) |
| `paidAt` | `String` | Время оплаты (`null`, если не оплачен) |
| `cancelledAt` | `String` | Время отмены (`null`, если не отменён) |

---

## Обработка ошибок

| Исключение | Когда выбрасывается |
|---|---|
| `ApiRequestException` | Сервер вернул код ответа, отличный от 200. Содержит `statusCode` и `body`. |
| `ApiException` | Сетевая ошибка или прерывание потока. |
| `IllegalStateException` | Вызов метода до `init`. |

```java
try {
    Receipt receipt = client.addIncome(services);
} catch (ApiRequestException e) {
    System.err.println("Код ответа: " + e.getStatusCode());
    System.err.println("Ответ сервера: " + e.getBody());
} catch (ApiException e) {
    System.err.println("Сетевая ошибка: " + e.getMessage());
}
```

---

## Как работает обновление токена

Токен доступа имеет ограниченный срок жизни. Перед каждым вызовом `addIncome`, `createInvoice` и `getPaymentTypes` библиотека проверяет, не истёк ли токен, и при необходимости автоматически обновляет его через refresh-токен — без повторного ввода пароля.

Для безопасной работы в многопоточной среде используется `ReadWriteLock`:
- Обычные запросы захватывают **read-lock** — выполняются параллельно.
- Обновление токена захватывает **write-lock** — блокирует новые запросы до завершения обновления.

---

## Changelog

### 1.1.5
- **Аннулирование чека**: `cancelReceipt(uuid, CancelReason)` + асинхронный вариант
- **История доходов**: `getIncomes(IncomesQuery)` с фильтром по периоду и пагинацией
- **Профиль** и **налоги**: `getUser()`, `getTaxes()`
- **Отмена счёта**: `cancelInvoice(invoiceId)`; выставление счёта физлицу с телефоном/email — `createInvoiceForIndividual(...)`
- **Чек по контрагенту**: перегрузка `addIncome(services, Counterparty)` (физлицо/юрлицо/иностранная организация); загрузка JSON чека — `getReceiptJson(uuid)`
- Суммы `IncomeItem` переведены на `BigDecimal` (точные деньги); конструктор с `double` сохранён для совместимости
- Надёжность: автоматический повтор запроса с обновлением токена при `401`; обновление токена с запасом 60 сек до истечения; устранена гонка двойного обновления токена
- `MoyNalogClient` теперь `AutoCloseable` (освобождение пула соединений); в Spring закрывается автоматически
- Исключения сохраняют первопричину (`cause`)
- Добавлены тесты на mock HTTP-сервере

### 1.1.4
- Переход с JDK `java.net.http.HttpClient` на Apache HttpClient5: его `AuthenticationFilter` на любой `401` (от прокси или от target) требовал заголовок `WWW-Authenticate` и при его отсутствии бросал синтетическое исключение, проглатывая реальное тело ответа
- Убран JDK `Authenticator`, проглатывавший `401` от target; `Proxy-Authorization` теперь отправляется вручную
- Apache HttpClient5 различает proxy/target через `AuthScope` и корректно возвращает `401` от target как обычный ответ

### 1.1.3
- Убрана принудительная фиксация `HTTP/1.1` — клиент теперь автоматически согласует протокол (HTTP/1.1 / HTTP/2) через TLS ALPN
- Добавлен настраиваемый таймаут ответа (`moy-nalog.request-timeout`, по умолчанию 30 сек)
- Добавлен автоматический retry при аутентификации в случае transient сетевой ошибки

### 1.1.1
- Добавлен enum `ClientType` (`FROM_LEGAL_ENTITY`, `FROM_INDIVIDUAL`) вместо строки в `createInvoice`

### 1.1.0
- Добавлена поддержка прокси (с аутентификацией и без)
- Добавлены методы `getPaymentTypes()` и `createInvoice()`
- Новые модели: `PaymentType`, `Invoice`

### 1.0.1
- Первый публичный релиз
