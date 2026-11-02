# RotIP (v0.1) — бесконечная ротация IP с root

## Что уже есть в скелете
- `Engine.kt` — ядро: rotateOnce(DATA → AIRPLANE эскалация), IP-детект (cellular → default), history JSONL, cooldown.
- `MainActivity.kt` — Compose UI: кнопка, статус root, история.
- `RotateTileService.kt` — Tile в шторке: один тап = ротация.
- `RotateSchedulerService.kt` — foreground-сервис планировщика (specialUse type).
- `RotipApp.kt` — init libsu (FLAG_REDIRECT_STDERR).
- `AndroidManifest.xml` — schema rotip://rotate (deeplink), Tile, FG-service, INTERNET/FGS/POST_NOTIFICATIONS.

## Ограничения v0.1
- qcrilNrd (RIL) НЕ трогаем — на vivo рестарт непредсказуем.
- IP-детекция через Network.openConnection cell — VPN может незаметно прокидывать.
- Планировщик цель cool-down 90 c default; adaptация через Engine.cooldownMs.

## README: как собрать
1. **PC build:**
   - `cd rotip-android/`
   - $GRADLE_HOME/bin/gradle wrapper --gradle-version 8.7
   - ./gradlew :app: assembleDebug
   - adb install -r app/build/outputs/apk/debug/app-debug.apk
2. **Прогять root:** в ReSukiSU дать приложению RotIP root.
3. **Использовать:** Открыть RotIP → «РОТИРОВАТЬ IP» → новый IP ≤25 c.
4. **Tile:** опустить шторку → иконка RotIP → тап → ротация.
5. **Дееelink:** `adb shell am start -a android.intent.action.VIEW -d "rotip://rotate"`.

## Чего ещё НЕТ (следующий шаг)
- [ ] WARP (Ф4) multi-provider pool.
- [ ] per-UID routing (Ф3).
- [ ] Battery/thermal guard (Ф2).
- [ ] CI-сборка APK через GitHub Actions (нужен PAT с actions scope, Ф0.6).
