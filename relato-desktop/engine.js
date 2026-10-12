// Cliente do motor Java (relato engine): um processo que fala JSON por linha em stdin/stdout.
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

class Engine {
  constructor(appRoot, resourcesPath, packaged) {
    this.appRoot = appRoot;
    this.resourcesPath = resourcesPath;
    this.packaged = packaged;
    this.proc = null;
    this.seq = 0;
    this.pending = new Map();
    this.readyWaiters = [];
    this.ready = false;
    this.lastError = '';
  }

  /** java + jar: empacotado usa o runtime embutido; em dev, build-resources ou o jar do Gradle. */
  locate() {
    const bases = this.packaged
      ? [path.join(this.resourcesPath, 'engine')]
      : [path.join(this.appRoot, 'build-resources', 'engine')];
    for (const b of bases) {
      const jar = path.join(b, 'relato-engine.jar');
      const java = path.join(b, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
      if (fs.existsSync(jar)) return { jar, java: fs.existsSync(java) ? java : this.systemJava() };
    }
    const gradleJar = path.join(this.appRoot, '..', 'relato-server', 'build', 'libs', 'relato-server-0.1.0-SNAPSHOT-all.jar');
    return { jar: gradleJar, java: this.systemJava() };
  }

  systemJava() {
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    for (const v of ['RELATO_JAVA_HOME', 'JAVA_HOME']) {
      const h = process.env[v];
      if (h && fs.existsSync(path.join(h, 'bin', exe))) return path.join(h, 'bin', exe);
    }
    return 'java';
  }

  start() {
    if (this.proc) return;
    const { jar, java } = this.locate();
    this.ready = false;
    this.lastError = '';
    const env = { ...process.env };
    delete env.JAVA_TOOL_OPTIONS; // opções globais da máquina não devem mudar o motor
    this.proc = spawn(java, ['-Xmx1g', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-jar', jar, 'engine'], {
      env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.proc.on('error', (e) => this.fail('não foi possível iniciar o Java (' + java + '): ' + e.message));
    this.proc.on('exit', (code) => {
      this.proc = null;
      this.fail('o motor parou (código ' + code + ')' + (this.lastError ? ': ' + this.lastError : ''));
    });
    this.proc.stderr.on('data', (d) => {
      const s = String(d).trim();
      if (s && !s.startsWith('Picked up')) this.lastError = s.split('\n').slice(-3).join('\n');
    });
    readline.createInterface({ input: this.proc.stdout }).on('line', (line) => {
      let msg;
      try { msg = JSON.parse(line); } catch { return; }
      if (msg.event === 'ready') {
        this.ready = true;
        this.readyWaiters.splice(0).forEach((w) => w.resolve());
        return;
      }
      const p = this.pending.get(msg.id);
      if (p) {
        this.pending.delete(msg.id);
        p.resolve(msg);
      }
    });
  }

  fail(reason) {
    for (const p of this.pending.values()) p.resolve({ error: reason });
    this.pending.clear();
    this.readyWaiters.splice(0).forEach((w) => w.reject(new Error(reason)));
    this.ready = false;
    this.lastFailure = reason;
  }

  whenReady() {
    if (this.ready) return Promise.resolve();
    this.start();
    return new Promise((resolve, reject) => this.readyWaiters.push({ resolve, reject }));
  }

  async call(cmd, args = {}) {
    try {
      await this.whenReady();
    } catch (e) {
      return { error: e.message };
    }
    const id = ++this.seq;
    return new Promise((resolve) => {
      this.pending.set(id, { resolve });
      this.proc.stdin.write(JSON.stringify({ id, cmd, ...args }) + '\n');
    });
  }

  stop() {
    if (this.proc) {
      try { this.proc.stdin.end(); } catch {}
      this.proc.kill();
      this.proc = null;
    }
  }
}

module.exports = { Engine };
