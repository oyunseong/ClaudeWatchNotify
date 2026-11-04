/** 서비스 계정 → OAuth 액세스 토큰 → FCM HTTP v1 전송. (docs/DESIGN.md 5.2, 6) */

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
const OAUTH_CACHE_KEY = "oauth:access";
/** Google 액세스 토큰은 1시간 유효. 여유를 두고 50분만 캐시한다 */
const OAUTH_CACHE_TTL = 3000;

export function parseServiceAccount(raw: string | undefined): ServiceAccount {
  if (!raw) throw new Error("FCM_SERVICE_ACCOUNT secret이 없음");
  const sa = JSON.parse(raw) as ServiceAccount;
  if (!sa.project_id || !sa.client_email || !sa.private_key) {
    throw new Error("FCM_SERVICE_ACCOUNT에 project_id/client_email/private_key가 없음");
  }
  return sa;
}

export async function getAccessToken(sa: ServiceAccount, kv: KVNamespace): Promise<string> {
  const cached = await kv.get(OAUTH_CACHE_KEY);
  if (cached) return cached;

  const tokenUri = sa.token_uri ?? DEFAULT_TOKEN_URI;
  const now = Math.floor(Date.now() / 1000);
  const jwt = await signJwt(
    { alg: "RS256", typ: "JWT" },
    { iss: sa.client_email, scope: SCOPE, aud: tokenUri, iat: now, exp: now + 3600 },
    sa.private_key,
  );

  const res = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) throw new Error(`OAuth 토큰 발급 실패: HTTP ${res.status} ${await res.text()}`);
  const { access_token } = (await res.json()) as { access_token: string };

  await kv.put(OAUTH_CACHE_KEY, access_token, { expirationTtl: OAUTH_CACHE_TTL });
  return access_token;
}

export type SendResult =
  | { ok: true }
  | { ok: false; status: number; code: string; tokenInvalid: boolean };

export async function sendData(
  sa: ServiceAccount,
  accessToken: string,
  deviceToken: string,
  data: Record<string, string>,
): Promise<SendResult> {
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      message: {
        token: deviceToken,
        // notification 필드를 넣지 않는다: 앱이 백그라운드여도 onMessageReceived가 호출되게
        data,
        android: { priority: "high", ttl: "600s" },
      },
    }),
  });
  if (res.ok) return { ok: true };

  const body = (await res.json().catch(() => ({}))) as FcmErrorBody;
  const fcmCode =
    body.error?.details?.find((d) => d["@type"]?.endsWith("FcmError"))?.errorCode ?? body.error?.status ?? "UNKNOWN";
  const tokenInvalid =
    fcmCode === "UNREGISTERED" ||
    (fcmCode === "INVALID_ARGUMENT" && /registration token/i.test(body.error?.message ?? ""));
  return { ok: false, status: res.status, code: fcmCode, tokenInvalid };
}

interface FcmErrorBody {
  error?: {
    status?: string;
    message?: string;
    details?: { "@type"?: string; errorCode?: string }[];
  };
}

// ---- JWT (RS256, WebCrypto) ----

async function signJwt(header: object, claims: object, privateKeyPem: string): Promise<string> {
  const enc = new TextEncoder();
  const unsigned = `${b64url(enc.encode(JSON.stringify(header)))}.${b64url(enc.encode(JSON.stringify(claims)))}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(privateKeyPem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(unsigned));
  return `${unsigned}.${b64url(new Uint8Array(sig))}`;
}

function pemToDer(pem: string): ArrayBuffer {
  const base64 = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

function b64url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
