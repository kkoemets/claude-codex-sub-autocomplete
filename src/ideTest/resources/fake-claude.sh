#!/bin/sh

if [ "$1" = "auth" ] && [ "$2" = "status" ]; then
  printf '%s\n' '{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}'
  exit 0
fi

prompt=$(cat)
case "$prompt" in
  *"File: media-demo.ts"*) completion='urn users\n    .filter((user) => user.active)\n    .map((user) => user.name)\n    .sort((left, right) => left.localeCompare(right))\n}' ;;
  *"File: sample.ts"*) completion='install dependencies' ;;
  *"File: sample.json"*) completion=',' ;;
  *"File: sample.js"*) completion=' = (value) => value * 2' ;;
  *"File: sample.py"*) completion=' = lambda value: value * 2' ;;
  *"File: sample.sh"*) completion='=production' ;;
  *"File: Sample.java"*) completion='install dependencies' ;;
  *"File: Sample.kt"*) completion='install dependencies' ;;
  *"File: docker-compose.yml"*) completion='install dependencies' ;;
  *"File: sample.sql"*) completion='install dependencies' ;;
  *"File: sample.html"*) completion='install dependencies -->' ;;
  *"File: Dockerfile"*) completion='install dependencies' ;;
  *) completion='' ;;
esac

printf '{"type":"result","subtype":"success","is_error":false,"result":"%s"}\n' "$completion"
