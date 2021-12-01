package java.lang;

public final class Runtime {
    public static final class Version {
        private Version() {
        }
        
        public int feature() {
            return 8;
        }
        
        public static Version parse(String v) {
            throw new IllegalStateException();
        }
    }
}
