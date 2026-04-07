# 🚀 Keycloak Migration SPI

<div align="center">
  <img src="https://img.shields.io/badge/Keycloak-Migration_SPI-blue?style=for-the-badge&logo=keycloak" alt="Keycloak SPI" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Author-Vitaly_Belotserkovskii-brightgreen?style=for-the-badge" alt="Author" />
  <img src="https://img.shields.io/badge/Year-2026-yellow?style=for-the-badge" alt="Year" />
</div>

---

## 📋 Описание проекта

**Keycloak Migration SPI** — это кастомный провайдер ресурсов для Keycloak, разработанный для точного и безопасного переноса конфигураций между различными средами (Realm).

Плагин обеспечивает глубокую синхронизацию сущностей, включая строгий контроль над устаревшими данными (строгая синхронизация с удалением мусора) и рекурсивную обработку сложных деревьев (например, групп и потоков аутентификации).

### ✨ Ключевые возможности:
* **Группы (Groups):** Рекурсивный экспорт и импорт всех корневых групп и их подгрупп с сохранением ролей и атрибутов.
* **Клиенты (Clients):** Комплексный перенос клиентов, включая Service Accounts, Protocol Mappers, настройки авторизации и клиентские роли.
* **Потоки аутентификации (Authentication Flows):** Полный экспорт и импорт потоков аутентификации с вложенными шагами (executions) и конфигурациями (authenticator configs).
* **Умное версионирование:** Поддержка штампов времени (`stamp`) для создания независимых копий при импорте.
* **Аудит:** Интеграция с подсистемой событий администратора Keycloak (`AdminEventBuilder`) для логирования всех операций.

---

## ⚙️ Спецификация API

Все эндпоинты API монтируются по базовому пути:
> `/admin/realms/{realm}/migrations`

Для доступа к API требуются права администратора (просмотр — `requireView()`, управление — `requireManage()`) на соответствующие ресурсы.

### 🛡️ 1. Client Scopes (Области клиентов)

Управление экспортом и импортом Client Scopes, включая назначение `default` и `optional`.

* **Экспорт (`GET /client-scopes`)**
    * **Права:** `clients().requireView()`
    * **Ответ:** `200 OK` — возвращает `ClientScopeExportDto` (содержит списки scopes и их назначения).
* **Импорт (`POST /client-scopes`)**
    * **Права:** `clients().requireManage()`
    * **Тело запроса:** `ClientScopeExportDto`
    * **Ответ:** `200 OK` — возвращает объект `ResponseDto` со статистикой (создано, обновлено, удалено).
    * **Особенность:** Добавляет атрибут `managed-by=migration-importer` и удаляет неактуальные области.

### 👥 2. Realm Roles (Роли Realm)

Синхронизация глобальных ролей в рамках Realm.

* **Экспорт (`GET /realm-roles`)**
    * **Ответ:** `200 OK` — список `RoleRepresentation`.
    * **Особенность:** Исключает системные роли (описание которых начинается с `$`).
* **Импорт (`POST /realm-roles`)**
    * **Тело запроса:** Список `RoleRepresentation`.
    * **Ответ:** `200 OK` — `ResponseDto` (статистика по ролям).

### 🗂️ 3. Groups (Группы пользователей)

Рекурсивная синхронизация древовидной структуры групп.

* **Экспорт (`GET /groups`)**
    * **Права:** `groups().requireView()`
    * **Ответ:** `200 OK` — список корневых `GroupRepresentation` с заполненным массивом `subGroups`.
* **Импорт (`POST /groups`)**
    * **Права:** `groups().requireManage()`
    * **Тело запроса:** Список `GroupRepresentation`.
    * **Ответ:** `200 OK` — `ResponseDto`.
    * **Особенность:** Выполняет строгую синхронизацию, удаляя атрибуты и подгруппы, отсутствующие в JSON импорта.

### 💻 4. Clients (Клиентские приложения)

Точечный перенос настроек клиентов по их идентификаторам (`client_id`).

* **Экспорт (`GET /clients`)**
    * **Параметры URL:** `client_ids` (строка, идентификаторы через запятую).
    * **Ответ:** `200 OK` — `ClientListExportDto` (содержит списки успешно экспортированных клиентов и тех, что не найдены).
* **Импорт (`POST /clients`)**
    * **Параметры URL:** * `stamp` (String, опционально) — штамп для модификации имени.
        * `isAlwaysCreate` (Boolean, по умолчанию `false`) — форсировать создание дубликата клиента.
    * **Тело запроса:** `ClientListExportDto`.
    * **Ответ:** `200 OK` — `ClientImportResponseDto`.

### 🔄 5. Authentication Flows (Потоки аутентификации)

Перенос сложных сценариев аутентификации.

* **Экспорт (`GET /flows`)**
    * **Параметры URL:** `alias` (название потока или `*` для экспорта всех).
    * **Ответ:** `200 OK` — `ExportFlowDto` (списки потоков и конфигураций аутентификаторов).
* **Импорт (`POST /flows`)**
    * **Параметры URL:** `stamp` (String, опционально) — временная метка для защиты от коллизий имен (если не передана, генерируется автоматически).
    * **Тело запроса:** `ExportFlowDto`.
    * **Ответ:** `200 OK` с текстом "Flows created successfully".
    * **Особенность:** К имени новых потоков добавляется маркер `migrated`.

---

## 🛠 Общие форматы данных

В проекте используются стандартные сущности Keycloak API (`RoleRepresentation`, `GroupRepresentation`, `ClientRepresentation`), упакованные в DTO:

* **`ResponseDto`**: Стандартный ответ при массовых операциях. Содержит массивы `created`, `updated`, `deleted`, `failed`.
* **`ClientExportDto`**: Агрегированная сущность клиента, включающая системного пользователя (`serviceAccountUser`), роли и настройки UMA-авторизации (`exportSettings`).

<div align="center">
  <sub>Built with ❤️ by Belotserkovskii Vitaly</sub>
</div>