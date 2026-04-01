# moynalog-client

Java-библиотека для работы с API сервиса [«Мой налог»](https://lknpd.nalog.ru) (ФНС России).  
Позволяет программно регистрировать доходы и получать чеки для самозанятых.

---

## Возможности

- Аутентификация по логину и паролю
- Автоматическое обновление токена (без повторного ввода пароля)
- Отправка чека: синхронно и асинхронно
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
    <version>1.0.1</version>
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

---

## Использование без Spring

```java
// С настройками по умолчанию
MoyNalogClient client = new MoyNalogClient();
client.init("ваш_логин", "ваш_пароль");

// Или с кастомной конфигурацией
MoyNalogClientConfig config = new MoyNalogClientConfig();
config.setZoneOffset("+03:00");

MoyNalogClient client = new MoyNalogClient(config);
AuthenticationDTO.Profile profile = client.init("ваш_логин", "ваш_пароль");

System.out.println("Авторизован: " + profile.getInn());
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

---

## Модель данных

### `IncomeItem` — позиция в чеке

| Поле | Тип | Описание |
|---|---|---|
| `name` | `String` | Наименование услуги |
| `quantity` | `int` | Количество единиц |
| `amount` | `double` | Цена за **одну** единицу (не итог) |

> Итоговая сумма по услуге рассчитывается автоматически: `amount × quantity`.

### `Receipt` — подтверждение регистрации

| Поле | Тип | Описание |
|---|---|---|
| `uuid` | `String` | Идентификатор чека в ФНС |
| `jsonUrl` | `String` | Ссылка на данные чека (JSON) |
| `printUrl` | `String` | Ссылка на чек для печати |

---

## Обработка ошибок

| Исключение | Когда выбрасывается |
|---|---|
| `ApiRequestException` | Сервер вернул код ответа, отличный от 200. Содержит `statusCode` и `body`. |
| `ApiException` | Сетевая ошибка или прерывание потока. |
| `IllegalStateException` | Вызов `addIncome` до `init`. |

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

Токен доступа имеет ограниченный срок жизни. Перед каждым вызовом `addIncome` библиотека проверяет, не истёк ли токен, и при необходимости автоматически обновляет его через refresh-токен — без повторного ввода пароля.

Для безопасной работы в многопоточной среде используется `ReadWriteLock`:
- Обычные запросы (`addIncome`) захватывают **read-lock** — выполняются параллельно.
- Обновление токена захватывает **write-lock** — блокирует новые запросы до завершения обновления.
