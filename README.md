# Portafolios (Android)

App para gestionar varios portafolios de inversión de uno o varios brokers. Los datos se importan desde
los extractos que descargas del broker (CSV o Excel): la app **nunca pide ni guarda la contraseña del broker**.

## Funciones (v0.1)

- Varios portafolios, cada uno asociado a un broker.
- Importación de extractos con vista previa, aviso si el archivo es de otro broker, filas con problemas
  señaladas, y sin duplicados al subir dos veces el mismo periodo. Cada importación se puede deshacer.
- Brokers: Interactive Brokers, Charles Schwab, Revolut, XTB, Robinhood, Trading 212, Degiro, Yahoo Finance,
  eToro, E*Trade, Quantfury, Snowball Analytics, Webull, tastytrade, Apex Clearing, Robinhood Crypto y Otro.
  Quantfury, Apex Clearing y Otro usan el importador genérico (detecta columnas habituales en inglés y español).
- Posiciones a coste medio, P/L latente y realizado, dividendos, efectivo por divisa.
- Precios, sector y país con Finnhub (clave gratuita que pone el usuario en Ajustes).
- Análisis: concentración, sectores sin exposición, posiciones en pérdida, exceso de liquidez.
- Alertas de precio con notificación (cada ~30 min).

## Seguridad

- Base de datos cifrada con SQLCipher (AES-256). La clave es aleatoria y está protegida por el Android Keystore.
- Bloqueo con huella, cara o PIN del móvil; se vuelve a pedir tras 1 minuto en segundo plano.
- Capturas de pantalla bloqueadas (FLAG_SECURE) y vista previa oculta en apps recientes.
- Sin copias automáticas a la nube (`allowBackup=false`); solo HTTPS.
- Copia de seguridad manual cifrada con contraseña (PBKDF2 + AES-256-GCM).
- La clave de Finnhub se guarda cifrada y se envía en una cabecera, no en la URL.

## Estructura

- `core/`: Kotlin puro, sin Android. Lectores CSV/XLSX, importadores por broker, cálculos y análisis. Con tests.
- `app/`: app Android (Jetpack Compose, Room + SQLCipher, WorkManager, Biometric).

## Compilar

- Tests del núcleo: `./gradlew :core:test`
- APK de prueba: `./gradlew :app:assembleDebug` (requiere Android SDK; o usa el workflow de GitHub Actions, que
  deja el APK como artefacto descargable).
- Versión de Google Play: `./gradlew :app:bundleRelease` con las variables `ANDROID_KEYSTORE_PATH`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`. El keystore nunca se sube al repositorio.

## Pendiente

- Formatos de broker verificados con extractos reales (los de esta versión siguen la documentación pública).
- Importar PDF.
- Cuentas de usuario y sincronización entre dispositivos (necesario para publicar en Google Play con varios usuarios).
