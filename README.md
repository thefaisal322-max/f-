# Faisal AI Assistance (F+) — Android

د Android اصلي ایپ، په Kotlin لیکل شوی. Package: `com.fainet.faisalai`، Android 8.0 (API 26) او پورته.

> دا کوډ تر اوسه نه دی compile شوی او په تلیفون نه دی ازمایل شوی. په لومړي build کې ممکن وړې تېروتنې راووځي؛ که راووتې، د تېروتنې متن راولېږئ.

## څنګه یې جوړ کړئ (APK)

1. په کمپیوټر کې **Android Studio** نصب کړئ (وړیا: developer.android.com/studio).
2. دا zip خلاص کړئ، بیا په Android Studio کې **File > Open** او د `FaisalAI` فولډر وټاکئ.
3. صبر وکړئ چې Gradle sync بشپړ شي (لومړی ځل انټرنېټ ته اړتیا لري).
4. تلیفون په USB ونښلوئ او **Run** ووهئ، یا **Build > Build APK(s)** وټاکئ.
   APK دلته جوړېږي: `app/build/outputs/apk/debug/app-debug.apk`

که Android Studio د Gradle یا plugin د نوي کولو وړاندیز وکړ، منلای یې شئ.

## د AI لپاره API key

AI Chat، Lens، Text Reader او Translator د Google Gemini API کاروي.

1. له **aistudio.google.com** څخه یو API key واخلئ.
2. په ایپ کې **Settings > Gemini API key** ته لاړ شئ او key ولیکئ.

Key یوازې په تلیفون کې ساتل کېږي. د ماډل نوم په Settings کې بدلېدای شي؛ ډیفالټ یې `gemini-flash-latest` دی.

## برخې

| برخه | څه کوي | فایل |
|---|---|---|
| AI Chat | له Gemini سره خبرې، تاریخ په تلیفون کې | `ChatActivity.kt` |
| Lens | عکس واخلئ یا وټاکئ، ایپ یې پېژني | `VisionActivity.kt` |
| Text Reader (OCR) | د عکس متن لولي، پښتو او دري هم | `VisionActivity.kt` |
| BVR | په شالید کې ویډیو ثبتوي، سکرین بند هم | `Bvr.kt` |
| Browser | د ایپ دننه براوزر او Google لټون | `BrowserActivity.kt` |
| Navigation | Google Maps د ایپ دننه، له موقعیت سره | `BrowserActivity.kt` |
| Translator | ۱۲ ژبې | `TranslateActivity.kt` |
| Share Files | له بل تلیفون سره د Wi‑Fi له لارې فایل لېږل او اخیستل | `Share.kt` |
| Files | ساتل شوي یادښتونه او ترلاسه شوي فایلونه | `FilesSettings.kt` |
| Settings | تیم، ژبه، AI، BVR، محرمیت | `FilesSettings.kt`, `res/xml/prefs.xml` |

## هغه څه چې باید پوه شئ

- **BVR:** Android د ثبت په ټول وخت کې یو نوټیفیکیشن ښيي چې Stop تڼۍ لري. دا د Android قانون دی او نه لرې کېږي. ویډیوګانې په `Movies/FaisalAI` کې ساتل کېږي.
- **Share Files:** دواړه تلیفونونه باید په یوه Wi‑Fi یا هاټسپاټ وي. بل تلیفون ایپ ته اړتیا نه لري؛ یوازې ښودل شوې پته په براوزر کې خلاصوي. لېږد کوډ شوی (encrypted) نه دی، نو یوازې په باوري شبکه کې یې وکاروئ.
- **Navigation:** Google Maps د ویب پاڼې په توګه خلاصېږي. د Maps SDK دننه نقشه د Google Cloud API key او billing غواړي، نو دلته نه ده کارول شوې.
- **AI لګښت:** د Gemini API وړیا حد لري؛ له هغه وروسته Google پیسې اخلي.
- **ژبې:** English، پښتو، دري. متنونه په `res/values`, `res/values-ps`, `res/values-fa` کې دي.
