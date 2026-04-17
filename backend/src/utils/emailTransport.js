const nodemailer = require("nodemailer");
const { env } = require("../config/env");

function createTransporter() {
  if (env.smtpHost) {
    return nodemailer.createTransport({
      host: env.smtpHost,
      port: env.smtpPort,
      secure: env.smtpSecure,
      auth: env.smtpUser
        ? {
            user: env.smtpUser,
            pass: env.smtpPass
          }
        : undefined
    });
  }

  return nodemailer.createTransport({
    jsonTransport: true
  });
}

const transporter = createTransporter();
const isConfigured = Boolean(env.smtpHost);

async function sendVerificationCode(email, code) {
  await transporter.sendMail({
    from: env.mailFrom,
    to: email,
    subject: "Подтверждение почты",
    text: `Ваш код подтверждения: ${code}`,
    html: `<p>Ваш код подтверждения: <strong>${code}</strong></p>`
  });
}

module.exports = {
  isConfigured,
  transporter,
  sendVerificationCode
};
