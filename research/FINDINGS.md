# Результаты исследования источников видео

Дата проверки: 2026-07-11.

## Устройство

- XGIMI XK03H / `xgmigalileo`, платформа MStar `m7632`;
- Android 11, API 30;
- только 32-bit ABI: `armeabi-v7a`, `armeabi`;
- 4× ARM Cortex-A55, максимум 1.5 ГГц;
- RAM: заявлено 2 ГБ, доступно ОС около 1697 МБ; heap приложения 192 МБ;
- UI: 1920×1080, 60 Гц; Mali-G52, OpenGL ES 3.2;
- аппаратные декодеры MStar: AVC/H.264, HEVC/H.265, VP8, VP9, AV1; аппаратный AAC.

Вывод: HLS + H.264 + AAC — наиболее безопасный путь. WebView и Compose для поискового UI
не нужны; декодирование должно выполняться через системный MediaCodec/MediaPlayer.

## RUTUBE

Проверены официальный Android TV APK 31.3.5 и extractor `yt-dlp` 2026.07.04.

- поиск: `GET https://rutube.ru/api/search/video/?query={q}&page=1`;
- метаданные: `GET https://rutube.ru/api/video/{video_id}/?format=json`;
- воспроизведение: `GET https://rutube.ru/api/play/options/{video_id}/?format=json`;
- HLS находится в объекте `video_balancer`;
- официальный APK использует тот же `api/play/options/{videoId}`;
- в официальном APK поиск формируется как `search/video/?query=...&limit=20`;
- autocomplete: `api/search/autocomplete/video/` и `api/search/suggest`.

## VK Видео

Проверены TV APK 2.107 (`com.vk.tv`) и extractor `yt-dlp` 2026.07.04.

- TV-приложение ищет методом `catalog.getVideoSearchSmartTv`;
- основные параметры: `q`, `sort`, `hd`, `date`, `show_suggests`, `category`,
  `content_type`, `duration`, `suggest_trackcode`, `input_type`;
- официальный публичный API предоставляет `video.search`, но требует токен;
- карточка видео содержит `player`, обычно URL `video_ext.php` с `oid`, `id` и `hash`;
- страница плеера содержит `playerParams.params[0]`;
- варианты потока: `url144..url2160`, `hls*`, `dash*`, live-варианты;
- `yt-dlp` предпочитает HLS/DASH либо прямой MP4 и подтверждает тот же формат данных;
- TV APK использует AndroidX Media3. Проверка на XGIMI показала, что поиск системным клиентом
  работает, а запуск через platform `MediaPlayer` — нет; поэтому приложение также переведено
  на Media3 HLS, сохраняя аппаратный MediaCodec для самого декодирования.

## Проверенные файлы

- официальный RUTUBE APK: `RutubeTv.release.31.3.5.TV-general.apk`, SHA-256
  `15ea26dd9dfd4180e38f37ad12d9b4ee8052fec9e19cd6ed43157f659cc940d7`;
- VK TV 2.107 XAPK: SHA-256
  `a73e07f086b135b3c23808caefe0f4e924196813320ed8d3a60a1875f8da12c3`;
- VK package: `com.vk.tv`, minSdk 28, split `armeabi-v7a`;
- подпись VK имеет lineage Google Play → VKontakte; SHA-1 для API 24–32:
  `39c54b542a32ec26d822b1abb63cc305dd28751b`.
