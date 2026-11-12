# Ядро MASQUE

`usque/` — исходники [usque](https://github.com/Diniboy1123/usque) (MIT, см. `usque/LICENSE.md`), коммит
`6aa03fc97d12848dce34eedbd187fb1077b5d1ea`, без документации и файлов сборки upstream. Из них собирается
`app/src/main/jniLibs/*/libmasque.so`: это исполняемый файл, а не библиотека. Android ставит файлы `lib*.so` из APK
рядом с приложением и разрешает их запускать, а приложение запускает ядро отдельным процессом (`masque/MasqueTunnel.kt`).

## Изменения относительно upstream

Полный diff лежит в `usque-changes.patch`, изменённые места отмечены комментариями `Dostup:`.

- `api/masque.go`: `SocketControl` показывает приложению каждый сокет туннеля до использования, чтобы его можно было
  вывести из VPN (`VpnService.protect`).
- `api/tunnel.go`: `OnConnected` и `OnDisconnected` сообщают о состоянии туннеля без запуска внешних программ.
- `androidcore/` (новое): сама программа для Android. Конфиг приходит в stdin, интерфейс VPN и защита сокетов идут
  через Unix-сокет приложения, события выводятся в stdout строками JSON.

## Сборка

Нужен Go 1.26 или новее. NDK не нужен.

```bash
sh masque/build.sh
```

Собираются `arm64-v8a` (`GOOS=android`) и `armeabi-v7a` (статический `GOOS=linux`: под `android/arm` Go собирает
только через cgo). Для x86 ядра нет, на таких устройствах приложение работает без MASQUE.
