const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { execSync } = require('child_process');

const INSTANCES_DIR = path.join(process.env.USERPROFILE, 'curseforge', 'minecraft', 'Instances');
const PROJECT_DIR = path.resolve(__dirname, '..');
const MOD_JAR_DIR = path.join(PROJECT_DIR, 'build', 'libs');
const RUNTIME_DIR = path.join(PROJECT_DIR, 'agent-runtime');
const TUI_EXE = path.join(PROJECT_DIR, 'agent-tui', 'agent-tui.exe');
const USER_RUNTIME_DIR = path.join(process.env.USERPROFILE, '.agent-mod', 'agent-runtime');

const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
function ask(q) { return new Promise(r => rl.question(q, r)); }

function copyDirSync(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name);
    const d = path.join(dest, entry.name);
    if (entry.isDirectory()) copyDirSync(s, d);
    else fs.copyFileSync(s, d);
  }
}

async function main() {
  console.log('\n=== Agent Mod 배포 ===\n');

  // 1. 목록
  const dirs = fs.readdirSync(INSTANCES_DIR, { withFileTypes: true })
    .filter(d => d.isDirectory())
    .map(d => d.name)
    .sort();

  console.log('모드팩 목록:\n');
  dirs.forEach((name, i) => {
    let ver = '', loader = '';
    try {
      const json = JSON.parse(fs.readFileSync(path.join(INSTANCES_DIR, name, 'minecraftinstance.json'), 'utf-8'));
      ver = json.gameVersion || '';
      loader = json.baseModLoader?.name || '';
    } catch {}
    const ok = ver === '1.20.1' && loader.startsWith('forge');
    const mark = ok ? '\x1b[32m[O]\x1b[0m' : '\x1b[90m[X]\x1b[0m';
    console.log(`  ${String(i).padStart(2)}) ${mark}  ${name}  (${loader || 'unknown'})`);
  });

  console.log('\n[O] = Forge 1.20.1 호환  [X] = 비호환\n');
  const choice = parseInt(await ask('설치할 모드팩 번호: '));

  if (isNaN(choice) || choice < 0 || choice >= dirs.length) {
    console.log('잘못된 번호');
    process.exit(1);
  }

  const targetName = dirs[choice];
  const target = path.join(INSTANCES_DIR, targetName);
  console.log(`\n대상: ${targetName}\n`);

  // 2. JAR
  console.log('[1/4] 모드 JAR...');
  const jarFilter = f => f.startsWith('agent-') && f.endsWith('.jar') && !f.includes('sources') && !f.includes('slim');
  let jars = [];
  try { jars = fs.readdirSync(MOD_JAR_DIR).filter(jarFilter); } catch {}
  if (jars.length === 0) {
    console.log('  빌드 중...');
    execSync('gradlew.bat build -q', { cwd: PROJECT_DIR, stdio: 'inherit' });
    jars = fs.readdirSync(MOD_JAR_DIR).filter(jarFilter);
  }
  const jar = jars[0];
  console.log(`  OK: ${jar}`);

  // 3. Runtime
  console.log('[2/4] agent-runtime...');
  if (!fs.existsSync(path.join(RUNTIME_DIR, 'dist', 'index.js'))) {
    console.log('  빌드 중...');
    execSync('npm install --silent && npm run build', { cwd: RUNTIME_DIR, stdio: 'inherit' });
  }
  console.log('  OK');

  // 4. TUI
  console.log('[3/4] agent-tui...');
  if (!fs.existsSync(TUI_EXE)) {
    console.log('  빌드 중...');
    execSync('go build -o agent-tui.exe .', { cwd: path.join(PROJECT_DIR, 'agent-tui'), stdio: 'inherit' });
  }
  console.log('  OK');

  // 5. agent-runtime → ~/.agent-mod/agent-runtime (shared across instances)
  console.log('[4/5] agent-runtime → ~/.agent-mod/ ...');
  // Clean old instance-local runtime if exists
  const oldRtTarget = path.join(target, 'agent-runtime');
  if (fs.existsSync(oldRtTarget)) {
    fs.rmSync(oldRtTarget, { recursive: true, force: true });
    console.log(`  삭제: (인스턴스 내 레거시) agent-runtime/`);
  }
  copyDirSync(path.join(RUNTIME_DIR, 'dist'), path.join(USER_RUNTIME_DIR, 'dist'));
  copyDirSync(path.join(RUNTIME_DIR, 'node_modules'), path.join(USER_RUNTIME_DIR, 'node_modules'));
  fs.copyFileSync(path.join(RUNTIME_DIR, 'package.json'), path.join(USER_RUNTIME_DIR, 'package.json'));
  console.log(`  -> ${USER_RUNTIME_DIR}`);

  // 6. JAR + TUI → 인스턴스
  console.log('[5/5] JAR + TUI → 인스턴스...');
  const modsDir = path.join(target, 'mods');
  for (const f of fs.readdirSync(modsDir)) {
    if (f.startsWith('agent-') && f.endsWith('.jar')) {
      fs.unlinkSync(path.join(modsDir, f));
      console.log(`  삭제: mods/${f}`);
    }
  }
  fs.copyFileSync(path.join(MOD_JAR_DIR, jar), path.join(modsDir, jar));
  console.log(`  -> mods/${jar}`);

  fs.copyFileSync(TUI_EXE, path.join(target, 'agent-tui.exe'));
  console.log('  -> agent-tui.exe');

  console.log('\n=== 배포 완료 ===\n');
  console.log(`  agent-runtime: ${USER_RUNTIME_DIR}`);
  console.log(`  mod JAR:       ${target}\\mods\\${jar}`);
  console.log(`  TUI:           ${target}\\agent-tui.exe\n`);
  console.log(`  1. CurseForge에서 ${targetName} 실행`);
  console.log('  2. 월드 진입');
  console.log(`  3. 별도 터미널: cd "${target}" && .\\agent-tui.exe\n`);

  rl.close();
}

main().catch(e => { console.error(e); process.exit(1); });
