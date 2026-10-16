import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const androidDir = path.join(root, 'android');
const configPath = path.join(root, 'capacitor.config.json');
if (!fs.existsSync(configPath)) throw new Error('No se encontró capacitor.config.json.');
if (!fs.existsSync(androidDir)) throw new Error('No se encontró la carpeta android. Ejecuta npx cap add android y npx cap sync android antes de este script.');

const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const appId = String(config.appId || 'com.plusmusic.app');
if (!/^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+$/.test(appId)) throw new Error(`appId no válido para Java: ${appId}`);
const javaRelativePath = path.join('app', 'src', 'main', 'java', ...appId.split('.'));
const javaDir = path.join(androidDir, javaRelativePath);
fs.mkdirSync(javaDir, { recursive: true });

for (const name of ['NativePlaybackPlugin.java', 'PlusMusicPlaybackService.java']) {
  const templatePath = path.join(root, 'native', 'android', name);
  if (!fs.existsSync(templatePath)) throw new Error(`Falta plantilla nativa: ${templatePath}`);
  const source = fs.readFileSync(templatePath, 'utf8').replaceAll('__PACKAGE__', appId);
  fs.writeFileSync(path.join(javaDir, name), source, 'utf8');
}

const mainActivity = `package ${appId};\n\nimport android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;\n\n/** Registers PlusMusic's native Media3 player bridge. */\npublic class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n        registerPlugin(NativePlaybackPlugin.class);\n        super.onCreate(savedInstanceState);\n    }\n}\n`;
fs.writeFileSync(path.join(javaDir, 'MainActivity.java'), mainActivity, 'utf8');

const manifestPath = path.join(androidDir, 'app', 'src', 'main', 'AndroidManifest.xml');
if (!fs.existsSync(manifestPath)) throw new Error('No se encontró android/app/src/main/AndroidManifest.xml');
let manifest = fs.readFileSync(manifestPath, 'utf8');
const permissions = [
  'android.permission.INTERNET',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
  'android.permission.WAKE_LOCK',
  'android.permission.MODIFY_AUDIO_SETTINGS',
  'android.permission.POST_NOTIFICATIONS'
];
const manifestTag = manifest.match(/<manifest\b[^>]*>/);
if (!manifestTag) throw new Error('No se encontró la etiqueta <manifest>');
const missingPermissions = permissions.filter(permission => !manifest.includes(`android:name="${permission}"`));
if (missingPermissions.length) {
  const block = missingPermissions.map(permission => `    <uses-permission android:name="${permission}" />`).join('\n');
  manifest = manifest.replace(manifestTag[0], `${manifestTag[0]}\n${block}`);
}
const serviceName = `${appId}.PlusMusicPlaybackService`;
if (!manifest.includes(`android:name="${serviceName}"`) && !manifest.includes('android:name=".PlusMusicPlaybackService"')) {
  const service = `        <service\n            android:name=".PlusMusicPlaybackService"\n            android:enabled="true"\n            android:exported="true"\n            android:foregroundServiceType="mediaPlayback"\n            android:stopWithTask="false">\n            <intent-filter>\n                <action android:name="androidx.media3.session.MediaSessionService" />\n                <action android:name="android.media.browse.MediaBrowserService" />\n            </intent-filter>\n        </service>\n`;
  if (!manifest.includes('</application>')) throw new Error('No se encontró </application> en el manifiesto');
  manifest = manifest.replace('</application>', `${service}    </application>`);
}
fs.writeFileSync(manifestPath, manifest, 'utf8');

// Media3 requires compile SDK 34. Capacitor 5's generated template can still default to 33.
const varsPath = path.join(androidDir, 'variables.gradle');
if (fs.existsSync(varsPath)) {
  let vars = fs.readFileSync(varsPath, 'utf8');
  vars = vars.replace(/compileSdkVersion\s*=\s*(\d+)/, (full, v) => Number(v) < 34 ? 'compileSdkVersion = 34' : full);
  vars = vars.replace(/targetSdkVersion\s*=\s*(\d+)/, (full, v) => Number(v) < 34 ? 'targetSdkVersion = 34' : full);
  fs.writeFileSync(varsPath, vars, 'utf8');
}

const appGradle = path.join(androidDir, 'app', 'build.gradle');
if (!fs.existsSync(appGradle)) throw new Error('No se encontró android/app/build.gradle');
let gradle = fs.readFileSync(appGradle, 'utf8');
const mediaDeps = [
  '    implementation "androidx.media3:media3-exoplayer:1.4.1"',
  '    implementation "androidx.media3:media3-session:1.4.1"'
];
const missingDeps = mediaDeps.filter(line => !gradle.includes(line.split('"')[1]));
if (missingDeps.length) {
  const dependencyBlock = gradle.match(/dependencies\s*\{/);
  if (!dependencyBlock) throw new Error('No se encontró el bloque dependencies en android/app/build.gradle');
  gradle = gradle.replace(dependencyBlock[0], `${dependencyBlock[0]}\n${missingDeps.join('\n')}`);
}
fs.writeFileSync(appGradle, gradle, 'utf8');

console.log('Integración Media3 aplicada: ExoPlayer, MediaSessionService, puente Capacitor, permisos y ecualizador Android.');
