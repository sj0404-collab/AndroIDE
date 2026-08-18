const { spawn } = require('child_process');
let pty;
try { pty = require('node-pty'); } catch (e) { pty = null; }

const cmd = process.env.CMD || 'echo no-command';
const chat = process.env.CHAT || '';
const line = chat ? `${cmd} ; echo "CHAT:${chat}"` : cmd;

if (pty) {
  const p = pty.spawn('bash', ['-lc', line], {
    name: 'xterm-256color',
    cols: 120,
    rows: 36,
    cwd: process.cwd(),
    env: process.env
  });
  p.onData((d) => process.stdout.write(d));
  p.onExit(({ exitCode }) => process.exit(exitCode || 0));
} else {
  const p = spawn('bash', ['-lc', line], { stdio: 'inherit' });
  p.on('exit', (c) => process.exit(c || 0));
}
