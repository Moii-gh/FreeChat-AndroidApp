const { Client } = require('ssh2');
const conn = new Client();
conn.on('ready', () => {
  const cmds = [
    'echo "=== UFW STATUS ==="',
    'ufw status',
    'echo "=== NGINX LOGS ==="',
    'tail -n 10 /var/log/nginx/error.log',
    'echo "=== DOCKER PROXY FOR 443 ==="',
    'docker ps | grep 443'
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
