/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package nbjavac;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;

public class VMWrapper {
    private VMWrapper() {
    }

    public static String[] getRuntimeArguments() {
        return new String[0];
    }

    private static final String[] symbolFileLocation = { "lib", "ct.sym" };
    private static Reference<Path> cachedCtSym = new SoftReference<>(null);

    public static Path findCtSym() {
        Path obj = cachedCtSym.get();
        if (obj instanceof Path) {
            return obj;
        }
        try {
            ClassLoader loader = VMWrapper.class.getClassLoader();
            if (loader == null) {
                loader = ClassLoader.getSystemClassLoader();
            }
            Enumeration<URL> en = loader.getResources("META-INF/services/com.sun.tools.javac.platform.PlatformProvider");
            URL res = en.hasMoreElements() ? en.nextElement() : null;
            if (res == null) {
                //runnning inside a JDK image, try to look for lib/ct.sym:
                String javaHome = System.getProperty("java.home");
                Path file = Paths.get(javaHome);
                for (String name : symbolFileLocation) {
                    file = file.resolve(name);
                }
                if (!Files.exists(file)) {
                    throw new IllegalStateException("Cannot find ct.sym at " + file);
                }
                return FileSystems.newFileSystem(file, (ClassLoader)null).getRootDirectories().iterator().next();
            }
            if (!res.getProtocol().equals("jar")) {
                res = en.hasMoreElements() ? en.nextElement() : null;
            }
            URL jar = ((JarURLConnection)res.openConnection()).getJarFileURL();
            Path path = Paths.get(jar.toURI());
            FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null);
            Path ctSym = fs.getPath("META-INF", "ct.sym");
            cachedCtSym = new SoftReference<>(ctSym);
            return ctSym;
        } catch (IOException | URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }


}
