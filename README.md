# Faisal AI Assistance (F+) — Android

د Android اصلي ایپ، په Kotlin لیکل شوی. Package: `com.fainet.faisalai`، Android 8.0 (API 26) او پورته.

## APK له کومه واخلم

وروستی APK تل دلته دی (مستقیم دانلود):
**https://github.com/thefaisal322-max/f-/releases/download/latest-apk/FaisalAI.apk**

هر ځل چې `main` بدل شي، GitHub نوی APK جوړوي او همدا لینک تازه کوي. ټول builds په یوه ثابت کیلي لاسلیک کېږي (`app/debug.p12`)، نو نوی APK په زاړه باندې نصبېږي او تنظیمات نه ورکېږي. دا کیلي عامه ده: د ازموینې لپاره سمه ده، خو Google Play ته د لېږلو لپاره خپله شخصي کیلي جوړه کړئ.

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
| Scanner | د کاغذ عکس واضح کوي او PDF، Word، PNG یا JPG یې ساتي | `ScannerActivity.kt` |
| Code Maker | QR او بارکوډ جوړوي (۱۰ ډولونه) | `Tools.kt` |
| Converter | اسعار: AFN، PKR، USD، سنټ او نور | `Tools.kt` |
| Weather | د ښار هوا او ۷ ورځې وړاندوینه | `Tools.kt` |
| Share Files | د Wi‑Fi له لارې فایل لېږل او اخیستل، له QR کوډ سره | `Share.kt` |
| Files | د ایپ ټول فایلونه له انځورګوټي (thumbnail) او لینک سره | `FilesSettings.kt` |
| Settings | ژبه، ۴ تیمونه، AI، BVR، محرمیت، Policy، About | `FilesSettings.kt`, `res/xml/prefs.xml` |

AI، Tavily، بارکوډ او QR کوډ ټول په `Core.kt` کې دي.

## هغه څه چې باید پوه شئ

- **BVR:** Android د ثبت په ټول وخت کې یو نوټیفیکیشن ښيي چې Stop تڼۍ لري. دا د Android قانون دی. ویډیوګانې په `Movies/FaisalAI` کې ساتل کېږي.
- **Share Files:** دواړه تلیفونونه باید په یوه Wi‑Fi یا هاټسپاټ وي. بل تلیفون QR کوډ سکن کوي یا پته په براوزر کې خلاصوي. ایپ Wi‑Fi په لوړ فعالیت ساتي او سکرین روان سرعت ښيي؛ حد یې پخپله Wi‑Fi ده، او 5GHz هاټسپاټ له 2.4GHz څو ځله چټک دی. لېږد encrypted نه دی.
- **غږ:** له Gemini key سره ایپ غږ ثبتوي او Gemini یې لیکي او ځواب په غږ وايي (پښتو او دري هم). بې له Gemini key د تلیفون خپل غږ خدمت کارول کېږي، چې پښتو ښايي ونه لري.
- **Live او BVR:** دواړه کامره کاروي، نو په یو وخت نه چلېږي.
- **Scanner:** د کاغذ څنډې پخپله نه پرې کوي؛ عکس له نږدې او نېغ واخلئ. Word فایل د پاڼې انځور او (که AI key موجود وي) متن لري.
- **Converter:** نرخونه له `open.er-api.com` څخه راځي (ورځ کې یو ځل تازه کېږي، key نه غواړي). پته په Settings > Tools کې بدلېدای شي؛ ځواب باید د `rates` برخه ولري.
- **Weather:** معلومات له Open-Meteo څخه دي. وړیا کارول یې یوازې د غیر تجارتي ایپ لپاره دي.
- **Navigation:** نقشه له OpenStreetMap څخه ده او key نه غواړي. "Directions" لار په Google Maps کې خلاصوي.
- **تیمونه:** Classic، Black، White، Neon glow. رنګونه په `res/values/themes.xml` کې دي.
- **ژبې:** English، پښتو، دري. متنونه په `res/values`, `res/values-ps`, `res/values-fa` کې دي. نوې ژبه د نوي `values-xx/strings.xml` فایل په زیاتولو راځي.
