# shop-acceptance-tests

Cross-service **end-to-end acceptance tests** (Cucumber + JUnit Platform) that run
against the **preprod** environment through the API gateway.

System-level (black-box) test layer. Component-level BDD lives in each service repo
under `src/test/resources/features/`.

## Test isolation (important)

Every scenario is **fully independent**:

1. it **provisions its own** product (+ stock) on preprod via non-prod test-support
   endpoints (unique product id),
2. asserts **absolute** values (the product starts fresh),
3. and a Cucumber `@After` hook (`TeardownHooks`) **deletes** everything it created.

No shared seed data, no ordering between scenarios, safe to re-run (and later
parallelize).

## What it checks (`features/purchase.feature`)

- **Happy path** — order `CONFIRMED`, stock decremented.
- **Out of stock** — order `REJECTED` (forward recovery), stock unchanged.
- **Payment declined** — order `CANCELLED`, reserved stock released. Deterministic
  via a decline hook: amount `x.66` is always declined by the payment mock
  (scenario provisions a product priced `6.66`).

## API contract it depends on (through the gateway)

| Call | Expected | Notes |
|------|----------|-------|
| `POST /api/orders` + `Idempotency-Key`, body `{"productId","quantity"}` | `202 {"orderId"}` | |
| `GET /api/orders/{orderId}` | `{"status": PENDING\|RESERVED\|CONFIRMED\|REJECTED\|CANCELLED}` | |
| `GET /api/inventory/{productId}` | `{"available": <int>}` | |
| `POST /api/products` body `{"name","price"}` | `{"id"}` | **test-support, non-prod only** |
| `DELETE /api/products/{id}` | `204` | **test-support, non-prod only** |
| `PUT /api/inventory/{productId}` body `{"stock"}` | `200/204` | **test-support, non-prod only** |
| `DELETE /api/inventory/{productId}` | `204` | **test-support, non-prod only** |

The test-support endpoints must be guarded to the preprod profile and never exposed
in prod.

## Running — local-first (now)

GitHub-hosted runners cannot reach a local kind/preprod, so for now the suite runs
**locally** against your preprod. Deploy the stack to `kind-preprod`, then:

```powershell
# one shot: port-forwards the gateway and runs the suite
./run-local.ps1
./run-local.ps1 -Tags "not @payment-decline"   # skip the decline scenario
```

…or manually:

```powershell
kubectl --context kind-preprod -n shop port-forward svc/shop-gateway 8080:8080   # separate window
$env:SHOP_GATEWAY_URL = "http://localhost:8080"; .\gradlew.bat test
```

## Merge gating — self-hosted runner (later, the target)

The gate is wired but not active yet. The target is a **self-hosted GitHub Actions
runner on the dev machine**, so jobs execute locally and can reach `kind-preprod`
directly (the runner connects outbound to GitHub — no inbound ports).

`.github/workflows/acceptance.yml` is a reusable workflow (`workflow_call`); when we
switch it to `runs-on: [self-hosted]`, each service's PR pipeline calls it on
`pull_request` to `main`, deploys the candidate to preprod, runs this suite, and a
branch-protection **required check** blocks the merge unless it is green.
