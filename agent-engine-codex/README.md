# PaperAgent Codex Engine

Independent, lightweight TypeScript ReAct implementation of the frozen PaperAgent Agent Engine contract. It intentionally has no DeepSeek Harness dependency so that it can be compared with `agent-engine-dsh` behind the same control-plane and product-tool gateway.

## P1 behavior

- Implements all five Engine HTTP/SSE operations with a deployment-scoped bearer credential.
- Validates frozen JSON schemas and verifies the canonical authority digest.
- Persists task projections, model budget, pending native tool calls, append-only JSONL events, and an fsync-backed accepted-answer journal.
- Keeps task grants only in memory. After restart, an exact task replay refreshes the grant and resumes unfinished work without reallocating model budget or sandbox call IDs.
- Recovers journal-only answers, durable questions, and durable deliveries across projection-write crash windows without consuming an answer, asking a question, or invoking the model twice.
- Maps model-native functions to `project.list`, `project.read`, and `sandbox.execute`; tools execute serially.
- Uses the fixed 4096 output-token / 20 model-call budget and 1, 2, 4, 5, 5… sandbox polling schedule.
- Emits bounded summaries only; project file bodies and credentials never enter the event stream.
- Treats sandbox `FAILED` receipts as code-validation evidence that the model may repair or accurately deliver to the user; transport, deadline, timeout, and system failures retain separate failure categories and do not produce a false delivery.
- Accepts only schema-shaped gateway Problems and fails closed on malformed gateway JSON without reflecting untrusted error content.

P1 is read/execute/deliver only. This Engine cannot write a Workspace, publish a ProjectVersion, access broker credentials, or select a gateway origin from task input.

## Configuration

```powershell
$env:ENGINE_SERVICE_TOKEN = '<at-least-32-character-deployment-token>'
$env:PRODUCT_GATEWAY_ORIGIN = 'http://127.0.0.1:8080'
$env:AGENT_ENGINE_DATA_DIR = '.data'
$env:HOST = '127.0.0.1'
$env:AGENT_ENGINE_PROVIDERS_JSON = '{"deepseek":{"baseUrl":"https://api.deepseek.com","apiKeyEnv":"DEEPSEEK_API_KEY"}}'
npm start
```

`AGENT_ENGINE_CONTRACT_DIR` may override the default sibling `../agent-engine-contract` location. Provider API keys are read only from the named process environment variable and never from task JSON.

## Verification

```powershell
npm ci
npm run typecheck
npm test
npm run build
python ../agent-engine-contract/conformance/validate_contract.py
```
