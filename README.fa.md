<div dir="rtl" align="right">

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

[متن اصلی (روسی): README.md](README.md)

> **توجه:** این مخزن یک نسخه‌ی تغییریافته (فورک) از پروژه‌ی [alexus282/ByeByeRKN](https://github.com/alexus282/ByeByeRKN) است و نسخه‌ی رسمی نیست. تغییرات: افزودن زبان فارسی به رابط برنامه (`values-fa`) و نام شهرهای خروجی به فارسی، ترجمه‌ی README، و workflow ساخت و انتشار APK. کد برنامه جز همین موارد با پروژه‌ی اصلی یکی است. مجوز اصلی (Apache-2.0) حفظ شده است. نسخه‌ی رسمی را می‌توانید از مخزن اصلی بگیرید.

یک کلاینت اندروید برای Cloudflare WARP بر پایه‌ی پروتکل AmneziaWG و MASQUE. همه‌چیز با یک دکمه کار می‌کند: برنامه خودش در WARP ثبت‌نام می‌کند، سرور و پارامترهای اتصال را انتخاب می‌کند و در صورت خرابی یا تغییر شبکه بدون دخالت کاربر دوباره وصل می‌شود.

**وضعیت: بتا (نسخه‌ی ۰٫۲٫۰).** روی Galaxy S24 با اندروید ۱۶ آزمایش شده است. حداقل نسخه‌ی اندروید: ۸٫۰.

<p align="center">
  <img src="art/screenshot-home.webp" width="280" alt="صفحه‌ی اصلی برنامه">
</p>

## امکانات

- انتخاب خودکار آدرس، پورت، پارامترهای پروتکل و MTU. مسیرهای کارآمد برای هر شبکه جداگانه ذخیره می‌شوند.
- بررسی مسیر با ترافیک واقعی، نه فقط با پاسخ سرور.
- بازیابی خودکار: اگر ترافیک از جریان بیفتد، برنامه خودش به مسیر دیگری می‌رود.
- تونل تفکیکی: «همه»، «همه به‌جز» یا «فقط برنامه‌های انتخاب‌شده».
- کاشی در پرده‌ی اعلان، «VPN همیشه روشن» سیستم و روشن شدن خودکار پس از ریبوت و به‌روزرسانی.
- خروجی گرفتن کانفیگ برای AmneziaWG و AmneziaVPN و وارد کردن کانفیگ شخصی.
- رابط به زبان‌های روسی، انگلیسی و فارسی. در اندروید ۱۳ و بالاتر زبان برنامه را می‌توان در تنظیمات سیستم (یا در بخش «درباره‌ی برنامه») انتخاب کرد. در نسخه‌های قدیمی‌تر، زبان سیستم ملاک است. چیدمان فارسی راست‌به‌چین است.
- MASQUE (HTTP/3 و HTTP/2) به‌عنوان راه دوم رسیدن به WARP، با هسته‌ای که از usque ساخته شده است.
- کمک برای کار در پس‌زمینه: برداشتن محدودیت باتری و رفتن به صفحه‌ی اجرای خودکار در گوشی‌های Xiaomi، Huawei، OPPO، vivo و مشابه که سیستم خودش برنامه‌ها را می‌بندد.
- کلید WARP+ در صورت داشتن. بدون کلید همه‌چیز روی WARP رایگان کار می‌کند.
- فهرست آدرس‌های WARP و سایت‌های پنهان‌سازی بدون انتشار نسخه‌ی جدید به‌روز می‌شود (پایین‌تر را ببینید).
- اشتراک‌گذاری VPN با لپ‌تاپ یا تلویزیون: روی هات‌اسپات، تدرینگ USB یا بلوتوث گوشی یک پروکسی HTTP و SOCKS5 کار می‌کند، تا وقتی VPN متصل است. فقط TCP از آن عبور می‌کند.
- Android TV: آیکون در لانچر تلویزیون، کنترل با ریموت و چیدمان صفحه‌ی عریض.

## نصب

فایل APK را از صفحه‌ی [آخرین نسخه](https://github.com/alexus282/ByeByeRKN/releases/latest) دانلود و نصب کنید. برای دریافت خودکار به‌روزرسانی‌ها، آدرس این مخزن را به [Obtainium](https://github.com/ImranR98/Obtainium) اضافه کنید.

اصالت APK را می‌توان با اثر انگشت گواهی امضا بررسی کرد (`apksigner verify --print-certs`):

```text
SHA-256: 5420f6aa3f30ada56368925223eb26e65be4393e088c4178dd183e4ccfd140da
```

## محدودیت‌ها

- کشور و شهر خروجی قابل انتخاب نیست؛ آن را Cloudflare تعیین می‌کند.
- ثبت‌نام از یک API مستندنشده‌ی Cloudflare استفاده می‌کند که ممکن است تغییر کند یا از کار بیفتد. برنامه‌ای که قبلاً ثبت‌نام کرده باشد، برای اتصال به آن نیازی ندارد.
- در شبکه‌هایی که دسترسی فقط به فهرستی از آدرس‌های مجاز محدود است، اتصال برقرار نمی‌شود. برنامه این را اعلام می‌کند.

## برنامه چه درخواست‌هایی می‌فرستد

برنامه سرور، تحلیل‌گر و تبلیغات خودش را ندارد. همه‌ی داده‌ها روی دستگاه ذخیره می‌شوند و در نسخه‌ی پشتیبان قرار نمی‌گیرند. درخواست‌های شبکه، به‌جز خود تونل:

- API کلودفلر: ثبت‌نام حساب WARP و، در اولین نیاز به MASQUE، یک دستگاه دیگر. کلیدها روی گوشی ساخته می‌شوند و بخش خصوصی آن‌ها هیچ‌جا ارسال نمی‌شود.
- سرورهای WARP و `cloudflare.com/cdn-cgi/trace`: انتخاب مسیر و بررسی اتصال.
- دیتاسنترهای Telegram: یکی از آزمون‌های کیفیت اتصال.
- `github.com`: بررسی نسخه‌ی جدید، حداکثر یک بار در روز هنگام باز شدن برنامه.
- `raw.githubusercontent.com`: دریافت فهرست امضاشده‌ی آدرس‌ها، حداکثر یک بار در روز (پایین‌تر را ببینید).

## ساخت

JDK 17 و Android SDK با پلتفرم ۳۶ لازم است. به NDK و Go نیازی نیست: فایل‌های بومی آماده در مخزن قرار دارند. Go نسخه‌ی ۱٫۲۶ به بالا فقط برای ساخت دوباره‌ی هسته‌ی MASQUE لازم است (`sh masque/build.sh`). مسیر SDK با `ANDROID_HOME` یا `sdk.dir` در `local.properties` مشخص می‌شود.

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

آزمون زنده، WARP را ثبت می‌کند و سرورها را از شبکه‌ی فعلی بررسی می‌کند:

```bash
DOSTUP_LIVE=1 ./gradlew testDebugUnitTest --tests "*LiveWarpTest*"
```

اگر در مسیر پروفایل کاربر حروف سیریلیک باشد، ابزارهای اندروید ممکن است خراب شوند. در این صورت `GRADLE_USER_HOME` و `ANDROID_USER_HOME` را با مسیرهایی به حروف لاتین تنظیم کنید.

برای ساخت release امضاشده، فایل `keystore.properties` را در ریشه‌ی پروژه بگذارید. این فایل و خود کلید وارد مخزن نمی‌شوند:

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## انتشار نسخه

1. `versionCode` و `versionName` را در `app/build.gradle.kts` افزایش دهید.
2. مطمئن شوید در `gradle.properties` مخزن مشخص شده است: `releasesRepo=owner/repo`. در غیر این صورت بررسی به‌روزرسانی خاموش است.
3. با `./gradlew assembleRelease` بسازید و یک release با تگ `vX.Y.Z` بسازید و APK و SHA-256 آن را پیوست کنید.
4. release را Pre-release علامت نزنید: گیت‌هاب چنین نسخه‌ای را «آخرین» حساب نمی‌کند و برنامه آن را نمی‌بیند. کلمه‌ی «بتا» را در عنوان بنویسید.

کلید امضا و `applicationId` را نباید تغییر داد؛ وگرنه برنامه‌های نصب‌شده نمی‌توانند به‌روز شوند.

## به‌روزرسانی فهرست آدرس‌ها بدون انتشار نسخه

برنامه روزی یک بار فایل [`catalog/warp.json`](catalog/warp.json) را از شاخه‌ی `main` می‌گیرد. اگر امضای `catalog/warp.json.sig` با کلید داخل برنامه بخواند و `version` از نسخه‌ی فعلی بیشتر باشد، از آن استفاده می‌کند، وگرنه فهرست داخلی می‌ماند. کلید امضا مال نویسنده‌ی اصلی پروژه است، پس این فهرست را فقط او می‌تواند عوض کند.

**نکته برای این فورک:** برنامه فهرست را از مخزنی می‌گیرد که در `releasesRepo` (فایل `gradle.properties`) نوشته شده، یعنی از همین فورک. این نسخه از فهرست فقط وقتی تازه می‌شود که تغییرات پروژه‌ی اصلی دوباره در فورک ادغام شود.

## نحوه‌ی کار

1. **ثبت‌نام** (`warp/WarpApi.kt`): `POST reg` و `PATCH reg/{id}`، مانند کلاینت رسمی.
2. **انتخاب سرور** (`probe/`): به آدرس‌های WARP همزمان بسته‌های ابتدایی AmneziaWG و handshake واقعی WireGuard فرستاده می‌شود. به این ترتیب ظرف چند ثانیه معلوم می‌شود کدام ترکیب‌های آدرس، پورت و پارامتر پاسخ می‌دهند، بدون اینکه VPN برپا شود.
3. **پارامترهای پروتکل** (`awg/`): گونه‌های بسته‌ی اول AmneziaWG (QUIC طبق RFC 9001، STUN، DNS، SIP و غیره). یکی‌یکی امتحان می‌شوند. سرور همان WireGuard معمولی می‌ماند، پس فقط پارامترهای سمت کلاینت تغییر می‌کنند.
4. **کیفیت مسیر** (`RouteSearch` در `vpn/VpnController.kt`): مسیر وقتی خوب حساب می‌شود که ترافیک از WARP عبور کند و درخواست‌های آزمایشی موفق شوند. اگر مسیر خوبی نباشد، برنامه به بهترین مسیر پیداشده وصل می‌شود و درباره‌ی آن هشدار می‌دهد. نتایج برای هر شبکه جداگانه ذخیره می‌شوند (`data/RouteBook.kt`).
5. **MASQUE** (`masque/`): اگر AmneziaWG مسیر خوبی ندهد، برنامه با MASQUE به WARP وصل می‌شود، مانند کلاینت رسمی امروزی: از طریق HTTP/3، از طریق HTTP/2 روی TCP و با نام رسمی سرور. برای این کار یک دستگاه WARP جداگانه ثبت می‌شود. هسته از [usque](https://github.com/Diniboy1123/usque) ساخته شده است و جزئیات در [`masque/README.md`](masque/README.md) آمده.
6. **کنترل نشست** (`vpn/StallDetector.kt`, `vpn/NetworkWatcher.kt`): برنامه شمارنده‌های تونل را می‌بیند و در صورت شک، اتصال را با یک درخواست واقعی می‌سنجد. با تغییر شبکه یا بازگشت آن، فوراً handshake جدید انجام می‌شود. اگر چیزی پیدا نشد، جست‌وجو با وقفه‌های ۳۰ ثانیه تا ۵ دقیقه تکرار می‌شود. روی گوشی داغ‌شده، جست‌وجوی خودکار به تعویق می‌افتد.
7. **کار در پس‌زمینه**: سرویس VPN به‌صورت foreground و با اعلان دائمی اجرا می‌شود و اگر VPN روشن بوده باشد، پس از ریبوت و به‌روزرسانی خودش روشن می‌شود (`vpn/BootReceiver.kt`).

اشکال‌زدایی بدون صفحه، فقط در نسخه‌ی debug:

```bash
adb shell am broadcast -a com.dostup.app.DEBUG -p com.dostup.app --es cmd connect
```

دستورها: `connect`، `disconnect`، `failover`، `survey`، `diagnose`. لاگ:

```bash
adb logcat -s Dostup/Vpn:I Dostup/Net:I Dostup/Diag:I Dostup/Update:I
```

## آیکون‌ها

فایل‌های مبدأ در `art/` هستند: `icon-source.webp` برای آیکون برنامه و `splash-source.webp` برای صفحه‌ی بارگذاری. بازسازی (نیازمند Pillow) از ریشه‌ی پروژه: `python art/make_icon.py`. در کاشی و اعلان از `drawable/ic_tile` تک‌رنگ استفاده می‌شود، چون اندروید در آنجا آیکون تک‌رنگ می‌خواهد.

## ماژول `tunnel`

بخش جاوای `tunnel` از [amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) (تگ `v3.1.20260814`) و `libwg-go.so` از APK همان release رسمی. تغییرات نسبت به نسخه‌ی اصلی:

- بک‌اند root حذف شده است (`AwgQuickBackend`، `RootShell`، `ToolsInstaller`)؛
- `GoBackend.setState`: هنگام تغییر کانفیگ، VpnService متوقف نمی‌شود، وگرنه `onDestroy` دیرهنگام تونل جدیدِ از قبل بالا آمده را خاموش می‌کند؛
- `GoBackend`: `protectSocket`، `closeTunnelKeepService`، `stopIdleService` و کار سرویس به‌صورت foreground اضافه شده است؛
- `GoBackend`: `establishForeignTunnel`، `releaseForeignTunnel` و `protectFd` رابط VPN را به هسته‌ی MASQUE می‌دهند و تنظیم رابط در `configureBuilder` جدا شده تا هر دو هسته رابط یکسانی بگیرند؛
- مانیفست: نوع سرویس foreground برابر `specialUse` و مجوزهای لازم برای آن.

جاهای تغییرکرده در `GoBackend.java` با کامنت‌های `Dostup:` علامت‌گذاری شده‌اند.

## مجوز

[Apache-2.0](LICENSE). اجزای ثالث و هش کتابخانه‌های آماده در [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) فهرست شده‌اند.

پروژه «همان‌طور که هست» و بدون هیچ ضمانتی منتشر می‌شود. رعایت قوانین کشور خود بر عهده‌ی کاربر است.

پروژه به Cloudflare, Inc.، Amnezia یا WireGuard ربطی ندارد. Cloudflare و WARP علائم تجاری Cloudflare, Inc. هستند و WireGuard علامت تجاری ثبت‌شده‌ی Jason A. Donenfeld است.

</div>
