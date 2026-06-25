#!/usr/bin/env bash
set -euo pipefail

JMETER_VERSION="${JMETER_VERSION:-5.6.2}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${ROOT_DIR}/ci/smoke/out"
WORK_DIR="${OUT_DIR}/work"
JMETER_DIR="${WORK_DIR}/apache-jmeter-${JMETER_VERSION}"
JMETER_ARCHIVE="${WORK_DIR}/apache-jmeter-${JMETER_VERSION}.tgz"
JMX_FILE="${ROOT_DIR}/ci/smoke/smoke.jmx"
RESULTS_FILE="${OUT_DIR}/results-${JMETER_VERSION}.jtl"
LOG_FILE="${OUT_DIR}/jmeter-${JMETER_VERSION}.log"

mkdir -p "${OUT_DIR}" "${WORK_DIR}"

PLUGIN_JAR="$(ls -1 "${ROOT_DIR}"/target/jmeter-agent-*.jar | grep -vE 'sources|javadoc|original' | head -n 1 || true)"
if [[ -z "${PLUGIN_JAR}" ]]; then
  echo "Plugin JAR not found in target/."
  exit 1
fi

if [[ ! -d "${JMETER_DIR}" ]]; then
  curl -fsSL "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" -o "${JMETER_ARCHIVE}"
  tar -xzf "${JMETER_ARCHIVE}" -C "${WORK_DIR}"
fi

cp "${PLUGIN_JAR}" "${JMETER_DIR}/lib/ext/"

"${JMETER_DIR}/bin/jmeter" \
  -n \
  -t "${JMX_FILE}" \
  -l "${RESULTS_FILE}" \
  -j "${LOG_FILE}"

if [[ ! -s "${RESULTS_FILE}" ]]; then
  for _ in {1..10}; do
    sleep 0.5
    if [[ -s "${RESULTS_FILE}" ]]; then
      break
    fi
  done
  if [[ ! -s "${RESULTS_FILE}" ]]; then
    echo "No JTL results produced."
    exit 1
  fi
fi

if rg -n \
  -e "^[0-9]{4}-[0-9]{2}-[0-9]{2}.*\\sERROR\\s" \
  -e "(?i)stackoverflowerror" \
  -e "(?i)incompatibleclasschangeerror" \
  -e "(?i)groovyruntimeexception" \
  -e "(?i)conflicting module versions" \
  -e "^\\s*Caused by:\\s+.*Exception" \
  -e "^\\s*(java|javax|org)\\.[A-Za-z0-9_.$]+Exception" \
  "${LOG_FILE}" >/dev/null; then
  echo "Smoke test failed: error patterns detected in ${LOG_FILE}"
  rg -n \
    -e "^[0-9]{4}-[0-9]{2}-[0-9]{2}.*\\sERROR\\s" \
    -e "(?i)stackoverflowerror" \
    -e "(?i)incompatibleclasschangeerror" \
    -e "(?i)groovyruntimeexception" \
    -e "(?i)conflicting module versions" \
    -e "^\\s*Caused by:\\s+.*Exception" \
    -e "^\\s*(java|javax|org)\\.[A-Za-z0-9_.$]+Exception" \
    "${LOG_FILE}" || true
  exit 1
fi

echo "Smoke test passed for JMeter ${JMETER_VERSION}."
