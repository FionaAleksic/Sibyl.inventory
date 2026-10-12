#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf build dist
mkdir -p build/classes build/tests build/stage dist
javac --release 21 -Xlint:all -d build/classes src/main/java/org/sibyl/addons/inventory/InventorySnapshot.java
javac --release 21 -Xlint:all -cp build/classes -d build/tests src/test/java/org/sibyl/addons/inventory/InventorySnapshotTest.java
java -ea -cp build/classes:build/tests org.sibyl.addons.inventory.InventorySnapshotTest
jar --create --file dist/sibyl-inventory-0.1.0-rc.2.jar -C build/classes .
cp module.json deployment.json config.schema.json LICENSE README.md build/stage/
cp -r ui build/stage/
cp dist/sibyl-inventory-0.1.0-rc.2.jar build/stage/
(cd build/stage && sha256sum module.json deployment.json config.schema.json ui/index.html sibyl-inventory-0.1.0-rc.2.jar > SHA256SUMS.txt)
(cd build/stage && zip -q -X -r ../../dist/Sibyl.inventory-v0.1.0-rc.2.zip .)
python3 - <<'PY'
import zipfile,json,hashlib
p='dist/Sibyl.inventory-v0.1.0-rc.2.zip'
with zipfile.ZipFile(p) as z:
 m=json.loads(z.read('module.json'))
 assert m['id']=='Sibyl.inventory' and m['version']=='0.1.0-rc.2'
 for line in z.read('SHA256SUMS.txt').decode().splitlines():
  sha,name=line.split(maxsplit=1);assert hashlib.sha256(z.read(name)).hexdigest()==sha
print('Release archive verified')
PY
(cd dist && sha256sum Sibyl.inventory-v0.1.0-rc.2.zip > Sibyl.inventory-v0.1.0-rc.2.zip.sha256)
echo 'BUILD_OK Sibyl.inventory v0.1.0-rc.2'
