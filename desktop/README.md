# PlusMusic Pro — versión de escritorio (Windows / macOS / Linux)

Envuelve el mismo `www/index.html` de la app Android en Electron. No toca el proyecto Android.

## Probar en local
```bash
cd desktop
npm install
npm start
```

## Generar instaladores
```bash
npm run dist:win     # .exe instalador (NSIS) + portable
npm run dist:mac     # .dmg (compilar en un Mac)
npm run dist:linux   # .AppImage + .deb
```
O sin instalar nada: Actions → **Build Desktop** → Run workflow (genera los tres sistemas).

## Por qué sirve la app por http://127.0.0.1:38917
- YouTube bloquea sus iframes en `file://` (error 153).
- Spotify exige Redirect URI `http://127.0.0.1:PUERTO/...`: registra **http://127.0.0.1:38917/index.html** en developer.spotify.com.
- La biblioteca y ajustes se guardan por origen; por eso el puerto es fijo.

## Diferencias respecto a Android
- No hay reproductor nativo Media3: usa el motor web (ecualizador WebAudio de 10 bandas, teclas multimedia del sistema).
- Necesita internet la primera vez para Tailwind, FontAwesome y Google Fonts (CDN).
- Los instaladores no están firmados: Windows SmartScreen y macOS Gatekeeper avisarán.
