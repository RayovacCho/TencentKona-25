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
 * @summary Verify recursive serialization preserves the object field snapshot
 * @run main FieldValuesBuffer
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class FieldValuesBuffer {
    public static void main(String[] args) throws Exception {
        testFieldSnapshot();
        testDeepGraph();
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
        Node root = null;
        for (int i = 99; i >= 0; i--) {
            root = new Node("node-" + i, root);
        }

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
}
