/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.dataflow.IFDS;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;

import org.eclipse.core.runtime.IProgressMonitor;

import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.eclipse.util.CancelException;
import com.ibm.wala.eclipse.util.MonitorUtil;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Heap;
import com.ibm.wala.util.collections.Iterator2Collection;
import com.ibm.wala.util.collections.ToStringComparator;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.heapTrace.HeapTracer;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;
import com.ibm.wala.util.ref.ReferenceCleanser;

/**
 * 
 * A precise interprocedural tabulation solver.
 * <p>
 * See Reps, Horwitz, Sagiv POPL 95.
 * <p>
 * This version differs in some ways from the POPL algorithm. In particular ...
 * <ul>
 * <li>to support exceptional control flow ... there may be several return sites for each call site.
 * <li>it supports an optional merge operator, useful for non-IFDS problems and widening
 * <li> it store summary edges at each callee instead of at each call site, to avoid unnecessary propagation during
 * demand-driven tabulation.
 * </ul>
 * <p>
 * 
 * Type parameter T represents type of nodes in the supergraph. Type parameter P represents the type of procedure (or
 * box in RSM parlance)
 * 
 * @author sfink
 */
public class TabulationSolver<T, P> {

  /**
   * DEBUG_LEVEL:
   * <ul>
   * <li>0 No output
   * <li>1 Print some simple stats and warning information
   * <li>2 Detailed debugging
   * <li>3 Also print worklists
   * </ul>
   */
  protected static final int DEBUG_LEVEL = 0;

  static protected final boolean verbose = true && ("true".equals(System.getProperty("com.ibm.wala.fixedpoint.impl.verbose")) ? true
      : false);

  static final int VERBOSE_INTERVAL = 1000;

  static final boolean VERBOSE_TRACE_MEMORY = false;

  private static int verboseCounter = 0;

  /**
   * Should we periodically clear out soft reference caches in an attempt to help the GC?
   */
  final protected static boolean PERIODIC_WIPE_SOFT_CACHES = true;

  /**
   * Interval which defines the period to clear soft reference caches
   */
  private final static int WIPE_SOFT_CACHE_INTERVAL = 1000000;

  /**
   * Counter for wiping soft caches
   */
  private static int wipeCount = WIPE_SOFT_CACHE_INTERVAL;

  /**
   * The supergraph which induces this dataflow problem
   */
  protected final ISupergraph<T, P> supergraph;

  /**
   * A map from an edge in a supergraph to a flow function
   */
  protected final IFlowFunctionMap<T> flowFunctionMap;

  /**
   * The problem being solved.
   */
  private final TabulationProblem<T, P> problem;

  /**
   * A map from Object (entry node in supergraph) -> LocalPathEdges.
   * 
   * Logically, this represents a set of edges (s_p,d_i) -> (n, d_j). The data structure is chosen to attempt to save
   * space over representing each edge explicitly.
   */
  final private Map<T, LocalPathEdges> pathEdges = HashMapFactory.make();

  /**
   * A map from Object (entry node in supergraph) -> CallFlowEdges.
   * 
   * Logically, this represents a set of edges (c,d_i) -> (s_p, d_j). The data structure is chosen to attempt to save
   * space over representing each edge explicitly.
   */
  final private Map<T, CallFlowEdges> callFlowEdges = HashMapFactory.make();

  /**
   * A map from Object (procedure) -> LocalSummaryEdges.
   * 
   */
  final protected Map<P, LocalSummaryEdges> summaryEdges = HashMapFactory.make();

  /**
   * the set of all {@link PathEdge}s that were used as seeds during the tabulation.
   */
  private final Collection<PathEdge<T>> seeds = HashSetFactory.make();

  /**
   * The worklist
   */
  final protected Worklist worklist = new Worklist();

  /**
   * A progress monitor. can be null.
   */
  private final IProgressMonitor progressMonitor;

  /**
   * @param p a description of the dataflow problem to solve
   * @throws IllegalArgumentException if p is null
   */
  protected TabulationSolver(TabulationProblem<T, P> p, IProgressMonitor monitor) {
    if (p == null) {
      throw new IllegalArgumentException("p is null");
    }
    this.supergraph = p.getSupergraph();
    this.flowFunctionMap = p.getFunctionMap();
    this.problem = p;
    this.progressMonitor = monitor;
  }

  /**
   * @param p a description of the dataflow problem to solve
   * @throws IllegalArgumentException if p is null
   */
  public static <T, P> TabulationSolver<T, P> make(TabulationProblem<T, P> p) {
    return new TabulationSolver<T, P>(p, null);
  }

  /**
   * Solve the dataflow problem.
   * 
   * @return a representation of the result
   */
  public TabulationResult<T, P> solve() throws CancelException {

    try {
      initialize();
      forwardTabulateSLRPs();
      Result r = new Result();
      return r;
    } catch (CancelException e) {
      // store a partially-tabulated result in the thrown exception.
      Result r = new Result();
      throw new TabulationCancelException(e, r);
    }
  }

  /**
   * Start tabulation with the initial seeds.
   */
  protected void initialize() {
    for (PathEdge<T> seed : problem.initialSeeds()) {
      seeds.add(seed);
      propagate(seed.entry, seed.d1, seed.target, seed.d2);
    }
  }

  /**
   * Restart tabulation from a particular path edge. Use with care.
   */
  public void addSeed(PathEdge<T> seed) {
    seeds.add(seed);
    propagate(seed.entry, seed.d1, seed.target, seed.d2);
  }

  /**
   * See POPL 95 paper for this algorithm, Figure 3
   * 
   * @throws CancelException
   */
  private void forwardTabulateSLRPs() throws CancelException {
    while (worklist.size() > 0) {
      MonitorUtil.throwExceptionIfCanceled(progressMonitor);
      if (verbose) {
        performVerboseAction();
      }
      if (PERIODIC_WIPE_SOFT_CACHES) {
        tendToSoftCaches();
      }

      final PathEdge<T> edge = popFromWorkList();
      if (DEBUG_LEVEL > 0) {
        System.err.println("TABULATE " + edge);
      }
      int j = merge(edge.entry, edge.d1, edge.target, edge.d2);
      if (j == -1 && DEBUG_LEVEL > 0) {
        System.err.println("merge -1: DROPPING");
      }
      if (j != -1) {
        if (j != edge.d2) {
          // this means that we don't want to push the edge. instead,
          // we'll push the merged fact. a little tricky, but i think should
          // work.
          propagate(edge.entry, edge.d1, edge.target, j);
        } else {
          if (supergraph.isCall(edge.target)) {
            // [13]
            processCall(edge);
          } else if (supergraph.isExit(edge.target)) {
            // [21]
            processExit(edge);
          } else {
            // [33]
            processNormal(edge);
          }
        }
      }
    }
  }

  /**
   * For some reason (either a bug in our code that defeats soft references, or a bad policy in the GC), leaving soft
   * reference caches to clear themselves out doesn't work. Help it out.
   * 
   * It's unfortunate that this method exits.
   */
  protected void tendToSoftCaches() {
    wipeCount++;
    if (wipeCount > WIPE_SOFT_CACHE_INTERVAL) {
      wipeCount = 0;
      ReferenceCleanser.clearSoftCaches();
    }
  }

  /**
   * 
   */
  protected final void performVerboseAction() {
    verboseCounter++;
    if (verboseCounter % VERBOSE_INTERVAL == 0) {
      System.err.println("Tabulation Solver " + verboseCounter);
      System.err.println("  " + peekFromWorkList());
      if (VERBOSE_TRACE_MEMORY) {
        ReferenceCleanser.clearSoftCaches();
        System.err.println("Analyze leaks..");
        HeapTracer.traceHeap(Collections.singleton(this), true);
        System.err.println("done analyzing leaks");
      }
    }
  }

  /**
   * Handle lines [33-37] of the algorithm
   * 
   * @param edge
   */
  private void processNormal(final PathEdge<T> edge) {
    if (DEBUG_LEVEL > 0) {
      System.err.println("process normal: " + edge);
    }
    for (Iterator<? extends T> it = supergraph.getSuccNodes(edge.target); it.hasNext();) {
      final T m = it.next();
      if (DEBUG_LEVEL > 0) {
        System.err.println("normal successor: " + m);
      }
      IUnaryFlowFunction f = flowFunctionMap.getNormalFlowFunction(edge.target, m);
      IntSet D3 = computeFlow(edge.d2, f);
      if (DEBUG_LEVEL > 0) {
        System.err.println(" reached: " + D3);
      }
      if (D3 != null) {
        D3.foreach(new IntSetAction() {
          public void act(int d3) {
            propagate(edge.entry, edge.d1, m, d3);
          }
        });
      }
    }
  }

  /**
   * Handle lines [21 - 32] of the algorithm, propagating information from an exit node.
   * 
   * Note that we've changed the way we record summary edges. Summary edges are now associated with a callee (s_p,exit),
   * where the original algorithm used a call, return pair in the caller.
   */
  protected void processExit(final PathEdge<T> edge) {
    if (DEBUG_LEVEL > 0) {
      System.err.println("process exit: " + edge);
    }

    // succ:= successor nodes of edge.n (the return block in the callee)
    IntSet succ = supergraph.getSuccNodeNumbers(edge.target);
    if (succ == null) {
      // This should only happen for return from the entry point of the supergraph
      // (fake root method for whole-program analysis).
      // if (DEBUG_LEVEL > 0) {
      // P n = supergraph.getProcOf(edge.n);
      // Assertions._assert(supergraph.getMain().equals(n), "no successors for " + edge.n);
      // }
      return;
    }

    final LocalSummaryEdges summaries = findOrCreateLocalSummaryEdges(supergraph.getProcOf(edge.target));
    int s_p_n = supergraph.getLocalBlockNumber(edge.entry);
    int x = supergraph.getLocalBlockNumber(edge.target);
    if (!summaries.contains(s_p_n, x, edge.d1, edge.d2)) {
      summaries.insertSummaryEdge(s_p_n, x, edge.d1, edge.d2);
    }

    final CallFlowEdges callFlow = findOrCreateCallFlowEdges(edge.entry);

    // [22] for each c /in callers(p)
    for (Iterator<? extends T> it = supergraph.getPredNodes(edge.entry); it.hasNext();) {
      final T c = it.next();
      final int cNum = supergraph.getLocalBlockNumber(c);
      if (DEBUG_LEVEL > 0) {
        System.err.println("caller: " + c + " " + cNum);
      }

      // [23] for each d4 s.t. <c,d4> -> <s_p,d1> occurred earlier
      int globalC = supergraph.getNumber(c);
      final IntSet D4 = callFlow.getCallFlowSources(globalC, edge.d1);

      // [23] for each d5 s.t. <e_p,d2> -> <returnSite(c),d5> ...
      if (D4 != null) {
        propagateToReturnSites(edge, succ, c, D4);
      }
    }
  }

  /**
   * Propagate information for an "exit" edge to the appropriate return sites
   * 
   * [23] for each d5 s.t. <s_p,d2> -> <returnSite(c),d5> ..
   * 
   * @param edge the edge being processed
   * @param succ numbers of the nodes that are successors of edge.n (the return block in the callee) in the call graph.
   * @param c a call site of edge.s_p
   * @param D4 set of d1 s.t. <c, d1> -> <edge.s_p, edge.d2> was recorded as call flow
   */
  private void propagateToReturnSites(final PathEdge<T> edge, IntSet succ, final T c, final IntSet D4) {

    if (DEBUG_LEVEL > 1) {
      System.err.println("Successor nodes: " + succ);
      for (IntIterator it = succ.intIterator(); it.hasNext();) {
        int x = it.next();
        System.err.println("  " + x + " " + supergraph.getNode(x));
      }
    }

    P proc = supergraph.getProcOf(c);
    final T[] entries = supergraph.getEntriesForProcedure(proc);

    // we iterate over each potential return site;
    // we might have multiple return sites due to exceptions
    // note that we might have different summary edges for each
    // potential return site, and different flow functions from this
    // exit block to each return site.
    for (Iterator<? extends T> retSites = supergraph.getReturnSites(c); retSites.hasNext();) {
      final T retSite = retSites.next();
      if (DEBUG_LEVEL > 1) {
        System.err.println("candidate return site: " + retSite + " " + supergraph.getNumber(retSite));
      }
      // note: since we might have multiple exit nodes for the callee, (to
      // handle exceptional returns)
      // not every return site might be valid for this exit node (edge.n).
      // so, we'll filter the logic by checking that we only process
      // reachable return sites.
      // the supergraph carries the information regarding the legal
      // successors
      // of the exit node
      if (!succ.contains(supergraph.getNumber(retSite))) {
        continue;
      }
      if (DEBUG_LEVEL > 1) {
        System.err.println("feasible return site: " + retSite);
      }
      final IFlowFunction retf = flowFunctionMap.getReturnFlowFunction(c, edge.target, retSite);
      if (retf instanceof IBinaryReturnFlowFunction) {
        propagateToReturnSiteWithBinaryFlowFunction(edge, c, D4, entries, retSite, retf);
      } else {
        final IntSet D5 = computeFlow(edge.d2, (IUnaryFlowFunction) retf);
        if (DEBUG_LEVEL > 1) {
          System.err.println("D4" + D4);
          System.err.println("D5 " + D5);
        }
        IntSetAction action = new IntSetAction() {
          public void act(final int d4) {
            if (D5 != null) {
              D5.foreach(new IntSetAction() {
                public void act(final int d5) {
                  // [26 - 28]
                  // note that we've modified the algorithm here to account
                  // for potential
                  // multiple entry nodes. Instead of propagating the new
                  // summary edge
                  // with respect to one s_profOf(c), we have to propagate
                  // for each
                  // potential entry node s_p /in s_procof(c)
                  for (int i = 0; i < entries.length; i++) {
                    final T s_p = entries[i];
                    if (DEBUG_LEVEL > 1) {
                      System.err.println(" do entry " + s_p);
                    }
                    IntSet D3 = getInversePathEdges(s_p, c, d4);
                    if (DEBUG_LEVEL > 1) {
                      System.err.println("D3" + D3);
                    }
                    if (D3 != null) {
                      D3.foreach(new IntSetAction() {
                        public void act(int d3) {
                          propagate(s_p, d3, retSite, d5);
                        }
                      });
                    }
                  }
                }
              });
            }
          }
        };
        D4.foreach(action);
      }
    }
  }

  /**
   * Propagate information for an "exit" edge to a caller return site
   * 
   * [23] for each d5 s.t. <s_p,d2> -> <returnSite(c),d5> ..
   * 
   * @param edge the edge being processed
   * @param c a call site of edge.s_p
   * @param D4 set of d1 s.t. <c, d1> -> <edge.s_p, edge.d2> was recorded as call flow
   * @param entries the blocks in the supergraph that are entries for the procedure of c
   * @param retSite the return site being propagated to
   * @param retf the flow function
   */
  private void propagateToReturnSiteWithBinaryFlowFunction(final PathEdge edge, final T c, final IntSet D4, final T[] entries,
      final T retSite, final IFlowFunction retf) {
    D4.foreach(new IntSetAction() {
      public void act(final int d4) {
        final IntSet D5 = computeBinaryFlow(d4, edge.d2, (IBinaryReturnFlowFunction) retf);
        if (D5 != null) {
          D5.foreach(new IntSetAction() {
            public void act(final int d5) {
              // [26 - 28]
              // note that we've modified the algorithm here to account
              // for potential
              // multiple entry nodes. Instead of propagating the new
              // summary edge
              // with respect to one s_profOf(c), we have to propagate
              // for each
              // potential entry node s_p /in s_procof(c)
              for (int i = 0; i < entries.length; i++) {
                final T s_p = entries[i];
                if (DEBUG_LEVEL > 1) {
                  System.err.println(" do entry " + s_p);
                }
                IntSet D3 = getInversePathEdges(s_p, c, d4);
                if (DEBUG_LEVEL > 1) {
                  System.err.println("D3" + D3);
                }
                if (D3 != null) {
                  D3.foreach(new IntSetAction() {
                    public void act(int d3) {
                      propagate(s_p, d3, retSite, d5);
                    }
                  });
                }
              }
            }
          });
        }
      }
    });
  }

  /**
   * @param s_p
   * @param n
   * @param d2 note that s_p must be an entry for procof(n)
   * @return set of d1 s.t. <s_p, d1> -> <n, d2> is a path edge, or null if none found
   */
  protected IntSet getInversePathEdges(T s_p, T n, int d2) {
    int number = supergraph.getLocalBlockNumber(n);
    LocalPathEdges lp = pathEdges.get(s_p);
    if (lp == null) {
      return null;
    }
    return lp.getInverse(number, d2);
  }

  /**
   * Handle lines [14 - 19] of the algorithm, propagating information into and across a call site.
   */
  protected void processCall(final PathEdge<T> edge) {
    if (DEBUG_LEVEL > 0) {
      System.err.println("process call: " + edge);
    }

    // c:= number of the call node
    final int c = supergraph.getNumber(edge.target);

    final Collection<T> returnSites = Iterator2Collection.toCollection(supergraph.getReturnSites(edge.target));

    // [14 - 16]
    for (Iterator<? extends T> it = supergraph.getCalledNodes(edge.target); it.hasNext();) {
      final T callee = it.next();
      if (DEBUG_LEVEL > 0) {
        System.err.println(" process callee: " + callee);
      }
      IUnaryFlowFunction f = flowFunctionMap.getCallFlowFunction(edge.target, callee);
      // reached := {d1} that reach the callee
      IntSet reached = computeFlow(edge.d2, f);
      if (DEBUG_LEVEL > 0) {
        System.err.println(" reached: " + reached);
      }
      if (reached != null) {
        final LocalSummaryEdges summaries = summaryEdges.get(supergraph.getProcOf(callee));
        final CallFlowEdges callFlow = findOrCreateCallFlowEdges(callee);
        final int s_p_num = supergraph.getLocalBlockNumber(callee);

        reached.foreach(new IntSetAction() {
          public void act(final int d1) {
            propagate(callee, d1, callee, d1);
            // cache the fact that we've flowed <c, d2> -> <callee, d1> by a
            // call flow
            callFlow.addCallEdge(c, edge.d2, d1);
            // handle summary edges now as well. this is different from the PoPL
            // 95 paper.
            if (summaries != null) {
              // for each exit from the callee
              P p = supergraph.getProcOf(callee);
              T[] exits = supergraph.getExitsForProcedure(p);
              for (int e = 0; e < exits.length; e++) {
                T exit = exits[e];
                // if "exit" is a valid exit from the callee to the return
                // site being processed
                if (DEBUG_LEVEL > 0 && Assertions.verifyAssertions) {
                  Assertions._assert(supergraph.containsNode(exit));
                }
                for (Iterator<? extends T> succ = supergraph.getSuccNodes(exit); succ.hasNext();) {
                  final T returnSite = succ.next();
                  if (returnSites.contains(returnSite)) {
                    int x_num = supergraph.getLocalBlockNumber(exit);
                    // reachedBySummary := {d2} s.t. <callee,d1> -> <exit,d2>
                    // was recorded as a summary edge
                    IntSet reachedBySummary = summaries.getSummaryEdges(s_p_num, x_num, d1);
                    if (reachedBySummary != null) {
                      final IFlowFunction retf = flowFunctionMap.getReturnFlowFunction(edge.target, exit, returnSite);
                      reachedBySummary.foreach(new IntSetAction() {
                        public void act(int d2) {
                          if (retf instanceof IBinaryReturnFlowFunction) {
                            final IntSet D5 = computeBinaryFlow(edge.d2, d2, (IBinaryReturnFlowFunction) retf);
                            if (D5 != null) {
                              D5.foreach(new IntSetAction() {
                                public void act(int d5) {
                                  propagate(edge.entry, edge.d1, returnSite, d5);
                                }
                              });
                            }
                          } else {
                            final IntSet D5 = computeFlow(d2, (IUnaryFlowFunction) retf);
                            if (D5 != null) {
                              D5.foreach(new IntSetAction() {
                                public void act(int d5) {
                                  propagate(edge.entry, edge.d1, returnSite, d5);
                                }
                              });
                            }
                          }
                        }
                      });
                    }
                  }
                }
              }
            }
          }
        });
      }
    }
    // special logic: in backwards problems, a "call" node can have
    // "normal" successors as well. deal with these.
    for (Iterator<? extends T> it = supergraph.getNormalSuccessors(edge.target); it.hasNext();) {
      final T m = it.next();
      if (DEBUG_LEVEL > 0) {
        System.err.println("normal successor: " + m);
      }
      IUnaryFlowFunction f = flowFunctionMap.getNormalFlowFunction(edge.target, m);
      IntSet D3 = computeFlow(edge.d2, f);
      if (D3 != null) {
        D3.foreach(new IntSetAction() {
          public void act(int d3) {
            propagate(edge.entry, edge.d1, m, d3);
          }
        });
      }
    }

    // [17 - 19]
    // we modify this to handle each return site individually
    for (final T returnSite : returnSites) {
      if (DEBUG_LEVEL > 0) {
        System.err.println(" process return site: " + returnSite);
      }
      IUnaryFlowFunction f = null;
      if (hasCallee(returnSite)) {
        f = flowFunctionMap.getCallToReturnFlowFunction(edge.target, returnSite);
      } else {
        f = flowFunctionMap.getCallNoneToReturnFlowFunction(edge.target, returnSite);
      }
      IntSet reached = computeFlow(edge.d2, f);
      if (DEBUG_LEVEL > 0) {
        System.err.println("reached: " + reached);
      }
      if (reached != null) {
        reached.foreach(new IntSetAction() {
          public void act(int x) {
            if (Assertions.verifyAssertions) {
              Assertions._assert(x >= 0);
              Assertions._assert(edge.d1 >= 0);
            }
            propagate(edge.entry, edge.d1, returnSite, x);
          }
        });
      }
    }
  }

  private boolean hasCallee(T returnSite) {
    // if the supergraph says returnSite has a predecessor which indicates a
    // return
    // edge, then we say this return site has a callee.
    for (Iterator<? extends T> it = supergraph.getPredNodes(returnSite); it.hasNext();) {
      T pred = it.next();
      if (!supergraph.getProcOf(pred).equals(supergraph.getProcOf(returnSite))) {
        // an interprocedural edge. there must be a callee.
        return true;
      }
    }
    return false;
  }

  /**
   * @return f(call_d, exit_d);
   * 
   */
  protected IntSet computeBinaryFlow(int call_d, int exit_d, IBinaryReturnFlowFunction f) {
    if (DEBUG_LEVEL > 0) {
      System.err.println("got binary flow function " + f);
    }
    IntSet result = f.getTargets(call_d, exit_d);
    return result;
  }

  /**
   * @return f(d1)
   * 
   */
  protected IntSet computeFlow(int d1, IUnaryFlowFunction f) {
    if (DEBUG_LEVEL > 0) {
      System.err.println("got flow function " + f);
    }
    IntSet result = f.getTargets(d1);

    if (result == null) {
      return null;
    } else {
      return result;
    }
  }

  /**
   * @return f^{-1}(d2)
   */
  protected IntSet computeInverseFlow(int d2, IReversibleFlowFunction f) {
    return f.getSources(d2);
  }

  protected PathEdge<T> popFromWorkList() {
    return worklist.take();
  }

  private PathEdge peekFromWorkList() {
    // horrible. don't use in performance-critical
    PathEdge<T> result = worklist.take();
    worklist.insert(result);
    return result;
  }

  /**
   * Propagate the fact <s_p,i> -> <n, j> has arisen as a path edge. Note: apply merging if necessary.
   * 
   * Merging: suppose we're doing propagate <s_p,i> -> <n,j> but we already have path edges <s_p,i> -> <n, x>, <s_p,i> ->
   * <n,y>, and <s_p,i> -><n, z>.
   * 
   * let \alpha be the merge function. then instead of <s_p,i> -> <n,j>, we propagate <s_p,i> -> <n, \alpha(j,x,y,z) >
   * !!!
   * 
   * @param s_p entry block
   * @param i dataflow fact on entry
   * @param n reached block
   * @param j dataflow fact reached
   */
  protected void propagate(T s_p, int i, T n, int j) {
    int number = supergraph.getLocalBlockNumber(n);
    if (Assertions.verifyAssertions) {
      if (number < 0) {
        System.err.println("BOOM " + n);
        supergraph.getLocalBlockNumber(n);
      }
      Assertions._assert(number >= 0);
    }

    LocalPathEdges pLocal = findOrCreateLocalPathEdges(s_p);

    if (Assertions.verifyAssertions) {
      Assertions._assert(j >= 0);
    }

    if (!pLocal.contains(i, number, j)) {
      if (DEBUG_LEVEL > 0) {
        System.err.println("propagate " + s_p + "  " + i + " " + number + " " + j);
      }
      pLocal.addPathEdge(i, number, j);
      addToWorkList(s_p, i, n, j);
    }
  }

  /**
   * Merging: suppose we're doing propagate <s_p,i> -> <n,j> but we already have path edges <s_p,i> -> <n, x>, <s_p,i> ->
   * <n,y>, and <s_p,i> -><n, z>.
   * 
   * let \alpha be the merge function. then instead of <s_p,i> -> <n,j>, we propagate <s_p,i> -> <n, \alpha(j,x,y,z) >
   * !!!
   * 
   * return -1 if no fact should be propagated
   */
  private int merge(T s_p, int i, T n, int j) {
    if (Assertions.verifyAssertions) {
      Assertions._assert(j >= 0);
    }
    IMergeFunction alpha = problem.getMergeFunction();
    if (alpha != null) {
      LocalPathEdges lp = pathEdges.get(s_p);
      IntSet preExistFacts = lp.getReachable(supergraph.getLocalBlockNumber(n), i);
      if (preExistFacts == null) {
        return j;
      } else {
        int size = preExistFacts.size();
        if ((size == 0) || ((size == 1) && preExistFacts.contains(j))) {
          return j;
        } else {
          int result = alpha.merge(preExistFacts, j);
          return result;
        }
      }
    } else {
      return j;
    }
  }

  protected void addToWorkList(T s_p, int i, T n, int j) {
    worklist.insert(PathEdge.createPathEdge(s_p, i, n, j));
    if (DEBUG_LEVEL >= 3) {
      System.err.println("WORKLIST: " + worklist);
    }
  }

  protected LocalPathEdges findOrCreateLocalPathEdges(T s_p) {
    LocalPathEdges result = pathEdges.get(s_p);
    if (result == null) {
      result = makeLocalPathEdges();
      pathEdges.put(s_p, result);
    }
    return result;
  }

  private LocalPathEdges makeLocalPathEdges() {
    return problem.getMergeFunction() == null ? new LocalPathEdges(false) : new LocalPathEdges(true);
  }

  protected LocalSummaryEdges findOrCreateLocalSummaryEdges(P proc) {
    LocalSummaryEdges result = summaryEdges.get(proc);
    if (result == null) {
      result = new LocalSummaryEdges();
      summaryEdges.put(proc, result);
    }
    return result;
  }

  private CallFlowEdges findOrCreateCallFlowEdges(T s_p) {
    CallFlowEdges result = callFlowEdges.get(s_p);
    if (result == null) {
      result = new CallFlowEdges();
      callFlowEdges.put(s_p, result);
    }
    return result;
  }

  public class Result implements TabulationResult<T, P> {

    /**
     * get the bitvector of facts that hold at the entry to a given node
     * 
     * @param node
     * @return IntSet representing the bitvector
     */
    public IntSet getResult(T node) {

      if (Assertions.verifyAssertions) {
        Assertions._assert(node != null);
      }
      P proc = supergraph.getProcOf(node);
      if (Assertions.verifyAssertions && proc == null) {
        Assertions.UNREACHABLE("no proc for node " + node);
      }
      int n = supergraph.getLocalBlockNumber(node);
      Object[] entries = supergraph.getEntriesForProcedure(proc);
      MutableIntSet result = MutableSparseIntSet.makeEmpty();

      for (int i = 0; i < entries.length; i++) {
        Object s_p = entries[i];
        LocalPathEdges lp = pathEdges.get(s_p);
        if (lp != null) {
          result.addAll(lp.getReachable(n));
        }
      }
      return result;
    }

    @Override
    public String toString() {

      StringBuffer result = new StringBuffer();
      TreeMap<Object, TreeSet<T>> map = new TreeMap<Object, TreeSet<T>>(ToStringComparator.instance());

      Comparator<Object> c = new Comparator<Object>() {
        public int compare(Object o1, Object o2) {
          if (!(o1 instanceof IBasicBlock)) {
            return -1;
          }
          IBasicBlock bb1 = (IBasicBlock) o1;
          IBasicBlock bb2 = (IBasicBlock) o2;
          return bb1.getNumber() - bb2.getNumber();
        }
      };
      for (Iterator<? extends T> it = supergraph.iterator(); it.hasNext();) {
        T n = it.next();
        P proc = supergraph.getProcOf(n);
        TreeSet<T> s = map.get(proc);
        if (s == null) {
          s = new TreeSet<T>(c);
          map.put(proc, s);
        }
        s.add(n);
      }

      for (Iterator<Map.Entry<Object, TreeSet<T>>> it = map.entrySet().iterator(); it.hasNext();) {
        Map.Entry<Object, TreeSet<T>> e = it.next();
        Set<T> s = e.getValue();
        for (Iterator<T> it2 = s.iterator(); it2.hasNext();) {
          T o = it2.next();
          result.append(o + " : " + getResult(o) + "\n");
        }
      }
      return result.toString();
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationResult#getProblem()
     */
    public TabulationProblem<T, P> getProblem() {
      return problem;
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationResult#getSupergraphNodesReached()
     */
    public Collection<T> getSupergraphNodesReached() {
      Collection<T> result = HashSetFactory.make();
      for (Entry<T, LocalPathEdges> e : pathEdges.entrySet()) {
        T key = e.getKey();
        P proc = supergraph.getProcOf(key);
        IntSet reached = e.getValue().getReachedNodeNumbers();
        for (IntIterator ii = reached.intIterator(); ii.hasNext();) {
          result.add(supergraph.getLocalBlock(proc, ii.next()));
        }
      }

      return result;
    }

    /**
     * @param n1
     * @param d1
     * @param n2
     * @return set of d2 s.t. (n1,d1) -> (n2,d2) is recorded as a summary edge, or null if none found
     */
    public IntSet getSummaryTargets(T n1, int d1, T n2) {
      LocalSummaryEdges summaries = summaryEdges.get(supergraph.getProcOf(n1));
      if (summaries == null) {
        return null;
      }
      int num1 = supergraph.getLocalBlockNumber(n1);
      int num2 = supergraph.getLocalBlockNumber(n2);
      return summaries.getSummaryEdges(num1, num2, d1);
    }

    public Collection<PathEdge<T>> getSeeds() {
      return TabulationSolver.this.getSeeds();
    }
  }

  /**
   * @return Returns the supergraph.
   */
  public ISupergraph<T, P> getSupergraph() {
    return supergraph;
  }

  protected class Worklist extends Heap<PathEdge<T>> {

    Worklist() {
      super(100);
    }

    @Override
    protected boolean compareElements(PathEdge<T> p1, PathEdge<T> p2) {
      if (p1.d2 != p2.d2) { // TODO should we remove this check?
        if (problem.getDomain().hasPriorityOver(p1, p2)) {
          return true;
        }
      }
      return false;
    }

  }

  /**
   * @return set of d1 s.t. (n1,d1) -> (n2,d2) is recorded as a summary edge, or null if none found
   * @throws UnsupportedOperationException unconditionally
   */
  public IntSet getSummarySources(T n2, int d2, T n1) throws UnsupportedOperationException {
    throw new UnsupportedOperationException("not currently supported.  be careful");
    // LocalSummaryEdges summaries = summaryEdges.get(supergraph.getProcOf(n1));
    // if (summaries == null) {
    // return null;
    // }
    // int num1 = supergraph.getLocalBlockNumber(n1);
    // int num2 = supergraph.getLocalBlockNumber(n2);
    // return summaries.getInvertedSummaryEdgesForTarget(num1, num2, d2);
  }

  public TabulationProblem<T, P> getProblem() {
    return problem;
  }

  public Collection<PathEdge<T>> getSeeds() {
    return Collections.unmodifiableCollection(seeds);
  }
}
