import {test} from "node:test";
import assert from "node:assert/strict";
import {geminiTransport,GEMINI_MODEL} from "../src/gemini.mjs";
import {createHandler} from "../src/index.mjs";

const env={GEMINI_API_KEY:"test-only-not-a-key"};
const chat=(content="Say hello",tools)=>({
  messages:[{role:"developer",content:"Do not invent observations."},
    {role:"user",content:"Earlier question"},{role:"assistant",content:"Earlier answer"},
    {role:"user",content}],tools,
});
const reply=(steps,status="completed")=>Response.json({
  status,model:GEMINI_MODEL,steps,
  usage:{total_input_tokens:12,total_output_tokens:8},
});
test("Gemini uses fixed endpoint, stateless input and server-side header credential",async()=>{
  let captured;
  const transport=geminiTransport(async(url,init)=>{
    captured={url,...init,body:JSON.parse(init.body)};
    return reply([{type:"model_output",content:[{type:"text",text:"Hello."}]}]);
  },env,true);
  const response=await transport("https://api.meta.ai/v1/chat/completions",{body:JSON.stringify(chat())});
  assert.equal(captured.url,"https://generativelanguage.googleapis.com/v1beta/interactions");
  assert.equal(captured.headers["x-goog-api-key"],env.GEMINI_API_KEY);
  assert.equal(captured.body.model,GEMINI_MODEL);
  assert.equal(captured.body.store,false);
  assert.equal(captured.body.generation_config.thinking_level,"low");
  assert.match(captured.body.input[0].content[0].text,/Earlier answer/);
  const result=await response.json();
  assert.equal(result.choices[0].message.content,"Hello.");
  assert.equal(result.usage.prompt_tokens,12);
});
test("all selected images reach Gemini as inline image content",async()=>{
  let body;
  const transport=geminiTransport(async(_,init)=>{
    body=JSON.parse(init.body);
    return reply([{type:"model_output",content:[{type:"text",text:"Saved views."}]}]);
  },env,true);
  const images=Array.from({length:12},()=>({type:"image_url",image_url:{url:"data:image/jpeg;base64,/9j/2Q=="}}));
  await transport("https://api.meta.ai/v1/chat/completions",{body:JSON.stringify(chat([{type:"text",text:"Describe"},...images]))});
  assert.equal(body.input[0].content.filter(x=>x.type==="image").length,12);
  assert.equal(body.input[0].content.filter(x=>x.type==="image")[0].data,"/9j/2Q==");
});
test("Gemini function calls are adapted for the existing closed app-action validator",async()=>{
  const action={action:"BACK",query:null,category:null,value:null};
  const tools=[{function:{name:"app_action",description:"Existing control",parameters:{type:"object"}}}];
  const transport=geminiTransport(async()=>reply([{type:"function_call",name:"app_action",arguments:action}],"requires_action"),env,true);
  const result=await(await transport("https://api.meta.ai/v1/chat/completions",{body:JSON.stringify(chat("Back",tools))})).json();
  assert.equal(result.choices[0].finish_reason,"tool_calls");
  assert.deepEqual(JSON.parse(result.choices[0].message.tool_calls[0].function.arguments),action);
});
test("pending or failed Gemini replies cannot become completed app answers",async()=>{
  for (const status of ["requires_action","in_progress","failed"]) {
    const transport=geminiTransport(async()=>reply([{type:"model_output",content:[{type:"text",text:"Not complete"}]}],status),env,true);
    await assert.rejects(transport("https://api.meta.ai/v1/chat/completions",{body:JSON.stringify(chat())}),/INCOMPLETE_GEMINI_RESPONSE/);
  }
});
test("daily budget exhaustion blocks paid work",async()=>{
  let paid=0;
  const transport=geminiTransport(async()=>{paid++;throw Error("must not call");},
    {...env,DAILY_BUDGET:{idFromName:()=>1,get:()=>({fetch:async()=>Response.json({success:false})})}});
  const response=await transport("https://api.meta.ai/v1/chat/completions",{body:JSON.stringify(chat())});
  assert.equal(response.status,429);assert.equal(paid,0);
});
test("production Gemini refuses to run without the persistent budget binding",async()=>{
  const handler=createHandler(()=>{throw Error("must not call");});
  const request=new Request("https://gateway.example/assistant",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({question:"hello",context:"{}",history:[]})});
  const response=await handler(request,{...env,AI_PROVIDER:"gemini",APP_ACCESS:"public",CLIENT_LIMITER:{limit:async()=>({success:true})},GLOBAL_LIMITER:{limit:async()=>({success:true})}});
  assert.equal(response.status,503);
  assert.deepEqual(await response.json(),{error:"BUDGET_NOT_CONFIGURED"});
});
