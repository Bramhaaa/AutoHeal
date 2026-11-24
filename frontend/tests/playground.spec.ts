import { expect, test } from "@playwright/test";

const MOCK_SCENARIOS = [
  {
    id: "payment-npe-01",
    title: "Payment Service NullPointerException",
    description: "Missing null check on optional discount payload in checkout calculation.",
    language: "Java 21",
    replayAvailable: true,
  },
  {
    id: "pagination-offbyone-02",
    title: "Off-by-One Pagination Bug",
    description: "Boundary condition error returns incorrect page slice for final page.",
    language: "Java 21",
    replayAvailable: true,
  },
  {
    id: "inventory-race-03",
    title: "Concurrency Race Condition",
    description: "Unsynchronized inventory decrement drops updates under concurrent threads.",
    language: "Java 21",
    replayAvailable: false,
  },
  {
    id: "order-jpql-04",
    title: "Repository Query Typo",
    description: "Broken JPQL field reference fails H2 repository query test.",
    language: "Java 21",
    replayAvailable: false,
  },
];

const MOCK_RUN_ID = "3f1c9a2e-7b4d-4c1e-9a8f-2d6b5e0c1a77";

const MOCK_DIFF = `--- a/src/main/java/com/autoheal/demo/payment/PaymentService.java
+++ b/src/main/java/com/autoheal/demo/payment/PaymentService.java
@@ -12,6 +12,9 @@
   public BigDecimal calculateTotal(BigDecimal subtotal, Discount discount) {
-    return subtotal.subtract(discount.amount());
+    if (discount == null || discount.amount() == null) {
+      return subtotal;
+    }
+    return subtotal.subtract(discount.amount());
   }
 }`;

function buildSseBody(): string {
  const events = [
    {
      eventId: 1,
      timestamp: "2026-12-01T10:15:01Z",
      stage: "INCIDENT_RECEIVED",
      type: "STAGE_UPDATE",
      payload: { scenarioId: "payment-npe-01", branch: "scenario/payment-npe-01" },
    },
    {
      eventId: 2,
      timestamp: "2026-12-01T10:15:03Z",
      stage: "INCIDENT_RECEIVED",
      type: "LOG_LINE",
      payload: { message: "[ERROR] BUILD FAILURE - NullPointerException", phase: "baseline" },
    },
    {
      eventId: 3,
      timestamp: "2026-12-01T10:15:05Z",
      stage: "LOG_DISTILLED",
      type: "STAGE_UPDATE",
      payload: { reductionPercent: 84 },
    },
    {
      eventId: 4,
      timestamp: "2026-12-01T10:15:08Z",
      stage: "AGENT_PATCHING",
      type: "STAGE_UPDATE",
      payload: { maxAttempts: 3 },
    },
    {
      eventId: 5,
      timestamp: "2026-12-01T10:15:12Z",
      stage: "SANDBOX_VERIFY",
      type: "LOG_LINE",
      payload: {
        message: "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0",
        phase: "verify",
      },
    },
    {
      eventId: 6,
      timestamp: "2026-12-01T10:15:13Z",
      stage: "SANDBOX_VERIFY",
      type: "LOG_LINE",
      payload: { message: "[INFO] BUILD SUCCESS", phase: "verify" },
    },
    {
      eventId: 7,
      timestamp: "2026-12-01T10:15:14Z",
      stage: "SANDBOX_VERIFY",
      type: "ATTEMPT_RESULT",
      payload: { attempt: 1, outcome: "VERIFIED", testsRun: 12, testsFailed: 0 },
    },
    {
      eventId: 8,
      timestamp: "2026-12-01T10:15:15Z",
      stage: "PR_OPENED",
      type: "COMPLETED",
      payload: {
        attemptsUsed: 1,
        rootCauseSummary: "Added null guard for optional discount payload.",
        costUsd: "0.0014",
      },
    },
  ];

  return events
    .map(
      (evt) =>
        `id:${evt.eventId}\nevent:${evt.type}\ndata:${JSON.stringify(evt)}\n\n`,
    )
    .join("");
}

test("Playground streams SSE events, renders verified diff, and navigates to Benchmarks", async ({
  page,
}) => {
  await page.route("**/api/v1/scenarios", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(MOCK_SCENARIOS),
    });
  });

  await page.route("**/api/v1/incidents/simulate", async (route) => {
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({
        runId: MOCK_RUN_ID,
        status: "QUEUED",
        streamUrl: `/api/v1/incidents/${MOCK_RUN_ID}/stream`,
      }),
    });
  });

  await page.route(`**/api/v1/incidents/${MOCK_RUN_ID}/stream`, async (route) => {
    await route.fulfill({
      status: 200,
      headers: {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
      },
      body: buildSseBody(),
    });
  });

  await page.route(`**/api/v1/incidents/${MOCK_RUN_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        runId: MOCK_RUN_ID,
        source: "PLAYGROUND",
        scenarioId: "payment-npe-01",
        repoName: "autoheal-demo-service",
        commitSha: "scenario/payment-npe-01",
        status: "SUCCEEDED",
        failureReason: null,
        attemptsUsed: 1,
        ragEnabled: true,
        rootCauseSummary: "Added null guard for optional discount payload.",
        generatedDiff: MOCK_DIFF,
        prUrl: "https://github.com/example/autoheal-demo-service/pull/42",
        totalPromptTokens: 1420,
        totalCompletionTokens: 280,
        estimatedCostUsd: 0.0014,
        durationMs: 14200,
        createdAt: "2026-12-01T10:15:00Z",
        completedAt: "2026-12-01T10:15:15Z",
        attempts: [],
      }),
    });
  });

  await page.route("**/api/v1/benchmarks/summary", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        reportId: "eval-2026-12",
        generatedAt: "2026-12-01T12:00:00Z",
        totalBugs: 28,
        passAt1Rate: 0.679,
        passAt3Rate: 0.821,
        medianDurationMs: 18400,
        p90DurationMs: 41200,
        meanCostUsd: 0.0021,
        ablations: [
          {
            label: "Full system (Distiller + RAG + Retry)",
            passAt1Rate: 0.679,
            passAt3Rate: 0.821,
            medianDurationMs: 18400,
            meanCostUsd: 0.0021,
          },
        ],
        failureTaxonomy: [{ reason: "MAX_ATTEMPTS", count: 3 }],
      }),
    });
  });

  await page.goto("/");

  await expect(page.getByRole("heading", { name: "Live Incident Playground" })).toBeVisible();
  await expect(page.getByText("Payment Service NullPointerException")).toBeVisible();
  await expect(page.getByText("Repository Query Typo")).toBeVisible();

  await page.getByRole("button", { name: "Simulate crash" }).click();

  await expect(page.getByTestId("run-summary")).toBeVisible();
  await expect(page.getByText("Added null guard for optional discount payload.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Open GitHub Draft PR" })).toHaveAttribute(
    "href",
    "https://github.com/example/autoheal-demo-service/pull/42",
  );

  // Switch back to Live terminal to confirm RED -> GREEN Maven lines streamed
  await page.getByRole("tab", { name: "Live terminal" }).click();
  await expect(page.getByTestId("live-terminal")).toContainText("BUILD FAILURE");
  await expect(page.getByTestId("live-terminal")).toContainText("BUILD SUCCESS");

  // Switch to Patch diff tab and confirm parsed file header renders
  await page.getByRole("tab", { name: "Patch diff" }).click();
  await expect(page.getByTestId("patch-diff-viewer")).toContainText("PaymentService.java");

  // Navigate to Benchmarks tab
  await page.getByRole("link", { name: "Benchmarks" }).click();
  await expect(page.getByRole("heading", { name: "Evaluation Benchmarks" })).toBeVisible();
  await expect(page.getByText("28 bugs")).toBeVisible();
  await expect(
    page.getByRole("definition").filter({ hasText: "82.1%" }),
  ).toBeVisible();
});

test("Playground offers one-click switch to Instant replay on 429 rate limit", async ({
  page,
}) => {
  await page.route("**/api/v1/scenarios", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(MOCK_SCENARIOS),
    });
  });

  await page.route("**/api/v1/incidents/simulate", async (route) => {
    await route.fulfill({
      status: 429,
      contentType: "application/json",
      body: JSON.stringify({
        code: "RATE_OR_BUDGET_LIMIT",
        message: "Live run rate limit reached (3/hour). Try Instant replay.",
        suggestedMode: "REPLAY",
      }),
    });
  });

  await page.goto("/");
  await expect(page.getByText("Payment Service NullPointerException")).toBeVisible();
  await page.getByRole("button", { name: "Simulate crash" }).click();

  await expect(
    page.getByText("Live run rate limit reached (3/hour). Try Instant replay."),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Switch to Instant replay" })).toBeVisible();
});
