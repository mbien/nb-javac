/*
 * Copyright 2003-2004 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package global;

import com.sun.source.util.JavacTask;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

public class SwitchPrimitiveTypeTest extends TestCase {

    public SwitchPrimitiveTypeTest(String name) {
        super(name);
    }

    static class MyFileObject extends SimpleJavaFileObject {
        private String text;
        public MyFileObject(String text) {
            super(URI.create("myfo:/Test.java"), JavaFileObject.Kind.SOURCE);
            this.text = text;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    public void testSwitchOnPrimitiveType() throws IOException {
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        String code = """
            class X {
                public int checkType(Number v) {
                    return switch (v) {
                        case Integer i -> 40 + i;
                        default -> -42;
                    };
                }

                public static void main(String... args) {
                    X x = new X();
                    System.out.println(x.checkType(2));
                }
            }
            """;
        MemoryOutputJFM fm = new MemoryOutputJFM(tool.getStandardFileManager(null, null, null));
        JavacTask ct = (JavacTask)tool.getTask(null, fm, null, global.Utils.asParameters("--enable-preview", "--release", "19"), null, Arrays.asList(new MyFileObject(code)));
        Iterator<? extends JavaFileObject> out = ct.generate().iterator();

        assertTrue("Contains at least one element", out.hasNext());
        JavaFileObject xClass = out.next();
        assertEquals("generated:/X", xClass.toUri().toString());
    }

    private static class MemoryOutputJFM extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, byte[]> writtenClasses = new HashMap<String, byte[]>();

        public MemoryOutputJFM(StandardJavaFileManager m) {
            super(m);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, final String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
            if (location.isOutputLocation() && kind == JavaFileObject.Kind.CLASS) {
                return new SimpleJavaFileObject(URI.create("generated:/" + className), kind) {
                    @Override
                    public OutputStream openOutputStream() throws IOException {
                        return new ByteArrayOutputStream() {
                            @Override public void close() throws IOException {
                                super.close();
                                writtenClasses.put(className, toByteArray());
                            }
                        };
                    }
                };
            } else {
                return super.getJavaFileForOutput(location, className, kind, sibling);
            }
        }

    }

}
