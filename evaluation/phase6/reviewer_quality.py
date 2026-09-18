"""Score pinned review outputs against explicit, hash-bound human/fixture judgments.

Matching a source line is NOT treated as proof of a correct finding. Without judgments,
semantic quality is unavailable. Default inputs are deliberately labeled fixtures.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def ratio(numerator, denominator):
    return numerator / denominator if denominator else None

def grounded(finding, files):
    if not isinstance(finding, dict):
        return False
    evidence = finding.get('evidence')
    if not isinstance(evidence, list) or not 1 <= len(evidence) <= 3:
        return False
    for e in evidence:
        if not isinstance(e, dict) or e.get('path') not in files:
            return False
        start, end = e.get('startLine'), e.get('endLine')
        lines = files[e['path']].splitlines()
        if type(start) is not int or type(end) is not int or start < 1 or end < start or end > len(lines) or end-start >= 20:
            return False
        if not isinstance(e.get('snippet'), str) or not e['snippet'].strip() or '\n'.join(lines[start-1:end]) != e['snippet']:
            return False
    return True

def score(dataset, reports, judgments=None):
    cases = {c['id']: c for c in dataset['cases']}
    if len(cases) != len(dataset['cases']): raise ValueError('Duplicate dataset case')
    received = reports['cases']
    if len(received) != len(cases) or {c['caseId'] for c in received} != set(cases):
        raise ValueError('Reports must include each dataset case exactly once, including empty results')
    candidates, valid = {}, 0
    gold = {(c['id'], g['id']): g for c in cases.values() for g in c['gold']}
    if len(gold) != sum(len(c['gold']) for c in cases.values()): raise ValueError('Duplicate gold identity')
    for case in received:
        if not isinstance(case.get('findings'), list): raise ValueError('Findings must be a list')
        for index, finding in enumerate(case['findings']):
            if not isinstance(finding, dict): raise ValueError('Each finding must be an object')
            key = (case['caseId'], index)
            candidates[key] = finding
            valid += grounded(finding, {f['path']: f['content'] for f in cases[case['caseId']]['files']})
    result = {'candidates': len(candidates), 'groundedCandidates': valid, 'groundingRate': ratio(valid, len(candidates)),
              'semanticQualityStatus': 'UNAVAILABLE_WITHOUT_ADJUDICATION', 'precision': None, 'recall': None,
              'falseDiscoveryRate': None, 'severityAccuracy': None, 'actionableRate': None, 'byKind': {}}
    if judgments is None: return result
    entries = judgments['judgments']
    if any(type(j.get('candidateIndex')) is not int or j['candidateIndex'] < 0 for j in entries):
        raise ValueError('Candidate indices must be non-negative integers')
    keys = [(j['caseId'], j['candidateIndex']) for j in entries]
    if len(set(keys)) != len(keys) or set(keys) != set(candidates): raise ValueError('Every candidate needs exactly one judgment')
    matched, tp, actionable, severity = set(), 0, 0, 0
    tp_by_kind = {'IMPROVEMENT': 0, 'GOOD_PRACTICE': 0}
    for j in entries:
        key = (j['caseId'], j['candidateIndex']); finding = candidates[key]
        if any(type(j.get(field)) is not bool for field in ('correct', 'actionable', 'severityCorrect')):
            raise ValueError('Judgment flags must be booleans')
        identity = (j['caseId'], j.get('goldId'))
        if j['correct'] and (identity not in gold or gold[identity]['kind'] != finding.get('kind')):
            raise ValueError('Correct judgments must identify a gold finding of the same kind in this case')
        if not j['correct'] and j.get('goldId') is not None: raise ValueError('False findings cannot claim a gold match')
        if j['correct'] and identity not in matched:
            matched.add(identity); tp += 1; tp_by_kind[finding['kind']] += 1
            actionable += j['actionable']; severity += j['severityCorrect']
    result.update(semanticQualityStatus='ADJUDICATED', truePositives=tp, falsePositives=len(candidates)-tp,
                  falseNegatives=len(gold)-tp, precision=ratio(tp,len(candidates)), recall=ratio(tp,len(gold)),
                  falseDiscoveryRate=ratio(len(candidates)-tp,len(candidates)), severityAccuracy=ratio(severity,tp),
                  actionableRate=ratio(actionable,len(candidates)))
    for kind in tp_by_kind:
        result['byKind'][kind]={'precision':ratio(tp_by_kind[kind],sum(f.get('kind')==kind for f in candidates.values())),
                                'recall':ratio(tp_by_kind[kind],sum(g['kind']==kind for g in gold.values()))}
    return result

def evaluate(dataset_path, reports_path, judgments_path=None):
    if digest(dataset_path) != Path(dataset_path).with_suffix('.sha256').read_text().strip(): raise ValueError('Dataset pin mismatch')
    dataset = json.loads(Path(dataset_path).read_text()); reports = json.loads(Path(reports_path).read_text())
    if reports.get('datasetSha256') != digest(dataset_path): raise ValueError('Reports use another dataset')
    judgments = None
    if judgments_path:
        judgments = json.loads(Path(judgments_path).read_text())
        if judgments.get('datasetSha256') != digest(dataset_path) or judgments.get('reportsSha256') != digest(reports_path):
            raise ValueError('Judgments do not match these exact reports and dataset')
        if not judgments.get('adjudicator'): raise ValueError('Judgments must identify their adjudicator')
    return {'datasetSha256':digest(dataset_path), 'reportsSha256':digest(reports_path), 'origin':reports.get('origin','UNSPECIFIED'),
            'provider':reports.get('provider'), 'model':reports.get('model'), 'adjudicator':judgments.get('adjudicator') if judgments else None,
            'metrics':score(dataset,reports,judgments), 'limitations':['Grounding checks location only; semantic scores require supplied judgments.',
            'Curated fixture scores are not live-model quality estimates. Small datasets do not establish general reviewer performance.']}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--dataset',type=Path,default=ROOT/'review-quality-v1.json')
    parser.add_argument('--reports',type=Path,default=ROOT/'reviewer-fixture-reports.json')
    parser.add_argument('--judgments',type=Path,help='Omit for grounding-only evaluation; semantic metrics remain null')
    parser.add_argument('--fixture-check',action='store_true',help='Run only the bundled fixture regression with its explicit annotations')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.fixture_check:
        if args.reports != ROOT/'reviewer-fixture-reports.json' or args.dataset != ROOT/'review-quality-v1.json' or args.judgments:
            parser.error('--fixture-check cannot be mixed with custom inputs')
        args.judgments=ROOT/'reviewer-fixture-judgments.json'
    result=evaluate(args.dataset,args.reports,args.judgments)
    if args.fixture_check:
        m=result['metrics']
        assert m['truePositives']==5 and m['falsePositives']==1 and m['recall']==1.0
        assert m['groundedCandidates']==5 and m['byKind']['GOOD_PRACTICE']['recall']==1.0
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result['metrics']))
if __name__=='__main__': main()
