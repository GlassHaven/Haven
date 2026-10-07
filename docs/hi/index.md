---
layout: default
title: Haven
---

<p align="center" style="font-size:.9em">
  <a href="../">English</a> · <a href="../zh/">简体中文</a> · <a href="../es/">Español</a> · <a href="../fr/">Français</a> · <a href="../de/">Deutsch</a> · <a href="../pt/">Português</a> · <a href="../ru/">Русский</a> · <a href="../ja/">日本語</a> · <a href="../ko/">한국어</a> · <a href="../ar/">العربية</a> · <b>हिन्दी</b> · <a href="../bn/">বাংলা</a>
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
  AGPLv3 के अंतर्गत ओपन सोर्स। कोई टेलीमेट्री नहीं। कोई विज्ञापन नहीं। कोई खाता नहीं।
</p>

## डाउनलोड

<table>
<thead><tr><th>चैनल</th><th>टिप्पणियाँ</th></tr></thead>
<tbody>
<tr><td><a href="https://f-droid.org/en/packages/sh.haven.app"><b>F-Droid</b></a></td><td>सोर्स से बनाया गया, स्वतः अपडेट होने वाला, अधिकांश उपयोगकर्ताओं के लिए अनुशंसित।</td></tr>
<tr><td><a href="https://github.com/GlassHaven/Haven/releases/latest"><b>GitHub Releases</b></a></td><td>हस्ताक्षरित APK (arm64, armv7 &amp; x86_64), सबसे पहले जारी, F-Droid जैसी ही सुविधाएँ।</td></tr>
</tbody>
</table>

दोनों तरीकों में सुविधाएँ एक जैसी हैं। न्यूनतम Android 8.0 (API 26)।

**एक चैनल चुनें और उसी पर बने रहें।** GitHub Releases और F-Droid
**अलग-अलग कुंजियों** से हस्ताक्षरित हैं (GitHub, Haven की अपनी रिलीज़ कुंजी का उपयोग करता है; F-Droid अपनी
प्रति-ऐप कुंजी से हस्ताक्षर करता है), इसलिए Android इन्हें अलग-अलग ऐप मानता है — आप एक से दूसरे में
सीधे अपडेट नहीं कर सकते। चैनल बदलने के लिए अनइंस्टॉल + रीइंस्टॉल करना पड़ता है, जिससे
ऐप डेटा मिट जाता है, तो पहले **Settings → Backup** के ज़रिए बैकअप ले लें। Obtainium और सीधे
साइडलोड, GitHub Releases कुंजी का अनुसरण करते हैं।

## स्क्रीनशॉट

<p align="center">
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1_terminal.png" width="160" alt="Terminal" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2_connections.png" width="160" alt="Connections" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3_wayland_desktop.png" width="160" alt="Wayland desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4_cloud_storage.png" width="160" alt="Cloud storage" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6_vnc_desktop.png" width="160" alt="VNC desktop" />
  <img src="https://raw.githubusercontent.com/GlassHaven/Haven/main/fastlane/metadata/android/en-US/images/phoneScreenshots/7_keys.png" width="160" alt="Keys" />
</p>

## एक नज़र में

- **[टर्मिनल](../features/terminal.md)** — Mosh / Eternal Terminal / SSH, tmux सत्र पुनर्स्थापना, विन्यास-योग्य कीबोर्ड टूलबार, OSC 7/8/9/52/133/777 एकीकरण।
- **[डेस्कटॉप](../features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), SPICE (QEMU/KVM, GLZ/QUIC), एक GPU-त्वरित नेटिव Wayland कंपोज़िटर, और एक बहु-डिस्ट्रो स्थानीय-डेस्कटॉप प्रबंधक।
- **[फ़ाइलें और क्लाउड](../features/files-and-cloud.md)** — SFTP/SCP, SMB, 60+ क्लाउड प्रदाता (rclone), क्रॉस-फ़ाइलसिस्टम कॉपी/मूव; साथ ही FFmpeg ट्रांसकोड, HLS, और DLNA।
- **[कनेक्शन](../features/connections.md)** — पोर्ट फ़ॉरवर्डिंग (-L/-R/-D/-J), SOCKS/HTTP/Tor प्रॉक्सी, प्रति-ऐप WireGuard और Tailscale टनल, पोर्ट नॉकिंग + fwknop SPA, SSH कुंजियाँ और FIDO2।
- **[ईमेल](../features/email.md)** — ProtonMail + IMAP/SMTP, लिखें/जवाब दें/अग्रेषित करें, बहु-खाता, Mail Rules स्वचालन।
- **[स्थानीय Linux](../features/local-linux.md)** — PRoot के ज़रिए Alpine / Debian / Arch / Void, साथ-साथ, बिना root के।
- **[USB फ़ॉरवर्डिंग](../features/usb.md)** — किसी USB डिवाइस को एजेंट, Linux गेस्ट, या USB/IP के माध्यम से किसी दूरस्थ होस्ट को सौंपें।
- **[Reticulum](../features/reticulum.md)** — rnsh शेल, फ़ाइल स्थानांतरण, और मेश पर `-L`/`-D` फ़ॉरवर्डिंग। वह एकमात्र ट्रांसपोर्ट जो बिना किसी इंटरनेट के भी काम करता रहता है।
- **[एजेंट ट्रांसपोर्ट (MCP)](../mcp-tools.md)** — ~130 सहमति-नियंत्रित उपकरण; एजेंट Haven की अपनी UI तक चला सकता है।
- **[सुरक्षा](../features/security.md)** — बायोमेट्रिक लॉक, कोई टेलीमेट्री नहीं, एन्क्रिप्टेड बैकअप/पुनर्स्थापना (AES-256-GCM)।

[पूर्ण सुविधा सूची](../FEATURES.md) देखें।

## एक ही ऐप क्यों?

ऊपर दी गई सूची तो पुर्ज़े हैं; असली बात यह है कि वे आपस में कैसे जुड़ते हैं। इनमें से हर एक Haven के भीतर एक ही प्रवाह है — कोई दूसरा ऐप नहीं, `curl | ssh` जैसा कोई मंत्र नहीं:

- Google Drive में किसी 4K MKV पर टैप करें → FFmpeg उसे HTTP पर ट्रांसकोड करता है और नतीजा वापस उसी Drive फ़ोल्डर में आ जाता है, बिना कभी लोकल डिस्क को छुए।
- किसी मशीन पर SSH करें, उसका पोर्ट फ़ॉरवर्ड करें, `localhost` की ओर इशारा करने वाली VNC प्रोफ़ाइल पर टैप करें — डेस्कटॉप उसी ऐप में खुलता है, कीबोर्ड और क्लिपबोर्ड साझा।
- किसी S3 बकेट से लॉग डायरेक्टरी काटें, टैब बदलें, और उसे SFTP सर्वर पर पेस्ट करें — rclone संभव होने पर सर्वर-साइड कॉपी करता है, वरना Haven उसे स्ट्रीम करके भेजता है।
- डिवाइस की Linux शेल में अपने एजेंट का CLI चलाएँ; यह उस SSH एजेंट के ज़रिए `push` करता है जिसे आपने अपने लैपटॉप से फ़ॉरवर्ड किया था, और आप उसी स्क्रीन पर देखते रहते हैं।
- क्लाउड का कोई वीडियो HLS के ज़रिए कमरे के दूसरे छोर पर रखे TV पर कास्ट करें, स्नैकबार से LAN URL कॉपी करें, और किसी दोस्त को भेज दें ताकि वह भी देख सके।

फ़ोन थिन क्लाइंट है, Haven थिन-क्लाइंट OS है, और क्लाउड, आपके सर्वर और आपके एजेंट ही असली कंप्यूटर हैं। चौड़ाई पर्याप्त है; असली बात है कंपोज़िशन। ([विज़न](https://github.com/GlassHaven/Haven/blob/main/VISION.md)।)

## भाषाएँ

12 भाषाओं में उपलब्ध: अंग्रेज़ी, चीनी (सरलीकृत), स्पेनिश, हिन्दी, अरबी (RTL समर्थन के साथ), पुर्तगाली, बंगाली, रूसी, जापानी, कोरियाई, फ़्रेंच, और जर्मन। UI डिवाइस की भाषा का अनुसरण करता है।

**[🌍 Haven का अनुवाद करने में मदद करें →](../translate.html)** — संदर्भ के साथ हर इन-ऐप स्ट्रिंग देखें, अपनी भाषा में क्या छूट गया है यह जानें, और GitHub पुल रिक्वेस्ट के रूप में अनुवाद प्रस्तावित करें।

## Haven क्यों?

- **एक ऐप पूरे चक्र को कवर करता है।** SSH + Mosh + ET + VNC + RDP + SFTP + क्लाउड स्टोरेज + ऑन-डिवाइस Linux + मीडिया ट्रांसकोड, एक ही टैब बार से।
- **कोई टेलीमेट्री नहीं, कोई विज्ञापन नहीं, कोई खाता नहीं।** कुछ भी घर वापस नहीं भेजा जाता। [गोपनीयता नीति](../privacy-policy.html) देखें।
- **प्रति-ऐप टनल।** अलग-अलग SSH प्रोफ़ाइलों को WireGuard या Tailscale के माध्यम से रूट करें *बिना* Android के एकमात्र VPN स्लॉट को लिए — अन्य ऐप सीधे नेटवर्क का उपयोग करते रहते हैं।
- **सब कुछ नेटिव।** FFmpeg, labwc, IronRDP, rclone, और Kotlin Reticulum ट्रांसपोर्ट सभी सोर्स से संकलित हैं — कोई Python रनटाइम नहीं, कोई Chaquopy नहीं।
- **बार-बार रिलीज़।** रिलीज़ एक स्वचालित MR के ज़रिए 24 घंटों के भीतर F-Droid तक पहुँचती हैं। [रिलीज़ इतिहास](https://github.com/GlassHaven/Haven/releases) देखें।

## सोर्स से बनाएँ

Android लक्ष्यों के साथ [Rust](https://rustup.rs/), `cargo-ndk`, [Go](https://go.dev/dl/) 1.26+, और `gomobile` की आवश्यकता है:

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

आउटपुट `app/build/outputs/apk/debug/haven-*-debug.apk` में आता है।

## समस्याएँ, अनुरोध, प्रतिक्रिया

- [GitHub issues](https://github.com/GlassHaven/Haven/issues) — बग और सुविधा अनुरोध।
- [Ko-fi](https://ko-fi.com/glassontin) — अगर आप धन्यवाद कहना चाहें।

## दस्तावेज़ीकरण

- [सुविधाएँ](../FEATURES.md) — विस्तृत सुविधा संदर्भ।
- [गोपनीयता नीति](../privacy-policy.html)।
- [सोर्स कोड](https://github.com/GlassHaven/Haven)।

---

<p align="center">
  <sub>
    Haven, <a href="https://github.com/GlassHaven/Haven/blob/main/LICENSE">AGPLv3</a> के अंतर्गत ओपन सोर्स है।
    &middot; Android, Google LLC का एक ट्रेडमार्क है।
  </sub>
</p>
