export interface ReqOptions {
  token?: string;
  body?: unknown;
  ip?: string;
}

export function req(
  method: string,
  path: string,
  { token, body, ip }: ReqOptions = {},
): Request {
  const headers: Record<string, string> = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (ip) headers["x-forwarded-for"] = ip;
  let payload: string | undefined;
  if (body !== undefined) {
    headers["content-type"] = "application/json";
    payload = typeof body === "string" ? body : JSON.stringify(body);
  }
  return new Request(`http://localhost${path}`, { method, headers, body: payload });
}
