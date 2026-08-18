import { AgentEngine } from "./engine.js";
import { HttpGatewayClient } from "./gateway.js";
import { OpenAiCompatibleProvider } from "./provider.js";
import { createEngineServer } from "./server.js";
import { HttpTaskStore } from "./store.js";
import { ContractValidator } from "./validation.js";

const token = process.env.ENGINE_SERVICE_TOKEN ?? "";
const gatewayOrigin = process.env.PRODUCT_GATEWAY_ORIGIN;
if (!gatewayOrigin) throw new Error("PRODUCT_GATEWAY_ORIGIN is required");
const engine = new AgentEngine({
  store: new HttpTaskStore(gatewayOrigin, token),
  provider: OpenAiCompatibleProvider.fromEnvironment(),
  gateway: new HttpGatewayClient(gatewayOrigin),
  validator: new ContractValidator()
});
await engine.initialize();
const port = Number(process.env.PORT ?? "8092");
const host = process.env.HOST ?? "127.0.0.1";
createEngineServer(engine, token).listen(port, host, () => process.stdout.write(`agent-engine-reactplan listening on ${host}:${port}\n`));
