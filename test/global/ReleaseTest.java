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
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

public class ReleaseTest extends TestCase {

    public ReleaseTest(String name) {
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

    public void testRelease9() throws IOException {
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        String code = "package test; public class Test { Class<?> f() { return Module.class; } }";

        JavacTask ct = (JavacTask)tool.getTask(null, null, null, Arrays.asList("--release",  "9"), null, Arrays.asList(new MyFileObject(code)));

        ct.analyze();
    }

    // ct.sym tripwire test, if this fails, something is likely wrong with the ct.sym

    // Tied to the pinned JDK (jdk.properties): the ceiling is the highest
    // release the embedded ct.sym supports. When the pin moves, update the
    // releases and keep the probe source referencing an API that only
    // exists at the ceiling.
    private static final String RELEASE_CEILING = "28";
    private static final String RELEASE_BELOW_CEILING = "27";
    private static final String RELEASE_FLOOR = "21";

    // not in runtime JDK -> must be in ct.sym
    private static final String CEILING_SOURCE =
            "public class Test { java.lang.Character.UnicodeBlock b = "
            + "java.lang.Character.UnicodeBlock.MUSICAL_SYMBOLS_SUPPLEMENT; }";

    public void testReleaseAtCeiling() throws IOException {
        List<Diagnostic<? extends JavaFileObject>> errors = compile(CEILING_SOURCE, "--release", RELEASE_CEILING);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    public void testReleaseBelowCeilingHidesCeilingApi() throws IOException {
        List<Diagnostic<? extends JavaFileObject>> errors = compile(CEILING_SOURCE, "--release", RELEASE_BELOW_CEILING);
        assertFalse(errors.toString(), errors.isEmpty());
    }

    public void testReleaseAtFloor() throws IOException {
        List<Diagnostic<? extends JavaFileObject>> errors = compile("public class Test {}", "--release", RELEASE_FLOOR);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    private static List<Diagnostic<? extends JavaFileObject>> compile(String code, String... args) throws IOException {
        JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assertNotNull(tool);
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavacTask ct = (JavacTask) tool.getTask(null, null, diags, Arrays.asList(args), null, Arrays.asList(new MyFileObject(code)));
        ct.analyze();
        return diags.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .toList();
    }

}
