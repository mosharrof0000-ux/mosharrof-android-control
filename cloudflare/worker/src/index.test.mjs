import test from "node:test";
import assert from "node:assert/strict";
import { RelayDO } from "./index.ts";

class FakeStorage {
  values = new Map();
  async get(key) {
    const value = this.values.get(key);
    return value === undefined ? undefined : structuredClone(value);
  }
  async put(key, value) {
    this.values.set(key, structuredClone(value));
  }
  async delete(key) {
    this.values.delete(key);
  }
  async deleteAll() {
    this.values.clear();
  }
  async transaction(callback) {
    return callback(this);
  }
}

function setup() {
  const storage = new FakeStorage();
  storage.values.set("deviceToken", "device-secret");
  storage.values.set("paired", true);
  const env = {
    CHATGPT_CONNECTOR_TOKEN: "connector-secret",
    DEVICE_SETUP_TOKEN: "setup-secret",
  };
  return { storage, worker: new RelayDO({ storage }, env) };
}

function request(path, method, token, body) {
  return new Request("https://relay.test" + path, {
    method,
    headers: {
      authorization: "Bearer " + token,
      ...(body === undefined ? {} : { "content-type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

async function createJob(worker, idempotencyKey = "stable-key-0001") {
  const response = await worker.fetch(request(
    "/v1/chat/commands",
    "POST",
    "connector-secret",
    { operation_id: "termux.python_version", idempotency_key: idempotencyKey },
  ));
  return { response, body: await response.json() };
}

test("command creation requires idempotency and reuses the same command", async () => {
  const { storage, worker } = setup();
  const missing = await worker.fetch(request(
    "/v1/chat/commands", "POST", "connector-secret",
    { operation_id: "termux.python_version" },
  ));
  assert.equal(missing.status, 400);

  const first = await createJob(worker);
  assert.equal(first.response.status, 202);
  const second = await createJob(worker);
  assert.equal(second.response.status, 200);
  assert.equal(second.body.reused, true);
  assert.equal(second.body.command_id, first.body.command_id);
  assert.equal((await storage.get("queue")).length, 1);
});

test("poll leases one job, returns the same live lease, then retries after expiry", async () => {
  const { storage, worker } = setup();
  const created = await createJob(worker);
  const first = await worker.fetch(request("/v1/device/jobs/next", "GET", "device-secret"));
  const firstBody = await first.json();
  assert.equal(first.status, 200);
  assert.equal(firstBody.job.command_id, created.body.command_id);
  assert.equal(firstBody.job.attempts, 1);

  const repeated = await worker.fetch(request("/v1/device/jobs/next", "GET", "device-secret"));
  const repeatedBody = await repeated.json();
  assert.equal(repeatedBody.job.command_id, created.body.command_id);
  assert.equal(repeatedBody.job.attempts, 1);

  const queue = await storage.get("queue");
  queue[0].lease_until = Date.now() - 1;
  await storage.put("queue", queue);
  const retried = await worker.fetch(request("/v1/device/jobs/next", "GET", "device-secret"));
  const retriedBody = await retried.json();
  assert.equal(retriedBody.job.command_id, created.body.command_id);
  assert.equal(retriedBody.job.attempts, 2);
  assert.equal((await storage.get("audit:" + created.body.command_id)).status, "leased");
});

test("accepted result removes queue item and identical retries are idempotent", async () => {
  const { storage, worker } = setup();
  const created = await createJob(worker);
  const resultPath = "/v1/device/jobs/" + created.body.command_id + "/result";
  const payload = { output: "Python 3.13", exit_code: 0 };

  const accepted = await worker.fetch(request(resultPath, "POST", "device-secret", payload));
  assert.equal(accepted.status, 200);
  assert.equal((await accepted.json()).accepted, true);
  assert.equal((await storage.get("queue")).length, 0);
  assert.equal((await storage.get("audit:" + created.body.command_id)).status, "completed");

  const retry = await worker.fetch(request(resultPath, "POST", "device-secret", payload));
  assert.equal(retry.status, 200);
  assert.equal((await retry.json()).reused, true);

  const conflict = await worker.fetch(request(resultPath, "POST", "device-secret", {
    output: "different output", exit_code: 0,
  }));
  assert.equal(conflict.status, 409);
});

test("unknown command result is rejected and expired jobs are audited", async () => {
  const { storage, worker } = setup();
  const unknown = await worker.fetch(request(
    "/v1/device/jobs/00000000-0000-4000-8000-000000000000/result",
    "POST", "device-secret", { output: "x", exit_code: 0 },
  ));
  assert.equal(unknown.status, 404);

  const commandId = "00000000-0000-4000-8000-000000000001";
  await storage.put("queue", [{
    command_id: commandId,
    operation_id: "termux.python_version",
    expires_at: Date.now() - 1,
    lease_until: 0,
    attempts: 0,
  }]);
  await storage.put("audit:" + commandId, {
    command_id: commandId,
    status: "queued",
    expires_at: Date.now() + 600_000,
  });
  const poll = await worker.fetch(request("/v1/device/jobs/next", "GET", "device-secret"));
  assert.equal((await poll.json()).job, null);
  assert.equal((await storage.get("audit:" + commandId)).status, "expired");
});

test("device polling is rate limited", async () => {
  const { worker } = setup();
  for (let i = 0; i < 30; i++) {
    const response = await worker.fetch(request("/v1/device/jobs/next", "GET", "device-secret"));
    assert.equal(response.status, 200);
  }
  const limited = await worker.fetch(request("/v1/device/jobs/next", "GET", "device-secret"));
  assert.equal(limited.status, 429);
});


test("health response exposes configuration state without secret values", async () => {
  const { worker } = setup();
  const response = await worker.fetch(new Request("https://relay.test/healthz"));
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.status, "ok");
  assert.equal(body.secrets_configured, true);
  assert.equal(JSON.stringify(body).includes("connector-secret"), false);
  assert.equal(JSON.stringify(body).includes("device-secret"), false);
  assert.equal(JSON.stringify(body).includes("setup-secret"), false);
});

test("connector authentication and operation allowlist fail closed", async () => {
  const { worker } = setup();
  const unauthorized = await worker.fetch(request(
    "/v1/chat/commands", "POST", "wrong-token",
    { operation_id: "termux.python_version", idempotency_key: "stable-key-0002" },
  ));
  assert.equal(unauthorized.status, 401);

  const unsupported = await worker.fetch(request(
    "/v1/chat/commands", "POST", "connector-secret",
    { operation_id: "termux.execute_shell", idempotency_key: "stable-key-0003" },
  ));
  assert.equal(unsupported.status, 400);
  assert.equal((await unsupported.json()).error, "operation_not_allowed");
});

test("malformed result payload and malformed command identifiers are rejected", async () => {
  const { worker } = setup();
  const malformed = await worker.fetch(request(
    "/v1/device/jobs/not-a-uuid/result", "POST", "device-secret",
    { output: "ok", exit_code: 0 },
  ));
  assert.equal(malformed.status, 404);

  const created = await createJob(worker);
  const malformedResult = await worker.fetch(request(
    "/v1/device/jobs/" + created.body.command_id + "/result",
    "POST", "device-secret", { output: 7, exit_code: "0" },
  ));
  assert.equal(malformedResult.status, 400);
  assert.equal((await malformedResult.json()).error, "invalid_result");
});
