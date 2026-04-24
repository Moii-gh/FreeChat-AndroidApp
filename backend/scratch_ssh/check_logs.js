const { Client } = require('ssh2');

const conn = new Client();
conn.on('ready', () => {
  conn.exec('systemctl status chatapp-auth.service; echo "===LOGS==="; journalctl -u chatapp-auth.service -n 50 --no-pager', (err, stream) => {
    if (err) throw err;
    stream.on('close', () => conn.end())
    .on('data', (data) => process.stdout.write(data))
    .stderr.on('data', (data) => process.stderr.write(data));
  });
}).connect({
  host: '138.124.97.180',
  port: 22,
  username: 'root',
  password: '103L84cc569l'
});
