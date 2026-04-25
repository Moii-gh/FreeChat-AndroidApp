const { Client } = require('ssh2');
const conn = new Client();
conn.on('ready', () => {
  const cmds = [
    'docker ps --format "{{.ID}} {{.Image}} {{.Ports}} {{.Names}}" 2>/dev/null || echo "no docker"',
    'echo "=== DNS CHECK ==="',
    'apt list --installed 2>/dev/null | grep -i certbot || echo "certbot not installed"',
    'echo "=== ALL LISTENERS ==="',
    'ss -tlnp | head -30'
  ];
  conn.exec(cmds.join(' && '), (err, stream) => {
    if (err) throw err;
    stream.on('close', () => conn.end())
    .on('data', (d) => process.stdout.write(d))
    .stderr.on('data', (d) => process.stderr.write(d));
  });
}).connect({
  host: '138.124.97.180',
  port: 22,
  username: 'root',
  password: '103L84cc569l'
});
