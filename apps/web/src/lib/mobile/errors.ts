import * as z from "zod/v4";
import { logger } from "@/lib/logger";

export class ApiError extends Error {
  status: number;
  code: string;
  retryAfterSec?: number;

  constructor(status: number, code: string, message: string, retryAfterSec?: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.retryAfterSec = retryAfterSec;
  }
}

export function apiError(
  status: number,
  code: string,
  message: string,
  headers?: Record<string, string>,
): Response {
  return Response.json({ error: { code, message } }, { status, headers });
}

export function handle<C = any>(fn: (req: Request, ctx: C) => Promise<Response>) {
  return async (req: Request, ctx: C): Promise<Response> => {
    try {
      return await fn(req, ctx);
    } catch (err) {
      if (err instanceof ApiError) {
        const headers =
          err.retryAfterSec !== undefined ? { "Retry-After": String(err.retryAfterSec) } : undefined;
        return apiError(err.status, err.code, err.message, headers);
      }
      if (err instanceof z.ZodError) {
        return apiError(422, "validation_failed", "Invalid request");
      }
      logger.error("Unhandled mobile API error", err instanceof Error ? err : new Error(String(err)));
      return apiError(500, "internal_error", "Internal server error");
    }
  };
}

export async function readJson(req: Request): Promise<unknown> {
  try {
    return await req.json();
  } catch {
    throw new ApiError(400, "invalid_json", "Request body must be valid JSON");
  }
}
