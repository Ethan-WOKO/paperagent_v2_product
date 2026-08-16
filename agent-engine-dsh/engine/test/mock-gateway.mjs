// Controlled HTTP gateway test double implementing the contract gateway
// endpoints over one fixture file. Counts sandbox submissions so recovery
// tests can assert no duplicate dispatch.
import { createServer } from 'node:http';
import { readFileSync, appendFileSync, existsSync, readdirSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');
const FIXTURE = 'public class Sort { public static void main(String[] args) {} }\n';
const FIXTURE_SHA = sha256(FIXTURE);

export function startMockGateway({ port, submissionLog }) {
  const executions = new Map();
  const receipts = new Map();
  let receiptSeq = 0;
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://gateway.local');
    const send = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    const grant = (req.headers.authorization ?? '').replace(/^Bearer /, '');
    if (!grant || grant.length < 32) return send(401, { contractVersion: '1.0', code: 'UNAUTHORIZED', category: 'authorization', message: 'bad grant', retryable: false });

    if (url.pathname.endsWith('/workspace/files') && req.method === 'GET') {
      const taskId = url.pathname.split('/tasks/')[1].split('/workspace')[0];
      return send(200, {
        contractVersion: '1.0',
        taskId,
        projectVersion: 'a'.repeat(64),
        files: [{ path: 'src/main/java/Sort.java', sizeBytes: Buffer.byteLength(FIXTURE), sha256: FIXTURE_SHA, mediaType: 'text/x-java' }],
      });
    }
    if (url.pathname.endsWith('/workspace/read') && req.method === 'POST') {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        const parsed = JSON.parse(body);
        if (parsed.path !== 'src/main/java/Sort.java' || parsed.expectedSha256 !== FIXTURE_SHA) {
          return send(400, { contractVersion: '1.0', code: 'HASH_MISMATCH', category: 'request', message: 'mismatch', retryable: false });
        }
        send(200, {
          contractVersion: '1.0', path: parsed.path, sizeBytes: Buffer.byteLength(FIXTURE), sha256: FIXTURE_SHA,
          mediaType: 'text/x-java', encoding: 'utf-8', content: FIXTURE, truncated: false,
        });
      });
      return;
    }
    if (url.pathname.endsWith('/sandbox-executions') && req.method === 'POST') {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        const parsed = JSON.parse(body);
        const existing = executions.get(parsed.clientRequestId);
        if (existing) {
          if (existing.requestDigest !== parsed.requestDigest) return send(409, { contractVersion: '1.0', code: 'SUBMIT_DIGEST_CONFLICT', category: 'request', message: 'conflict', retryable: false });
          return send(202, existing.view);
        }
        const executionRef = 'mock-exec.' + sha256(parsed.clientRequestId).slice(0, 12);
        const receiptRef = 'receipt.mock.' + ++receiptSeq;
        const view = { contractVersion: '1.0', clientRequestId: parsed.clientRequestId, requestDigest: parsed.requestDigest, executionRef, state: 'SUCCEEDED', receiptRef };
        executions.set(parsed.clientRequestId, { requestDigest: parsed.requestDigest, view });
        receipts.set(receiptRef, {
          contractVersion: '1.0', receiptRef, executionRef, status: 'SUCCEEDED', exitCode: 0,
          stdout: { text: '[1, 2, 3]\n', truncated: false, originalBytes: 8 },
          stderr: { text: '', truncated: false, originalBytes: 0 },
          inputFingerprint: sha256(JSON.stringify([parsed.argv, parsed.inputs])),
          inputs: parsed.inputs.map((i) => ({ path: i.path, sha256: i.sha256, sizeBytes: i.path.endsWith('Sort.java') ? Buffer.byteLength(FIXTURE) : 0 })),
          startedAt: new Date().toISOString(), finishedAt: new Date().toISOString(),
        });
        appendFileSync(submissionLog, JSON.stringify({ clientRequestId: parsed.clientRequestId, argv: parsed.argv }) + '\n');
        send(202, view);
      });
      return;
    }
    const execMatch = url.pathname.match(/\/sandbox-executions\/(call\.[A-Za-z0-9_-]{16,120})$/);
    if (execMatch && req.method === 'GET') {
      const existing = executions.get(execMatch[1]);
      if (!existing) return send(404, { contractVersion: '1.0', code: 'EXECUTION_NOT_FOUND', category: 'request', message: 'missing', retryable: false });
      return send(200, existing.view);
    }
    const receiptMatch = url.pathname.match(/\/receipts\/(.+)$/);
    if (receiptMatch && req.method === 'GET') {
      const receipt = receipts.get(decodeURIComponent(receiptMatch[1]));
      if (!receipt) return send(404, { contractVersion: '1.0', code: 'RECEIPT_NOT_FOUND', category: 'request', message: 'missing', retryable: false });
      return send(200, receipt);
    }
    send(404, { contractVersion: '1.0', code: 'NOT_FOUND', category: 'request', message: 'unknown', retryable: false });
  });
  return new Promise((resolve) => {
    server.listen(port, () => resolve({ server, submissionLog }));
  });
}
