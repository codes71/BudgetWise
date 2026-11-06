import { db, leafId, scopedId, sha256Hex, toDate, withTransaction, type MongoRecord } from "@/lib/mongodb";
import { formatKyat, formatLakh } from "@/lib/kyat";
import { yangonDate } from "@/lib/mobile/scan";
import type { Budget } from "@/lib/types";
import { requireActiveAccount } from "@/lib/account-access";

const budgetsCol = async () => (await db()).collection<MongoRecord>("budgets");
const monthsCol = async () => (await db()).collection<MongoRecord>("budgetMonths");

/** One category's part of a month's total. */
export interface MonthShare {
  category: string;
  limit: number;
}

/** What the phone sends for a month: total is newMoney + carriedOver. */
export interface MonthPlanInput {
  newMoney: number;
  carriedOver: number;
  shares: MonthShare[];
}

/** An account's monthly plan, with dates decoded from BSON. */
export interface MonthPlan extends MonthPlanInput {
  month: string;
  endCheck: { onHand: number; checkedAt: Date } | null;
  createdAt: Date;
  updatedAt: Date;
}

/** The calendar month in Yangon, "YYYY-MM": the month whose plan listBudgets shows. */
export const yangonMonth = (now: Date): string => yangonDate(now).slice(0, 7);

export const splitOf = (shares: MonthShare[]): number => shares.reduce((sum, s) => sum + s.limit, 0);

export const totalOf = (plan: MonthPlanInput): number => plan.newMoney + plan.carriedOver;

/** Thrown inside a transaction so nothing is written; the message is shown on the web as is. */
export class SplitOverTotalError extends Error {
  constructor(total: number) {
    super(`The split would go over this month's budget of ${formatKyat(total)}.`);
    this.name = "SplitOverTotalError";
  }
}

/**
 * What the web, the dashboard, AI suggestions and GET /api/v1/budgets show: this Yangon month's plan's shares in
 * plan order when it has one, otherwise the old per-category budgets, oldest first (the order they were added).
 */
export async function listBudgets(userId: string): Promise<Budget[]> {
  const plan = await getMonthPlan(userId, yangonMonth(new Date()));
  if (plan) return plan.shares.map(({ category, limit }) => ({ _id: sha256Hex(category), userId, category, limit }));
  const docs = await (await budgetsCol()).find({ userId }).toArray();
  return docs
    .map((doc) => ({ doc, id: leafId(doc._id), createdAt: toDate(doc.createdAt)?.getTime() ?? 0 }))
    .sort((a, b) => a.createdAt - b.createdAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
    .map(({ doc, id }) => ({ _id: id, userId, category: doc.category, limit: doc.limit }));
}

/**
 * One budget per category (doc id is a hash of it). Updating keeps createdAt, so the order holds.
 * When this month has a plan, listBudgets shows the plan, so the category's share is set in it too (added if
 * new); refused with SplitOverTotalError when the split would go over the month's total. The old budget is
 * written either way, so it is still sensible in months without a plan.
 */
export async function setBudgetLimit(userId: string, category: string, limit: number): Promise<Budget> {
  const col = await budgetsCol();
  const months = await monthsCol();
  const id = sha256Hex(category);
  const key = scopedId(userId, id);
  const now = new Date();
  const planKey = scopedId(userId, yangonMonth(now));
  await withTransaction(async (session) => {
    await requireActiveAccount(userId, session);
    const snap = await col.findOne({ _id: key, userId }, { session });
    const planSnap = await months.findOne({ _id: planKey, userId }, { session });
    if (planSnap) {
      const plan = readMonthPlan(planSnap);
      const shares = plan.shares.some((s) => s.category === category)
        ? plan.shares.map((s) => (s.category === category ? { category, limit } : s))
        : [...plan.shares, { category, limit }];
      if (splitOf(shares) > totalOf(plan)) throw new SplitOverTotalError(totalOf(plan));
      await months.updateOne({ _id: planKey, userId }, { $set: { shares, updatedAt: now } }, { session });
    }
    if (snap) await col.updateOne({ _id: key, userId }, { $set: { limit, updatedAt: now } }, { session });
    else await col.insertOne({ _id: key, userId, category, limit, createdAt: now, updatedAt: now }, { session });
  });
  return { _id: id, userId, category, limit };
}

function readMonthPlan(doc: MongoRecord): MonthPlan {
  const shares: unknown = doc.shares;
  const endCheck = doc.endCheck;
  return {
    month: leafId(doc._id),
    newMoney: doc.newMoney,
    carriedOver: doc.carriedOver,
    shares: Array.isArray(shares) ? shares.map((s) => ({ category: s.category, limit: s.limit })) : [],
    endCheck: endCheck
      ? { onHand: endCheck.onHand, checkedAt: toDate(endCheck.checkedAt) ?? new Date(0) }
      : null,
    createdAt: toDate(doc.createdAt) ?? new Date(0),
    updatedAt: toDate(doc.updatedAt) ?? new Date(0),
  };
}

export async function getMonthPlan(userId: string, month: string): Promise<MonthPlan | null> {
  const doc = await (await monthsCol()).findOne({ _id: scopedId(userId, month), userId });
  return doc ? readMonthPlan(doc) : null;
}

/** Plans from `from` to `to` ("YYYY-MM", both included), oldest first; months without one are left out. */
export async function listMonthPlans(userId: string, from: string, to: string): Promise<MonthPlan[]> {
  const docs = await (await monthsCol()).find({ userId, month: { $gte: from, $lte: to } }).sort({ month: 1 }).toArray();
  return docs.map(readMonthPlan);
}

/**
 * Creates or replaces a month's plan, keeping its end check and createdAt. The caller checks the split and the
 * categories. The old budgets are left alone: listBudgets reads this month's plan in their place.
 */
export async function saveMonthPlan(userId: string, month: string, input: MonthPlanInput): Promise<MonthPlan> {
  const col = await monthsCol();
  const key = scopedId(userId, month);
  const now = new Date();
  const { newMoney, carriedOver } = input;
  const shares = input.shares.map(({ category, limit }) => ({ category, limit }));
  return withTransaction(async (session) => {
    await requireActiveAccount(userId, session);
    const snap = await col.findOne({ _id: key, userId }, { session });
    const old = snap ? readMonthPlan(snap) : null;

    if (old) await col.updateOne({ _id: key, userId }, { $set: { newMoney, carriedOver, shares, updatedAt: now } }, { session });
    else await col.insertOne({ _id: key, userId, month, newMoney, carriedOver, shares, createdAt: now, updatedAt: now }, { session });

    return {
      month,
      newMoney,
      carriedOver,
      shares,
      endCheck: old?.endCheck ?? null,
      createdAt: old?.createdAt ?? now,
      updatedAt: now,
    };
  });
}

/** Saves (or replaces) the end check; null when the month has no plan. The caller checks the month is over. */
export async function saveEndCheck(userId: string, month: string, onHand: number): Promise<MonthPlan | null> {
  const col = await monthsCol();
  const key = scopedId(userId, month);
  const now = new Date();
  return withTransaction(async (session) => {
    await requireActiveAccount(userId, session);
    const snap = await col.findOne({ _id: key, userId }, { session });
    if (!snap) return null;
    const endCheck = { onHand, checkedAt: now };
    await col.updateOne({ _id: key, userId }, { $set: { endCheck, updatedAt: now } }, { session });
    return { ...readMonthPlan(snap), endCheck, updatedAt: now };
  });
}

/** "November budget: 2,280,000 Ks (22.8 lakh). 680,000 Ks not split." */
export function monthPlanSummary(plan: MonthPlan): string {
  const [year, month] = plan.month.split("-").map(Number);
  const name = new Date(Date.UTC(year, month - 1, 1)).toLocaleString("en-US", { month: "long", timeZone: "UTC" });
  const total = totalOf(plan);
  return `${name} budget: ${formatKyat(total)} (${formatLakh(total)}). ${formatKyat(total - splitOf(plan.shares))} not split.`;
}
