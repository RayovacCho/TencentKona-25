/*
 * Copyright (c) 2026, Tencent. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @summary Verify recursive serialization safely reuses object field buffers
 * @modules java.base/java.io:open
 * @run main FieldValuesBuffer
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;

public class FieldValuesBuffer {
    public static void main(String[] args) throws Exception {
        testFieldSnapshot();
        testDeepGraph();
        testNestedBuffersAreReleased();
        testNestedBuffersAreReleasedAfterException();
        testRepeatedTopLevelWrites();
        testWideThenNarrowAtSameDepth();
        testNarrowThenWideAtSameDepth();
    }

    private static void testFieldSnapshot() throws Exception {
        Holder value = new Holder();
        value.first = new Mutator(value);
        value.second = "before";

        Holder restored = (Holder) roundTrip(value);

        if (!"after".equals(value.second)) {
            throw new RuntimeException("nested writeObject was not invoked");
        }
        if (!"before".equals(restored.second)) {
            throw new RuntimeException(
                    "object fields were not captured before recursive serialization: "
                    + restored.second);
        }
    }

    private static void testDeepGraph() throws Exception {
        Node root = createDeepGraph();

        Node restored = (Node) roundTrip(root);
        for (int i = 0; i < 100; i++) {
            if (restored == null || !("node-" + i).equals(restored.name)) {
                throw new RuntimeException("deep graph mismatch at index " + i);
            }
            restored = restored.next;
        }
        if (restored != null) {
            throw new RuntimeException("deep graph contains unexpected trailing nodes");
        }
    }

    private static void testNestedBuffersAreReleased() throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(createDeepGraph());
            assertNestedBuffersReleased(output, "successful serialization");
        }
    }

    private static void testNestedBuffersAreReleasedAfterException() throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            try {
                output.writeObject(new ExceptionalRoot());
                throw new RuntimeException("expected nested serialization to fail");
            } catch (IOException expected) {
                if (!"intentional failure".equals(expected.getMessage())) {
                    throw expected;
                }
            }
            assertNestedBuffersReleased(output, "failed serialization");
        }
    }

    private static void testRepeatedTopLevelWrites() throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(createDeepGraph());
            assertNestedBuffersReleased(output, "first top-level serialization");

            output.reset();
            output.writeObject(createDeepGraph());
            assertNestedBuffersReleased(output, "second top-level serialization");
        }
    }

    private static void testWideThenNarrowAtSameDepth() throws Exception {
        Siblings restored = (Siblings) roundTrip(new Siblings());
        if (!"wide-8".equals(restored.aWide.eighth)
                || !"narrow".equals(restored.bNarrow.only)) {
            throw new RuntimeException("field buffer reuse corrupted sibling objects");
        }
    }

    private static void testNarrowThenWideAtSameDepth() throws Exception {
        ReverseSiblings restored = (ReverseSiblings) roundTrip(new ReverseSiblings());
        if (!"narrow".equals(restored.aNarrow.only)
                || !"wide-8".equals(restored.bWide.eighth)) {
            throw new RuntimeException("field buffer expansion corrupted sibling objects");
        }
    }

    private static void assertNestedBuffersReleased(
            ObjectOutputStream output, String operation) throws Exception {
        Field buffers = ObjectOutputStream.class.getDeclaredField("nestedObjFieldVals");
        buffers.setAccessible(true);
        if (buffers.get(output) != null) {
            throw new RuntimeException(
                    "nested field buffers retained after " + operation);
        }
    }

    private static Node createDeepGraph() {
        Node root = null;
        for (int i = 99; i >= 0; i--) {
            root = new Node("node-" + i, root);
        }
        return root;
    }

    private static Object roundTrip(Object value) throws Exception {
        byte[] bytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ObjectOutputStream output = new ObjectOutputStream(buffer)) {
            output.writeObject(value);
            bytes = buffer.toByteArray();
        }

        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            return input.readObject();
        }
    }

    private static final class Holder implements Serializable {
        private static final long serialVersionUID = 1L;

        private Mutator first;
        private String second;
    }

    private static final class Mutator implements Serializable {
        private static final long serialVersionUID = 1L;

        private transient Holder owner;

        private Mutator(Holder owner) {
            this.owner = owner;
        }

        private void writeObject(ObjectOutputStream output) throws IOException {
            owner.second = "after";
            output.defaultWriteObject();
        }
    }

    private static final class Node implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String name;
        private final Node next;

        private Node(String name, Node next) {
            this.name = name;
            this.next = next;
        }
    }

    private static final class ExceptionalRoot implements Serializable {
        private static final long serialVersionUID = 1L;

        private final ThrowingValue first = new ThrowingValue();
        private final String second = "must be released";
    }

    private static final class ThrowingValue implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String first = "nested-first";
        private final String second = "nested-second";

        private void writeObject(ObjectOutputStream output) throws IOException {
            output.defaultWriteObject();
            throw new IOException("intentional failure");
        }
    }

    private static final class Siblings implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Wide aWide = new Wide();
        private final Narrow bNarrow = new Narrow();
    }

    private static final class ReverseSiblings implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Narrow aNarrow = new Narrow();
        private final Wide bWide = new Wide();
    }

    private static final class Wide implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String first = "wide-1";
        private final String second = "wide-2";
        private final String third = "wide-3";
        private final String fourth = "wide-4";
        private final String fifth = "wide-5";
        private final String sixth = "wide-6";
        private final String seventh = "wide-7";
        private final String eighth = "wide-8";
    }

    private static final class Narrow implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String only = "narrow";
    }
}
