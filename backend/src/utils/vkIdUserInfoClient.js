const DEFAULT_USER_INFO_URL = "https://id.vk.ru/oauth2/user_info";

function pickVkUser(payload) {
  return payload?.user || payload?.response?.user || payload?.result?.user || payload;
}

function normalizeVkProfile(user) {
  const vkUserId = user?.user_id ?? user?.id ?? user?.sub;
  if (!vkUserId) {
    throw new Error("VK ID user_info response does not contain user_id");
  }

  const firstName = user.first_name || null;
  const lastName = user.last_name || null;
  const fullName = [firstName, lastName].filter(Boolean).join(" ").trim() ||
    user.name ||
    `VK ${vkUserId}`;

  return {
    vkUserId: String(vkUserId),
    fullName,
    vkFirstName: firstName,
    vkLastName: lastName,
    vkPhotoUrl: user.avatar || user.picture || user.photo_200 || null,
    vkEmail: user.email || null
  };
}

async function readErrorBody(response) {
  const text = await response.text().catch(() => "");
  if (!text) {
    return "";
  }

  try {
    const parsed = JSON.parse(text);
    return parsed.error_description || parsed.error || text;
  } catch (_error) {
    return text;
  }
}

async function fetchVkUserInfo({
  accessToken,
  clientId,
  expectedUserId,
  fetchImpl = global.fetch,
  userInfoUrl = DEFAULT_USER_INFO_URL
}) {
  if (!fetchImpl) {
    throw new Error("Fetch implementation is not available for VK ID verification");
  }

  const url = new URL(userInfoUrl);
  url.searchParams.set("client_id", String(clientId));

  const response = await fetchImpl(url.toString(), {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: new URLSearchParams({
      access_token: accessToken
    })
  });

  if (!response.ok) {
    const details = await readErrorBody(response);
    throw new Error(`VK ID user_info request failed${details ? `: ${details}` : ""}`);
  }

  const payload = await response.json();
  if (payload?.error) {
    throw new Error(payload.error_description || payload.error);
  }

  const profile = normalizeVkProfile(pickVkUser(payload));
  if (expectedUserId && profile.vkUserId !== String(expectedUserId)) {
    throw new Error("VK ID user_id does not match the access token owner");
  }

  return profile;
}

module.exports = {
  DEFAULT_USER_INFO_URL,
  fetchVkUserInfo,
  normalizeVkProfile
};
