# site-backend

Бэкенд сайта «Милитант»: Quarkus 3, Kotlin, Hibernate Reactive Panache, PostgreSQL, Liquibase.

Контракт API — `docs/api.md` и `docs/openapi.yaml` в общем каталоге проекта (рядом с `frontend/`). Ответы публичного API совпадают с типами фронта `frontend/src/lib/api/types.ts` поле в поле.

## Что есть

| Часть | Пути |
|---|---|
| Публичное API, только опубликованное | `GET /api/v1/home`, `/news`, `/news/{slug}`, `/articles`, `/articles/{slug}`, `/related/{slug}`, `/newspaper`, `/newspaper/{slug}`, `/books`, `/magazine`, `/videos`, `/support`, `/slugs`, `/search` |
| Админское API, по токену | `/api/admin/v1/{news,articles,newspaper,books,magazine,videos}` — список, чтение, создание, правка, удаление; `/support` — кошельки и магазин; `/media` — загрузка файлов |
| Проверка живости | `/q/health/ready` |

Страницы списков считаются **с единицы**. Материал виден на сайте, когда у него `status = PUBLISHED` и наступила `publishedAt`. После изменений бэкенд сам сбрасывает кэш сайта (`POST {SITE_REVALIDATE_URL}`).

Доступ к админскому API пока по токенам из `ADMIN_TOKENS` (`Authorization: Bearer …`). Вход по логину и паролю — этап A2 плана админки (`docs/admin-plan.md`).

## Запуск для разработки

Нужна JDK 21. `gradlew` в репозитории не хранится — создать один раз любым установленным Gradle: `gradle wrapper --gradle-version 9.5.1 --distribution-type all` (версия — как в `gradle/wrapper/gradle-wrapper.properties`, иначе файл перепишется).

```bash
# База: любая PostgreSQL 15+, по умолчанию localhost:5432, база site, пользователь site, пароль site
createdb site
./gradlew quarkusDev                     # http://localhost:8080, админский токен — dev-token

# Наполнить фикстурами сайта (из каталога frontend):
API_BASE_URL=http://localhost:8080 ADMIN_TOKEN=dev-token yarn api:seed
```

## Переменные окружения

| Переменная | Что | По умолчанию |
|---|---|---|
| `DB_USER`, `DB_PASSWORD` | доступ к базе | `site`; пароль в продакшне обязателен |
| `DB_JDBC_URL`, `DB_REACTIVE_URL` | адрес базы (JDBC — для миграций) | `localhost:5432/site` |
| `ADMIN_TOKENS` | токены админского API через запятую | пусто — API закрыто; в dev — `dev-token` |
| `SITE_REVALIDATE_URL`, `REVALIDATE_SECRET` | сброс кэша сайта | пусто — не сбрасывать |
| `MEDIA_DIR`, `MEDIA_URL` | куда класть загруженные файлы и откуда их раздаёт прокси | `./media`, `http://localhost:8080/media` |

## Тесты

```bash
createdb site_test                       # тесты чистят её перед прогоном
./gradlew test
```

Другая база — через `TEST_DB_JDBC_URL` и `TEST_DB_REACTIVE_URL`. В CI (`.github/workflows/ci.yml`) база поднимается сервисом.

---

## Справка Quarkus

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./gradlew quarkusDev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./gradlew build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

The application is now runnable using `java -jar build/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./gradlew build -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar build/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./gradlew build -Dquarkus.native.enabled=true
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./build/site-project-1.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/gradle-tooling>.

## Related Guides

- REST ([guide](https://quarkus.io/guides/rest)): Build RESTful web services and APIs using Jakarta REST (formerly
  JAX-RS)
- YAML Configuration ([guide](https://quarkus.io/guides/config-yaml)): Use YAML to configure your Quarkus application
- Kotlin ([guide](https://quarkus.io/guides/kotlin)): Write your services in Kotlin
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplified JPA/Hibernate data
  access layer with active record and repository patterns
- Reactive PostgreSQL client ([guide](https://quarkus.io/guides/reactive-sql-clients)): Connect to the PostgreSQL
  database using the reactive pattern
- SmallRye Context Propagation ([guide](https://quarkus.io/guides/context-propagation)): Propagate contexts between
  managed threads in reactive applications

## Provided Code

### YAML Config

Configure your application with YAML

[Related guide section...](https://quarkus.io/guides/config-reference#configuration-examples)

The Quarkus application configuration is located in `src/main/resources/application.yml`.

### Hibernate ORM

Create your first JPA entity

[Related guide section...](https://quarkus.io/guides/hibernate-orm)

[Related Hibernate with Panache section...](https://quarkus.io/guides/hibernate-orm-panache)

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)
