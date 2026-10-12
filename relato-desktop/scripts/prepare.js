// Prepara o app: copia o designer do plugin, os exemplos e o motor (jar + Java mínimo via jlink).
//   node scripts/prepare.js            copia tudo e gera o jar pelo Gradle
//   node scripts/prepare.js --quick    copia o designer/exemplos; usa o jar se já existir
//   node scripts/prepare.js --runtime  também monta o Java mínimo (jlink) para o instalador
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const REPO = path.join(ROOT, '..');
const RES = path.join(ROOT, 'build-resources');
const quick = process.argv.includes('--quick');
const runtime = process.argv.includes('--runtime');

function copyDir(from, to, filter = () => true) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    const s = path.join(from, e.name), d = path.join(to, e.name);
    if (e.isDirectory()) copyDir(s, d, filter);
    else if (filter(e.name)) fs.copyFileSync(s, d);
  }
}

// 1. designer (fonte única: recursos do plugin)
const designerSrc = path.join(REPO, 'relato-intellij', 'src', 'main', 'resources', 'designer');
const designerDst = path.join(ROOT, 'renderer', 'designer');
fs.rmSync(designerDst, { recursive: true, force: true });
copyDir(designerSrc, designerDst, (n) => n !== 'designer.html');
console.log('designer copiado');

// 2. exemplos + miniaturas da galeria
const exDst = path.join(RES, 'examples');
fs.rmSync(exDst, { recursive: true, force: true });
copyDir(path.join(REPO, 'examples'), exDst, (n) => /\.(report|sample)\.json$/.test(n));
copyDir(path.join(REPO, 'docs', 'galeria'), path.join(exDst, 'galeria'), (n) => n.endsWith('.png'));
console.log('exemplos copiados');

// 3. motor
const engineDst = path.join(RES, 'engine');
fs.mkdirSync(engineDst, { recursive: true });
const jar = path.join(REPO, 'relato-server', 'build', 'libs', 'relato-server-0.1.0-SNAPSHOT-all.jar');
const gradleJava = process.env.RELATO_GRADLE_JAVA || path.join(os.homedir(), 'AppData', 'Local', 'Programs', 'IntelliJ IDEA', 'jbr');
if (!quick || !fs.existsSync(jar)) {
  console.log('gerando o jar do motor (Gradle)…');
  const gradlew = path.join(REPO, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  execFileSync(gradlew, [':relato-server:fatJar', '-q'], {
    cwd: REPO, stdio: 'inherit', shell: process.platform === 'win32',
    env: { ...process.env, JAVA_HOME: fs.existsSync(gradleJava) ? gradleJava : process.env.JAVA_HOME },
  });
}
fs.copyFileSync(jar, path.join(engineDst, 'relato-engine.jar'));
console.log('motor copiado');

// 4. Java mínimo (jlink) para rodar sem Java instalado
if (runtime) {
  const jdk = findJdkWithJmods();
  if (!jdk) throw new Error('nenhum JDK com jmods encontrado (defina RELATO_JLINK_JDK)');
  const out = path.join(engineDst, 'runtime');
  fs.rmSync(out, { recursive: true, force: true });
  const modules = ['java.base', 'java.desktop', 'java.xml', 'java.logging', 'java.sql', 'java.naming', 'jdk.httpserver', 'jdk.charsets', 'jdk.crypto.ec', 'jdk.zipfs', 'jdk.unsupported'];
  console.log('jlink com ' + jdk);
  execFileSync(path.join(jdk, 'bin', 'jlink'), [
    '--add-modules', modules.join(','), '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2', '--output', out,
  ], { stdio: 'inherit' });
  console.log('runtime pronto em ' + out);
}

function findJdkWithJmods() {
  const cands = [process.env.RELATO_JLINK_JDK];
  const jdks = path.join(os.homedir(), '.jdks');
  if (fs.existsSync(jdks)) {
    // prefere um LTS 21 (o motor é compilado para 17)
    for (const d of fs.readdirSync(jdks).filter((n) => /-21\./.test(n)).sort().reverse()) cands.push(path.join(jdks, d));
  }
  cands.push(process.env.JAVA_HOME);
  return cands.find((c) => c && fs.existsSync(path.join(c, 'jmods', 'java.base.jmod')) && fs.existsSync(path.join(c, 'bin', 'jlink.exe')));
}
