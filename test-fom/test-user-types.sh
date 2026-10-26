#!/usr/bin/env bash
# Tests de non-regression des types "a responsabilite du developpeur" (rprfom-codegen).
#
# Usage (depuis n'importe ou) :  bash test-fom/test-user-types.sh
# Prerequis : bash, javac/java (JDK 17+), g++ (C++17), python3 ; projet compile (mvn package -> target/classes).
# Variables optionnelles :
#   CLASSES   classpath du generateur         (defaut : <projet>/target/classes ; sinon compile src/main dans un dossier temporaire)
#   WORK      dossier de travail temporaire   (defaut : /tmp/rprfom-tests)
#   BASE_DIR  dossier contenant les sorties de reference d'un generateur AVANT la fonctionnalite,
#             sous-dossiers federe/{java,cpp} et complet/{java,cpp} ; absent = scenario [A] ignore.
set -u
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJ"
W="${WORK:-/tmp/rprfom-tests}"; rm -rf "$W"; mkdir -p "$W"
CLS="${CLASSES:-$PROJ/target/classes}"
if [ ! -f "$CLS/rprfom/codegen/Main.class" ]; then
  echo "target/classes absent : compilation directe des sources dans $W/cls"
  mkdir -p "$W/cls" && javac -nowarn -d "$W/cls" $(find src/main/java -name '*.java') 2>&1 | grep -v JAVA_TOOL
  cp -r src/main/resources/. "$W/cls/"; CLS="$W/cls"
fi
FOM="--fom FOM/RPR_FOM_v2.0_1516-2010.xml"; FED="federate/FedereAircraftCollision.xml"
EX=examples/user-types; WJ=$EX/java/WorldLocationStruct.java
PASS=0; FAIL=0
gen() { java -cp "$CLS" rprfom.codegen.Main "$@" > $W/out.txt 2>&1; RC=$?; }
ok() { if eval "$2"; then PASS=$((PASS+1)); echo "  OK   $1"; else FAIL=$((FAIL+1)); echo "  FAIL $1"; fi; }
has() { grep -q -- "$1" $W/out.txt; }
jc() { rm -rf $W/jc && mkdir $W/jc && javac -nowarn -d $W/jc $(find $1/src -name '*.java') > $W/jc.txt 2>&1; grep -v JAVA_TOOL $W/jc.txt | grep -q "error" && return 1; return 0; }
jt() { java -cp $W/jc $2.conformance.UserTypesConformanceCheck > $W/jt.txt 2>&1; }
U="--user-type WorldLocationStruct --user-types-java $EX/java --user-types-cpp $EX/cpp"

echo "[A] Non-regression sans option (octets identiques a la reference)"
if [ -n "${BASE_DIR:-}" ] && [ -d "$BASE_DIR/federe" ]; then
gen $FOM --federate-fom $FED --out-java $W/a/j --out-cpp $W/a/c
ok "A1 federe: identique reference (hors manifeste)" "diff -rq $BASE_DIR/federe/java $W/a/j | grep -v manifest | wc -l | grep -qx 0 && diff -rq $BASE_DIR/federe/cpp $W/a/c | grep -v manifest | wc -l | grep -qx 0"
gen $FOM --out-java $W/a2/j --out-cpp $W/a2/c
ok "A2 FOM complete: identique reference" "diff -rq $BASE_DIR/complet/java $W/a2/j | grep -v manifest | wc -l | grep -qx 0 && diff -rq $BASE_DIR/complet/cpp $W/a2/c | grep -v manifest | wc -l | grep -qx 0"
else echo "  (ignore : BASE_DIR non defini)"; fi

echo "[B] Cas nominaux"
gen $FOM --out-java $W/b1/j --out-cpp $W/b1/c --user-type WorldLocationStruct --user-type LinearSegmentStruct --user-type VariableDatumStruct --user-type MarkingStruct
ok "B1 secours seul: exit 0" "[ $RC -eq 0 ]"
jc $W/b1/j; ok "B1 Java compile" "[ $? -eq 0 ]"
jt x rprfom; ok "B1 test Java OK (4 types)" "grep -q 'OK (4 type' $W/jt.txt"
g++ -std=c++17 -Wall -Werror -I$W/b1/c/include $W/b1/c/test/UserTypesConformance.cpp -o $W/b1/t > $W/gpp.txt 2>&1; ok "B1 C++ compile -Wall -Werror" "[ $? -eq 0 ]"
$W/b1/t > $W/ct.txt 2>&1; ok "B1 test C++ OK" "[ $? -eq 0 ] && grep -q 'OK' $W/ct.txt"
gen $FOM --out-java $W/b2/j --out-cpp $W/b2/c --user-type ArticulatedParameterStruct --user-type NamedLocationStruct --user-type EnvironmentRecStruct
ok "B2 variants: exit 0" "[ $RC -eq 0 ]"
jc $W/b2/j; ok "B2 Java compile" "[ $? -eq 0 ]"; jt x rprfom; ok "B2 test Java OK" "grep -q 'OK (3 type' $W/jt.txt"
g++ -std=c++17 -Wall -I$W/b2/c/include $W/b2/c/test/UserTypesConformance.cpp -o $W/b2/t > $W/gpp.txt 2>&1; ok "B2 C++ compile" "[ $? -eq 0 ]"; $W/b2/t >/dev/null 2>&1; ok "B2 test C++ OK" "[ $? -eq 0 ]"
gen $FOM --out-java $W/b3/j --out-cpp $W/b3/c $U --user-type LinearSegmentStruct
ok "B3 custom+secours: CUSTOM/SECOURS" "has 'WorldLocationStruct  CUSTOM      CUSTOM' && has 'LinearSegmentStruct  SECOURS     SECOURS'"
jc $W/b3/j; ok "B3 Java compile" "[ $? -eq 0 ]"; jt x rprfom; ok "B3 test Java OK" "grep -q 'OK (2 type' $W/jt.txt"
g++ -std=c++17 -Wall -I$W/b3/c/include $W/b3/c/test/UserTypesConformance.cpp -o $W/b3/t > $W/gpp.txt 2>&1; $W/b3/t >/dev/null 2>&1; ok "B3 C++ compile+test" "[ $? -eq 0 ]"
gen $FOM --out-java $W/b4/j --out-cpp $W/b4/c --user-types-config $EX/user-types.xml
ok "B4 config XML: 3 types, exit 0" "[ $RC -eq 0 ] && has 'WorldLocationStruct  CUSTOM' && has 'MarkingStruct        SECOURS'"
jc $W/b4/j; ok "B4 Java compile" "[ $? -eq 0 ]"; jt x rprfom; ok "B4 test Java OK" "grep -q 'OK (3 type' $W/jt.txt"

echo "[C] Mutations (le test de conformité doit ECHOUER)"
export W PROJ
mut() { mkdir -p $W/m$1; python3 - "$1" <<'PY'
import sys,re,os
n=sys.argv[1]; s=open(os.environ['PROJ']+'/examples/user-types/java/WorldLocationStruct.java').read()
if n=='1': s=re.sub(r'appendFloat64BE\(b, v\.([xyz])\)',r'appendFloat32BE(b, (float) v.\1)',s)
if n=='2': s='\n'.join(l for l in s.split('\n') if 'appendFloat64BE(b, v.z)' not in l)
if n=='3': s='\n'.join(l for l in s.split('\n') if 'v.z = HLADecoding' not in l)
if n=='4': s=s.replace("HLAEncoding.appendFloat64BE(b, v.x);\n        HLAEncoding.appendFloat64BE(b, v.y);","HLAEncoding.appendFloat64BE(b, v.y);\n        HLAEncoding.appendFloat64BE(b, v.x);")
open(os.environ['W']+'/m%s/WorldLocationStruct.java'%n,'w').write(s)
PY
gen $FOM --out-java $W/m$1/j --out-cpp $W/m$1/c --user-type-java WorldLocationStruct=$W/m$1/WorldLocationStruct.java; jc $W/m$1/j; jt x rprfom; ok "C$1 mutation detectee" "grep -q 'ECHEC conformite' $W/jt.txt"; }
mut 1; mut 2; mut 3; mut 4

echo "[D] Garde-fous à la génération"
sed 's/80bf448eeef3f1f4/0000000000000000/' $WJ > $W/stale.java
gen $FOM --out-java $W/d1/j --out-cpp $W/d1/c --user-type-java WorldLocationStruct=$W/stale.java
ok "D1 empreinte perimee: exit 1, rien ecrit" "[ $RC -eq 1 ] && has 'COPIE PERIMEE' && [ ! -e $W/d1 ]"
gen $FOM --out-java $W/d1b/j --out-cpp $W/d1b/c --allow-stale-user-types --user-type-java WorldLocationStruct=$W/stale.java
ok "D1b --allow-stale: exit 0 + avertissement" "[ $RC -eq 0 ] && has 'tolere par --allow-stale'"
grep -v rprfom-fom-fingerprint $WJ > $W/nofp.java
gen $FOM --out-java $W/d2/j --out-cpp $W/d2/c --user-type-java WorldLocationStruct=$W/nofp.java
ok "D2 sans empreinte: avertissement, exit 0" "[ $RC -eq 0 ] && has 'aucune empreinte'"
gen $FOM --out-java $W/d2s/j --out-cpp $W/d2s/c --strict-user-types --user-type-java WorldLocationStruct=$W/nofp.java
ok "D2s strict: exit 1" "[ $RC -eq 1 ] && has 'aucune empreinte'"
grep -v decodeAt $WJ > $W/nom.java
gen $FOM --out-java $W/d3/j --out-cpp $W/d3/c --user-type-java WorldLocationStruct=$W/nom.java
ok "D3 methode manquante: exit 1" "[ $RC -eq 1 ] && has 'methode obligatoire absente'"
mkdir -p $W/typo; cp $WJ $W/typo/WorldLocationStructt.java
gen $FOM --out-java $W/d4/j --out-cpp $W/d4/c --user-type WorldLocationStruct --user-types-java $W/typo
ok "D4 faute de frappe dossier: avertissement + suggestion" "[ $RC -eq 0 ] && has 'Vouliez-vous dire : WorldLocationStruct'"
gen $FOM --out-java $W/d5/j --out-cpp $W/d5/c --user-type-java WorldLocationStruct=$W/none.java
ok "D5 fichier explicite absent: secours + avertissement" "[ $RC -eq 0 ] && has \"n'existe pas\" && has 'SECOURS     SECOURS'"
gen $FOM --out-java $W/d5s/j --out-cpp $W/d5s/c --strict-user-types --user-type WorldLocationStruct
ok "D5s strict sans fichier: exit 1" "[ $RC -eq 1 ]"
gen $FOM --out-java $W/d6/j --out-cpp $W/d6/c --user-type WorldLocationStrct
ok "D6 type inconnu: exit 1 + suggestion" "[ $RC -eq 1 ] && has 'Vouliez-vous dire : WorldLocationStruct'"
gen $FOM --out-java $W/d6b/j --out-cpp $W/d6b/c --user-type RPRboolean
ok "D6b non fixedRecord: exit 1" "[ $RC -eq 1 ] && has 'seuls les fixedRecordData'"
mkdir -p $W/half; cp $EX/cpp/WorldLocationStruct.hpp $W/half/
gen $FOM --out-java $W/d7/j --out-cpp $W/d7/c --user-type-cpp WorldLocationStruct=$W/half/WorldLocationStruct.hpp
ok "D7 C++ un seul fichier: exit 1" "[ $RC -eq 1 ] && has 'DEUX fichiers'"
gen $FOM --out-java $W/d8/j --out-cpp $W/d8/c --java-package com.acme.fom $U
ok "D8 package reecrit" "[ $RC -eq 0 ] && has 'reecrit de' && head -1 $W/d8/j/src/main/java/com/acme/fom/WorldLocationStruct.java | grep -q 'package com.acme.fom;'"
jc $W/d8/j; ok "D8 compile" "[ $? -eq 0 ]"; jt x com.acme.fom; ok "D8 test OK" "grep -q 'OK (1 type' $W/jt.txt"
gen $FOM --out-java $W/d8/j --out-cpp $W/d8/c --java-package com.acme.fom
ok "D9 option retiree: fichiers supprimes" "[ ! -e $W/d8/j/src/main/java/com/acme/fom/WorldLocationStruct.java ] && [ ! -e $W/d8/j/src/test ] && ! grep -q conformance $W/d8/j/pom.xml"
jc $W/d8/j; ok "D9 compile (type redevenu genere)" "[ $? -eq 0 ]"
mkdir -p $W/d10/j/src/main/java/rprfom; echo '// a moi' > $W/d10/j/src/main/java/rprfom/WorldLocationStruct.java
gen $FOM --out-java $W/d10/j --out-cpp $W/d10/c --user-type WorldLocationStruct
ok "D10 .bak d'un fichier non gere" "[ -e $W/d10/j/src/main/java/rprfom/WorldLocationStruct.java.bak ] && has '.bak'"
gen $FOM --out-java $W/d11/j --out-cpp $W/d11/c --user-type WorldLocationStruct --fallback-dir $W/d11/fb --skip-conformance-test
ok "D11 fallback-dir + skip test" "[ -e $W/d11/fb/java/WorldLocationStruct.java ] && [ -e $W/d11/fb/cpp/usertypes/WorldLocationStructCodec.hpp ] && [ ! -e $W/d11/j/src/test ] && ! grep -q conformance $W/d11/j/pom.xml"
gen $FOM --federate-fom $FED --out-java $W/d12/j --out-cpp $W/d12/c $U
ok "D12 type exclu par federe: ignore, rien copie" "[ $RC -eq 0 ] && has 'non utilise par ce federe' && [ -z \"\$(find $W/d12 -name 'WorldLocationStruct*')\" ]"
gen $FOM --out-java $W/d13/j --out-cpp $W/d13/c $U; gen $FOM --federate-fom $FED --out-java $W/d13/j --out-cpp $W/d13/c $U
ok "D13 puis federe meme sortie: fichiers supprimes" "[ -z \"\$(find $W/d13 -type f \( -name 'WorldLocationStruct*' -o -name '*Conformance*' \))\" ]"
jc $W/d13/j; ok "D13 compile" "[ $? -eq 0 ]"

echo "[E] Fichier XML de configuration"
printf '<userTypes><userType/></userTypes>' > $W/e1.xml; gen $FOM --out-java $W/e1/j --out-cpp $W/e1/c --user-types-config $W/e1.xml
ok "E1 sans name: exit 1" "[ $RC -eq 1 ] && has 'sans attribut name'"
printf '<userTypes><userType name="A"><js dir="x"/></userType></userTypes>' > $W/e2.xml; gen $FOM --out-java $W/e2/j --out-cpp $W/e2/c --user-types-config $W/e2.xml
ok "E2 element inattendu: exit 1" "[ $RC -eq 1 ] && has 'inattendu'"
gen $FOM --out-java $W/e3/j --out-cpp $W/e3/c --user-types-config $W/none.xml
ok "E3 fichier introuvable: exit 1" "[ $RC -eq 1 ] && has 'introuvable'"
printf '<userTypes><userType name="A"/><userType name="A"/></userTypes>' > $W/e4.xml; gen $FOM --out-java $W/e4/j --out-cpp $W/e4/c --user-types-config $W/e4.xml
ok "E4 type en double: exit 1" "[ $RC -eq 1 ] && has 'deux fois'"

echo "[F] Nettoyage automatique"
gen $FOM --out-java $W/f/j --out-cpp $W/f/c; echo note > $W/f/j/MesNotes.txt; gen $FOM --federate-fom $FED --out-java $W/f/j --out-cpp $W/f/c
gen $FOM --federate-fom $FED --out-java $W/f2/j --out-cpp $W/f2/c
ok "F1 complet puis federe = federe seul (hors notes)" "diff -rq $W/f $W/f2 2>&1 | grep -v MesNotes | grep -v 'Only in $W/f:' | wc -l | grep -qx 0 || diff -r $W/f/c $W/f2/c >/dev/null && diff -r $W/f/j $W/f2/j 2>&1 | grep -v MesNotes | grep -vc '^diff\|^Only' | grep -qx 0"
ok "F2 fichier non gere conserve" "[ -e $W/f/j/MesNotes.txt ]"
jc $W/f/j; ok "F3 compile apres nettoyage" "[ $? -eq 0 ]"
echo; echo "RESULTAT : $PASS OK, $FAIL echec(s)"
[ $FAIL -eq 0 ]
