const { Client } = require('ssh2');

const conn = new Client();
const filesToUpload = [
  {
    local: '../src/controllers/chatShareController.js',
    remote: '/opt/chatapp-backend/src/controllers/chatShareController.js'
  }
];

conn.on('ready', () => {
  console.log('Connected');
  conn.sftp((err, sftp) => {
    if (err) throw err;
    let i = 0;
    const next = () => {
      if (i >= filesToUpload.length) {
        console.log('All files uploaded. Restarting...');
        conn.exec('systemctl restart chatapp-auth.service', (err, stream) => {
          if (err) throw err;
          stream.on('close', () => { console.log('Service restarted.'); conn.end(); })
          .on('data', (d) => console.log('STDOUT: ' + d))
          .stderr.on('data', (d) => console.log('STDERR: ' + d));
        });
        return;
      }
      const f = filesToUpload[i];
      console.log(`Uploading ${f.local} -> ${f.remote}`);
      sftp.fastPut(f.local, f.remote, (err) => { if (err) throw err; i++; next(); });
    };
    next();
  });
}).connect({
  host: '138.124.97.180',
  port: 22,
  username: 'root',
  password: '103L84cc569l'
});
