/** Authorization: Bearer <secret> 를 상수 시간으로 비교한다. (docs/DESIGN.md 6) */
export async function hasBearer(request: Request, secret: string | undefined): Promise<boolean> {
  if (!secret) return false;
  const header = request.headers.get("Authorization") ?? "";
  if (!header.startsWith("Bearer ")) return false;
  const given = header.slice("Bearer ".length);

  // 길이가 다르면 timingSafeEqual이 예외를 던지므로 해시끼리 비교한다
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(given)),
    crypto.subtle.digest("SHA-256", enc.encode(secret)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}
