import { expect, test } from "vitest";
import { toTransactionDto } from "@/lib/mobile/dto";

const base = {
  _id: "64b000000000000000000001",
  userId: "u1",
  description: "coffee",
  amount: 4,
  category: "Food and Drink Item",
  type: "expense" as const,
};

test("maps ISO datetime date to YYYY-MM-DD", () => {
  const dto = toTransactionDto({ ...base, date: "2026-10-19T17:30:00.000Z" });
  expect(dto.date).toBe("2026-10-19");
});

test("keeps YYYY-MM-DD date unchanged", () => {
  expect(toTransactionDto({ ...base, date: "2026-10-19" }).date).toBe("2026-10-19");
});

test("legacy row maps source web and synthesized clientId", () => {
  const dto = toTransactionDto({ ...base, date: "2026-10-19" });
  expect(dto.source).toBe("web");
  expect(dto.clientId).toBe(base._id);
  expect(dto.id).toBe(base._id);
  expect(dto.transcript).toBeUndefined();
});

test("mobile row keeps clientId, source, transcript and ISO timestamps", () => {
  const created = new Date("2026-10-19T01:00:00.000Z");
  const dto = toTransactionDto({
    ...base,
    date: "2026-10-19",
    clientId: "c-1",
    source: "voice",
    transcript: "kafe 4",
    createdAt: created,
    updatedAt: created,
  });
  expect(dto).toMatchObject({
    clientId: "c-1",
    source: "voice",
    transcript: "kafe 4",
    createdAt: created.toISOString(),
    updatedAt: created.toISOString(),
  });
});

test("returns decimal amounts as stored", () => {
  expect(toTransactionDto({ ...base, date: "2026-10-19", amount: 12.5 }).amount).toBe(12.5);
});
