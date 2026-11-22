const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied read and write across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("vault_items").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("ai_logs").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("note_1")
      .set({
        userId: ALICE_UID,
        itemType: "NOTE",
        title: "Unauthorized Note",
        content: "Should fail",
        category: "Notes",
        tags: [],
        sizeBytes: 11,
        pinned: false,
        createdAt: now,
        updatedAt: now,
      })
  );
});

test("User profile: owner can create, read, and update; cannot list or spoof another user", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      defaultInferenceMode: "PREFER_ON_DEVICE",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("users").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());

  // Valid preference update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      defaultInferenceMode: "PREFER_IN_CLOUD",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / role escalation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Vault items: Kotlin create, partial update, pin toggle, AI summary attach, and delete succeed for owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  // Mirror VaultRepository.createItem payload (NO @DocumentId `id` field, optional nulls omitted)
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("item_1")
      .set({
        userId: ALICE_UID,
        itemType: "NOTE",
        title: "BuildHybridAI Hackathon Ideas",
        content: "Use Firebase AI Logic hybrid inference with Gemini Nano.",
        category: "Ideas",
        tags: ["firebase", "hybrid-ai"],
        sizeBytes: 58,
        pinned: false,
        createdAt: created,
        updatedAt: created,
      })
  );

  // Mirror list query on user subcollection
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("vault_items").get()
  );

  // Mirror partial update (edit note)
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("item_1")
      .update({
        title: "BuildHybridAI Hackathon Architecture",
        content: "Updated content with on-device telemetry.",
        category: "Plans",
        tags: ["firebase", "android"],
        sizeBytes: 42,
        updatedAt: new Date(Date.now() - 2000),
      })
  );

  // Mirror AI summary attachment
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("item_1")
      .update({
        aiSummary: "Architecture plan for Firebase AI Logic hybrid inference.",
        updatedAt: new Date(Date.now() - 1500),
      })
  );

  // Mirror pin toggle
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("item_1")
      .update({
        pinned: true,
        updatedAt: new Date(Date.now() - 1000),
      })
  );

  // Reject immutable createdAt modification
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("item_1")
      .update({
        createdAt: new Date(Date.now() - 500),
        updatedAt: new Date(Date.now() - 500),
      })
  );

  // Reject extra `id` field on create
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("item_bad_id")
      .set({
        id: "item_bad_id",
        userId: ALICE_UID,
        itemType: "NOTE",
        title: "Bad ID Field",
        content: "Contains forbidden id field",
        category: "Notes",
        tags: [],
        sizeBytes: 10,
        pinned: false,
        createdAt: created,
        updatedAt: created,
      })
  );

  // Reject future timestamp tampering
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("item_future")
      .set({
        userId: ALICE_UID,
        itemType: "LINK",
        title: "Future Link",
        content: "",
        category: "Links",
        tags: [],
        sourceUrl: "https://firebase.google.com",
        sizeBytes: 0,
        pinned: false,
        createdAt: new Date(Date.now() + 60000),
        updatedAt: new Date(Date.now() + 60000),
      })
  );

  // Owner delete succeeds
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("item_1")
      .delete()
  );
});

test("Vault items: cross-user isolation blocks Bob from reading, writing, or deleting Alice's items", async () => {
  const created = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("secret_note")
      .set({
        userId: ALICE_UID,
        itemType: "NOTE",
        title: "Alice Private Note",
        content: "Confidential thoughts",
        category: "Personal",
        tags: ["private"],
        sizeBytes: 21,
        pinned: false,
        createdAt: created,
        updatedAt: created,
      });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("secret_note")
      .get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("vault_items").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("secret_note")
      .update({ title: "Hijacked", updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_items")
      .doc("secret_note")
      .delete()
  );
});

test("AI audit logs: owner can append, list, and delete; cannot mutate logs; cross-user blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("ai_logs")
      .doc("log_1")
      .set({
        userId: ALICE_UID,
        taskType: "SUMMARIZE",
        promptPreview: "BuildHybridAI Hackathon Ideas",
        requestedMode: "PREFER_ON_DEVICE",
        actualSource: "IN_CLOUD",
        modelName: "gemini-3.5-flash-lite",
        transmittedToCloud: true,
        latencyMs: 482,
        succeeded: true,
        createdAt: created,
      })
  );

  // List succeeds for owner
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("ai_logs").get()
  );

  // Immutable: updates must fail even for owner
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("ai_logs")
      .doc("log_1")
      .update({ actualSource: "ON_DEVICE", transmittedToCloud: false })
  );

  // Cross-user read/delete blocked
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("ai_logs").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("ai_logs")
      .doc("log_1")
      .delete()
  );

  // Owner can clear history from Privacy Center
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("ai_logs")
      .doc("log_1")
      .delete()
  );
});

test("Document analyses: owner can create, update on reanalysis, and delete; cross-user blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("document_analyses")
      .doc("doc_1")
      .set({
        userId: ALICE_UID,
        itemId: "doc_1",
        summary: "Quarterly financial report covering Q3 revenue growth.",
        detailedExplanation: "Detailed walkthrough of Q3 financials, margins, and regional performance.",
        conclusions: ["APAC expansion drove 28% QoQ revenue growth."],
        extractionStatus: "COMPLETE",
        extractionMethod: "PDF_MULTIMODAL_OCR",
        extractionDisclosure: "Extracted 4/4 pages via PDF multimodal OCR.",
        chunksProcessed: 2,
        totalChunks: 2,
        mainTopics: ["Revenue", "Margin Expansion"],
        sourceFacts: [
          { fact: "Q3 revenue reached $4.2M.", sourceRef: "Page 1" },
        ],
        aiInterpretations: ["Growth is driven primarily by enterprise renewals."],
        definitions: [
          { term: "ARR", explanation: "Annual Recurring Revenue", sourceRef: "Page 2" },
        ],
        entities: [
          { name: "Q3 Revenue", type: "Figure", context: "$4.2M total", sourceRef: "Page 1" },
        ],
        tableInsights: [
          { title: "Regional Breakdown", finding: "APAC grew 28% QoQ.", sourceRef: "Page 3" },
        ],
        actionItems: [
          { action: "Finalize Q4 hiring plan", priority: "High", sourceRef: "Page 4" },
        ],
        groundedQa: [
          { question: "What was Q3 revenue?", answer: "$4.2M.", sourceRef: "Page 1" },
        ],
        extractedPreview: "[Page 1]\nQuarterly Financial Report...",
        requestedMode: "PREFER_IN_CLOUD",
        actualSource: "IN_CLOUD",
        modelName: "gemini-3.8-flash",
        latencyMs: 1420,
        createdAt: created,
        updatedAt: created,
      })
  );

  // Reanalysis update succeeds while preserving createdAt
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("document_analyses")
      .doc("doc_1")
      .update({
        summary: "Updated deep synthesis of Q3 financial report.",
        detailedExplanation: "Updated detailed explanation across all 4 pages.",
        conclusions: ["Q3 performance beat baseline forecast."],
        latencyMs: 1180,
        updatedAt: new Date(Date.now() - 1000),
      })
  );

  // Cross-user read & write denied
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("document_analyses")
      .doc("doc_1")
      .get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("document_analyses")
      .doc("doc_1")
      .delete()
  );

  // Owner delete succeeds
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("document_analyses")
      .doc("doc_1")
      .delete()
  );
});

