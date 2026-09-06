#!/bin/sh

if [ "$1" = "auth" ] && [ "$2" = "status" ]; then
  printf '%s\n' '{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}'
  exit 0
fi

prompt=$(cat)
case "$prompt" in
  *"autocomplete terminal compatibility check"*)
    printf '%s\n' requested >> @TERMINAL_REQUEST_LOG@
    completion=@TERMINAL_COMMAND_JSON@
    ;;
  *"File: media-demo.ts"*) completion='urn users\n    .filter((user) => user.active)\n    .map((user) => user.name)\n    .sort((left, right) => left.localeCompare(right))\n}' ;;
  *"File: sample.ts"*) completion='install dependencies' ;;
  *"File: sample.json"*) completion=',' ;;
  *"File: sample.js"*) completion=' = (value) => value * 2' ;;
  *"File: sample.py"*) completion=' = lambda value: value * 2' ;;
  *"File: sample.sh"*) completion='=production' ;;
  *"File: Sample.java"*) completion='install dependencies' ;;
  *"File: Sample.kt"*) completion='e * 2' ;;
  *"File: docker-compose.yml"*) completion='install dependencies' ;;
  *"File: sample.sql"*) completion='install dependencies' ;;
  *"File: sample.html"*) completion='install dependencies -->' ;;
  *"File: sample.xml"*) completion='omplete</string>' ;;
  *"File: Dockerfile"*) completion='install dependencies' ;;
  *) completion='' ;;
esac

# Keep editor generation visible long enough to inspect its loading state on
# the private test display, including the emulated Android Studio guest.
case "$prompt" in
  *"autocomplete terminal compatibility check"*) ;;
  *)
    sleep 2
    # The first automatic editor request stays pending throughout its screenshot.
    # Fail if the harness never releases it, within the provider's 15s timeout.
    loading_wait=0
    while [ -f @EDITOR_LOADING_GATE@ ]; do
      loading_wait=$((loading_wait + 1))
      [ "$loading_wait" -le 100 ] || exit 70
      sleep 0.1
    done
    ;;
esac

printf '{"type":"result","subtype":"success","is_error":false,"result":"%s"}\n' "$completion"
