#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${APP_ENV:-development}"
