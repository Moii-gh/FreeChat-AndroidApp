const { Client } = require('ssh2');
const fs = require('fs');

const conn = new Client();
const filesToUpload = [
  {
    local: '../src/models/chatShareModel.js',
    remote: '/opt/chatapp-backend/src/models/chatShareModel.js'
  },
  {
    local: '../src/controllers/chatShareController.js',
    remote: '/opt/chatapp-backend/src/controllers/chatShareController.js'
  },
  {
    local: '../src/routes/chatShareRoutes.js',
    remote: '/opt/chatapp-backend/src/routes/chatShareRoutes.js'
  }
];

conn.on('ready', () => {
  console.log('Client :: ready');
  conn.sftp((err, sftp) => {
    if (err) throw err;
    
    let uploadedCount = 0;
    
    const uploadNext = () => {
      if (uploadedCount >= filesToUpload.length) {
        console.log('All files uploaded. Restarting service...');
        conn.exec('systemctl restart chatapp-auth.service', (err, stream) => {
          if (err) throw err;
          stream.on('close', (code, signal) => {
            console.log('Service restarted.');
            conn.end();
          }).on('data', (data) => {
            console.log('STDOUT: ' + data);
          }).stderr.on('data', (data) => {
            console.log('STDERR: ' + data);
          });
        });
        return;
      }
      
      const file = filesToUpload[uploadedCount];
      console.log(`Uploading ${file.local} -> ${file.remote}`);
      sftp.fastPut(file.local, file.remote, (err) => {
        if (err) throw err;
        uploadedCount++;
        uploadNext();
      });
    };
    
    uploadNext();
  });
}).connect({
  host: '138.124.97.180',
  port: 22,
  username: 'root',
  password: '103L84cc569l'
});
