# EBS Snapshot Cleanup — Interview Revision Notes
*AWS Lambda + Java (SDK v2) — cloud cost optimization project*

---

## 1. The 30-second pitch

> "I built a scheduled AWS Lambda function in Java that scans every EBS snapshot an account owns and deletes the ones that are no longer useful — orphaned, attached to a deleted volume, or attached to a volume nobody's using. It runs on an EventBridge schedule, uses AWS SDK v2, and is packaged as a Maven fat jar. The whole point is storage cost reduction: snapshots are billed forever unless someone explicitly deletes them, and nothing else does that automatically."

Keep that paragraph ready — it's the answer to "walk me through a project you built."

---

## 2. Architecture (say this out loud, draw it if there's a whiteboard)

```
EventBridge (schedule rule)
        │  invokes (async)
        ▼
AWS Lambda  ── Java 17 runtime ── fat jar (Maven Shade)
        │
        │  uses execution role with:
        │  ec2:DescribeSnapshots, ec2:DescribeVolumes,
        │  ec2:DeleteSnapshot, logs:CreateLogGroup/Stream, logs:PutLogEvents
        ▼
EC2 API (DescribeSnapshots → DescribeVolumes → DeleteSnapshot)
        │
        ▼
CloudWatch Logs  (audit trail of what was deleted and why)
```

No database, no queue, no other compute — it's a single stateless function that reads AWS's own metadata as its source of truth on every run.

---

## 3. The core logic — 3 conditions, each = immediate delete

| Snapshot's volume reference | Meaning | Action |
|---|---|---|
| `null` / empty | Never tied to a volume | Delete |
| Points to a volume → `DescribeVolumes` throws `InvalidVolume.NotFound` | Source volume was deleted | Delete |
| Volume exists, `attachments` list is empty | Nothing is using it | Delete |
| Volume exists, has an attachment | Still in use | Keep |

One rule, applied independently per snapshot, no batching, no confirmation step. That simplicity is a feature — easy to reason about, easy to explain in an interview.

---

## 4. Key Java/SDK patterns used in the code

- **`RequestHandler<Map<String, Object>, String>`** — generic input type since the trigger (EventBridge schedule) doesn't carry a meaningful payload; output is just a human-readable summary string, visible in the console/logs.
- **`Ec2Client` created once as an instance field**, not inside `handleRequest`. This matters because Lambda reuses the execution environment ("warm start") across invocations — anything in a field/static scope survives between calls, so you avoid paying init cost (a new HTTP connection pool, TLS handshake, etc.) on every single invocation.
- **`LambdaLogger` via `context.getLogger()`** — writes to CloudWatch Logs without you having to configure a logging framework. (You'll see SLF4J "no-op logger" warnings in the logs — harmless; the SDK looks for an SLF4J binding, doesn't find one, and falls back silently. It doesn't affect `LambdaLogger` output at all.)
- **Explicit HTTP client (`UrlConnectionHttpClient`)** — see section 6.
- **Try/catch on `Ec2Exception`, checked by `awsErrorDetails().errorCode()`** — this is the SDK v2 way of branching on a specific AWS error (the equivalent of boto3's `e.response['Error']['Code']`).

---

## 5. AWS Lambda fundamentals

### Execution lifecycle — 3 phases
1. **INIT** — Lambda provisions a new execution environment: downloads code, starts the runtime (JVM, for Java), runs anything outside the handler (your field initializers, static blocks). This is the "cold start."
2. **INVOKE** — your handler method actually runs.
3. **SHUTDOWN** — environment is frozen/recycled (not always immediately destroyed — it can be reused for a future invocation, which is what gives you a "warm start" with no INIT phase).

You can see all three reflected in the `REPORT` log line: `Duration`, `Billed Duration`, `Init Duration`.

### Cold start vs warm start — and a billing change worth knowing
- **Cold start** = new environment, pays the INIT cost. Java tends to have one of the longer cold starts of any Lambda runtime because the JVM itself has to boot and JIT-warm.
- **Warm start** = environment reused, INIT phase skipped, handler runs immediately.
- **Important and recent**: as of **August 1, 2025**, AWS changed billing so the **INIT phase duration is now billed** for on-demand invocations of all managed runtimes (previously it was free unless you used Provisioned Concurrency, custom runtimes, or container images). This disproportionately affects JVM-based functions since Java's INIT phase tends to be the longest. Worth mentioning if asked about Lambda cost optimization — it's a live, current consideration, not just a Python/Node interview fact.
- **Mitigations**: SnapStart (see below), Provisioned Concurrency, smaller deployment packages, lazy-loading anything not needed on every request.

### SnapStart (Java-relevant)
- Snapshots the initialized execution environment (after INIT) and restores from that snapshot instead of re-running INIT on future cold starts — can take Java cold starts from seconds down to sub-100ms territory.
- Originally Java-only; now also available for Python and .NET runtimes.
- Gotcha: anything that captures state during INIT (timestamps, random seeds, open network connections) gets "frozen" into the snapshot and reused across restores — so initialization code has to be safe to snapshot (stateless-ish). IAM credentials are refreshed automatically after restore, but anything you cached yourself isn't.

### Memory, timeout, concurrency
- **Memory**: 128 MB – 10,240 MB. CPU allocation scales with memory — more memory often means a *faster*, not just bigger, function. (Lambda Power Tuning is the standard tool for finding the cost/speed sweet spot.)
- **Timeout**: default 3 seconds (too short for almost anything real); max 900 seconds (15 minutes).
- **Concurrency**: account/region has a default concurrent-execution limit (commonly 1,000, raisable via support request). **Reserved concurrency** caps/guarantees a slice for one function; **Provisioned concurrency** pre-warms a number of environments so there's no cold start for those.

### Invocation types — this trips people up
| Type | Examples | Retry behavior |
|---|---|---|
| **Synchronous** | API Gateway, direct SDK `Invoke` call | Caller gets the error directly; no automatic retry by Lambda |
| **Asynchronous** | **EventBridge**, SNS, S3 events | Lambda retries automatically (commonly up to 2 retries with backoff); after that, the event is **dropped unless you've configured a DLQ or an on-failure destination** |
| **Poll-based** | SQS, Kinesis, DynamoDB Streams | Lambda's poller manages batching/retries against the stream/queue itself |

Our function is invoked **asynchronously** by EventBridge — see section 9 for why that matters here.

### IAM — two different things people confuse
- **Execution role** (what we configured): permissions the function's *code* has when it runs (`DescribeSnapshots`, `DeleteSnapshot`, etc.).
- **Resource-based policy**: permissions controlling *who/what is allowed to invoke* the function (e.g., letting EventBridge invoke it — the console wires this automatically when you add the trigger there).

### Other things worth being able to define
- **Versions & aliases**: a version is an immutable snapshot of code+config; an alias is a named pointer to a version (e.g., `prod` → version 3) — used for safe rollout/rollback.
- **Layers**: a way to share code/dependencies across functions without bundling them into every jar (we don't use one here — the shaded fat jar bundles everything itself instead).
- **Deployment package limits**: 50 MB zipped for direct upload, 250 MB unzipped; container images allow up to 10 GB.

---

## 6. Java-specific integration details (the part most likely to get probed)

### SDK v1 vs v2 — know this cold
| | SDK v1 (`com.amazonaws...`) | SDK v2 (`software.amazon.awssdk...`) |
|---|---|---|
| Client creation | `AmazonEC2ClientBuilder.standard().build()` | `Ec2Client.builder()...build()` |
| Object style | Mutable POJOs, setters | Immutable, builder pattern throughout |
| HTTP client | Bundled by default | **Must be added explicitly** (see below) |
| Async support | Bolt-on, awkward | First-class async clients (`Ec2AsyncClient`) |
| Pagination | Manual loops | Built-in paginator methods (e.g. `describeSnapshotsPaginator`) |

### Why you must add an HTTP client dependency
SDK v2 service modules (like `ec2`) don't bundle an HTTP implementation. Without one on the classpath, `Ec2Client.create()` fails at runtime with no usable HTTP client found. We added `url-connection-client` — the lightest option, good for Lambda's cold-start-sensitive environment (Apache and Netty clients are heavier and better suited to high-throughput services that benefit from connection pooling/HTTP2).

### Fat jar packaging gotcha
`maven-shade-plugin` merges every dependency's classes into one jar — but by default it does **not** merge `META-INF/services/*` files, which is exactly how the SDK's `ServiceLoader`-based HTTP client discovery works. Without the `ServicesResourceTransformer`, the jar builds fine and then **fails at runtime** because the service file from one dependency silently overwrote another's. This is a classic "works in my IDE, breaks in Lambda" trap specific to SDK v2 + shading.

### Handler string format
`<package>.<ClassName>::<methodName>` — e.g. `com.example.SnapshotCleanupLambda::handleRequest`. Get the package or class name wrong after a rename (which happened in this project) and the function fails immediately on invoke with a class-not-found-style error — always double check this after refactoring.

### `aws-lambda-java-core` vs `aws-lambda-java-events`
We only used `aws-lambda-java-core` (gives you `RequestHandler`, `Context`, `LambdaLogger`) since our input is a generic `Map`. If this were triggered by, say, S3 or SQS instead of a bare schedule, `aws-lambda-java-events` provides typed POJOs (`S3Event`, `SQSEvent`, etc.) instead of hand-parsing a `Map` — worth knowing this library exists even though we didn't need it here.

---

## 7. The IAM policy, justified permission by permission

```json
"ec2:DescribeSnapshots"   → list snapshots to evaluate
"ec2:DescribeInstances"   → (originally included; removed once the running-instances
                             lookup was found to be unused dead code — see section 10)
"ec2:DescribeVolumes"     → check attachments on a snapshot's source volume
"ec2:DeleteSnapshot"      → the actual cleanup action
"logs:CreateLogGroup"     → Lambda's auto-created log group on first invoke
"logs:CreateLogStream"    →   ...and a new stream per concurrent execution environment
"logs:PutLogEvents"       → actually writing log lines
```

Interview-worthy critique: `"Resource": "*"` is broad. EC2 actions are largely account/region-scoped rather than ARN-scoped by nature, but a tighter version of this policy could still constrain the logs permissions to the specific log group ARN instead of `*`.

---

## 8. EventBridge — quick recap
- Originally "CloudWatch Events" — same service, renamed/expanded; you'll still see the old name in some console menus and docs.
- Two trigger styles: **schedule-based** (`rate(1 day)`, or a cron expression) and **event-pattern-based** (react to something happening elsewhere in AWS). We only need schedule-based.
- Adding it via the Lambda console's "Add trigger" flow automatically creates both the rule *and* the resource-based policy permission letting EventBridge invoke the function — doing it via CLI/IaC instead means you'd separately need `aws lambda add-permission`.

---

## 9. Tips & tricks (grab-bag)

- **SLF4J warning in logs is noise, not a bug** — ignore it.
- **`DescribeSnapshots` and `DescribeInstances` are paginated** (~1000 results per call by default). Neither the original Python script nor the current Java version handles `NextToken` — fine for small accounts, a silent correctness bug on large ones. Mention this proactively; it shows you understand the limits of what you built.
- **Bump the timeout before you test** — the 3-second default will look like a mysterious failure otherwise.
- **CloudWatch occasionally double-logs a single line** under the same timestamp — a delivery-pipeline quirk, not a double execution. Confirm with the `deletedCount` value and a single `START`/`END`/`REPORT` block per invocation; for absolute certainty about how many times an API call actually fired, check **CloudTrail → Event history** filtered by event name.
- **Set memory above the default 128 MB even if you don't need the RAM** — more memory also means more CPU, which can make a function finish faster and sometimes cost *less* overall despite the higher per-ms rate. Don't tune blind — use AWS Lambda Power Tuning.
- **Arm64 (Graviton) is usually cheaper and just as fast for this kind of workload** — worth mentioning as a known cost lever even though we deployed on x86.
- **`url-connection-client` over `apache-client` for low-throughput scheduled jobs** — smaller jar, faster cold start; only reach for Apache/Netty if you need connection pooling at higher request volume.

---

## 10. Honest gaps — what I'd add for production (great interview material)

Being able to list these unprompted is more impressive than pretending the project is "done":

1. **No pagination** — large accounts could have snapshots silently skipped past the first page.
2. **No dry-run mode** — every match is deleted immediately; a production version should support an env var like `DRY_RUN=true` that only logs intent, given `DeleteSnapshot` is irreversible.
3. **No failure destination on the async trigger** — EventBridge invokes Lambda asynchronously; after Lambda's automatic retries are exhausted, a failed run is silently dropped unless you configure a DLQ or an on-failure destination (SNS/SQS/EventBridge). Currently there isn't one.
4. **No automated tests** — only manual console "Test" invocations against real (throwaway) resources so far. Next step: unit test the handler with `Ec2Client` mocked via Mockito, or spin up integration tests against **LocalStack** instead of a real account.
5. **Single-region only** — one `Ec2Client` targets one region; a real fleet-wide tool would loop across regions or be deployed per-region.
6. **No tag-based safety net** — a production cleanup tool usually lets you exclude resources via a tag (e.g. `DoNotDelete=true`) before anything destructive runs.
7. **No cost reporting** — it counts snapshots deleted but never estimates *dollars* saved, which is the actual point of a "cost optimization" tool. `Snapshot.volumeSize()` (GB) × the region's EBS snapshot $/GB-month rate would get you there.
8. **IAM is broader than strictly necessary** (`Resource: "*"` on the logs actions, in particular).

### Debugging stories worth having ready
- **Found dead code while porting**: the original Python script fetched running-instance IDs into a set but never actually used it to gate any decision — the real check was just "does the volume have any attachment." Removed it entirely in the Java port rather than carrying forward unused logic.
- **Caught a copy-paste log bug**: a refactor left the "volume has no attachments" branch logging the wrong reason ("not attached to any volume," copied from a different branch) — harmless to function, but would have produced misleading audit logs on a destructive action. Fixed by reviewing every log message against the actual condition that triggered it.
- **Investigated a duplicate log line** rather than assuming the worst — checked the deleted count and invocation count before concluding it was a logging-pipeline artifact, not a double deletion.

---

## 11. Rapid-fire Q&A

**Q: Why initialize the EC2 client as a field instead of inside the handler?**
A: Lambda reuses execution environments across invocations (warm starts). Anything outside the handler — fields, static blocks — persists across those reused invocations, so you only pay the client-construction cost on a cold start, not on every single call.

**Q: Why does AWS SDK v2 need an explicit HTTP client dependency?**
A: Service modules don't bundle one; the SDK uses `ServiceLoader` to discover whichever implementation (URL connection, Apache, Netty) is on the classpath at runtime. Skipping this throws at runtime even though the code compiles fine.

**Q: What's the difference between Lambda's execution role and its resource-based policy?**
A: Execution role = what the function's code is allowed to *do* (call DeleteSnapshot, write logs). Resource-based policy = who/what is allowed to *invoke* the function.

**Q: Sync vs async Lambda invocation — why does it matter here?**
A: EventBridge invokes asynchronously, so Lambda — not the caller — manages retries on failure, and after retries are exhausted the failure is silently dropped unless a DLQ/destination is configured. That's a real gap in this project right now.

**Q: How would you make this safe to run unattended?**
A: Add a dry-run flag for the first deployment window, a tag-based exclusion check, a failure destination on the trigger, and a CloudWatch alarm on the function's `Errors` metric.

**Q: How would you test this without touching a real AWS account every time?**
A: Mock `Ec2Client` with Mockito and stub `describeSnapshots`/`describeVolumes` responses for unit tests; use LocalStack for a closer-to-real integration test without real AWS spend.

**Q: What would you change about the IAM policy?**
A: Scope the `logs:*` actions to the specific log group ARN instead of `Resource: "*"`.

**Q: How does Lambda billing work, and what changed recently?**
A: Billed on request count plus duration × allocated memory (GB-seconds). As of August 2025, the INIT (cold start) phase duration is now included in billed duration for all on-demand invocations of managed runtimes — previously it was unbilled for ZIP-packaged functions. That matters more for Java than for lighter runtimes because JVM cold starts tend to run longer.

**Q: What's SnapStart, and would it help here?**
A: It snapshots a fully-initialized execution environment and restores from that snapshot on future cold starts instead of re-running INIT — can cut Java cold starts from seconds to sub-100ms. For a once-a-day scheduled job that's almost always a cold start anyway, it's one of the more relevant optimizations to mention even though it isn't enabled in this project yet.

---

## 12. Command cheat sheet

```bash
# Build the fat jar
mvn clean package

# Deploy directly (small jar)
aws lambda update-function-code \
  --function-name your-function-name \
  --zip-file fileb://target/aws-cost-optimizer-1.0.0.jar

# Deploy via S3 (large jar, or first-time create)
aws s3 cp target/aws-cost-optimizer-1.0.0.jar s3://your-bucket/aws-cost-optimizer-1.0.0.jar

aws lambda update-function-code \
  --function-name your-function-name \
  --s3-bucket your-bucket \
  --s3-key aws-cost-optimizer-1.0.0.jar

# First-time create via S3
aws lambda create-function \
  --function-name your-function-name \
  --runtime java17 \
  --handler com.example.SnapshotCleanupLambda::handleRequest \
  --role arn:aws:iam::<account-id>:role/<execution-role> \
  --code S3Bucket=your-bucket,S3Key=aws-cost-optimizer-1.0.0.jar \
  --timeout 120 \
  --memory-size 256
```
