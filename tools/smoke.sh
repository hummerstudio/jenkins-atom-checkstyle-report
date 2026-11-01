#!/usr/bin/env bash
# checkstyle errors fail the default gate; PMD priorities and SpotBugs priority mapping come next.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# 1. checkstyle: two errors and one warning -> default maxErrors=0 fails the atom
dir="$(mktemp -d)"; mkdir -p "$dir/build/reports/checkstyle"
cat >"$dir/build/reports/checkstyle/main.xml" <<'XML'
<?xml version="1.0"?>
<checkstyle version="10.0">
  <file name="src/main/java/A.java">
    <error line="3" severity="error" message="Missing a Javadoc comment."/>
    <error line="7" severity="error" message="Line is longer than 120 characters."/>
    <error line="9" severity="warning" message="Parameter x should be final."/>
  </file>
</checkstyle>
XML
printf '{"pattern": "**/checkstyle-result.xml, **/checkstyle/*.xml", "label": "Static analysis"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 && fail "two errors with maxErrors=0 must fail"
[ "$(status "$dir")" = "failure" ] || fail "status: $(cat "$dir/output.json")"
[ "$(output "$dir" errors)" = "2" ] || fail "errors: $(cat "$dir/output.json")"
[ "$(output "$dir" warnings)" = "1" ] || fail "warnings: $(cat "$dir/output.json")"
[ -f "$dir/out/static-analysis/index.html" ] || fail "no report"
grep -q "Line is longer" "$dir/out/static-analysis/index.html" || fail "report misses a finding"

# 2. raising the limit turns the same input green
dir="$(mktemp -d)"; mkdir -p "$dir/reports"
cat >"$dir/reports/checkstyle.xml" <<'XML'
<checkstyle version="10.0"><file name="A.java"><error line="1" severity="error" message="e"/></file></checkstyle>
XML
printf '{"pattern": "reports/checkstyle.xml", "maxErrors": "5"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "maxErrors=5 with one error must pass: $(cat "$dir/output.json")"
[ "$(output "$dir" errors)" = "1" ] || fail "error count"

# 3. PMD priorities: 1 -> error, 2 -> warning, 3 -> info
dir="$(mktemp -d)"; mkdir -p "$dir/reports"
cat >"$dir/reports/pmd.xml" <<'XML'
<pmd version="7.0"><file name="B.java">
  <error line="1" priority="1" message="high"/>
  <error line="2" priority="2" message="medium"/>
  <error line="3" priority="3" message="low"/>
</file></pmd>
XML
printf '{"pattern": "reports/pmd.xml", "maxErrors": "2"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "pmd run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" errors)" = "1" ] || fail "pmd priority 1 -> error: $(cat "$dir/output.json")"
[ "$(output "$dir" warnings)" = "1" ] || fail "pmd priority 2 -> warning"
[ "$(output "$dir" infos)" = "1" ] || fail "pmd priority 3 -> info"

# 4. SpotBugs priority mapping
dir="$(mktemp -d)"; mkdir -p "$dir/reports"
cat >"$dir/reports/spotbugsXml.xml" <<'XML'
<BugCollection><BugInstance type="NP_NULL_ON_SOME_PATH" category="CORRECTNESS" priority="1">
  <Class classname="demo.C"/><SourceLine sourcepath="demo/C.java" start="12"/></BugInstance>
</BugCollection>
XML
printf '{"pattern": "reports/spotbugsXml.xml", "maxErrors": "0"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a priority-1 bug must trip the default gate"
grep -q "NP_NULL_ON_SOME_PATH" "$dir/out/static-analysis/index.html" || fail "spotbugs rule not in the report"

# 5. no reports is a clear error
dir="$(mktemp -d)"; printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "no reports must fail"
grep -q "no report matched" "$dir/output.json" || fail "message not readable"

# a report nobody could parse is not a report without violations
dir="$(mktemp -d)"
printf '<?xml version="1.0"?><checkstyle version="10.0"><file name="a.py"><error line="1" severity="warning" message="w"/></file></checkstyle>' >"$dir/good.xml"
printf '<?xml version="1.0"?><checkstyle version="10.0"><file name="b.py"><error line="1" sever' >"$dir/half.xml"
printf '{"pattern": "*.xml", "maxErrors": "0"}' >"$dir/input.json"
if run "$dir" >/dev/null 2>&1; then fail "a truncated checkstyle report must fail the step"; fi
grep -q '"errorCode":40003' "$dir/output.json" || fail "expected errorCode 40003: $(cat "$dir/output.json")"
grep -q 'half.xml' "$dir/output.json" || fail "the failure must name the file: $(cat "$dir/output.json")"
printf '{"pattern": "*.xml", "maxErrors": "0", "allowUnreadableReports": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "allowUnreadableReports should let it through: $(cat "$dir/output.json")"

# cppcheck writes <results><errors><error id severity msg file0 line/>: different attribute names, same meaning
dir="$(mktemp -d)"
printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' '<results version="2"><cppcheck version="2.13"/><errors>' \
  '<error id="nullPointer" severity="error" msg="Null pointer dereference: p" file0="src/main.c" line="42"/>' \
  '<error id="unusedVariable" severity="style" msg="Unused variable: x" file0="src/main.c" line="7"/>' \
  '<error id="arrayIndexOutOfBounds" severity="warning" msg="Index out of bounds" file0="src/util.c" line="13"/>' \
  '</errors></results>' >"$dir/cppcheck.xml"
printf '{"pattern": "*.xml", "maxErrors": "0"}' >"$dir/input.json"
if run "$dir" >/dev/null 2>&1; then fail "an error-level cppcheck finding must fail maxErrors=0"; fi
grep -q '"total":{"type":"string","value":"3"}' "$dir/output.json" || fail "cppcheck total: $(cat "$dir/output.json")"
grep -q '"errors":{"type":"string","value":"1"}' "$dir/output.json" || fail "cppcheck errors: $(cat "$dir/output.json")"
grep -q '"warnings":{"type":"string","value":"1"}' "$dir/output.json" || fail "cppcheck warnings: $(cat "$dir/output.json")"
grep -q '"infos":{"type":"string","value":"1"}' "$dir/output.json" || fail "cppcheck infos (style -> info): $(cat "$dir/output.json")"
# 明细在报告里，不在 output.json：失败结果只带计数
grep -q 'src/main.c' "$dir/out/static-analysis/index.html" || fail "file0 was not used as the location"
grep -q 'Null pointer' "$dir/out/static-analysis/index.html" || fail "msg was not used as the message"

echo "smoke ok: checkstyle gate, raised limit, pmd priorities, spotbugs priority, no reports, truncated report fails by default, cppcheck"
