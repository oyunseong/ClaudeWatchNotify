/**
 * Claude Code 훅 → (이 Worker) → FCM → 갤럭시 워치
 * API 명세: docs/DESIGN.md 6
 */
import { hasBearer } from "./auth";
import { getAccessToken, parseServiceAccount, sendData } from "./fcm";

export interface Env {
  TOKENS: KVNamespace;
  FCM_SERVICE_ACCOUNT: string;
  NOTIFY_SECRET: string;
  REGISTER_SECRET: string;
}

const DEVICE_PREFIX = "device:";
const EVENTS = new Set(["Stop", "Notification"]);
const FIELDS = ["event", "project", "session", "elapsed", "message", "host", "ts"] as const;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    try {
      if (pathname === "/health" && request.method === "GET") return json({ ok: true });

      if (pathname === "/register" && (request.method === "POST" || request.method === "DELETE")) {
        if (!(await hasBearer(request, env.REGISTER_SECRET))) return json({ error: "unauthorized" }, 401);
        // await 해야 아래 catch가 예외를 잡는다
        return await (request.method === "POST" ? register(request, env) : unregister(request, env));
      }

      if (pathname === "/notify" && request.method === "POST") {
        if (!(await hasBearer(request, env.NOTIFY_SECRET))) return json({ error: "unauthorized" }, 401);
        return await notify(request, env);
      }

      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: String(e instanceof Error ? e.message : e) }, 500);
    }
  },
} satisfies ExportedHandler<Env>;

async function register(request: Request, env: Env): Promise<Response> {
  const body = await readJson<{ token?: unknown; device?: unknown }>(request);
  if (typeof body?.token !== "string" || body.token.length < 20) return json({ error: "token 필요" }, 400);
  const device = typeof body.device === "string" ? body.device.slice(0, 64) : "unknown";
  await env.TOKENS.put(DEVICE_PREFIX + body.token, "1", {
    metadata: { device, registeredAt: new Date().toISOString() },
  });
  return json({ ok: true });
}

async function unregister(request: Request, env: Env): Promise<Response> {
  const body = await readJson<{ token?: unknown }>(request);
  if (typeof body?.token !== "string") return json({ error: "token 필요" }, 400);
  await env.TOKENS.delete(DEVICE_PREFIX + body.token);
  return json({ ok: true });
}

async function notify(request: Request, env: Env): Promise<Response> {
  const body = await readJson<Record<string, unknown>>(request);
  if (!body || typeof body.event !== "string" || !EVENTS.has(body.event)) {
    return json({ error: "event는 Stop 또는 Notification" }, 400);
  }

  // FCM data는 문자열만 받는다. 페이로드 4KB 제한을 넘지 않게 자른다
  const data: Record<string, string> = {};
  for (const f of FIELDS) {
    const v = body[f];
    data[f] = (v === undefined || v === null ? "" : String(v)).slice(0, f === "message" ? 200 : 64);
  }

  const { keys } = await env.TOKENS.list({ prefix: DEVICE_PREFIX });
  if (keys.length === 0) return json({ sent: 0, removed: 0, errors: [] });

  const sa = parseServiceAccount(env.FCM_SERVICE_ACCOUNT);
  const accessToken = await getAccessToken(sa, env.TOKENS);

  let sent = 0;
  let removed = 0;
  const errors: { status: number; code: string }[] = [];
  await Promise.all(
    keys.map(async ({ name }) => {
      const result = await sendData(sa, accessToken, name.slice(DEVICE_PREFIX.length), data);
      if (result.ok) {
        sent++;
        return;
      }
      if (result.tokenInvalid) {
        await env.TOKENS.delete(name);
        removed++;
      } else {
        errors.push({ status: result.status, code: result.code });
      }
    }),
  );
  return json({ sent, removed, errors });
}

async function readJson<T>(request: Request): Promise<T | null> {
  try {
    return (await request.json()) as T;
  } catch {
    return null;
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}
