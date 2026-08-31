# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 1/2 (50.0%)
- **Function parity:** 40/72 matched (target 61) — 55.6%
- **Class/type parity:** 4/4 matched (target 23) — 100.0%
- **Combined symbol parity:** 44/76 matched (target 84) — 57.9%
- **Average inline-code cosine:** 0.00 (function body across 0 matched files)
- **Average documentation cosine:** 0.00 (doc text across 0 matched files)
- **Cheat-zeroed Files:** 1
- **Critical Issues:** 1 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. pkg-config.lib

- **Target:** `pkgconfig.Lib [STUB]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 44810.0
- **Functions:** 40/44 matched (target 61)
- **Missing functions:** `fmt`, `run`, `default`, `test_library_filename`
- **Types:** 4/4 matched (target 23)
- **Missing types:** _none_
- **Tests:** 6/7 matched

## Success Criteria

For each file to be considered "complete":
- **Similarity ≥ 0.85** (Excellent threshold)
- All public APIs ported
- All tests ported
- Documentation ported
- port-lint header present

