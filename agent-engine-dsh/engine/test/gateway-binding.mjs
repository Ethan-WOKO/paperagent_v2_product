// Unit failure tests for the HttpGatewayClient binding checks: each gateway
// response field that can be tampered with (frozen projectVersion, file body
// hash/size, executionRef, receipt executionRef/terminal status/exact inputs,
// non-terminal view carrying a receiptRef, digest echo) must be rejected with
// its dedicated binding code, and unknown error codes must fail closed to
// GATEWAY_ERROR. One positive control proves the validators do not over-fire.
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';

const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');
const FIXTURE = 'public class Sort { public static void main(String[] args) {} }\n';
const FIXTURE_SHA = sha256(FIXTURE);
const FIXTURE_SIZE = Buffer.byteLength(FIXTURE);
const TASK_ID = 'task.' + 'e'.repeat(64);
const GRANT = 'grant.' + 'g'.repeat(48);
const IN_SHA = FIXTURE_SHA;

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!ok) failures++;
};

// one mutable corruption slot; each case sets it, calls the client, resets it
let corrupt = 'none';

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://gateway.local');
  const send = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  const readBody = () =>
    new Promise((resolve) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => resolve(JSON.parse(body)));
    });

  if (url.pathname.endsWith('/workspace/files')) {
    const errorCodes = {
      errorCode: 'MYSTERY_ERROR',
      errorTASK: 'TASK_GRANT_WRONG_TASK',
      errorWORKSPACE: 'WORKSPACE_FILE_NOT_FOUND',
      errorSANDBOX: 'SANDBOX_COMMAND_DENIED',
      errorLower: 'task_weird_code',
    };
    if (corrupt in errorCodes) {
      return send(500, { contractVersion: '1.0', code: errorCodes[corrupt], category: 'internal', message: 'boom', retryable: false });
    }
    return send(200, {
      contractVersion: '1.0',
      taskId: TASK_ID,
      projectVersion: corrupt === 'projectVersion' ? 'b'.repeat(64) : 'a'.repeat(64),
      files: [{ path: 'src/main/java/Sort.java', sizeBytes: FIXTURE_SIZE, sha256: FIXTURE_SHA, mediaType: 'text/x-java' }],
    });
  }
  if (url.pathname.endsWith('/workspace/read')) {
    void readBody().then((parsed) =>
      send(200, {
        contractVersion: '1.0',
        path: parsed.path,
        sizeBytes: corrupt === 'readSizeBytes' ? FIXTURE_SIZE + 1 : FIXTURE_SIZE,
        sha256: FIXTURE_SHA,
        mediaType: 'text/x-java',
        encoding: 'utf-8',
        // G3 tampers with the BODY only (same byte length, different hash):
        // declared hash/size still describe the fixture, so the body
        // re-attestation must catch the mismatch.
        content: corrupt === 'readContent' ? FIXTURE.replace('class', 'clazz') : FIXTURE,
        truncated: false,
      }),
    );
    return;
  }
  if (url.pathname.endsWith('/sandbox-executions') && req.method === 'POST') {
    void readBody().then((parsed) =>
      send(202, {
        contractVersion: '1.0',
        clientRequestId: parsed.clientRequestId,
        requestDigest: corrupt === 'viewDigest' ? 'd'.repeat(64) : parsed.requestDigest,
        executionRef: 'exec-1',
        state: corrupt === 'submitStateReceipt' ? 'RUNNING' : 'SUCCEEDED',
        receiptRef: corrupt === 'submitStateReceipt' ? 'receipt.1' : 'receipt.1',
      }),
    );
    return;
  }
  const execMatch = url.pathname.match(/\/sandbox-executions\/(call\.[A-Za-z0-9_-]{16,120})$/);
  if (execMatch) {
    return send(200, {
      contractVersion: '1.0',
      clientRequestId: execMatch[1],
      requestDigest: corrupt === 'execDigest' ? 'd'.repeat(64) : 'a'.repeat(64),
      executionRef: corrupt === 'executionRef' ? 'exec-other' : 'exec-1',
      state: corrupt === 'viewStateReceipt' ? 'RUNNING' : 'SUCCEEDED',
      receiptRef: corrupt === 'viewStateReceipt' ? 'receipt.1' : 'receipt.1',
    });
  }
  const receiptMatch = url.pathname.match(/\/receipts\/(.+)$/);
  if (receiptMatch) {
    const decodedRef = decodeURIComponent(receiptMatch[1]);
    const goodInputs = [{ path: 'src/main/java/Sort.java', sha256: IN_SHA, sizeBytes: FIXTURE_SIZE }];
    return send(200, {
      contractVersion: '1.0',
      receiptRef: decodedRef,
      executionRef: corrupt === 'receiptExecRef' ? 'exec-other' : 'exec-1',
      status: corrupt === 'receiptStatus' ? 'FAILED' : 'SUCCEEDED',
      exitCode: corrupt === 'receiptStatus' ? 1 : 0,
      stdout: { text: '[1, 2, 3]\n', truncated: false, originalBytes: 8 },
      stderr: { text: '', truncated: false, originalBytes: 0 },
      inputFingerprint: 'a'.repeat(64),
      inputs: corrupt === 'receiptInputs' ? [{ path: 'other.java', sha256: IN_SHA, sizeBytes: 1 }] : goodInputs,
      startedAt: '2026-08-16T00:00:00Z',
      finishedAt: '2026-08-16T00:00:01Z',
    });
  }
  send(500, { contractVersion: '1.0', code: 'MYSTERY_ERROR', category: 'internal', message: 'boom', retryable: false });
});

await new Promise((resolve) => server.listen(18299, resolve));
const { HttpGatewayClient } = await import('../src/gateway-http.ts');
const makeClient = () => new HttpGatewayClient('http://127.0.0.1:18299', TASK_ID, () => GRANT, () => 'a'.repeat(64));

const expectReject = async (name, code, fn) => {
  corrupt = 'none';
  let caught = null;
  try {
    await fn();
  } catch (e) {
    caught = e instanceof Error ? e.message : String(e);
  }
  check(name, caught === code, `caught=${caught}`);
};

await expectReject('G1 projectVersion mismatch rejected', 'FILE_LIST_PROJECT_VERSION_BINDING_MISMATCH', async () => {
  corrupt = 'projectVersion';
  await makeClient().listWorkspaceFiles();
});
await expectReject('G2 file body size mismatch rejected', 'FILE_READ_SIZE_BINDING_MISMATCH', async () => {
  corrupt = 'readSizeBytes';
  await makeClient().readWorkspaceFile('src/main/java/Sort.java', FIXTURE_SHA);
});
await expectReject('G3 file body hash mismatch rejected', 'FILE_READ_HASH_BINDING_MISMATCH', async () => {
  corrupt = 'readContent';
  await makeClient().readWorkspaceFile('src/main/java/Sort.java', FIXTURE_SHA);
});
await expectReject('G4 digest echo mismatch rejected', 'SANDBOX_VIEW_BINDING_MISMATCH', async () => {
  corrupt = 'viewDigest';
  await makeClient().submitSandbox({ clientRequestId: 'call.' + 'f'.repeat(20), requestDigest: 'a'.repeat(64), argv: ['javac', 'x.java'], inputs: [{ path: 'x.java', sha256: IN_SHA }], timeoutMillis: 120000 });
});
await expectReject('G5 executionRef drift rejected', 'SANDBOX_EXECUTION_REF_BINDING_MISMATCH', async () => {
  corrupt = 'executionRef';
  await makeClient().getSandboxExecution('call.' + 'f'.repeat(20), 'exec-1');
});
await expectReject('G6 non-terminal view carrying receiptRef rejected', 'SANDBOX_VIEW_STATE_BINDING_MISMATCH', async () => {
  corrupt = 'viewStateReceipt';
  await makeClient().getSandboxExecution('call.' + 'f'.repeat(20), 'exec-1');
});
await expectReject('G7 receipt executionRef mismatch rejected', 'RECEIPT_EXECUTION_REF_BINDING_MISMATCH', async () => {
  corrupt = 'receiptExecRef';
  await makeClient().getSandboxReceipt('receipt.1', { executionRef: 'exec-1', viewState: 'SUCCEEDED', inputs: [{ path: 'src/main/java/Sort.java', sha256: IN_SHA }] });
});
await expectReject('G8 receipt terminal status mismatch rejected', 'RECEIPT_STATUS_BINDING_MISMATCH', async () => {
  corrupt = 'receiptStatus';
  await makeClient().getSandboxReceipt('receipt.1', { executionRef: 'exec-1', viewState: 'SUCCEEDED', inputs: [{ path: 'src/main/java/Sort.java', sha256: IN_SHA }] });
});
await expectReject('G9 receipt exact inputs mismatch rejected', 'RECEIPT_INPUTS_BINDING_MISMATCH', async () => {
  corrupt = 'receiptInputs';
  await makeClient().getSandboxReceipt('receipt.1', { executionRef: 'exec-1', viewState: 'SUCCEEDED', inputs: [{ path: 'src/main/java/Sort.java', sha256: IN_SHA }] });
});
await expectReject('G10 unknown gateway error code fails closed', 'GATEWAY_ERROR', async () => {
  corrupt = 'errorCode';
  await makeClient().listWorkspaceFiles();
});
await expectReject('G12 poll requestDigest replaced rejected', 'SANDBOX_VIEW_BINDING_MISMATCH', async () => {
  corrupt = 'execDigest';
  await makeClient().getSandboxExecution('call.' + 'f'.repeat(20), 'exec-1', 'a'.repeat(64));
});
await expectReject('G13 submit non-terminal view carrying receiptRef rejected', 'SANDBOX_VIEW_STATE_BINDING_MISMATCH', async () => {
  corrupt = 'submitStateReceipt';
  await makeClient().submitSandbox({ clientRequestId: 'call.' + 'f'.repeat(20), requestDigest: 'a'.repeat(64), argv: ['javac', 'x.java'], inputs: [{ path: 'x.java', sha256: IN_SHA }], timeoutMillis: 120000 });
});
{
  corrupt = 'none';
  let roundtrip = null;
  try {
    const receipt = await makeClient().getSandboxReceipt('receipt/a b+c', { executionRef: 'exec-1', viewState: 'SUCCEEDED', inputs: [{ path: 'src/main/java/Sort.java', sha256: IN_SHA }] });
    roundtrip = receipt.receiptRef;
  } catch {
    roundtrip = 'rejected';
  }
  check('G14 receiptRef path uses encodeURIComponent (roundtrip)', roundtrip === 'receipt/a b+c', `ref=${roundtrip}`);
}
await expectReject('G15 TASK_GRANT_WRONG_TASK passes the #151 allowlist', 'TASK_GRANT_WRONG_TASK', async () => {
  corrupt = 'errorTASK';
  await makeClient().listWorkspaceFiles();
});
await expectReject('G16 WORKSPACE_FILE_NOT_FOUND passes the #151 allowlist', 'WORKSPACE_FILE_NOT_FOUND', async () => {
  corrupt = 'errorWORKSPACE';
  await makeClient().listWorkspaceFiles();
});
await expectReject('G17 SANDBOX_COMMAND_DENIED passes the #151 allowlist', 'SANDBOX_COMMAND_DENIED', async () => {
  corrupt = 'errorSANDBOX';
  await makeClient().listWorkspaceFiles();
});
await expectReject('G18 lowercase/odd code still fails closed', 'GATEWAY_ERROR', async () => {
  corrupt = 'errorLower';
  await makeClient().listWorkspaceFiles();
});

// Positive control: a fully consistent gateway passes every binding check.
{
  corrupt = 'none';
  let ok = true;
  try {
    const client = makeClient();
    const files = await client.listWorkspaceFiles();
    const read = await client.readWorkspaceFile(files[0].path, files[0].sha256);
    const view = await client.submitSandbox({ clientRequestId: 'call.' + 'f'.repeat(20), requestDigest: 'a'.repeat(64), argv: ['javac', 'x.java'], inputs: [{ path: 'src/main/java/Sort.java', sha256: IN_SHA }], timeoutMillis: 120000 });
    const polled = await client.getSandboxExecution('call.' + 'f'.repeat(20), 'exec-1');
    const receipt = await client.getSandboxReceipt('receipt.1', { executionRef: 'exec-1', viewState: 'SUCCEEDED', inputs: [{ path: 'src/main/java/Sort.java', sha256: IN_SHA }] });
    ok = files.length === 1 && read.sizeBytes === FIXTURE_SIZE && view.state === 'SUCCEEDED' && polled.executionRef === 'exec-1' && receipt.status === 'SUCCEEDED';
  } catch (e) {
    ok = false;
  }
  check('G11 positive control passes all bindings', ok, '');
}

// Drain the in-process server and its keep-alive connections BEFORE exiting:
// calling process.exit while undici/server sockets are mid-close trips a libuv
// teardown assertion on Windows.
server.closeAllConnections();
await new Promise((resolve) => server.close(resolve));
await new Promise((resolve) => setTimeout(resolve, 100));
console.log(failures === 0 ? '\nALL GATEWAY-BINDING CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
if (failures > 0) process.exit(1);
