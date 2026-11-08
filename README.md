<p align="center">
  <img src="art/readme.webp" width="240" alt="ByeByeRKN">
</p>

<h1 align="center">ByeByeRKN</h1>

<p align="center">
  <a href="https://github.com/alexus282/ByeByeRKN/releases/latest"><img src="https://img.shields.io/github/v/release/alexus282/ByeByeRKN?color=blue" alt="release"></a>
  <a href="https://github.com/alexus282/ByeByeRKN/releases"><img src="https://img.shields.io/github/downloads/alexus282/ByeByeRKN/total?color=brightgreen" alt="downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/alexus282/ByeByeRKN?color=orange" alt="license"></a>
  <a href="https://github.com/alexus282/ByeByeRKN"><img src="https://img.shields.io/github/languages/code-size/alexus282/ByeByeRKN?color=blue" alt="code size"></a>
</p>

[فارسی](README.fa.md)

Android-клиент Cloudflare WARP на базе протокола AmneziaWG. Всё работает через одну кнопку: приложение само
регистрируется в WARP, подбирает сервер и параметры соединения, а при сбое или смене сети переподключается без участия
пользователя.

**Статус: бета.** Проверялось на Galaxy S24 с Android 16. Минимальная версия Android: 8.0.

<p align="center">
  <img src="art/screenshot-home.webp" width="280" alt="Главный экран приложения">
</p>

## Возможности

- Автоматический подбор адреса, порта, параметров протокола и MTU. Рабочие маршруты запоминаются отдельно для каждой сети.
- Проверка маршрута настоящим трафиком, а не только ответом сервера.
- Автоматическое восстановление: если трафик перестал идти, приложение само переключается на другой маршрут.
- Раздельный туннель: «все», «все кроме» или «только выбранные» приложения.
- Плитка в шторке, системный «Постоянный VPN», автоматическое включение после перезагрузки и обновления.
- Экспорт конфига для AmneziaWG и AmneziaVPN, импорт своего конфига.

## Установка

Скачайте APK со страницы [последнего релиза](https://github.com/alexus282/ByeByeRKN/releases/latest) и установите его.
Чтобы получать обновления автоматически, добавьте адрес этого репозитория в [Obtainium](https://github.com/ImranR98/Obtainium).

Подлинность APK можно проверить по отпечатку сертификата подписи (`apksigner verify --print-certs`):

```text
SHA-256: 5420f6aa3f30ada56368925223eb26e65be4393e088c4178dd183e4ccfd140da
```

## Ограничения

- Страну и город выхода выбрать нельзя: их определяет Cloudflare.
- Регистрация использует недокументированный API Cloudflare, который может измениться или перестать работать.
  Уже зарегистрированному приложению он для подключения не нужен.
- В сетях, где доступ ограничен списком разрешённых адресов, подключиться не получится. Приложение об этом предупредит.

## Какие запросы делает приложение

У приложения нет своих серверов, аналитики и рекламы. Все данные хранятся на устройстве и не попадают в резервные
копии. Сетевые запросы, кроме самого туннеля:

- API Cloudflare: регистрация аккаунта WARP. Ключ создаётся на телефоне, приватная часть никуда не уходит.
- Серверы WARP и `cloudflare.com/cdn-cgi/trace`: подбор маршрута и проверка соединения.
- Дата-центры Telegram: один из тестов качества соединения.
- `github.com`: проверка новой версии не чаще раза в сутки при открытии приложения.

## Сборка

Нужны JDK 17 и Android SDK с платформой 36. NDK и Go не нужны: нативная библиотека лежит в репозитории готовой.
Путь к SDK задаётся через `ANDROID_HOME` или `sdk.dir` в `local.properties`.

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Живой тест регистрирует WARP и проверяет серверы из текущей сети:

```bash
DOSTUP_LIVE=1 ./gradlew testDebugUnitTest --tests "*LiveWarpTest*"
```

Если в пути к профилю пользователя есть кириллица, инструменты Android могут ломаться. В этом случае задайте
`GRADLE_USER_HOME` и `ANDROID_USER_HOME` с путями латиницей.

Для подписанной release-сборки положите в корень `keystore.properties`. Этот файл и сам ключ в репозиторий не попадают:

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Выпуск версии

1. Увеличьте `versionCode` и `versionName` в `app/build.gradle.kts`.
2. Проверьте, что в `gradle.properties` указан репозиторий: `releasesRepo=owner/repo`. Иначе проверка обновлений выключена.
3. Соберите `./gradlew assembleRelease` и создайте релиз с тегом `vX.Y.Z`, приложив APK и его SHA-256.
4. Не отмечайте релиз как Pre-release: GitHub не считает такой релиз последним, и приложение его не увидит.
   Слово «бета» пишите в названии.

Ключ подписи и `applicationId` менять нельзя: иначе установленные приложения не смогут обновиться.

## Как это работает

1. **Регистрация** (`warp/WarpApi.kt`): `POST reg` и `PATCH reg/{id}`, как в официальном клиенте.
2. **Подбор сервера** (`probe/`): на адреса WARP параллельно отправляются начальные пакеты AmneziaWG и настоящий
   handshake WireGuard. Так за пару секунд видно, какие сочетания адреса, порта и параметров отвечают, без поднятия VPN.
3. **Параметры протокола** (`awg/`): варианты первого пакета AmneziaWG (QUIC по RFC 9001, STUN, DNS, SIP и другие).
   Они пробуются по очереди. Сервер остаётся обычным WireGuard, поэтому меняются только параметры на стороне клиента.
4. **Качество маршрута** (`RouteSearch` в `vpn/VpnController.kt`): маршрут считается хорошим, если трафик идёт через WARP
   и проходят тестовые запросы. Если хорошего маршрута нет, приложение подключается к лучшему из найденных и
   предупреждает об этом. Результаты хранятся отдельно для каждой сети (`data/RouteBook.kt`).
5. **Контроль сессии** (`vpn/StallDetector.kt`, `vpn/NetworkWatcher.kt`): приложение смотрит счётчики туннеля и при
   подозрении проверяет соединение настоящим запросом. При смене сети или её возвращении сразу идёт новое рукопожатие.
   Если ничего не нашлось, поиск повторяется с паузами от 30 с до 5 мин. На перегретом телефоне автоматический поиск
   откладывается.
6. **Работа в фоне**: служба VPN работает как foreground с постоянным уведомлением и включается сама после
   перезагрузки и обновления, если VPN был включён (`vpn/BootReceiver.kt`).

Отладка без экрана, только в debug-сборке:

```bash
adb shell am broadcast -a com.dostup.app.DEBUG -p com.dostup.app --es cmd connect
```

Команды: `connect`, `disconnect`, `failover`, `survey`, `diagnose`. Журнал:

```bash
adb logcat -s Dostup/Vpn:I Dostup/Net:I Dostup/Diag:I Dostup/Update:I
```

## Иконки

Исходники лежат в `art/`: `icon-source.webp` для значка приложения и `splash-source.webp` для экрана загрузки. Пересборка
(нужен Pillow) из корня проекта: `python art/make_icon.py`. В плитке и уведомлении используется одноцветная
`drawable/ic_tile`, потому что Android требует там монохромный значок.

## Модуль `tunnel`

Java-часть `tunnel` из [amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) (тег `v3.1.20260814`) и
`libwg-go.so` из APK того же официального релиза. Изменения относительно оригинала:

- удалён root-бэкенд (`AwgQuickBackend`, `RootShell`, `ToolsInstaller`);
- `GoBackend.setState`: при смене конфига VpnService не останавливается, иначе поздний `onDestroy` гасит уже поднятый
  новый туннель;
- `GoBackend`: добавлены `protectSocket`, `closeTunnelKeepService`, `stopIdleService` и работа службы в foreground;
- манифест: тип foreground-службы `specialUse` и нужные для него разрешения.

Изменённые места в `GoBackend.java` отмечены комментариями `Dostup:`.

## Лицензия

[Apache-2.0](LICENSE). Сторонние компоненты и хеши готовых библиотек перечислены в
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Проект распространяется как есть, без каких-либо гарантий. Пользователь сам отвечает за соблюдение законов своей страны.

Проект не связан с Cloudflare, Inc., Amnezia или WireGuard. Cloudflare и WARP являются товарными знаками Cloudflare, Inc.,
WireGuard является зарегистрированным товарным знаком Jason A. Donenfeld.
