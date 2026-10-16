# PlusMusic Pro 1.0

Esta actualización centra la primera fase en la reproducción Android en segundo plano y el ecualizador, conservando la interfaz móvil y sus opciones de personalización.

## Cambios principales

- El HTML de `www/index.html` parte de la versión móvil Pro con ecualizador de 10 bandas.
- En Android, las canciones locales y las URLs directas de audio se envían al reproductor nativo Media3 (ExoPlayer + `MediaSessionService`). De esta manera, la reproducción no depende del ciclo de vida del WebView cuando la pantalla se apaga.
- El servicio nativo publica metadatos de reproducción, procesa los controles del sistema y respeta el foco de audio y los eventos de auriculares desconectados.
- Las canciones locales se copian al almacenamiento privado de la app para que Media3 pueda seguir reproduciéndolas sin depender de una URL `blob:` de WebView.
- El ecualizador intenta aplicar el perfil a través de Android `Equalizer`; la cantidad de bandas reales y la compatibilidad dependen del efecto que exponga el dispositivo. Las bandas web funcionan con fuentes compatibles cuando se ejecuta en un navegador.
- La eliminación de un álbum borra también las copias nativas de sus archivos locales.
- El flujo de GitHub Actions prepara Capacitor, añade Media3 y el puente nativo, y compila el APK.
- Se incluyen el manifiesto, iconos y service worker PWA que el HTML referencia; el service worker solo cachea la app shell, nunca los streams de audio.

## Cómo generar el APK con GitHub Actions

1. Descomprime el ZIP del proyecto.
2. Sube el contenido de la carpeta `PlusMusic-main` a la raíz del repositorio `enmacodeoficial/PlusMusic`, conservando carpetas como `.github` y `scripts`.
3. Haz commit en la rama `main`.
4. Abre la pestaña **Actions** y entra en la ejecución `Build Android APK`.
5. Si el job acaba en verde, descarga el artefacto `PlusMusic-Pro-debug-apk` e instala el APK en tu dispositivo de prueba.

No añadas a mano una carpeta `android/` generada anteriormente: el flujo crea una nueva instalación Android y aplica el parche nativo después de `npx cap sync android`.

## Prueba de aceptación en el dispositivo

- Importa o reproduce un archivo local MP3.
- Confirma que aparece la notificación multimedia y que los controles de reproducir/pausar responden.
- Bloquea la pantalla primero durante 15 minutos y luego durante 60 minutos. Comprueba que la pista continúa, que el tiempo avanza y que la pausa/reanudación desde la notificación funciona.
- Prueba desconectar los auriculares y volverlos a conectar.
- Cambia un perfil y una banda del ecualizador y confirma que se oye el cambio. En algunos dispositivos Android el efecto Equalizer puede no estar disponible; en ese caso la app mantiene el sonido pero no puede aplicar el efecto nativo.
- Fuerza cierre/reapertura de la interfaz (sin forzar detención del sistema) y confirma que la biblioteca guardada sigue disponible.

## Límites conocidos

- Spotify y YouTube reproducidos dentro de sus reproductores oficiales incrustados continúan usando su propio motor y sus propias restricciones; no son redirigidos al reproductor nativo.
- Los servicios de streaming de URLs directas necesitan una URL reproducible desde Media3 y conexión activa.
- Android y los fabricantes pueden imponer sus propias restricciones de batería. El código incorpora la arquitectura oficial `MediaSessionService`, pero esta entrega necesita una prueba física del APK antes de considerar confirmada la reproducción durante una hora.
- Esta entrega es el proyecto fuente; no contiene un APK compilado. El APK se genera con GitHub Actions.
