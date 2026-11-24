#!/bin/sh
set -eu

TEMPLATE_FILE="${ALERTMANAGER_TEMPLATE_FILE:-/etc/alertmanager/alertmanager.yml}"
OUTPUT_FILE="${ALERTMANAGER_OUTPUT_FILE:-/tmp/alertmanager.yml}"
WEBHOOK_URL="${ALERTMANAGER_WEBHOOK_URL:-}"

# Reject whitespace-only values explicitly rather than treating them as unset
if [ -n "${ALERTMANAGER_WEBHOOK_URL+x}" ] && [ -n "$ALERTMANAGER_WEBHOOK_URL" ]; then
  stripped=$(printf '%s' "$ALERTMANAGER_WEBHOOK_URL" | tr -d '[:space:]')
  if [ -z "$stripped" ]; then
    echo "ERROR: ALERTMANAGER_WEBHOOK_URL is whitespace-only. Unset it or provide a valid http(s):// URL." >&2
    exit 1
  fi
fi

if [ -z "$WEBHOOK_URL" ]; then
  cp "$TEMPLATE_FILE" "$OUTPUT_FILE"
  echo "INFO: ALERTMANAGER_WEBHOOK_URL is not configured; external webhook delivery is disabled (alerts remain visible in Alertmanager UI/API)."
  exit 0
fi

# Reject newlines, control characters, whitespace, quotes, or backslashes
case "$WEBHOOK_URL" in
  http://*|https://*) ;;
  *)
    echo "ERROR: ALERTMANAGER_WEBHOOK_URL must start with http:// or https://." >&2
    exit 1
    ;;
esac

if printf '%s' "$WEBHOOK_URL" | grep -Eq '[[:space:]"\\]'; then
  echo "ERROR: ALERTMANAGER_WEBHOOK_URL contains whitespace, quotes, or backslashes." >&2
  exit 1
fi

if ! printf '%s' "$WEBHOOK_URL" | grep -Eq '^https?://[][A-Za-z0-9._~:/?#@!$&'\''()*+,;=%-]+$'; then
  echo "ERROR: ALERTMANAGER_WEBHOOK_URL contains invalid URL characters." >&2
  exit 1
fi

host_part=$(printf '%s' "$WEBHOOK_URL" | sed -E 's|^https?://([^/?#]+).*|\1|')
if [ -z "$host_part" ]; then
  echo "ERROR: ALERTMANAGER_WEBHOOK_URL must include a valid host." >&2
  exit 1
fi

# Render using awk string concatenation (never sed replacement) so '&' and '\' are preserved literally
awk '
  /# __WEBHOOK_SLOT__/ {
    print "    webhook_configs:"
    print "      - url: \"" ENVIRON["ALERTMANAGER_WEBHOOK_URL"] "\""
    print "        send_resolved: true"
    next
  }
  { print }
' "$TEMPLATE_FILE" > "$OUTPUT_FILE"

if command -v /bin/amtool >/dev/null 2>&1; then
  /bin/amtool check-config "$OUTPUT_FILE" >/dev/null
fi

echo "INFO: Alertmanager webhook receiver configured from ALERTMANAGER_WEBHOOK_URL."
