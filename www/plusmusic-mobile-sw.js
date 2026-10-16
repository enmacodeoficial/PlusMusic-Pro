/* PlusMusic Pro 1.0 — shell cache only; never cache audio streams. */
'use strict';
const CACHE_NAME = 'plusmusic-app-shell-pro-1.0.0';
const SHELL_FILES = [
  './index.html',
  './plusmusic-mobile.webmanifest',
  './plusmusic-mobile-icon.svg',
  './plusmusic-mobile-icon-180.png',
  './plusmusic-mobile-icon-192.png',
  './plusmusic-mobile-icon-512.png'
];
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.addAll(SHELL_FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('plusmusic-app-shell-') && key !== CACHE_NAME).map(key => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  let url;
  try { url = new URL(req.url); } catch (_) { return; }
  if (url.origin !== self.location.origin) return;
  const path = url.pathname;
  const isShell = /\/(?:index\.html|plusmusic-mobile\.webmanifest|plusmusic-mobile-icon(?:-180|-192|-512)?\.png|plusmusic-mobile-icon\.svg)$/.test(path) || path.endsWith('/');
  if (!isShell) return; // Do not intercept/cache audio or arbitrary media requests.
  if (req.mode === 'navigate' || path.endsWith('/index.html') || path.endsWith('/')) {
    event.respondWith(fetch(req).then(response => {
      if (response && response.ok) caches.open(CACHE_NAME).then(cache => cache.put('./index.html', response.clone())).catch(() => {});
      return response;
    }).catch(() => caches.match('./index.html')));
  } else {
    event.respondWith(caches.match(req).then(cached => cached || fetch(req)));
  }
});
