# Збірка й підпис релізу

## Варіанти збірки

| Flavor | applicationId | Сервер | Призначення |
| :--- | :--- | :--- | :--- |
| `dev` | `ua.kpi.building_audit.dev` | `10.0.2.2:5156` | розробка на емуляторі, UI-тести |
| `prod` | `ua.kpi.building_audit` | `-PapiBaseUrl=...`, типово `10.0.2.2:5156` | публікація |

Обидва варіанти ставляться на один пристрій поруч. У `release` увімкнено R8 (`isMinifyEnabled`) і видалення невикористаних ресурсів (`isShrinkResources`). Правила для Gson лежать в `app/proguard-rules.pro`.

## Ключ підпису

Один раз створюю сховище ключів:

```
keytool -genkeypair -v -keystore release.jks -alias audit -keyalg RSA -keysize 2048 -validity 10000
```

Поруч із `settings.gradle.kts` кладу `keystore.properties` (у git не потрапляє):

```
storeFile=release.jks
storePassword=...
keyAlias=audit
keyPassword=...
```

Без цього файлу release підписується debug-ключем: так збірка не ламається на чужій машині, але такий APK не годиться для Play Market.

## Команди

```
gradle assembleProdRelease     # APK: app/build/outputs/apk/prod/release/
gradle bundleProdRelease       # AAB для Play Console: app/build/outputs/bundle/prodRelease/
apksigner verify --print-certs app/build/outputs/apk/prod/release/app-prod-release.apk
adb install -r app/build/outputs/apk/prod/release/app-prod-release.apk
```

`mapping.txt` з `app/build/outputs/mapping/prodRelease/` завантажую в Play Console разом з AAB, інакше стеки падінь буде не прочитати.

## CI

`.github/workflows/android.yml` на кожен push:

1. `build`: тести сервера, юніт-тести, звіт покриття JaCoCo, debug APK.
2. `release`: ключ із секретів `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` (або тимчасовий), `assembleProdRelease`, `bundleProdRelease`, перевірка підпису.
3. `screenshots`: емулятор API 34, UI-тести й обхід екранів зі знімками, встановлення release APK і перевірка, що процес запустився після R8.

## Публікація в Play Market

1. Play Console → Create app, мова українська, категорія «Інструменти».
2. App signing by Google Play: завантажую AAB, підписаний ключем вище (він стає upload key).
3. Store listing: опис, іконка 512×512, щонайменше два знімки екрана з артефакту `screenshots`.
4. App content: політика конфіденційності (застосунок використовує геолокацію, камеру й сповіщення), Data safety.
5. Testing → Internal testing: реліз для групи тестувальників; після перевірки — Production.
