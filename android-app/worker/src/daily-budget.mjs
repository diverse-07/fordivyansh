// One SQLite-backed Durable Object serializes reservations across all edge locations.
export class DailyRequestBudget {
  constructor(ctx, env) {
    this.ctx = ctx;
    const limit = Number(env.DAILY_AI_LIMIT ?? 100);
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 1000) throw Error("INVALID_DAILY_LIMIT");
    this.limit = limit;
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS daily_budget(day TEXT PRIMARY KEY, requests INTEGER NOT NULL)");
  }
  async fetch(request) {
    if (request.method !== "POST" || new URL(request.url).pathname !== "/reserve")
      return new Response("Not found", {status:404});
    const day = new Date().toISOString().slice(0,10);
    const success = this.ctx.storage.transactionSync(() => {
      const rows = this.ctx.storage.sql.exec("SELECT requests FROM daily_budget WHERE day = ?",day).toArray();
      if ((rows[0]?.requests ?? 0) >= this.limit) return false;
      this.ctx.storage.sql.exec("INSERT INTO daily_budget VALUES(?, 1) ON CONFLICT(day) DO UPDATE SET requests = requests + 1",day);
      this.ctx.storage.sql.exec("DELETE FROM daily_budget WHERE day < ?",day);
      return true;
    });
    return Response.json({success},{headers:{"Cache-Control":"no-store"}});
  }
}
