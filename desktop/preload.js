'use strict';
// Preload mínimo: la app web no necesita Node. Solo marca que corre en escritorio.
const { contextBridge } = require('electron');
contextBridge.exposeInMainWorld('plusMusicDesktop', { isDesktop: true, platform: process.platform });
