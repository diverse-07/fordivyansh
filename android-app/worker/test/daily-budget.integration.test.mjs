import {test} from "node:test";
import assert from "node:assert/strict";
import {Miniflare,convertV4MiniflareOptions} from "miniflare";
import {readFileSync} from "node:fs";

test("SQLite Durable Object enforces one shared atomic quota across concurrent callers",async()=>{
  const mf=new Miniflare(convertV4MiniflareOptions({
    modules:true,script:readFileSync(new URL("../src/daily-budget.mjs",import.meta.url),"utf8")+"\nexport default {fetch(){return new Response('test');}};",
    compatibilityDate:"2026-10-30",
    bindings:{DAILY_AI_LIMIT:"3"},
    durableObjects:{DAILY_BUDGET:{className:"DailyRequestBudget",useSQLite:true}},
  }));
  try {
    const ns=await mf.getDurableObjectNamespace("DAILY_BUDGET");
    const id=ns.idFromName("shared");
    const responses=await Promise.all(Array.from({length:10},()=>ns.get(id).fetch("https://budget.internal/reserve",{method:"POST"})));
    const values=await Promise.all(responses.map(x=>x.json()));
    assert.equal(values.filter(x=>x.success).length,3);
    assert.equal((await(await ns.get(id).fetch("https://budget.internal/reserve",{method:"POST"})).json()).success,false);
  } finally {await mf.dispose();}
});
