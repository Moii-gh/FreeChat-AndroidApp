const net = require("node:net");

const YOOKASSA_IP_RANGES = [
  "185.71.76.0/27",
  "185.71.77.0/27",
  "77.75.153.0/25",
  "77.75.156.11/32",
  "77.75.156.35/32",
  "77.75.154.128/25",
  "2a02:5180::/32"
];

const yookassaBlockList = new net.BlockList();

for (const range of YOOKASSA_IP_RANGES) {
  if (range.includes("/")) {
    const [network, prefix] = range.split("/");
    const family = net.isIP(network) === 6 ? "ipv6" : "ipv4";
    yookassaBlockList.addSubnet(network, Number(prefix), family);
  } else {
    const family = net.isIP(range) === 6 ? "ipv6" : "ipv4";
    yookassaBlockList.addAddress(range, family);
  }
}

function normalizeIp(value) {
  if (!value) {
    return "";
  }

  const normalized = String(value).trim();
  if (normalized.startsWith("::ffff:")) {
    return normalized.slice("::ffff:".length);
  }

  const zoneIndex = normalized.indexOf("%");
  return zoneIndex === -1 ? normalized : normalized.slice(0, zoneIndex);
}

function isPrivateOrLoopback(ip) {
  const normalized = normalizeIp(ip);
  const family = net.isIP(normalized);

  if (family === 4) {
    const octets = normalized.split(".").map(Number);
    if (octets[0] === 10 || octets[0] === 127) {
      return true;
    }

    if (octets[0] === 192 && octets[1] === 168) {
      return true;
    }

    return octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31;
  }

  if (family === 6) {
    return normalized === "::1" ||
      normalized.startsWith("fc") ||
      normalized.startsWith("fd") ||
      normalized.startsWith("fe80:");
  }

  return false;
}

function extractRequestIp(req) {
  const remoteAddress = normalizeIp(req?.socket?.remoteAddress || req?.ip || "");
  if (remoteAddress && !isPrivateOrLoopback(remoteAddress)) {
    return remoteAddress;
  }

  const forwarded = String(req?.headers?.["x-forwarded-for"] || "")
    .split(",")
    .map((item) => normalizeIp(item))
    .find(Boolean);
  if (forwarded) {
    return forwarded;
  }

  const realIp = normalizeIp(req?.headers?.["x-real-ip"]);
  return realIp || remoteAddress;
}

function isIpAllowed(blockList, ip) {
  const normalized = normalizeIp(ip);
  const family = net.isIP(normalized);
  if (!family) {
    return false;
  }

  return blockList.check(normalized, family === 6 ? "ipv6" : "ipv4");
}

function isYookassaWebhookIp(ip) {
  return isIpAllowed(yookassaBlockList, ip);
}

module.exports = {
  YOOKASSA_IP_RANGES,
  extractRequestIp,
  isYookassaWebhookIp
};
