# Tele 8 Hire (Android)

- الواجهة: `app/src/main/assets/index.html` داخل WebView.
- الحزمة: `x8.tele8hire.gs`
- البناء: ارفع المجلد إلى مستودع GitHub **خاص**، ثم Actions ← Build APK ← Run workflow، وحمّل `Tele8Hire.apk` من Artifacts.
- محلياً: `./gradlew assembleRelease` (يحتاج Android SDK).
- الإشعارات: Supabase Edge Function `push-message` + السر `FIREBASE_SERVICE_ACCOUNT`.
- مفتاح التوقيع `keystore/tele8hire-release.jks` احتفظ بنسخة منه؛ فقدانه يمنع تحديث التطبيق فوق نسخة مثبتة.
