const { Client } = require('ssh2');
const conn = new Client();
conn.on('ready', () => {
  const cmds = [
    'which nginx 2>/dev/null && nginx -v 2>&1 || echo "nginx not installed"',
    'echo "---"',
    'ss -tlnp | grep -E ":80|:443" || echo "No listeners on 80/443"',
    'echo "---"',
    'cat /etc/systemd/system/chatapp-auth.service 2>/dev/null || echo "no service file"'
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
