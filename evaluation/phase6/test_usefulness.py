"""Measure authored strong/vacuous tests with the existing offline Docker boundary.
No source, compiler or generated test runs on the host. Does not call an LLM.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('polyglot_smoke',ROOT/'worker/polyglot/smoke.py')
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)

def passed(result):
    tests = result.get('tests', [])
    return (result.get('outcome') == 'SUCCESS' and result.get('exitCode') == 0
            and any(t.get('status') == 'PASSED' for t in tests)
            and all(t.get('status') in ('PASSED', 'SKIPPED') for t in tests))

def summarize(baselines, mutant):
    baseline_valid = bool(baselines) and all(passed(r) for r in baselines)
    test_failure = (mutant.get('outcome') == 'TEST_FAILURE' and mutant.get('exitCode') not in (None, 0)
                    and any(t.get('status') == 'FAILED' for t in mutant.get('tests', [])))
    valid_mutant = test_failure or passed(mutant)
    return {'baselineValid': baseline_valid, 'baselinePasses': sum(passed(r) for r in baselines),
            'baselineAttempts': len(baselines), 'mutantOutcome': mutant.get('outcome'),
            'mutationStatus': 'MEASURED' if baseline_valid and valid_mutant else 'UNAVAILABLE',
            'seededMutationScore': (1.0 if test_failure else 0.0) if baseline_valid and valid_mutant else None}

def validate_dataset(dataset):
    if not dataset.get('origin') or not isinstance(dataset.get('cases'), list) or not 1 <= len(dataset['cases']) <= 100:
        raise ValueError('Dataset needs origin and 1..100 cases')
    ids = set()
    for case in dataset['cases']:
        for field in ('id', 'language', 'framework', 'sourcePath', 'source', 'mutant', 'testPath'):
            if not isinstance(case.get(field), str) or not case[field] or len(case[field]) > 100_000:
                raise ValueError('Missing or oversized case field: ' + field)
        if case['id'] in ids: raise ValueError('Duplicate case ID')
        ids.add(case['id'])
        if case['source'] == case['mutant']: raise ValueError('Mutant must differ from original source')
        variants = ('generated',) if 'generated' in case else ('strong', 'weak')
        for variant in variants:
            if not isinstance(case.get(variant), str) or not case[variant] or len(case[variant]) > 100_000:
                raise ValueError('Missing or oversized test code')
        expected = case.get('expectedMutationScores', {})
        if not isinstance(expected, dict) or any(k not in variants or type(v) not in (int, float) or v not in (0, 1) for k, v in expected.items()):
            raise ValueError('Expected mutation scores must name a supplied variant with score 0 or 1')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', default='testpilot-polyglot:local')
    parser.add_argument('--dataset', type=Path, default=ROOT/'evaluation/phase6/test-usefulness-v1.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); path = args.dataset
    digest=hashlib.sha256(path.read_bytes()).hexdigest()
    if digest!=path.with_suffix('.sha256').read_text().strip(): raise ValueError('Dataset pin mismatch')
    dataset=json.loads(path.read_text())
    validate_dataset(dataset)
    image_id=subprocess.check_output(['docker','image','inspect','--format={{.Id}}',args.image],text=True,timeout=10).strip()
    cases=[];failures=[]
    for case in dataset['cases']:
        variants = ('generated',) if 'generated' in case else ('strong', 'weak')
        for variant in variants:
            def run(source):
                payload=smoke.request(case['language'],case['framework'],case['sourcePath'],source,case['testPath'],case[variant])
                try:
                    return smoke.execute(args.image, payload)
                except (subprocess.SubprocessError, OSError, ValueError) as exc:
                    return {'outcome': 'HARNESS_FAILURE', 'output': str(exc)}
            baselines=[run(case['source']) for _ in range(2)]
            mutant=run(case['mutant'])
            measured=summarize(baselines,mutant)
            expected = case.get('expectedMutationScores', {}).get(variant)
            valid = measured['mutationStatus'] == 'MEASURED' and (expected is None or measured['seededMutationScore'] == expected)
            if not valid: failures.append(case['id']+':'+variant)
            cases.append({'caseId':case['id'],'language':case['language'],'framework':case['framework'],'variant':variant,
                          'metrics':measured,'expectedMutationScore':expected,'passed':valid,'baselines':baselines,'mutant':mutant})
            print(('PASS' if valid else 'FAIL')+': '+case['id']+' '+variant,flush=True)
    result={'datasetSha256':digest,'origin':dataset['origin'],'provider':dataset.get('provider'),'model':dataset.get('model'),'imageId':image_id,'verifiedAt':datetime.now(timezone.utc).isoformat(),
            'passed':not failures,'cases':cases,'limitations':['One supplied mutant per example; authors must check that the mutant is valid and non-equivalent. Scores do not establish general test usefulness.',
            'Bundled strong/weak examples are authored fixtures, not live generation. passed means valid measurement and any supplied expectation matched, not that every test killed a mutant.',
            'Two baseline runs only measure repeat consistency in this sample, not general flakiness.',
            'Compilation, infrastructure, missing-report and timeout failures do not count as killed mutants.']}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n')
    if failures: raise SystemExit('Failed usefulness regression: '+', '.join(failures))
if __name__=='__main__': main()
