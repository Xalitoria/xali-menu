#!/usr/bin/env bash
# Sets up the live tests: a Folia server with the freshly built plugin, and a mineflayer bot that can join it.
#
#   menu/live/setup.sh            build what is needed and set everything up in $XM_DIR (default /tmp/xm-live)
#
# It needs node and npm, the java of the workspace (JAVA_HOME), and a dev server in run/folia (its Folia jar and
# libraries are copied: `./gradlew :run:...` or the workspace README says how that one is made).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
ws=$(cd "$here/../.." && pwd)
dir=${XM_DIR:-/tmp/xm-live}
port=${XM_PORT:-25599}
# The java of this machine; JAVA_HOME is used when it points at a JDK.
[ -x "${JAVA_HOME:-}/bin/java" ] || export JAVA_HOME=/opt/openjdk-bin-25

echo "== building the plugin and the libraries it loads at start"
(cd "$ws" && ./gradlew --offline -q :menu:paper:shadowJar :util:jar :color:jar :util:generatePomFileForMavenJavaPublication :color:generatePomFileForMavenJavaPublication)
plugin=$(ls "$ws"/build/libs/menu/xali-menu-*-paper.jar | head -1)
util_version=$(unzip -p "$plugin" loader.json | grep -o 'mc.xalitoria:util:[0-9.]*' | cut -d: -f3)
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
for item in folia-*.jar libraries versions cache config bukkit.yml spigot.yml; do
  [ -e "$server/$item" ] || cp -r "$src"/$item "$server"/
done
cp "$src"/server.properties "$server/server.properties"
sed -i "s/^online-mode=.*/online-mode=false/; s/^server-port=.*/server-port=$port/" "$server/server.properties"
echo "eula=true" > "$server/eula.txt"
cp "$plugin" "$server/plugins/"
ls "$src"/plugins/PlaceholderAPI-*.jar >/dev/null && cp "$src"/plugins/PlaceholderAPI-*.jar "$server/plugins/"
cp "$here"/menus/*.conf "$server/plugins/XaliMenu/menus/"

# The plugin asks for util $util_version, which is only published once released; give the server the one just built.
lib="$server/libraries/mc/xalitoria"
for module in util color; do
  jar=$(ls "$ws/$module/build/libs/xali-$module-"*.jar | head -1)
  version=$(basename "$jar" .jar); version=${version#xali-$module-}
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
