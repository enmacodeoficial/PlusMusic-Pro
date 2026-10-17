'use strict';
/*
 * PlusMusic Pro — escritorio (Electron).
 * Sirve www/ desde http://127.0.0.1:PUERTO porque:
 *  - YouTube rechaza (error 153) los iframes cargados desde file:// (sin origen/referrer).
 *  - Spotify exige un Redirect URI http://127.0.0.1:PUERTO/... para el login PKCE.
 *  - localStorage/IndexedDB (biblioteca, ecualizador, Client ID) se guardan POR ORIGEN,
 *    así que el puerto debe ser siempre el mismo.
 */
const { app, BrowserWindow, Menu, dialog, shell, session } = require('electron');
const http = require('http');
const fs = require('fs');
const path = require('path');

const PREFERRED_PORT = 38917;
const HOST = '127.0.0.1';
const START_PATH = '/index.html';

const WWW_DIR = app.isPackaged
  ? path.join(process.resourcesPath, 'www')
  : path.join(__dirname, '..', 'www');

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff', '.txt': 'text/plain; charset=utf-8'
};

// Audio: reproducir sin gesto previo y usar teclas multimedia del sistema.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('enable-features', 'HardwareMediaKeyHandling,MediaSessionService');
// Que el audio no se corte al minimizar / ocultar la ventana.
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');

if (!app.requestSingleInstanceLock()) { app.quit(); }

let mainWindow = null;
let serverPort = PREFERRED_PORT;

function handleRequest(req, res) {
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); return res.end(); }
  let pathname;
  try { pathname = decodeURIComponent(new URL(req.url, 'http://x').pathname); } catch (_) { res.writeHead(400); return res.end(); }
  if (pathname === '/') pathname = START_PATH;
  const filePath = path.normalize(path.join(WWW_DIR, pathname));
  if (filePath !== WWW_DIR && !filePath.startsWith(WWW_DIR + path.sep)) { res.writeHead(403); return res.end(); }
  fs.stat(filePath, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
      'Content-Length': st.size,
      'Cache-Control': 'no-cache'
    });
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(filePath).pipe(res);
  });
}

function listen(port) {
  return new Promise((resolve, reject) => {
    const server = http.createServer(handleRequest);
    server.once('error', reject);
    server.listen(port, HOST, () => resolve(server));
  });
}

async function startServer() {
  for (let i = 0; i < 20; i++) {
    const port = PREFERRED_PORT + i;
    try { await listen(port); serverPort = port; return; }
    catch (e) { if (e.code !== 'EADDRINUSE') throw e; }
  }
  throw new Error('No hay ningún puerto libre entre ' + PREFERRED_PORT + ' y ' + (PREFERRED_PORT + 19));
}

const isAppUrl = u => { try { const x = new URL(u); return x.hostname === HOST && Number(x.port) === serverPort; } catch (_) { return false; } };
const isSpotifyAuth = u => { try { return new URL(u).hostname === 'accounts.spotify.com'; } catch (_) { return false; } };

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180, height: 780, minWidth: 380, minHeight: 600,
    backgroundColor: '#080908', title: 'PlusMusic Pro', autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true,
      backgroundThrottling: false
    }
  });

  // Enlaces externos (target=_blank, "Abrir en Spotify", etc.) → navegador del sistema.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  // El login de Spotify navega en la misma ventana (PKCE guarda el verifier en sessionStorage), así que se permite.
  mainWindow.webContents.on('will-navigate', (e, url) => {
    if (isAppUrl(url) || isSpotifyAuth(url)) return;
    e.preventDefault();
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
  });

  mainWindow.loadURL(`http://${HOST}:${serverPort}${START_PATH}`);
  mainWindow.on('closed', () => { mainWindow = null; });
}

function buildMenu() {
  const isMac = process.platform === 'darwin';
  const template = [
    ...(isMac ? [{ role: 'appMenu' }] : []),
    { label: 'Archivo', submenu: [isMac ? { role: 'close' } : { role: 'quit', label: 'Salir' }] },
    { label: 'Edición', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }] },
    { label: 'Ver', submenu: [{ role: 'reload', label: 'Recargar' }, { role: 'togglefullscreen', label: 'Pantalla completa' }, { type: 'separator' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { role: 'resetZoom' }, { type: 'separator' }, { role: 'toggleDevTools', label: 'Herramientas de desarrollo' }] },
    { label: 'Ayuda', submenu: [{ label: 'Redirect URI de Spotify', click: () => dialog.showMessageBox({ type: 'info', title: 'Spotify', message: 'Registra este Redirect URI en tu app de Spotify:', detail: `http://${HOST}:${serverPort}${START_PATH}` }) }] }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.on('second-instance', () => { if (mainWindow) { if (mainWindow.isMinimized()) mainWindow.restore(); mainWindow.focus(); } });

app.whenReady().then(async () => {
  try { await startServer(); }
  catch (err) { dialog.showErrorBox('PlusMusic Pro', 'No se pudo iniciar el servidor local.\n\n' + err.message); return app.quit(); }

  if (serverPort !== PREFERRED_PORT) {
    dialog.showMessageBoxSync({ type: 'warning', title: 'PlusMusic Pro',
      message: `El puerto ${PREFERRED_PORT} está ocupado; se usa el ${serverPort}.`,
      detail: 'Tu biblioteca y ajustes se guardan por puerto, así que no verás los datos de otras sesiones y deberás registrar de nuevo el Redirect URI de Spotify (Ayuda → Redirect URI de Spotify). Cierra el programa que usa el puerto original para recuperarlos.' });
  }

  // Algunos servicios bloquean user agents con "Electron/": usamos el de Chromium puro.
  session.defaultSession.setUserAgent(session.defaultSession.getUserAgent().replace(/\s?Electron\/[\d.]+/, '').replace(/\s?plusmusic-pro-desktop\/[\d.]+/i, ''));
  // Permisos: solo los necesarios para audio/reproducción.
  session.defaultSession.setPermissionRequestHandler((_wc, permission, cb) => cb(['media', 'mediaKeySystem', 'fullscreen', 'speaker-selection'].includes(permission)));

  buildMenu();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
