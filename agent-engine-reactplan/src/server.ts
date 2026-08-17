import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AgentEngine } from "./engine.js";
import type { TaskEvent, TaskSubmission } from "./types.js";
import { EngineProblem, problem, safeEqual } from "./util.js";

const TASK_ID = "task\\.[a-f0-9]{64}";
const TASK_PATH = new RegExp(`^/v1/tasks/(${TASK_ID})$`);
const EVENTS_PATH = new RegExp(`^/v1/tasks/(${TASK_ID})/events$`);
const CANCEL_PATH = new RegExp(`^/v1/tasks/(${TASK_ID})/cancel$`);
const ANSWER_PATH = new RegExp(`^/v1/tasks/(${TASK_ID})/answer$`);

export function createEngineServer(engine: AgentEngine, serviceToken: string): Server {
  if (serviceToken.length < 32) throw new Error("ENGINE_SERVICE_TOKEN must contain at least 32 characters");
  return createServer(async (request, response) => {
    try {
      authenticate(request, serviceToken);
      const url = new URL(request.url ?? "/", "http://engine.local");
      if (request.method === "GET" && url.pathname === "/health") {
        return json(response, 200, { status: "UP" });
      }
      if (request.method === "POST" && url.pathname === "/v1/tasks") {
        const accepted = await engine.submit(await jsonBody(request) as TaskSubmission);
        return json(response, 202, accepted);
      }
      let match = TASK_PATH.exec(url.pathname);
      if (request.method === "GET" && match) return json(response, 200, engine.get(match[1]!));
      match = EVENTS_PATH.exec(url.pathname);
      if (request.method === "GET" && match) return await streamEvents(engine, match[1]!, request, response);
      match = CANCEL_PATH.exec(url.pathname);
      if (request.method === "POST" && match) {
        const body = await jsonBody(request) as Record<string, unknown>;
        if (Object.keys(body).sort().join(",") !== "clientRequestId,contractVersion" || body.contractVersion !== "1.0" || typeof body.clientRequestId !== "string" || !/^cancel\.[A-Za-z0-9_-]{16,120}$/.test(body.clientRequestId)) throw new EngineProblem(400, problem("CONTRACT_VALIDATION_FAILED", "request", "Cancellation request is invalid"));
        return json(response, 202, await engine.cancel(match[1]!));
      }
      match = ANSWER_PATH.exec(url.pathname);
      if (request.method === "POST" && match) return json(response, 202, await engine.answer(match[1]!, await jsonBody(request) as never));
      throw new EngineProblem(404, problem("ROUTE_NOT_FOUND", "request", "Route was not found"));
    } catch (error) {
      if (response.headersSent) { response.end(); return; }
      const failure = error instanceof EngineProblem ? error : new EngineProblem(500, problem("ENGINE_INTERNAL_FAILURE", "internal", "The agent engine encountered an internal failure", true));
      json(response, failure.status, failure.problem);
    }
  });
}

function authenticate(request: IncomingMessage, serviceToken: string): void {
  const header = request.headers.authorization;
  if (!header?.startsWith("Bearer ") || !safeEqual(header.slice(7), serviceToken)) throw new EngineProblem(401, problem("ENGINE_SERVICE_UNAUTHORIZED", "authorization", "Engine service credential is invalid"));
}

async function jsonBody(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = []; let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.from(chunk); size += buffer.length;
    if (size > 1_100_000) throw new EngineProblem(413, problem("REQUEST_BODY_TOO_LARGE", "request", "Request body exceeds the engine limit"));
    chunks.push(buffer);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw new EngineProblem(400, problem("REQUEST_JSON_INVALID", "request", "Request body is not valid JSON")); }
}

async function streamEvents(engine: AgentEngine, taskId: string, request: IncomingMessage, response: ServerResponse): Promise<void> {
  const header = request.headers["last-event-id"];
  const after = header === undefined ? 0 : Number(Array.isArray(header) ? header[0] : header);
  if (!Number.isSafeInteger(after) || after < 0) throw new EngineProblem(400, problem("LAST_EVENT_ID_INVALID", "request", "Last-Event-ID must be a non-negative integer"));
  engine.get(taskId);
  response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache, no-transform", connection: "keep-alive", "x-accel-buffering": "no" });
  let last = after; const queued: TaskEvent[] = []; let replaying = true;
  const write = (event: TaskEvent): void => {
    if (event.sequence <= last) return;
    response.write(`id: ${event.sequence}\ndata: ${JSON.stringify(event)}\n\n`); last = event.sequence;
  };
  const unsubscribe = engine.subscribe(taskId, (event) => { if (replaying) queued.push(event); else write(event); });
  for (const event of await engine.events(taskId, after)) write(event);
  replaying = false; for (const event of queued) write(event);
  const heartbeat = setInterval(() => response.write(": heartbeat\n\n"), 15_000);
  const close = (): void => { clearInterval(heartbeat); unsubscribe(); };
  request.once("close", close); response.once("close", close);
}

function json(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  response.end(JSON.stringify(body));
}
