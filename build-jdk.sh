if ! [ -f $JAVA_HOME17/bin/javac ]; then
  echo Specify JAVA_HOME17 environment variable!
  exit 1
fi

rm -rf build/test
mkdir -p build/test
mkdir -p build/test/modules
cp $JAVA_HOME17/jmods/* build/test/modules
#clear ModuleHashes on java.base:
$JAVA_HOME17/bin/java --add-modules jdk.jdeps --add-exports jdk.jdeps/com.sun.tools.classfile=ALL-UNNAMED StripModuleHashes.java $JAVA_HOME17/jmods/java.base.jmod build/test/modules/java.base.jmod

#prepare:

#java.compiler:
rm build/test/modules/java.compiler.jmod

mkdir -p build/test/src/java.compiler

cp src/java.compiler/share/classes/module-info.java build/test/src/java.compiler

patch -R build/test/src/java.compiler/module-info.java temporary-patches/test-java.compiler

mkdir -p build/test/out/java.compiler
cp -r make/langtools/netbeans/nb-javac/build/classes/javax build/test/out/java.compiler/
cp -r make/langtools/netbeans/nb-javac/build/classes/nbjavac build/test/out/java.compiler/

#jdk.compiler:
rm build/test/modules/jdk.compiler.jmod

mkdir -p build/test/src/jdk.compiler
cp src/jdk.compiler/share/classes/module-info.java build/test/src/jdk.compiler

patch build/test/src/jdk.compiler/module-info.java temporary-patches/test-jdk.compiler

mkdir -p build/test/out/jdk.compiler
mkdir -p build/test/out/jdk.compiler/com/sun/source
cp -r make/langtools/netbeans/nb-javac/build/classes/com/sun/source build/test/out/jdk.compiler/com/sun/
mkdir -p build/test/out/jdk.compiler/com/sun/tools/javac
cp -r make/langtools/netbeans/nb-javac/build/classes/com/sun/tools/javac build/test/out/jdk.compiler/com/sun/tools/
mkdir -p build/test/out/jdk.compiler/com/sun/tools/doclint
cp -r make/langtools/netbeans/nb-javac/build/classes/com/sun/tools/doclint build/test/out/jdk.compiler/com/sun/tools/
cp -r make/langtools/netbeans/nb-javac/build/classes/jdk build/test/out/jdk.compiler/

rm -rf build/test/expanded/jdk.compiler
mkdir -p build/test/expanded/jdk.compiler

$JAVA_HOME17/bin/jmod extract --dir=build/test/expanded/jdk.compiler $JAVA_HOME17/jmods/jdk.compiler.jmod

#jdk.jdeps:
rm build/test/modules/jdk.jdeps.jmod

mkdir -p build/test/src/jdk.jdeps
cp src/jdk.jdeps/share/classes/module-info.java build/test/src/jdk.jdeps

mkdir -p build/test/out/jdk.jdeps

rm -rf build/test/expanded/jdk.jdeps
mkdir -p build/test/expanded/jdk.jdeps

$JAVA_HOME17/bin/jmod extract --dir=build/test/expanded/jdk.jdeps $JAVA_HOME17/jmods/jdk.jdeps.jmod

cp -r build/test/expanded/jdk.jdeps/classes/* build/test/out/jdk.jdeps/

rm -rf build/test/out/jdk.jdeps/com/sun/tools/classfile/

mkdir -p build/test/out/jdk.jdeps/com/sun/tools/classfile/
cp -r make/langtools/netbeans/nb-javac/build/classes/com/sun/tools/classfile build/test/out/jdk.jdeps/com/sun/tools/
mkdir -p build/test/out/jdk.jdeps/com/sun/tools/javap
cp -r make/langtools/netbeans/nb-javac/build/classes/com/sun/tools/javap build/test/out/jdk.jdeps/com/sun/tools/

#build module-infos:
$JAVA_HOME17/bin/javac --module-source-path build/test/src/ -d build/test/out `find build/test/src/ -type f -name "*.java"`

#build jmods:
$JAVA_HOME17/bin/jmod create --class-path build/test/out/java.compiler/ --module-version 17 build/test/modules/java.compiler.jmod
$JAVA_HOME17/bin/jmod create --class-path build/test/out/jdk.compiler/ --cmds build/test/expanded/jdk.compiler/bin/ --legal-notice build/test/expanded/jdk.compiler/legal/ --libs build/test/expanded/jdk.compiler/lib/ --man-pages build/test/expanded/jdk.compiler/man/ --module-version 17 build/test/modules/jdk.compiler.jmod
$JAVA_HOME17/bin/jmod create --class-path build/test/out/jdk.jdeps/ --cmds build/test/expanded/jdk.jdeps/bin/ --legal-notice build/test/expanded/jdk.jdeps/legal/ --man-pages build/test/expanded/jdk.jdeps/man/ --module-version 17 build/test/modules/jdk.jdeps.jmod

#build image:
$JAVA_HOME17/bin/jlink -p build/test/modules --add-modules ALL-MODULE-PATH --output build/test/jdk
