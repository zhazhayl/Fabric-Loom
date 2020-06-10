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

package net.fabricmc.loom.task.fernflower;

import java.io.PrintStream;
import java.util.Stack;

import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;

/**
 * This logger simply prints what each thread is doing
 * to the console in a machine parsable way.
 *
 * <p>Created by covers1624 on 11/02/19.
 */
public class ThreadIDFFLogger extends IFernflowerLogger {
	public final PrintStream stdOut;
	public final PrintStream stdErr;

	private final ThreadLocal<Stack<String>> workingClass = ThreadLocal.withInitial(Stack::new);
	private final ThreadLocal<Stack<String>> line = ThreadLocal.withInitial(Stack::new);

	public ThreadIDFFLogger(PrintStream stdOut, PrintStream stdErr) {
		this.stdOut = stdOut;
		this.stdErr = stdErr;
	}

	private void pushMessage(Severity severity, String message) {
    	line.get().push(severity.prefix + message);
    	writeMessage(message, severity);
    }

    @Override
    public void writeMessage(String message, Severity severity) {
		long threadID = Thread.currentThread().getId();
        stdOut.println(String.format("%d :: %s%s", threadID, severity.prefix, message).trim());
    }

    @Override
    public void writeMessage(String message, Severity severity, Throwable t) {
    	String currentClass = !workingClass.get().empty() ? workingClass.get().peek() : null;
    	stdErr.println("Error thrown whilst " + (currentClass == null ? "out of class" : "in " + currentClass));
        stdErr.println(message);
        t.printStackTrace(stdErr);
    }

    private void popMessage() {
    	Stack<String> stack = this.line.get();
    	stack.pop();

    	long threadID = Thread.currentThread().getId();
        stdOut.println(String.format("%d :: %s", threadID, stack.empty() ? "waiting" : stack.peek()).trim());
	}

	@Override
	public void startProcessingClass(String className) {
		workingClass.get().push(className);
		pushMessage(Severity.INFO, "Processing " + className);
	}

    @Override
    public void startReadingClass(String className) {
        workingClass.get().push(className);
        pushMessage(Severity.INFO, "Reading " + className);
    }

    @Override
    public void startClass(String className) {
        workingClass.get().push(className);
        pushMessage(Severity.INFO, "Decompiling " + className);
    }

    @Override
    public void startMethod(String methodName) {
        String className = workingClass.get().peek();
        pushMessage(Severity.INFO, "Decompiling " + className + '.' + methodName.substring(0, methodName.indexOf(' ')));
    }

	@Override
	public void endMethod() {
		popMessage();
	}

	@Override
    public void endClass() {
		popMessage();
        workingClass.get().pop();
    }

    @Override
    public void startWriteClass(String className) {
    	pushMessage(Severity.INFO, "Writing " + className);
    }

	@Override
	public void endWriteClass() {
		popMessage();
	}

	@Override
	public void endReadingClass() {
		popMessage();
		workingClass.get().pop();
	}

	@Override
	public void endProcessingClass() {
		popMessage();
		workingClass.get().pop();
	}
}
