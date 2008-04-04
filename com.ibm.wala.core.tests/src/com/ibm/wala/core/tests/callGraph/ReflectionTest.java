/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.core.tests.callGraph;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.jdt.core.JavaModelException;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.core.tests.util.TestConstants;
import com.ibm.wala.core.tests.util.WalaTestCase;
import com.ibm.wala.eclipse.util.CancelException;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.propagation.ConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.ReceiverInstanceContext;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.warnings.WalaException;
import com.ibm.wala.util.warnings.Warning;
import com.ibm.wala.util.warnings.Warnings;

/**
 * 
 * Tests for Call Graph construction
 * 
 * @author sfink
 */

public class ReflectionTest extends WalaTestCase {

  public static void main(String[] args) {
    justThisTest(ReflectionTest.class);
  }

  /**
   * test that when analyzing Reflect1.main(), there is no warning about "Integer".
   */
  public void testReflect1() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT1_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);

    Warnings.clear();
    CallGraphTest.doCallGraphs(options, new AnalysisCache(), cha, scope);
    for (Iterator<Warning> it = Warnings.iterator(); it.hasNext();) {
      Warning w = (Warning) it.next();
      if (w.toString().indexOf("com/ibm/jvm") > 0) {
        continue;
      }
      if (w.toString().indexOf("Integer") >= 0) {
        assertTrue(w.toString(), false);
      }
    }
  }

  /**
   * Test that when analyzing reflect2, the call graph includes a node for java.lang.Integer.<clinit>. This should be
   * forced by the call for Class.forName("java.lang.Integer").
   */
  public void testReflect2() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT2_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);

    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "<clinit>", "()V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Check that when analyzing Reflect3, the successors of newInstance do not include reflection/Reflect3$Hash
   */
  public void testReflect3() throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT3_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);

    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Class");
    MethodReference mr = MethodReference.findOrCreate(tr, "newInstance", "()Ljava/lang/Object;");
    Set<CGNode> newInstanceNodes = cg.getNodes(mr);
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : newInstanceNodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    TypeReference extraneousTR = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Reflect3$Hash");
    IClass hashClass = cha.lookupClass(extraneousTR);
    assert hashClass != null;
    MethodReference extraneousMR = MethodReference.findOrCreate(extraneousTR, "<init>", "()V");
    Set<CGNode> extraneousNodes = cg.getNodes(extraneousMR);
    succNodes.retainAll(extraneousNodes);
    assertTrue(succNodes.isEmpty());
  }

  /**
   * Check that when analyzing Reflect4, successors of newInstance() do not include FilePermission ctor.
   */
  public void testReflect4() throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT4_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);

    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Class");
    MethodReference mr = MethodReference.findOrCreate(tr, "newInstance", "()Ljava/lang/Object;");
    Set<CGNode> newInstanceNodes = cg.getNodes(mr);
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : newInstanceNodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    TypeReference extraneousTR = TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/io/FilePermission");
    MethodReference extraneousMR = MethodReference.findOrCreate(extraneousTR, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
    Set<CGNode> extraneousNodes = cg.getNodes(extraneousMR);
    succNodes.retainAll(extraneousNodes);
    assertTrue(succNodes.isEmpty());
  }

  /**
   * Check that when analyzing Reflect5, successors of newInstance do not include a Reflect5$A ctor
   */
  public void testReflect5() throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT5_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);

    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Class");
    MethodReference mr = MethodReference.findOrCreate(tr, "newInstance", "()Ljava/lang/Object;");
    Set<CGNode> newInstanceNodes = cg.getNodes(mr);
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : newInstanceNodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    TypeReference extraneousTR = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Reflect5$A");
    MethodReference extraneousMR = MethodReference.findOrCreate(extraneousTR, "<init>", "()V");
    Set<CGNode> extraneousNodes = cg.getNodes(extraneousMR);
    succNodes.retainAll(extraneousNodes);
    assertTrue(succNodes.isEmpty());
  }

  /**
   * Check that when analyzing Reflect6, successors of newInstance do not include a Reflect6$A ctor
   */
  public void testReflect6() throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT6_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);

    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Class");
    MethodReference mr = MethodReference.findOrCreate(tr, "newInstance", "()Ljava/lang/Object;");
    Set<CGNode> newInstanceNodes = cg.getNodes(mr);
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : newInstanceNodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    TypeReference extraneousTR = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Reflect6$A");
    MethodReference extraneousMR = MethodReference.findOrCreate(extraneousTR, "<init>", "(I)V");
    Set<CGNode> extraneousNodes = cg.getNodes(extraneousMR);
    succNodes.retainAll(extraneousNodes);
    assertTrue(succNodes.isEmpty());
  }

  @SuppressWarnings("unchecked")
  public void testReflect7() throws ClassHierarchyException, IllegalArgumentException, CancelException, IOException,
      JavaModelException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT7_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);

    final String mainClass = "Lreflection/Reflect7";
    TypeReference mainTr = TypeReference.findOrCreate(ClassLoaderReference.Application, mainClass);
    MethodReference mainMr = MethodReference.findOrCreate(mainTr, "main", "([Ljava/lang/String;)V");

    TypeReference constrTr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/reflect/Constructor");
    MethodReference newInstanceMr = MethodReference
        .findOrCreate(constrTr, "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;");

    String fpInitSig = "java.io.FilePermission.<init>(Ljava/lang/String;Ljava/lang/String;)V";
    String fpToStringSig = "java.security.Permission.toString()Ljava/lang/String;";

    Set<CGNode> mainNodes = cg.getNodes(mainMr);

    // Get all the children of the main node(s)
    Collection<CGNode> mainChildren = getSuccNodes(cg, mainNodes);

    // Verify that one of those children is Constructor.newInstance, where Constructor is a FilePermission constructor
    CGNode filePermConstrNewInstanceNode = null;

    for (CGNode node : mainChildren) {
      Context context = node.getContext();
      if (context instanceof ReceiverInstanceContext && node.getMethod().getReference().equals(newInstanceMr)) {
        ReceiverInstanceContext r = (ReceiverInstanceContext) context;
        ConstantKey<IMethod> c = (ConstantKey<IMethod>) r.getReceiver();
        IMethod ctor = (IMethod) c.getValue();
        if (ctor.getSignature().equals(fpInitSig)) {
          filePermConstrNewInstanceNode = node;
          break;
        }
      }
    }
    assertTrue(filePermConstrNewInstanceNode != null);

    // Now verify that this node has FilePermission.<init> children
    CGNode filePermInitNode = null;

    Iterator<? extends CGNode> filePermConstrNewInstanceChildren = cg.getSuccNodes(filePermConstrNewInstanceNode);
    while (filePermConstrNewInstanceChildren.hasNext()) {
      CGNode node = filePermConstrNewInstanceChildren.next();
      if (node.getMethod().getSignature().equals(fpInitSig)) {
        filePermInitNode = node;
        break;
      }
    }
    assertTrue(filePermInitNode != null);

    // Furthermore, verify that main has a FilePermission.toString child
    CGNode filePermToStringNode = null;
    for (CGNode node : mainChildren) {
      if (node.getMethod().getSignature().equals(fpToStringSig)) {
        filePermToStringNode = node;
        break;
      }
    }

    assertTrue(filePermToStringNode != null);
  }

  private Collection<CGNode> getSuccNodes(CallGraph cg, Collection<CGNode> nodes) {
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : nodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    return succNodes;
  }
  
  /**
   * Test that when analyzing reflect8, the call graph includes a node for java.lang.Integer.toString()
   */
  public void testReflect8() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT8_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing reflect9, the call graph includes a node for java.lang.Integer.toString()
   */
  public void testReflect9() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT9_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect10, the call graph includes a node for java.lang.Integer.toString()
   */
  public void testReflect10() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT10_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect11, the call graph includes a node for java.lang.Object.wait()
   */
  public void testReflect11() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT11_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Object");
    MethodReference mr = MethodReference.findOrCreate(tr, "wait", "()V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect12, the call graph does not include a node for reflection.Helper.n
   * but does include a node for reflection.Helper.m
   */
  public void testReflect12() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT12_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "m", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "n", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect13, the call graph includes both a node for reflection.Helper.n
   * and a node for reflection.Helper.m
   */
  public void testReflect13() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT13_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "m", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "n", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect14, the call graph does not include a node for reflection.Helper.n
   * but does include a node for reflection.Helper.s
   */
  public void testReflect14() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT14_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "s", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "n", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect15, the call graph includes a node for the constructor
   * of Helper that takes 2 parameters and for Helper.n, but no node for the constructors of
   * Helper that takes 0 or 1 parameters.
   */
  public void testReflect15() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT15_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "<init>", "(Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "<init>", "()V");
    nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "n", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect16, the call graph includes a node for java.lang.Integer.toString()
   */
  public void testReflect16() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT16_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect17, the call graph does not include a node for java.lang.Integer.toString()
   */
  public void testReflect17() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT17_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect18, the call graph includes a node for java.lang.Integer.toString()
   */
  public void testReflect18() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT18_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect19, the call graph includes a node for java.lang.Integer.toString()
   */
  public void testReflect19() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT19_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect20, the call graph includes a node for Helper.o.
   */
  public void testReflect20() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT20_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "o", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect21, the call graph includes a node for the constructor of {@link Helper} that takes
   * two {@link Object} parameters.  This is to test the support for Class.getDeclaredConstructor.
   */
  public void testReflect21() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT21_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect22, the call graph includes a node for the constructor of {@link Helper} that takes
   * one {@link Integer} parameters.  This is to test the support for Class.getDeclaredConstructors.
   */
  public void testReflect22() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT22_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "<init>", "(Ljava/lang/Integer;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
  
  /**
   * Test that when analyzing Reflect22, the call graph includes a node for the constructor of {@link Helper} that takes
   * one {@link Integer} parameters.  This is to test the support for Class.getDeclaredConstructors.
   */
  public void testReflect23() throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = CallGraphTestUtil.makeJ2SEAnalysisScope(TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    ClassHierarchy cha = ClassHierarchy.make(scope);
    Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
        TestConstants.REFLECT23_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCache(), cha, scope, false);
    TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "u", "(Ljava/lang/Integer;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }
}
