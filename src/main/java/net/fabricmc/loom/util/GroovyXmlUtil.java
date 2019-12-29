/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util;

import groovy.util.Node;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GroovyXmlUtil {
	private GroovyXmlUtil() {

	}

	public static Node getOrCreateNode(Node parent, String name) {
		return getNode(parent, name).orElseGet(() -> parent.appendNode(name));
	}

	public static Optional<Node> getNode(Node parent, String name) {
		return childrenNodesStream(parent).filter(node -> name.equals(node.name())).findFirst();
	}

	public static Stream<Node> childrenNodesStream(Node node) {
		return fishForNodes(node.children().stream());
	}

	/**
	 * Type safe casting a raw type {@link Stream} to a {@code Stream<Node>}
	 *
	 * @param stuff A raw stream of objects, potentially containing {@link Node}s
	 *
	 * @return The given raw stream with a filter and mapping operation on return only Nodes
	 */
	private static Stream<Node> fishForNodes(Stream<?> stuff) {
		return stuff.filter(o -> o instanceof Node).map(Node.class::cast);
	}

	public static Iterable<Node> childrenNodes(Node node) {
		return childrenNodesStream(node).collect(Collectors.toList());
	}
}
