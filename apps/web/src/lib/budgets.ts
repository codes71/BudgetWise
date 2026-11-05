import type { DocumentSnapshot } from "firebase-admin/firestore";
import { db, sha256Hex, toDate, userDoc } from "@/lib/firestore";
import { formatKyat, formatLakh } from "@/lib/kyat";
import { yangonDate } from "@/lib/mobile/scan";
import type { Budget } from "@/lib/types";
import { requireActiveAccount } from "@/lib/account-access";

const budgetsCol = (userId: string) => userDoc(userId).collection("budgets");
const monthsCol = (userId: string) => userDoc(userId).collection("budgetMonths");

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

/** users/{uid}/budgetMonths/{YYYY-MM} as read. */
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
  const snap = await budgetsCol(userId).get();
  return snap.docs
    .map((doc) => ({ doc, createdAt: toDate(doc.get("createdAt"))?.getTime() ?? 0 }))
    .sort((a, b) => a.createdAt - b.createdAt || (a.doc.id < b.doc.id ? -1 : a.doc.id > b.doc.id ? 1 : 0))
    .map(({ doc }) => ({ _id: doc.id, userId, category: doc.get("category"), limit: doc.get("limit") }));
}

/**
 * One budget per category (doc id is a hash of it). Updating keeps createdAt, so the order holds.
 * When this month has a plan, listBudgets shows the plan, so the category's share is set in it too (added if
 * new); refused with SplitOverTotalError when the split would go over the month's total. The old budget is
 * written either way, so it is still sensible in months without a plan.
 */
export async function setBudgetLimit(userId: string, category: string, limit: number): Promise<Budget> {
  const ref = budgetsCol(userId).doc(sha256Hex(category));
  const now = new Date();
  const planRef = monthsCol(userId).doc(yangonMonth(now));
  await db().runTransaction(async (tx) => {
    await requireActiveAccount(userId, tx);
    const [snap, planSnap] = await tx.getAll(ref, planRef);
    if (planSnap.exists) {
      const plan = readMonthPlan(planSnap);
      const shares = plan.shares.some((s) => s.category === category)
        ? plan.shares.map((s) => (s.category === category ? { category, limit } : s))
        : [...plan.shares, { category, limit }];
      if (splitOf(shares) > totalOf(plan)) throw new SplitOverTotalError(totalOf(plan));
      tx.update(planRef, { shares, updatedAt: now });
    }
    if (snap.exists) tx.update(ref, { limit, updatedAt: now });
    else tx.create(ref, { userId, category, limit, createdAt: now, updatedAt: now });
  });
  return { _id: ref.id, userId, category, limit };
}

function readMonthPlan(doc: DocumentSnapshot): MonthPlan {
  const shares: unknown = doc.get("shares");
  const endCheck = doc.get("endCheck");
  return {
    month: doc.id,
    newMoney: doc.get("newMoney"),
    carriedOver: doc.get("carriedOver"),
    shares: Array.isArray(shares) ? shares.map((s) => ({ category: s.category, limit: s.limit })) : [],
    endCheck: endCheck
      ? { onHand: endCheck.onHand, checkedAt: toDate(endCheck.checkedAt) ?? new Date(0) }
      : null,
    createdAt: toDate(doc.get("createdAt")) ?? new Date(0),
    updatedAt: toDate(doc.get("updatedAt")) ?? new Date(0),
  };
}

export async function getMonthPlan(userId: string, month: string): Promise<MonthPlan | null> {
  const snap = await monthsCol(userId).doc(month).get();
  return snap.exists ? readMonthPlan(snap) : null;
}

/** Plans from `from` to `to` ("YYYY-MM", both included), oldest first; months without one are left out. */
export async function listMonthPlans(userId: string, from: string, to: string): Promise<MonthPlan[]> {
  const snap = await monthsCol(userId).where("month", ">=", from).where("month", "<=", to).orderBy("month").get();
  return snap.docs.map(readMonthPlan);
}

/**
 * Creates or replaces a month's plan, keeping its end check and createdAt. The caller checks the split and the
 * categories. The old budgets are left alone: listBudgets reads this month's plan in their place.
 */
export async function saveMonthPlan(userId: string, month: string, input: MonthPlanInput): Promise<MonthPlan> {
  const ref = monthsCol(userId).doc(month);
  const now = new Date();
  const { newMoney, carriedOver } = input;
  const shares = input.shares.map(({ category, limit }) => ({ category, limit }));
  return db().runTransaction(async (tx) => {
    await requireActiveAccount(userId, tx);
    const snap = await tx.get(ref);
    const old = snap.exists ? readMonthPlan(snap) : null;

    if (old) tx.update(ref, { newMoney, carriedOver, shares, updatedAt: now });
    else tx.create(ref, { month, newMoney, carriedOver, shares, createdAt: now, updatedAt: now });

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
  const ref = monthsCol(userId).doc(month);
  const now = new Date();
  return db().runTransaction(async (tx) => {
    await requireActiveAccount(userId, tx);
    const snap = await tx.get(ref);
    if (!snap.exists) return null;
    const endCheck = { onHand, checkedAt: now };
    tx.update(ref, { endCheck, updatedAt: now });
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
