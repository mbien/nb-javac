cd jdk

bash configure \
 --disable-warnings-as-errors \
 --with-native-debug-symbols=none

make images

./build/*/images/jdk/bin/java -version

