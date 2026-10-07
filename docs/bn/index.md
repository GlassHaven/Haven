---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <a href="../hi/">हिन्दी</a> · <b>বাংলা</b>
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
  AGPLv3 এর অধীনে ওপেন সোর্স। কোনো টেলিমেট্রি নেই। কোনো বিজ্ঞাপন নেই। কোনো অ্যাকাউন্ট নেই।
</p>

## ডাউনলোড

<table>
<thead><tr><th>চ্যানেল</th><th>নোট</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>সোর্স থেকে তৈরি, স্বয়ংক্রিয়ভাবে আপডেট হয়, বেশিরভাগ ব্যবহারকারীর জন্য প্রস্তাবিত।</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>স্বাক্ষরিত APK (arm64, armv7 &amp; x86_64), প্রথমে প্রকাশিত, F-Droid এর মতো একই বৈশিষ্ট্য।</td></tr>
</tbody>
</table>

উভয় উপায়েই একই বৈশিষ্ট্য। ন্যূনতম Android 8.0 (API 26)।

**একটি চ্যানেল বেছে নিন এবং সেটিতেই থাকুন।** GitHub Releases এবং F-Droid
**ভিন্ন কী** দিয়ে স্বাক্ষরিত (GitHub Haven এর নিজস্ব রিলিজ কী ব্যবহার করে; F-Droid তার
প্রতি-অ্যাপ কী দিয়ে স্বাক্ষর করে), তাই Android এদেরকে আলাদা অ্যাপ হিসেবে গণ্য করে — আপনি একটি থেকে
অন্যটিতে জায়গামতো আপডেট করতে পারবেন না। চ্যানেল পরিবর্তন করতে আনইনস্টল + পুনরায় ইনস্টল প্রয়োজন, যা
অ্যাপের ডেটা মুছে ফেলে, তাই আগে **সেটিংস → ব্যাকআপ** এর মাধ্যমে ব্যাকআপ নিন। Obtainium এবং সরাসরি
সাইডলোড GitHub Releases কী অনুসরণ করে।

## স্ক্রিনশট

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## এক নজরে

- **[টার্মিনাল](../features/terminal.md)** — Mosh / Eternal Terminal / SSH, tmux সেশন পুনরুদ্ধার, কনফিগারযোগ্য কীবোর্ড টুলবার, OSC 7/8/9/52/133/777 ইন্টিগ্রেশন।
- **[ডেস্কটপ](../features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), SPICE (QEMU/KVM, GLZ/QUIC), একটি GPU-অ্যাক্সিলারেটেড নেটিভ Wayland কম্পোজিটর, এবং একটি বহু-ডিস্ট্রো লোকাল-ডেস্কটপ ম্যানেজার।
- **[ফাইল ও ক্লাউড](../features/files-and-cloud.md)** — SFTP/SCP, SMB, ৬০+ ক্লাউড প্রদানকারী (rclone), ক্রস-ফাইলসিস্টেম কপি/মুভ; এছাড়াও FFmpeg ট্রান্সকোড, HLS, এবং DLNA।
- **[সংযোগ](../features/connections.md)** — পোর্ট ফরওয়ার্ডিং (-L/-R/-D/-J), SOCKS/HTTP/Tor প্রক্সি, প্রতি-অ্যাপ WireGuard ও Tailscale টানেল, পোর্ট নকিং + fwknop SPA, SSH কী ও FIDO2।
- **[ইমেইল](../features/email.md)** — ProtonMail + IMAP/SMTP, রচনা/উত্তর/ফরওয়ার্ড, বহু-অ্যাকাউন্ট, Mail Rules অটোমেশন।
- **[লোকাল Linux](../features/local-linux.md)** — PRoot এর মাধ্যমে Alpine / Debian / Arch / Void, পাশাপাশি, কোনো root প্রয়োজন নেই।
- **[USB ফরওয়ার্ডিং](../features/usb.md)** — USB/IP এর মাধ্যমে একটি USB ডিভাইস এজেন্ট, Linux গেস্ট, বা একটি দূরবর্তী হোস্টে ব্রোকার করুন।
- **[Reticulum](../features/reticulum.md)** — মেশের উপর rnsh শেল, ফাইল স্থানান্তর, এবং `-L`/`-D` ফরওয়ার্ডিং। একমাত্র ট্রান্সপোর্ট যা ইন্টারনেট ছাড়াই কাজ করতে থাকে।
- **[এজেন্ট ট্রান্সপোর্ট (MCP)](../mcp-tools.md)** — ~১৩০টি সম্মতি-নিয়ন্ত্রিত টুল; এজেন্ট এমনকি Haven এর নিজস্ব UI চালাতে পারে।
- **[নিরাপত্তা](../features/security.md)** — বায়োমেট্রিক লক, কোনো টেলিমেট্রি নেই, এনক্রিপ্ট করা ব্যাকআপ/পুনরুদ্ধার (AES-256-GCM)।

[সম্পূর্ণ বৈশিষ্ট্য সূচক](../FEATURES.md) ব্রাউজ করুন।

## কেন একটিই অ্যাপ?

উপরের তালিকাটি হলো যন্ত্রাংশ; আসল কথা হলো এগুলো কীভাবে একসঙ্গে কাজ করে। এর প্রতিটিই Haven-এর ভেতরে একটিমাত্র প্রবাহ — দ্বিতীয় কোনো অ্যাপ নেই, `curl | ssh`-এর মতো কোনো মন্ত্র নেই:

- Google Drive-এ একটি 4K MKV-তে ট্যাপ করুন → FFmpeg সেটিকে HTTP-এর মাধ্যমে ট্রান্সকোড করে এবং ফলাফল একই Drive ফোল্ডারে ফিরে আসে, লোকাল ডিস্ক একবারও স্পর্শ না করে।
- কোনো মেশিনে SSH করুন, তার পোর্ট ফরওয়ার্ড করুন, `localhost`-এ নির্দেশ করা VNC প্রোফাইলে ট্যাপ করুন — ডেস্কটপ একই অ্যাপে খোলে, কীবোর্ড ও ক্লিপবোর্ড ভাগ করে নেওয়া।
- একটি S3 বাকেট থেকে একটি লগ ডিরেক্টরি কাটুন, ট্যাব বদলান, এবং একটি SFTP সার্ভারে পেস্ট করুন — সম্ভব হলে rclone সার্ভার-সাইড কপি করে, না হলে Haven সেটি স্ট্রিম করে পাঠায়।
- ডিভাইসের Linux শেলে আপনার এজেন্টের CLI চালান; এটি আপনার ল্যাপটপ থেকে ফরওয়ার্ড করা SSH এজেন্টের মাধ্যমে `push` করে, আর আপনি একই স্ক্রিনে দেখতে থাকেন।
- ক্লাউডের একটি ভিডিও HLS-এর মাধ্যমে ঘরের অন্য প্রান্তের TV-তে কাস্ট করুন, snackbar থেকে LAN URL কপি করুন, এবং কোনো বন্ধুকে পাঠান যাতে সে-ও দেখতে পারে।

ফোনটি হলো থিন ক্লায়েন্ট, Haven হলো থিন-ক্লায়েন্ট OS, আর ক্লাউড, আপনার সার্ভার এবং আপনার এজেন্টরাই হলো কম্পিউটার। প্রস্থ যথেষ্ট; আসল কথা হলো কম্পোজিশন। ([ভিশন](https://github.com/GlassHaven/Haven/blob/main/VISION.md)।)

## ভাষা

১২টি ভাষায় উপলব্ধ: ইংরেজি, চাইনিজ (সরলীকৃত), স্প্যানিশ, হিন্দি, আরবি (RTL সমর্থন সহ), পর্তুগিজ, বাংলা, রুশ, জাপানি, কোরিয়ান, ফরাসি, এবং জার্মান। UI ডিভাইসের ভাষা অনুসরণ করে।

**[🌍 Haven অনুবাদে সাহায্য করুন →](../translate.html)** — প্রসঙ্গসহ প্রতিটি ইন-অ্যাপ স্ট্রিং ব্রাউজ করুন, আপনার ভাষায় কী অনুপস্থিত তা দেখুন, এবং একটি GitHub পুল রিকোয়েস্ট হিসেবে অনুবাদ প্রস্তাব করুন।

## কেন Haven?

- **একটি অ্যাপ পুরো লুপ কভার করে।** SSH + Mosh + ET + VNC + RDP + SFTP + ক্লাউড স্টোরেজ + অন-ডিভাইস Linux + মিডিয়া ট্রান্সকোড, একটি একক ট্যাব বার থেকে।
- **কোনো টেলিমেট্রি নেই, কোনো বিজ্ঞাপন নেই, কোনো অ্যাকাউন্ট নেই।** কিছুই বাড়িতে পাঠানো হয় না। [গোপনীয়তা নীতি](../privacy-policy.html) দেখুন।
- **প্রতি-অ্যাপ টানেল।** Android এর একমাত্র VPN স্লট *দখল না করেই* পৃথক SSH প্রোফাইলগুলোকে WireGuard বা Tailscale এর মাধ্যমে রুট করুন — অন্যান্য অ্যাপ সরাসরি নেটওয়ার্ক ব্যবহার করতে থাকে।
- **সবকিছু নেটিভ।** FFmpeg, labwc, IronRDP, rclone, এবং Kotlin Reticulum ট্রান্সপোর্ট সবই সোর্স থেকে কম্পাইল করা — কোনো Python রানটাইম নেই, কোনো Chaquopy নেই।
- **ঘন ঘন প্রকাশিত হয়।** একটি স্বয়ংক্রিয় MR এর মাধ্যমে রিলিজ ২৪ ঘণ্টার মধ্যে F-Droid এ পৌঁছায়। [রিলিজ ইতিহাস](https://github.com/GlassHaven/Haven/releases) দেখুন।

## সোর্স থেকে তৈরি করুন

Android টার্গেট সহ [Rust](https://rustup.rs/), `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+, এবং `gomobile` প্রয়োজন:

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

আউটপুট `app/build/outputs/apk/debug/haven-*-debug.apk` এ যায়।

## ইস্যু, অনুরোধ, মতামত

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — বাগ এবং বৈশিষ্ট্যের অনুরোধ।
- [Ko-fi](https://ko-fi.com/glassontin) — আপনি যদি ধন্যবাদ জানাতে চান।

## ডকুমেন্টেশন

- [বৈশিষ্ট্য](../FEATURES.md) — বিস্তারিত বৈশিষ্ট্য রেফারেন্স।
- [গোপনীয়তা নীতি](../privacy-policy.html)।
- [সোর্স কোড](https://github.com/GlassHaven/Haven)।

---

<p align="center">
  <sub>
    Haven <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a> এর অধীনে ওপেন সোর্স।
    &middot; Android হল Google LLC এর একটি ট্রেডমার্ক।
  </sub>
</p>
