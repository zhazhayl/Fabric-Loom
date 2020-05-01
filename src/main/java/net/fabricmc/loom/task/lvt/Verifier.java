/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.fabricmc.loom.task.lvt;

import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import net.fabricmc.loom.task.lvt.ClassInfo.TypeLookup;

class Verifier extends SimpleVerifier {
    final Type currentClass;
    private final Type currentSuperClass;
    private final List<Type> currentClassInterfaces;
    private final boolean isInterface;

    public Verifier(int api, Type currentClass, Type currentSuperClass, List<Type> currentClassInterfaces, boolean isInterface) {
        super(api, currentClass, currentSuperClass, currentClassInterfaces, isInterface);
        this.currentClass = currentClass;
        this.currentSuperClass = currentSuperClass;
        this.currentClassInterfaces = currentClassInterfaces;
        this.isInterface = isInterface;
    }

    @Override
    protected boolean isInterface(final Type type) {
        if (this.currentClass != null && type.equals(this.currentClass)) {
            return this.isInterface;
        }
        return ClassInfo.forType(type, TypeLookup.ELEMENT_TYPE).isInterface();
    }

    @Override
    protected Type getSuperClass(final Type type) {
        if (this.currentClass != null && type.equals(this.currentClass)) {
            return this.currentSuperClass;
        }
        ClassInfo c = ClassInfo.forType(type, TypeLookup.ELEMENT_TYPE).getSuperClass();
        return c == null ? null : Type.getObjectType(c.getName());
    }

    @Override
    protected boolean isAssignableFrom(final Type type, final Type other) {
        if (type.equals(other)) {
            return true;
        }
        if (this.currentClass != null && type.equals(this.currentClass)) {
            if (this.getSuperClass(other) == null) {
                return false;
            }
            if (this.isInterface) {//Questionable short-cutting
                return other.getSort() == Type.OBJECT || other.getSort() == Type.ARRAY;
            }
            return this.isAssignableFrom(type, this.getSuperClass(other));
        }
        if (this.currentClass != null && other.equals(this.currentClass)) {
            if (this.isAssignableFrom(type, this.currentSuperClass)) {
                return true;
            }
            if (this.currentClassInterfaces != null) {
                for (int i = 0; i < this.currentClassInterfaces.size(); ++i) {
                    Type v = this.currentClassInterfaces.get(i);
                    if (this.isAssignableFrom(type, v)) {
                        return true;
                    }
                }
            }
            return false;
        }
        switch (other.getSort()) {
	        case Type.BOOLEAN:
	        case Type.CHAR:
	        case Type.BYTE:
	        case Type.SHORT:
	        case Type.INT:
	        case Type.FLOAT:
	        case Type.LONG:
	        case Type.DOUBLE:
	        	assert type.getSort() != other.getSort();
	        	return false; //Primitives aren't assignable to each other

	        case Type.ARRAY: {
	        	ClassInfo typeInfo = ClassInfo.forType(type, TypeLookup.ELEMENT_TYPE); //Arrays are only assignable to Object
	        	return typeInfo != null && typeInfo.isObject;
	        }

	        case Type.OBJECT: {
	        	ClassInfo typeInfo = ClassInfo.forType(type, TypeLookup.ELEMENT_TYPE);
	        	if (typeInfo == null) return false; //Might be missing this if type is a primitive/array
	        	if (typeInfo.isObject) return true; //Can always cast objects to Object

	        	ClassInfo otherInfo = ClassInfo.forType(other, TypeLookup.ELEMENT_TYPE); //Shouldn't be missing this
	        	if (otherInfo == null) throw new NullPointerException("Unexpected null return for " + other + " type");

	        	return otherInfo.hasSuperClass(typeInfo, typeInfo.isInterface() || otherInfo.isInterface());
	        }

	        case Type.VOID:
	        case Type.METHOD:
	        default:
	        	throw new IllegalArgumentException("Unexpected type to try cast as " + type + ": " + other);
        }
    }
}