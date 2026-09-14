#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
EVALUATION_ROOT = ROOT / "evaluation"
VARIANTS = ("no_rag", "dense_only", "hybrid", "hybrid_rerank")
RRF_K = 60.0


def tokens(value: str) -> set[str]:
    return {item for item in re.split(r"[^a-z0-9_$]+", value.lower()) if len(item) > 1}


def java_hash(value: str) -> int:
    result = 0
    for character in value:
        result = (31 * result + ord(character)) & 0xFFFFFFFF
    return result - 0x100000000 if result & 0x80000000 else result


def embedding(value: str, dimensions: int) -> list[float]:
    vector = [0.0] * dimensions
    for term in tokens(value):
        hashed = java_hash(term)
        vector[hashed % dimensions] += 1.0 if hashed & 1 == 0 else -1.0
    norm = math.sqrt(sum(item * item for item in vector))
    return [item / norm for item in vector] if norm else vector


def cosine(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right))


def lexical_score(query: str, content: str) -> float:
    query_terms, content_terms = tokens(query), tokens(content)
    if not query_terms or not content_terms:
        return 0.0
    return len(query_terms & content_terms) / math.sqrt(len(query_terms) * len(content_terms))


def retrieve(case: dict[str, Any], documents: list[dict[str, Any]], variant: str,
             top_k: int, dimensions: int) -> tuple[list[dict[str, Any]], float]:
    started = time.perf_counter_ns()
    if variant == "no_rag":
        return [], (time.perf_counter_ns() - started) / 1_000_000

    query_vector = embedding(case["query"], dimensions)
    scored: list[dict[str, Any]] = []
    for document in documents:
        dense = cosine(query_vector, embedding(document["content"], dimensions))
        lexical = lexical_score(case["query"], document["content"])
        symbol_overlap = lexical_score(
            case["query"], f'{document.get("symbol", "")} {document["source"]}')
        scored.append({"document": document, "dense": dense, "lexical": lexical,
                       "symbolOverlap": symbol_overlap})

    dense_rank = sorted(scored, key=lambda item: (-item["dense"], item["document"]["id"]))
    lexical_rank = [item for item in sorted(
        scored, key=lambda item: (-item["lexical"], item["document"]["id"])) if item["lexical"] > 0]
    if variant == "dense_only":
        selected = dense_rank[:top_k]
    else:
        candidates: dict[str, dict[str, Any]] = {}
        for rank, item in enumerate(dense_rank):
            candidate = candidates.setdefault(item["document"]["id"], dict(item, fusion=0.0))
            candidate["fusion"] += 1.0 / (RRF_K + rank + 1)
        for rank, item in enumerate(lexical_rank):
            candidate = candidates.setdefault(item["document"]["id"], dict(item, fusion=0.0))
            candidate["fusion"] += 1.0 / (RRF_K + rank + 1)
        if variant == "hybrid":
            selected = sorted(candidates.values(), key=lambda item: (
                -item["fusion"], item["document"]["id"]))[:top_k]
        else:
            max_lexical = max((item["lexical"] for item in lexical_rank), default=1.0)
            for item in candidates.values():
                normalized_dense = max(0.0, min(1.0, (item["dense"] + 1.0) / 2.0))
                normalized_lexical = item["lexical"] / max_lexical if max_lexical else 0.0
                item["rerank"] = (0.50 * normalized_dense + 0.25 * normalized_lexical
                                    + 0.15 * item["symbolOverlap"]
                                    + 0.10 * min(1.0, item["fusion"] * 30.0))
            selected = sorted(candidates.values(), key=lambda item: (
                -item["rerank"], item["document"]["id"]))[:top_k]
    return selected, (time.perf_counter_ns() - started) / 1_000_000


def retrieval_metrics(case: dict[str, Any], ranked: list[dict[str, Any]], top_k: int) -> dict[str, float]:
    relevance: dict[str, int] = case["relevance"]
    ids = [item["document"]["id"] for item in ranked[:top_k]]
    relevant_hits = [document_id for document_id in ids if document_id in relevance]
    recall = len(set(relevant_hits)) / len(relevance)
    reciprocal_rank = next((1.0 / (index + 1) for index, document_id in enumerate(ids)
                            if document_id in relevance), 0.0)

    def dcg(values: list[int]) -> float:
        return sum((2 ** grade - 1) / math.log2(index + 2) for index, grade in enumerate(values))

    actual = [relevance.get(document_id, 0) for document_id in ids]
    ideal = sorted(relevance.values(), reverse=True)[:top_k]
    ideal_dcg = dcg(ideal)
    return {
        "recallAtK": recall,
        "mrr": reciprocal_rank,
        "ndcgAtK": dcg(actual) / ideal_dcg if ideal_dcg else 0.0,
        "contextPrecision": len(relevant_hits) / len(ids) if ids else 0.0,
    }


def average_metrics(values: list[dict[str, float]]) -> dict[str, float]:
    return {key: round(sum(item[key] for item in values) / len(values), 6) for key in values[0]}


def test_path(test_class: str) -> Path:
    return Path("src/test/java") / Path(*test_class.split(".")).with_suffix(".java")


def validate_generated_tests(dataset: dict[str, Any], variant_dir: Path) -> tuple[float, float]:
    valid = 0
    relevance_scores: list[float] = []
    for case in dataset["cases"]:
        source_path = variant_dir / test_path(case["testClass"])
        source = source_path.read_text(encoding="utf-8") if source_path.is_file() else ""
        simple_name = case["testClass"].rsplit(".", 1)[-1]
        expected_tag = case["level"].lower()
        structurally_valid = all((
            f"class {simple_name}" in source,
            "@Test" in source,
            f'@Tag("{expected_tag}")' in source,
            "assert" in source,
        ))
        valid += int(structurally_valid)
        terms = case["assertionTerms"]
        relevance_scores.append(sum(term in source for term in terms) / len(terms))
    case_count = len(dataset["cases"])
    return valid / case_count, sum(relevance_scores) / case_count


def run_maven(workspace: Path, maven: Path, ignore_failures: bool = False) -> subprocess.CompletedProcess[str]:
    command = [str(maven), "--batch-mode", "--no-transfer-progress", "-f", str(workspace / "pom.xml")]
    if ignore_failures:
        command.append("-Dmaven.test.failure.ignore=true")
    command.extend(["clean", "test"])
    return subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=180, check=False)


def surefire_statuses(workspace: Path) -> dict[str, str]:
    statuses: dict[str, str] = {}
    for report in sorted((workspace / "target/surefire-reports").glob("TEST-*.xml")):
        root = ET.parse(report).getroot()
        for case in root.findall("testcase"):
            key = f'{case.attrib.get("classname", "")}.{case.attrib.get("name", "")}'
            statuses[key] = "FAILED" if case.find("failure") is not None or case.find("error") is not None else "PASSED"
    return statuses


def line_coverage(workspace: Path) -> float:
    report = workspace / "target/site/jacoco/jacoco.csv"
    if not report.is_file():
        return 0.0
    missed = covered = 0
    with report.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            missed += int(row["LINE_MISSED"])
            covered += int(row["LINE_COVERED"])
    return covered / (missed + covered) if missed + covered else 0.0


def overlay_tree(source: Path, destination: Path) -> None:
    for path in source.rglob("*"):
        if path.is_file():
            target = destination / path.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)


def generation_metrics(dataset: dict[str, Any], variant: str, maven: Path,
                       repeat_runs: int) -> tuple[dict[str, float], dict[str, Any]]:
    generated = EVALUATION_ROOT / "benchmark/generated" / variant
    schema_rate, assertion_relevance = validate_generated_tests(dataset, generated)
    started = time.perf_counter()
    with tempfile.TemporaryDirectory(prefix=f"testpilot-eval-{variant}-") as temporary:
        workspace = Path(temporary) / "project"
        shutil.copytree(EVALUATION_ROOT / "benchmark/project", workspace)
        overlay_tree(generated, workspace)

        baseline_runs: list[dict[str, str]] = []
        compile_success = True
        baseline_output = ""
        coverage = 0.0
        for _ in range(repeat_runs):
            result = run_maven(workspace, maven)
            baseline_output = result.stdout
            compile_success = compile_success and result.returncode == 0
            baseline_runs.append(surefire_statuses(workspace))
            coverage = line_coverage(workspace)

        all_test_ids = set().union(*(run.keys() for run in baseline_runs))
        flaky = sum(1 for test_id in all_test_ids
                    if len({run.get(test_id, "MISSING") for run in baseline_runs}) > 1)

        for case in dataset["cases"]:
            mutant = EVALUATION_ROOT / "benchmark/mutants" / case["mutantId"]
            overlay_tree(mutant, workspace)
        mutated = run_maven(workspace, maven, ignore_failures=True)
        mutated_statuses = surefire_statuses(workspace)
        detected = 0
        detection: dict[str, bool] = {}
        for case in dataset["cases"]:
            class_prefix = case["testClass"] + "."
            caught = any(status == "FAILED" and test_id.startswith(class_prefix)
                         for test_id, status in mutated_statuses.items())
            detection[case["id"]] = caught
            detected += int(caught)

        if not compile_success:
            tail = "\n".join(baseline_output.splitlines()[-20:])
            raise RuntimeError(f"Generated {variant} fixture did not compile and pass:\n{tail}")
        if mutated.returncode != 0:
            tail = "\n".join(mutated.stdout.splitlines()[-20:])
            raise RuntimeError(f"Mutated {variant} fixture infrastructure failed:\n{tail}")

        case_count = len(dataset["cases"])
        metrics = {
            "schemaValidityRate": round(schema_rate, 6),
            "compileRate": 1.0,
            "assertionRelevance": round(assertion_relevance, 6),
            "lineCoverage": round(coverage, 6),
            "coverageDelta": 0.0,
            "mutationScore": round(detected / case_count, 6),
            "seededDefectDetectionRate": round(detected / case_count, 6),
            "flakinessRate": round(flaky / len(all_test_ids), 6) if all_test_ids else 0.0,
        }
        evidence = {
            "baselineTestCount": len(all_test_ids),
            "repeatRuns": repeat_runs,
            "seededDefects": detection,
            "durationMs": round((time.perf_counter() - started) * 1000, 3),
        }
        return metrics, evidence


def dataset_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def nested_value(data: dict[str, Any], path: str) -> float:
    value: Any = data
    for segment in path.split("."):
        value = value[segment]
    return float(value)


def check_thresholds(result: dict[str, Any], config: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    for path, minimum in config.get("minimums", {}).items():
        actual = nested_value(result, path)
        if actual + 1e-9 < float(minimum):
            failures.append(f"{path}={actual:.6f} is below minimum {minimum}")
    for path, maximum in config.get("maximums", {}).items():
        actual = nested_value(result, path)
        if actual - 1e-9 > float(maximum):
            failures.append(f"{path}={actual:.6f} exceeds maximum {maximum}")
    return failures


def render_report(result: dict[str, Any]) -> str:
    lines = [
        "# Phase 7 offline benchmark results",
        "",
        f'- Dataset: `{result["dataset"]["id"]}` `{result["dataset"]["version"]}`',
        f'- Dataset SHA-256: `{result["dataset"]["sha256"]}`',
        f'- Generated: `{result["generatedAtUtc"]}`',
        "- Generator: deterministic fixture generator; model/API cost is $0",
        "",
        "| Variant | Recall@3 | MRR | nDCG@3 | Context precision | Compile | Assertion relevance | Coverage | Coverage delta | Seeded mutation score | Flakiness |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for variant in VARIANTS:
        retrieval = result["variants"][variant]["retrieval"]
        generation = result["variants"][variant]["generation"]
        lines.append(
            f'| `{variant}` | {retrieval["recallAtK"]:.3f} | {retrieval["mrr"]:.3f} | '
            f'{retrieval["ndcgAtK"]:.3f} | {retrieval["contextPrecision"]:.3f} | '
            f'{generation["compileRate"]:.3f} | {generation["assertionRelevance"]:.3f} | '
            f'{generation["lineCoverage"]:.3f} | {generation["coverageDelta"]:+.3f} | '
            f'{generation["mutationScore"]:.3f} | {generation["flakinessRate"]:.3f} |')
    lines.extend([
        "",
        "## Interpretation",
        "",
        "This benchmark validates the evaluation machinery and shows how retrieved boundary evidence can improve deterministic fixture tests. It does not measure a live LLM, generalize beyond the pinned cases, or prove production effectiveness. Real-provider runs must be published separately with model, prompt, token, cost, and environment metadata.",
        "",
        "System and browser end-to-end testing remain outside scope.",
        "",
    ])
    return "\n".join(lines)


def evaluate(dataset_path: Path, config_path: Path, maven: Path) -> tuple[dict[str, Any], list[str]]:
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    config = json.loads(config_path.read_text(encoding="utf-8"))
    digest = dataset_digest(dataset_path)
    if digest != config["datasetSha256"]:
        raise ValueError(f"Dataset digest {digest} does not match pinned {config['datasetSha256']}")
    if dataset["datasetId"] != config["datasetId"] or dataset["datasetVersion"] != config["datasetVersion"]:
        raise ValueError("Dataset identity does not match the regression configuration")

    result: dict[str, Any] = {
        "schemaVersion": 1,
        "generatedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "dataset": {"id": dataset["datasetId"], "version": dataset["datasetVersion"], "sha256": digest},
        "configuration": {
            "topK": config["topK"],
            "embeddingDimensions": config["embeddingDimensions"],
            "repeatRuns": config["repeatRuns"],
            "generator": "deterministic-fixture-v1",
        },
        "environment": {"python": platform.python_version(), "platform": platform.platform()},
        "variants": {},
    }
    for variant in VARIANTS:
        per_case: list[dict[str, float]] = []
        latency: list[float] = []
        retrieved: dict[str, list[str]] = {}
        context_tokens = 0
        for case in dataset["cases"]:
            ranked, elapsed = retrieve(case, dataset["documents"], variant, config["topK"],
                                       config["embeddingDimensions"])
            per_case.append(retrieval_metrics(case, ranked, config["topK"]))
            latency.append(elapsed)
            retrieved[case["id"]] = [item["document"]["id"] for item in ranked]
            context_tokens += sum(max(1, len(item["document"]["content"]) // 4) for item in ranked)
        retrieval = average_metrics(per_case)
        retrieval["meanLatencyMs"] = round(sum(latency) / len(latency), 6)
        generation, generation_evidence = generation_metrics(
            dataset, variant, maven, config["repeatRuns"])
        result["variants"][variant] = {
            "retrieval": retrieval,
            "generation": generation,
            "operational": {
                "retrievedContextTokens": context_tokens,
                "estimatedModelCostUsd": 0.0,
                "retrievedDocumentIds": retrieved,
                **generation_evidence,
            },
        }

    baseline_coverage = result["variants"]["no_rag"]["generation"]["lineCoverage"]
    for variant in VARIANTS:
        coverage = result["variants"][variant]["generation"]["lineCoverage"]
        result["variants"][variant]["generation"]["coverageDelta"] = round(
            coverage - baseline_coverage, 6)
    failures = check_thresholds(result, config)
    result["regressionGate"] = {"passed": not failures, "failures": failures}
    return result, failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the pinned TestPilot Phase 7 benchmark")
    parser.add_argument("--dataset", type=Path, default=EVALUATION_ROOT / "datasets/java-testing-v1.json")
    parser.add_argument("--config", type=Path, default=EVALUATION_ROOT / "config/regression-thresholds-v1.json")
    parser.add_argument("--maven", type=Path, default=ROOT / "mvnw")
    parser.add_argument("--output", type=Path, default=EVALUATION_ROOT / "results/phase7-baseline.json")
    parser.add_argument("--report", type=Path, default=EVALUATION_ROOT / "results/phase7-baseline.md")
    parser.add_argument("--check", action="store_true", help="Return non-zero when a threshold fails")
    arguments = parser.parse_args()

    try:
        result, failures = evaluate(arguments.dataset, arguments.config, arguments.maven)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"evaluation failed: {error}", file=sys.stderr)
        return 2

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    arguments.report.write_text(render_report(result), encoding="utf-8")
    print(json.dumps(result["regressionGate"], sort_keys=True))
    if arguments.check and failures:
        for failure in failures:
            print(f"threshold failure: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
