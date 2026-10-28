import { afterAll, beforeAll, expect, it } from "vitest";
import bcrypt from "bcryptjs";
import mongoose from "mongoose";
import { startMongoServerOnly, stopMongo } from "../helpers/db";
import { req } from "../helpers/request";

// Cold start: the server is up but this process has never connected mongoose.
// rateLimit() used to await RateLimit.init() first, which waits forever.
beforeAll(async () => {
  await startMongoServerOnly();
});
afterAll(async () => {
  await stopMongo();
});

it("first login on a cold process resolves instead of hanging", async () => {
  expect(mongoose.connection.readyState).toBe(0);
  const POST = (await import("@/app/api/v1/auth/token/route")).POST as (r: Request, c?: unknown) => Promise<Response>;
  // Seed through a separate connection so the app's own path stays cold.
  const seed = await mongoose.createConnection(process.env.MONGODB_URI!).asPromise();
  await seed.collection("users").insertOne({
    email: "cold@example.com",
    password: await bcrypt.hash("correct-horse", 10),
    fullName: "Cold",
  });
  await seed.close();
  expect(mongoose.connection.readyState).toBe(0);

  const res = await Promise.race([
    POST(req("POST", "/api/v1/auth/token", {
      body: { email: "cold@example.com", password: "correct-horse" },
      ip: "10.9.9.9",
    })),
    new Promise<"timeout">((r) => setTimeout(() => r("timeout"), 5000)),
  ]);
  expect(res).not.toBe("timeout");
  expect((res as Response).status).toBe(200);
}, 15000);
