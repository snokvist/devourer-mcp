#!/usr/bin/env bash
# Assert that the numbers and lists the docs claim match the code.
#
# This exists because three of them were wrong at once: the test count (53 vs
# 63), the IRadio ratio (9/52 vs 11/52) — which the roadmap calls "the single
# most useful measure of what is left" — and a module table listing a
# `kotlin/ui/` that was never created. A project whose central discipline is
# "never assert what you have not measured" cannot hand-maintain its own
# metrics.
#
# Run it in CI and before any doc that quotes a number is edited.
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0
note() { printf '  %-46s %s\n' "$1" "$2"; }
bad()  { printf '  \033[31mFAIL\033[0m %-41s %s\n' "$1" "$2"; fail=1; }
ok()   { printf '  \033[32mok\033[0m   %-41s %s\n' "$1" "$2"; }

echo "== module table vs settings.gradle.kts =="
declared=$(grep -oE 'project\(":[a-z]+"\)\.projectDir = file\("kotlin/[a-z]+"\)' settings.gradle.kts \
           | grep -oE 'kotlin/[a-z]+' | sort -u)
for m in $declared; do
  grep -q "\`$m/\`" CLAUDE.md || bad "$m in build, missing from CLAUDE.md" ""
done
for m in $(grep -oE '`kotlin/[a-z]+/`' CLAUDE.md | tr -d '`' | sed 's:/$::' | sort -u); do
  echo "$declared" | grep -qx "$m" || bad "CLAUDE.md lists $m" "not in settings.gradle.kts"
done
[ $fail -eq 0 ] && ok "module table" "$(echo "$declared" | wc -l) modules agree"

echo "== Kotlin test count =="
actual=$(grep -rho '@Test' kotlin/*/src/test 2>/dev/null | wc -l)
for claim in $(grep -rhoE '[0-9]+ (Kotlin tests|offline tests)' docs/ CLAUDE.md 2>/dev/null | grep -oE '^[0-9]+' | sort -u); do
  [ "$claim" = "$actual" ] || bad "docs claim $claim tests" "actual $actual"
done
ok "test count" "$actual @Test across $(ls -d kotlin/*/src/test 2>/dev/null | wc -l) modules"

echo "== IRadio coverage =="
called=$(grep -ohE '_radio->[A-Za-z_]+' native/bridge/src/*.cpp | sed 's/.*>//' | sort -u | wc -l)
total=$(grep -c '^  virtual' vendor/devourer/src/IRadio.h)
# Look for a WRONG ratio rather than demanding an exact phrasing: the check
# should catch drift, not dictate prose. Line breaks inside a sentence made an
# exact-match version fail on correct docs, which is its own kind of wrong.
#
# Both numbers are checked. Watching only the numerator let "14 of 52" survive
# while IRadio gained three virtuals and the real ratio became 14 of 55.
wrong=""
while IFS= read -r claim; do
  [ -n "$claim" ] || continue
  n=$(printf '%s' "$claim" | grep -oE '^[0-9]+')
  m=$(printf '%s' "$claim" | grep -oE '[0-9]+ of [0-9]+' | grep -oE '[0-9]+$')
  [ "$n" = "$called" ] && { [ -z "$m" ] || [ "$m" = "$total" ]; } && continue
  wrong="$wrong ${n} of ${m:-?}"
done < <(grep -rhoE '[0-9]+ of ([0-9]+ )?`?IRadio' docs/ CLAUDE.md 2>/dev/null | sort -u)
if [ -n "$wrong" ]; then
  bad "docs claim IRadio coverage of$(echo "$wrong" | sed 's/  */ /g;s/^ / /')" \
      "actual ${called} of ${total}"
else
  ok "IRadio coverage" "${called} of ${total} methods"
fi

echo "== MCP tool count =="
tools=$(grep -c 'name = "' kotlin/mcp/src/main/kotlin/org/openipc/devourer/mcp/Tools.kt)
for claim in $(grep -rhoE '[0-9]+ MCP tools|[0-9]+ tools across' docs/ CLAUDE.md 2>/dev/null | grep -oE '^[0-9]+' | sort -u); do
  [ "$claim" = "$tools" ] || bad "docs claim $claim tools" "actual $tools"
done
ok "tool count" "$tools"

echo "== bridge ops documented =="
impl=$(grep -oE 'op == "[a-z._]+"' native/bridge/src/main.cpp | sed 's/op == //;s/"//g' | sort -u)
for o in $impl; do
  grep -q "\`$o\`" native/bridge/README.md || bad "op $o implemented" "undocumented in native/bridge/README.md"
done
[ $fail -eq 0 ] && ok "bridge ops" "$(echo "$impl" | wc -l) ops documented"

echo
[ $fail -eq 0 ] && echo "All doc claims match the code." || echo "Doc claims disagree with the code (above)."
exit $fail
