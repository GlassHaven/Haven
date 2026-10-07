---
layout: default
title: Haven
---

<style>
.main-content { direction: rtl; text-align: right; }
.main-content pre, .main-content code, .main-content table { direction: ltr; text-align: left; }
</style>

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <b>العربية</b> · <a href="../hi/">हिन्दी</a> · <a href="../bn/">বাংলা</a>
</p>

<p align="center">
  <a href="https://github.com/GlassHaven/Haven/releases/latest">
    <img src="https://img.shields.io/github/v/release/GlassHaven/Haven?style=flat-square" alt="Release" />
  </a>
  <a href="https://f-droid.org/en/packages/sh.haven.app">
    <img src="https://img.shields.io/f-droid/v/sh.haven.app?style=flat-square" alt="F-Droid" />
  </a>
  <a href="https://github.com/GlassHaven/Haven/actions/workflows/ci.yml?query=branch%3Amain">
    <img src="https://img.shields.io/github/check-runs/GlassHaven/Haven/main?style=flat-square&label=build" alt="Build" />
  </a>
  <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/GlassHaven/Haven?style=flat-square" alt="License" />
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

<p align="center">
  مفتوح المصدر بموجب رخصة AGPLv3. بلا قياس عن بُعد. بلا إعلانات. بلا حساب.
</p>

## التنزيل

<table>
<thead><tr><th>القناة</th><th>ملاحظات</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>مبني من المصدر، يُحدَّث تلقائيًا، ويُوصى به لمعظم المستخدمين.</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>حِزَم APK موقّعة (arm64, armv7 &amp; x86_64)، تصدر أولًا، بنفس مزايا F-Droid.</td></tr>
</tbody>
</table>

نفس المزايا في كلتا الحالتين. الحد الأدنى Android 8.0 (API 26).

**اختر قناة واحدة والتزم بها.** إصدارات GitHub Releases و F-Droid موقّعة
**بمفاتيح مختلفة** (يستخدم GitHub مفتاح الإصدار الخاص بـ Haven؛ ويوقّع F-Droid
بمفتاحه الخاص لكل تطبيق)، لذا يعاملهما Android كتطبيقَين منفصلين — لا يمكنك التحديث في مكانه
من أحدهما إلى الآخر. يتطلب تبديل القنوات إلغاء التثبيت ثم إعادة التثبيت، وهو ما
يمسح بيانات التطبيق، لذا اعمل نسخة احتياطية أولًا عبر **الإعدادات ← النسخ الاحتياطي**. تتتبع Obtainium
وعمليات التحميل الجانبي المباشرة مفتاح إصدارات GitHub Releases.

## لقطات الشاشة

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## لمحة سريعة

- **[الطرفية](../features/terminal.md)** — Mosh / Eternal Terminal / SSH، استعادة جلسات tmux، شريط أدوات لوحة مفاتيح قابل للتخصيص، تكامل OSC 7/8/9/52/133/777.
- **[أسطح المكتب](../features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt)، RDP (IronRDP + EGFX)، SPICE (QEMU/KVM، GLZ/QUIC)، مُركِّب Wayland أصلي مُسرَّع بواسطة وحدة معالجة الرسوميات، ومدير أسطح مكتب محلية متعدد التوزيعات.
- **[الملفات والسحابة](../features/files-and-cloud.md)** — SFTP/SCP، SMB، أكثر من 60 مزوّد سحابي (rclone)، النسخ/النقل عبر أنظمة الملفات؛ إضافةً إلى ترميز FFmpeg و HLS و DLNA.
- **[الاتصالات](../features/connections.md)** — إعادة توجيه المنافذ (-L/-R/-D/-J)، وكلاء SOCKS/HTTP/Tor، أنفاق WireGuard و Tailscale لكل تطبيق، طَرْق المنافذ + fwknop SPA، مفاتيح SSH و FIDO2.
- **[البريد الإلكتروني](../features/email.md)** — ProtonMail + IMAP/SMTP، الكتابة/الرد/إعادة التوجيه، تعدد الحسابات، أتمتة قواعد البريد.
- **[لينكس المحلي](../features/local-linux.md)** — Alpine / Debian / Arch / Void عبر PRoot، جنبًا إلى جنب، دون الحاجة إلى صلاحيات الجذر.
- **[إعادة توجيه USB](../features/usb.md)** — وساطة جهاز USB للوكيل، أو لضيف لينكس، أو لمضيف بعيد عبر USB/IP.
- **[Reticulum](../features/reticulum.md)** — صدفة rnsh، ونقل الملفات، وإعادة التوجيه `-L`/`-D` عبر الشبكة المتشابكة. الناقل الوحيد الذي يستمر في العمل بلا أي اتصال بالإنترنت إطلاقًا.
- **[ناقل الوكيل (MCP)](../mcp-tools.md)** — نحو 130 أداة محكومة بالموافقة؛ يمكن للوكيل حتى أن يقود واجهة Haven نفسها.
- **[الأمان](../features/security.md)** — قفل بيومتري، بلا قياس عن بُعد، نسخ احتياطي/استعادة مشفّر (AES-256-GCM).

تصفّح [فهرس المزايا الكامل](../FEATURES.md).

## لماذا تطبيق واحد؟

القائمة أعلاه هي القطع؛ والمهمّ هو كيف تتركّب معًا. كلّ واحد ممّا يلي هو تدفّق واحد داخل Haven — بلا تطبيق ثانٍ، وبلا تعويذة من نوع `curl | ssh`:

- انقر ملف MKV بدقة 4K في Google Drive فيحوّله FFmpeg عبر HTTP وتعود النتيجة إلى مجلّد Drive نفسه، دون أن تمسّ القرص المحلي إطلاقًا.
- اتّصل عبر SSH بجهاز، وحوّل منفذه، وانقر ملف تعريف VNC الذي يشير إلى `localhost` — يفتح سطح المكتب داخل التطبيق نفسه، مع مشاركة لوحة المفاتيح والحافظة.
- قُصّ مجلّد سجلّات من حاوية S3، وبدّل التبويب، والصقه على خادم SFTP — يقوم rclone بالنسخ من جهة الخادم عند الإمكان، وإلّا فإنّ Haven ينقله عبره.
- شغّل واجهة سطر أوامر وكيلك في صدفة لينكس على الجهاز؛ فهو ينفّذ `push` عبر وكيل SSH الذي حوّلته من حاسوبك المحمول بينما تشاهد على الشاشة نفسها.
- ابثّ فيديو من السحابة إلى التلفاز في الجهة الأخرى من الغرفة عبر HLS، وانسخ عنوان URL للشبكة المحلية من الشريط المنبثق، وأرسله إلى صديق ليشاهده هو أيضًا.

الهاتف هو العميل الخفيف، وHaven هو نظام تشغيل العميل الخفيف، والسحابة وخوادمك ووكلاؤك هم الحاسوب. الاتّساع كافٍ؛ والمهمّ هو التركيب. ([الرؤية](https://github.com/GlassHaven/Haven/blob/main/VISION.md).)

## اللغات

متوفر بـ 12 لغة: الإنجليزية، الصينية (المبسطة)، الإسبانية، الهندية، العربية (مع دعم RTL)، البرتغالية، البنغالية، الروسية، اليابانية، الكورية، الفرنسية، والألمانية. تتبع الواجهة لغة الجهاز.

**[🌍 ساعد في ترجمة Haven ←](../translate.html)** — تصفّح كل نص داخل التطبيق مع سياقه، وشاهد ما ينقص في لغتك، واقترح ترجمة بصيغة طلب سحب على GitHub.

## لماذا Haven؟

- **تطبيق واحد يغطي الدورة كاملة.** SSH + Mosh + ET + VNC + RDP + SFTP + التخزين السحابي + لينكس على الجهاز + ترميز الوسائط، من شريط تبويبات واحد.
- **بلا قياس عن بُعد، بلا إعلانات، بلا حساب.** لا يُرسَل أي شيء إلى الخوادم. راجع [سياسة الخصوصية](../privacy-policy.html).
- **أنفاق لكل تطبيق.** وجِّه ملفات تعريف SSH فردية عبر WireGuard أو Tailscale *دون* أن تشغل خانة VPN الوحيدة في Android — تبقى التطبيقات الأخرى تستخدم الشبكة المباشرة.
- **أصلي في كل شيء.** FFmpeg و labwc و IronRDP و rclone وناقل Reticulum المكتوب بلغة Kotlin كلها مُجمَّعة من المصدر — بلا بيئة تشغيل Python، وبلا Chaquopy.
- **إصدارات متكررة.** تصل الإصدارات إلى F-Droid خلال 24 ساعة عبر طلب دمج آلي. راجع [سجل الإصدارات](https://github.com/GlassHaven/Haven/releases).

## البناء من المصدر

يتطلب [Rust](https://rustup.rs/) مع أهداف Android، و`cargo-ndk`، و[Go](https://go.dev/dl/) 1.26+، و`gomobile`:

```bash
# Rust (for RDP)
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# Go (for rclone cloud storage)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

git clone --recurse-submodules https://github.com/GlassHaven/Haven.git
cd Haven
./gradlew assembleDebug
```

تظهر المخرجات في `app/build/outputs/apk/debug/haven-*-debug.apk`.

## المشكلات والطلبات والملاحظات

- [مشكلات GitHub](https://github.com/GlassHaven/Haven/issues) — الأخطاء وطلبات المزايا.
- [Ko-fi](https://ko-fi.com/glassontin) — إن أردت أن تقول شكرًا.

## التوثيق

- [المزايا](../FEATURES.md) — مرجع المزايا المفصّل.
- [سياسة الخصوصية](../privacy-policy.html).
- [الشيفرة المصدرية](https://github.com/GlassHaven/Haven).

---

<p align="center">
  <sub>
    Haven مفتوح المصدر بموجب رخصة <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a>.
    &middot; Android علامة تجارية لشركة Google LLC.
  </sub>
</p>
