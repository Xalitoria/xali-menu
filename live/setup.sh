#!/usr/bin/env bash
# Sets up the live tests: a Folia server with the freshly built plugin, and a mineflayer bot that can join it.
#
#   menu/live/setup.sh            build what is needed and set everything up in $XM_DIR (default /tmp/xm-live)
#
# It needs node and npm, the java of the workspace (JAVA_HOME), and a dev server in run/folia (its Folia jar and
# libraries are copied, or XM_PAPER=<paper jar> uses that one instead; the dev server's server.properties and plugins are still used: `./gradlew :run:...` or the workspace README says how that one is made).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
ws=$(cd "$here/../.." && pwd)
dir=${XM_DIR:-/tmp/xm-live}
port=${XM_PORT:-25599}
# The java of this machine; JAVA_HOME is used when it points at a JDK.
[ -x "${JAVA_HOME:-}/bin/java" ] || export JAVA_HOME=/opt/openjdk-bin-25

echo "== building the plugin and the libraries it loads at start"
(cd "$ws" && ./gradlew --offline -q :menu:paper:shadowJar :util:jar :color:jar :util:generatePomFileForMavenJavaPublication :color:generatePomFileForMavenJavaPublication)
# The newest jar: older versions may still be there from earlier builds.
plugin=$(ls -t "$ws"/build/libs/menu/xali-menu-*-paper.jar | head -1)
util_version=$(unzip -p "$plugin" loader.json | grep -o 'com.xalitoria:util:[0-9.]*' | cut -d: -f3)
echo "plugin: $plugin (loads util $util_version)"

echo "== the bot"
mkdir -p "$dir/bot"
cp "$here"/bot/* "$dir/bot/"
(cd "$dir/bot" && [ -d node_modules/mineflayer ] || { npm init -y >/dev/null; npm i mineflayer >/dev/null; })
# mineflayer knows protocol 775 (26.1) but Folia 26.2 speaks 776: give the 26.1 data that number, and move 26.2's out of the way.
node - "$dir/bot/node_modules/minecraft-data/minecraft-data/data/pc" <<'JS'
const fs = require('fs'), path = require('path')
const root = process.argv[2]
const file = path.join(root, 'common/protocolVersions.json')
const all = JSON.parse(fs.readFileSync(file))
for (const v of all) {
  if (v.minecraftVersion === '26.2' && v.version === 776) v.version = 7760
  else if (v.minecraftVersion === '26.1' && v.version === 775) v.version = 776
}
fs.writeFileSync(file, JSON.stringify(all, null, 2))
const own = path.join(root, '26.1/version.json')
const version = JSON.parse(fs.readFileSync(own))
version.version = 776
fs.writeFileSync(own, JSON.stringify(version, null, 2))
JS

echo "== the server"
server="$dir/server"
mkdir -p "$server/plugins/XaliMenu/menus"
src="$ws/run/folia"
[ -d "$src" ] || { echo "no dev server in $src to copy the Folia jar from" >&2; exit 1; }
if [ -n "${XM_PAPER:-}" ]; then
  # A Paper jar instead (for plugins that don't run on Folia): it fetches its own libraries on the first start.
  cp "$XM_PAPER" "$server/"
else
  for item in folia-*.jar libraries versions cache config bukkit.yml spigot.yml; do
    [ -e "$server/$item" ] || cp -r "$src"/$item "$server"/
  done
fi
cp "$src"/server.properties "$server/server.properties"
sed -i "s/^online-mode=.*/online-mode=false/; s/^server-port=.*/server-port=$port/" "$server/server.properties"
echo "eula=true" > "$server/eula.txt"
cp "$plugin" "$server/plugins/"
ls "$src"/plugins/PlaceholderAPI-*.jar >/dev/null && cp "$src"/plugins/PlaceholderAPI-*.jar "$server/plugins/"
# Other plugins of the dev server to try the menus with, by the start of their jar's name: XM_PLUGINS="FancyNpcs SkinsRestorer".
for name in ${XM_PLUGINS:-}; do cp "$src"/plugins/"$name"*.jar "$server/plugins/"; done
# XM_ADDON=1 also builds addon/XmDemo.java, a plugin in Java that adds an activator (bot/addon.js uses it).
if [ -n "${XM_ADDON:-}" ]; then
  # The jars of the Gradle cache the API classes of the plugin come from (the newest of each), without their sources.
  libs=$(for name in paper-api adventure-api adventure-key examination-api jspecify annotations bungeecord-chat; do
    find "$HOME/.gradle/caches/modules-2" -name "$name-[0-9]*.jar" ! -name '*sources*' | sort | tail -1
  done | paste -sd:)
  classes=$(mktemp -d)
  "$JAVA_HOME/bin/javac" -d "$classes" -cp "$libs:$plugin" "$here"/addon/XmDemo.java
  cp "$here"/addon/plugin.yml "$classes/"
  (cd "$classes" && "$JAVA_HOME/bin/jar" cf "$server/plugins/XmDemo.jar" .)
  rm -rf "$classes"
fi
# XM_EDITOR=1 turns the web editor's endpoint on (port XM_EDITOR_PORT, default 8123; XM_EDITOR_PACK is the pack zip to show).
# XM_PACK_URL=<url> sets editor.pack-url, the address the pack is hosted at.
# XM_RELAY=<url> sets editor.relay.url, for /xmenu share (run the relay with `node menu/relay/relay.js`).
# XM_GLYPHS=<folder> copies a glyphs folder of Nexo or Oraxen to the server, for the glyph names the editor lists.
if [ -n "${XM_EDITOR:-}${XM_RELAY:-}" ]; then
  mkdir -p "$server/plugins/XaliMenu"
  {
    printf 'editor:\n  enabled: %s\n  port: %s\n  pack: "%s"\n' "$([ -n "${XM_EDITOR:-}" ] && echo true || echo false)" "${XM_EDITOR_PORT:-8123}" "${XM_EDITOR_PACK:-}"
    [ -z "${XM_PACK_URL:-}" ] || printf '  pack-url: "%s"\n' "$XM_PACK_URL"
    [ -z "${XM_RELAY:-}" ] || printf '  relay:\n    url: "%s"\n' "$XM_RELAY"
  } > "$server/plugins/XaliMenu/config.yml"
fi
if [ -n "${XM_GLYPHS:-}" ]; then mkdir -p "$server/plugins/Nexo" && cp -r "$XM_GLYPHS" "$server/plugins/Nexo/glyphs"; fi
cp "$here"/menus/*.conf "$server/plugins/XaliMenu/menus/"

# The plugin asks for util $util_version, which is only published once released; give the server the one just built.
lib="$server/libraries/com/xalitoria"
for module in util color; do
  if [ "$module" = util ]; then
    version=$util_version
  else
    # The version of color that util's own pom asks for.
    version=$(python3 - "$ws/util/build/publications/mavenJava/pom-default.xml" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
print(re.search(r'<artifactId>color</artifactId>\s*<version>([^<]+)</version>', text).group(1))
PY
)
  fi
  # The jars of the modules are collected in build/libs/<module> of the workspace, not in the module's own build folder.
  jar="$ws/build/libs/$module/xali-$module-$version.jar"
  [ -f "$jar" ] || { echo "no $jar: the workspace did not build $module $version" >&2; exit 1; }
  target="$lib/$module/$version"
  mkdir -p "$target"
  cp "$jar" "$target/$module-$version.jar"
  # Only the artifact's own <version> (the first one) is renamed; the dependencies keep theirs.
  python3 - "$ws/$module/build/publications/mavenJava/pom-default.xml" "$target/$module-$version.pom" "$version" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
open(sys.argv[2], 'w').write(re.sub(r'<version>[^<]*</version>', '<version>%s</version>' % sys.argv[3], text, count=1))
PY
  printf '%s-%s.jar>xalitoria-releases=\n%s-%s.pom>xalitoria-releases=\n' "$module" "$version" "$module" "$version" > "$target/_remote.repositories"
done
echo "done: $dir. Start the server with menu/live/server.sh start, run a bot with node $dir/bot/act.js"
