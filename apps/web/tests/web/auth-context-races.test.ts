import React from "react";
import { act, create, type ReactTestRenderer } from "react-test-renderer";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserPayload } from "@/lib/types";

const api = vi.hoisted(() => ({
  verifySession: vi.fn(), getCategories: vi.fn(), getBudgets: vi.fn(),
  getTransactions: vi.fn(), getUserDetails: vi.fn(), signOut: vi.fn(),
}));
vi.mock("@/lib/auth", () => ({ verifySession: api.verifySession }));
vi.mock("@/app/db-actions", () => ({ getCategories: api.getCategories, getBudgets: api.getBudgets, getTransactions: api.getTransactions }));
vi.mock("@/app/actions", () => ({ getUserDetails: api.getUserDetails, signOut: api.signOut }));

import { AuthProvider, useAuth } from "@/context/auth-context";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

const A = { userId: "account-a", email: "a@example.invalid" } as UserPayload;
const B = { userId: "account-b", email: "b@example.invalid" } as UserPayload;
let current: ReturnType<typeof useAuth>;
let tree: ReactTestRenderer | undefined;
function Probe() { current = useAuth(); return null; }
async function mount() {
  await act(async () => { tree = create(React.createElement(AuthProvider, null, React.createElement(Probe))); });
}

beforeEach(() => {
  vi.resetAllMocks();
  api.getCategories.mockResolvedValue(["B category"]);
  api.getBudgets.mockResolvedValue([{ category: "B category", limit: 200 }]);
  api.getTransactions.mockResolvedValue([{ _id: "b-entry", description: "B entry" }]);
  api.getUserDetails.mockResolvedValue(B);
});
afterEach(async () => { if (tree) await act(async () => { tree!.unmount(); }); tree = undefined; });

describe("account identity across asynchronous deletion and startup", () => {
  it("a late startup account cannot replace a newer login or its financial data", async () => {
    const initial = deferred<UserPayload | null>();
    api.verifySession.mockReturnValue(initial.promise);
    await mount();
    await act(async () => { await current.handleLogin(B); });
    await act(async () => { initial.resolve(A); });
    expect(current.user?.userId).toBe(B.userId);
    expect(current.transactions).toEqual([{ _id: "b-entry", description: "B entry" }]);
    expect(api.getUserDetails).toHaveBeenCalledTimes(1);
  });

  it("deletion before startup verification finishes prevents restoration of the deleted account", async () => {
    const initial = deferred<UserPayload | null>();
    api.verifySession.mockReturnValue(initial.promise);
    await mount();
    await act(async () => { current.clearDeletedAccount(A.userId); });
    await act(async () => { initial.resolve(A); });
    expect(current.user).toBeNull();
    expect(current.transactions).toEqual([]);
    expect(api.getUserDetails).not.toHaveBeenCalled();
  });

  it("completion of account A deletion preserves a newer account B and its cache", async () => {
    api.verifySession.mockResolvedValue(null);
    await mount();
    await act(async () => { await current.handleLogin(B); });
    await act(async () => { current.clearDeletedAccount(A.userId); });
    expect(current.user?.userId).toBe(B.userId);
    expect(current.categories).toEqual(["B category"]);
    expect(current.budgets).toEqual([{ category: "B category", limit: 200 }]);
    expect(current.transactions).toEqual([{ _id: "b-entry", description: "B entry" }]);
  });

  it("a financial read started before deletion cannot restore the removed account", async () => {
    api.verifySession.mockResolvedValue(null);
    const oldRows = deferred<unknown[]>();
    api.getTransactions.mockReturnValueOnce(oldRows.promise);
    api.getUserDetails.mockResolvedValueOnce(A);
    await mount();
    let login!: Promise<void>;
    await act(async () => { login = current.handleLogin(A); });
    await act(async () => { current.clearDeletedAccount(A.userId); });
    await act(async () => { oldRows.resolve([{ _id: "old-a-entry" }]); await login; });
    expect(current.user).toBeNull();
    expect(current.transactions).toEqual([]);
    expect(current.budgets).toEqual([]);
  });
});
