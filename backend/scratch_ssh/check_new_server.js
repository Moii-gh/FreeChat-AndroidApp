const { Client } = require('ssh2');
const conn = new Client();
conn.on('ready', () => {
  const cmds = [
    'node -v || echo "node not installed"',
    'npm -v || echo "npm not installed"',
    'psql --version || echo "postgres not installed"',
    'nginx -v || echo "nginx not installed"',
    'ls -la /opt/chatapp-backend || echo "directory does not exist"'
  ];
  conn.exec(cmds.join(' && '), (err, stream) => {
    if (err) throw err;
    stream.on('close', () => conn.end())
    .on('data', (d) => process.stdout.write(d))
    .stderr.on('data', (d) => process.stderr.write(d));
  });
}).connect({
  host: '31.31.197.18',
  port: 22,
  username: 'root',
  password: 'WjveAP2rOA5QQ581'
});
