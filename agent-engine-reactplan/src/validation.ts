import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { createRequire } from "node:module";
import type { ErrorObject, ValidateFunction } from "ajv";
import { EngineProblem, problem } from "./util.js";

const require = createRequire(import.meta.url);
const Ajv2020: typeof import("ajv/dist/2020.js").default = require("ajv/dist/2020.js").default;
const addFormats: typeof import("ajv-formats").default = require("ajv-formats").default;

export class ContractValidator {
  private readonly validators = new Map<string, ValidateFunction>();

  constructor(contractDirectory = process.env.AGENT_ENGINE_CONTRACT_DIR ?? resolve(process.cwd(), "../agent-engine-contract")) {
    const ajv = new Ajv2020({ allErrors: true, strict: true });
    addFormats(ajv);
    const names = ["problem", "task-submission", "task-view", "task-event", "task-answer", "receipt", "gateway"];
    for (const name of names) {
      const schema = JSON.parse(readFileSync(resolve(contractDirectory, "schemas", `${name}.schema.json`), "utf8"));
      ajv.addSchema(schema);
    }
    for (const name of names) this.validators.set(name, ajv.getSchema(`https://paperagent.local/agent-engine/schemas/${name}.schema.json`)!);
    const gatewayId = "https://paperagent.local/agent-engine/schemas/gateway.schema.json";
    for (const definition of ["fileList", "fileReadRequest", "fileRead", "workspaceWriteRequest", "workspaceDocxCreateRequest", "workspaceDocxCreateResult", "workspaceWriteResult", "workspaceDiff", "workspacePublishRequest", "workspacePublishResult", "sandboxSubmit", "sandboxView"]) this.validators.set(`gateway-${definition}`, ajv.getSchema(`${gatewayId}#/$defs/${definition}`)!);
  }

  validate(name: string, value: unknown): void {
    const validator = this.validators.get(name);
    if (!validator) throw new Error(`Unknown contract schema: ${name}`);
    if (validator(value)) return;
    throw new EngineProblem(400, problem("CONTRACT_VALIDATION_FAILED", "request", summarize(validator.errors)));
  }
}

function summarize(errors: ErrorObject[] | null | undefined): string {
  return `Contract validation failed: ${(errors ?? []).slice(0, 4).map((item) => `${item.instancePath || "/"} ${item.message}`).join("; ")}`;
}
