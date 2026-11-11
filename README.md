# Faisal AI Assistance (F+) — Android

د Android اصلي ایپ، په Kotlin لیکل شوی. Package: `com.fainet.faisalai`، Android 8.0 (API 26) او پورته.

## APK له کومه واخلم

هر ځل چې کوډ بدل شي، GitHub پخپله APK جوړوي:
**Actions** ټب > وروستی run > **Artifacts** > `FaisalAI-debug-apk`. دا یو zip دی چې دننه یې `app-debug.apk` دی.

په کمپیوټر کې د جوړولو لپاره: فولډر په **Android Studio** کې خلاص کړئ او **Build > Build APK(s)** وټاکئ.

## Keys (په ایپ کې Settings)

Keys یوازې په تلیفون کې ساتل کېږي. هېڅکله یې په دې repo کې مه لیکئ.

| Key | د څه لپاره | له کومه |
|---|---|---|
| Cloudflare account ID + API token | AI Chat، Translator، AI Says | dash.cloudflare.com (Workers AI) |
| Mistral API key | همغه، که Mistral وټاکئ | console.mistral.ai |
| Gemini API key | د عکسونو لوستل، د پښتو او دري غږ (اورېدل او ویل)، او AI | aistudio.google.com |
| Tavily API key | په "AI Says" کې ژوندۍ ویب پایلې | tavily.com |

یو AI key بس دی. **AI provider** په Settings کې ټاکل کېږي؛ "Automatic" لومړی هغه کاروي چې key لري (Cloudflare، بیا Mistral، بیا Gemini). که Gemini key موجود وي، عکسونه تل Gemini ته ځي. د ماډل نومونه په Settings کې بدلېدای شي.

## برخې

| برخه | څه کوي | فایل |
|---|---|---|
| AI Chat | لاندې: ضمیمه، متن بکس، لېږل. څنګ مینو: New chat او پخوانۍ خبرې | `ChatActivity.kt` |
| Live | ژوندۍ کامره: QR او بارکوډ سمدستي لولي؛ په غږ وپوښتئ، ځواب په غږ اورئ | `LiveActivity.kt`, `Voice.kt` |
| Lens | په عکس کې بارکوډ او QR لولي، عکس پېژني، په ویب کې یې لټوي | `VisionActivity.kt` |
| Text Reader | د عکس متن په هره ژبه لولي | `VisionActivity.kt` |
| Browser | پورته د لټون بکس؛ لاندې AI Says، All، Images، Videos، News | `BrowserActivity.kt` |
| Navigation | د نړۍ نقشه (OpenStreetMap) تر سړک پورې، لټون، زما ځای، لارښوونه | `MapActivity.kt` |
| Translator | ۶۸ ژبې، هره یوه بلې ته | `TranslateActivity.kt` |
| BVR | په شالید کې ویډیو ثبتوي | `Bvr.kt` |
| Share Files | د Wi‑Fi له لارې فایل لېږل او اخیستل، له QR کوډ سره | `Share.kt` |
| Files | د ایپ ټول فایلونه له انځورګوټي (thumbnail) او لینک سره | `FilesSettings.kt` |
| Settings | ژبه، ۴ تیمونه، AI، BVR، محرمیت، Policy، About | `FilesSettings.kt`, `res/xml/prefs.xml` |

AI، Tavily، بارکوډ او QR کوډ ټول په `Core.kt` کې دي.

## هغه څه چې باید پوه شئ

- **BVR:** Android د ثبت په ټول وخت کې یو نوټیفیکیشن ښيي چې Stop تڼۍ لري. دا د Android قانون دی. ویډیوګانې په `Movies/FaisalAI` کې ساتل کېږي.
- **Share Files:** دواړه تلیفونونه باید په یوه Wi‑Fi یا هاټسپاټ وي. بل تلیفون QR کوډ سکن کوي یا پته په براوزر کې خلاصوي. سرعت په Wi‑Fi پورې اړه لري او تضمین نه دی. لېږد encrypted نه دی.
- **غږ:** له Gemini key سره ایپ غږ ثبتوي او Gemini یې لیکي او ځواب په غږ وايي (پښتو او دري هم). بې له Gemini key د تلیفون خپل غږ خدمت کارول کېږي، چې پښتو ښايي ونه لري.
- **Live او BVR:** دواړه کامره کاروي، نو په یو وخت نه چلېږي.
- **Navigation:** نقشه له OpenStreetMap څخه ده او key نه غواړي. "Directions" لار په Google Maps کې خلاصوي.
- **تیمونه:** Classic، Black، White، Neon glow. رنګونه په `res/values/themes.xml` کې دي.
- **ژبې:** English، پښتو، دري. متنونه په `res/values`, `res/values-ps`, `res/values-fa` کې دي. نوې ژبه د نوي `values-xx/strings.xml` فایل په زیاتولو راځي.
