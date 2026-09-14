# Phase 7 offline benchmark results

- Dataset: `testpilot-java-testing` `1.0.0`
- Dataset SHA-256: `2f605c11919dc21f946ff3f6733fa02969f8f14f8764eb1294804af21e119e9a`
- Generated: `2026-09-14T05:11:39Z`
- Generator: deterministic fixture generator; model/API cost is $0

| Variant | Recall@3 | MRR | nDCG@3 | Context precision | Compile | Assertion relevance | Coverage | Coverage delta | Seeded mutation score | Flakiness |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `no_rag` | 0.000 | 0.000 | 0.000 | 0.000 | 1.000 | 0.417 | 0.696 | +0.000 | 0.000 | 0.000 |
| `dense_only` | 0.833 | 1.000 | 0.779 | 0.556 | 1.000 | 0.583 | 0.913 | +0.217 | 0.000 | 0.000 |
| `hybrid` | 0.833 | 1.000 | 0.779 | 0.556 | 1.000 | 1.000 | 0.913 | +0.217 | 1.000 | 0.000 |
| `hybrid_rerank` | 1.000 | 1.000 | 0.910 | 0.667 | 1.000 | 1.000 | 0.957 | +0.261 | 1.000 | 0.000 |

## Interpretation

This benchmark validates the evaluation machinery and shows how retrieved boundary evidence can improve deterministic fixture tests. It does not measure a live LLM, generalize beyond the pinned cases, or prove production effectiveness. Real-provider runs must be published separately with model, prompt, token, cost, and environment metadata.

System and browser end-to-end testing remain outside scope.
